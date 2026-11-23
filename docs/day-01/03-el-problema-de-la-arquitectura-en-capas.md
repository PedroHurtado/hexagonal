# Día 1 — Bloque 3: El problema de la arquitectura en capas

> *"The database is a detail."* — Robert C. Martin, *Clean Architecture* (2017)

---

## 1. El problema en una frase

En la arquitectura en capas clásica de Spring (Controller → Service →
Repository), **la lógica de negocio depende de la base de datos**, y con el
tiempo se contamina de JPA, JSON y HTTP hasta que ya no se puede probar,
cambiar ni entender sin arrastrar toda la infraestructura.

En cristiano: las capas separan **por tecnología**, pero las flechas siguen
apuntando hacia abajo, hacia lo que **más cambia** y **menos nos importa**.

---

## 2. La arquitectura en capas de toda la vida

```
          HTTP / JSON
               │
               ▼
┌──────────────────────────────┐
│  @RestController             │   PedidoController
│  (presentación)              │
└──────────────┬───────────────┘
               │ depende de
               ▼
┌──────────────────────────────┐
│  @Service @Transactional     │   PedidoService
│  (negocio)                   │
└──────────────┬───────────────┘
               │ depende de
               ▼
┌──────────────────────────────┐
│  JpaRepository<..., UUID>    │   PedidoRepository (Spring Data)
│  (acceso a datos)            │
└──────────────┬───────────────┘
               ▼
          [ PostgreSQL ]
```

Es la que genera Spring Initializr en la cabeza de cualquiera, la de miles de
tutoriales, y **funciona muy bien** para CRUDs pequeños. El problema aparece
cuando hay **reglas de negocio reales** e **integraciones externas**.

Fíjate en una cosa: la capa de "negocio" está **en medio**. Depende de la de
abajo. Todo lo que cambie en persistencia **sube**.

---

## 3. Bad: un `PedidoService` realista

Esto no es una caricatura. Es lo que sale después de un año de *sprints*.

### 3.1 La entidad JPA (que hace de "modelo de dominio")

```java
package com.curso.pedidos.entity;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pedidos")
public class PedidoEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "cliente_id", nullable = false)
    private UUID clienteId;

    @JsonProperty("status")                     // Jackson en la entidad
    private String estado;                      // "BORRADOR", "CONFIRMADO"... String libre

    private BigDecimal total;                   // dato derivado, persistido "por si acaso"

    private Instant creadoEn;

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore                                  // para no hacer bucles infinitos en JSON
    private List<LineaPedidoEntity> lineas = new ArrayList<>();

    @Version
    private Long version;

    // getters y setters para TODO (modelo anémico)
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getClienteId() { return clienteId; }
    public void setClienteId(UUID clienteId) { this.clienteId = clienteId; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }
    public List<LineaPedidoEntity> getLineas() { return lineas; }
    public void setLineas(List<LineaPedidoEntity> lineas) { this.lineas = lineas; }
}
```

### 3.2 El servicio "de negocio"

```java
package com.curso.pedidos.service;

import com.curso.pedidos.entity.LineaPedidoEntity;
import com.curso.pedidos.entity.PedidoEntity;
import com.curso.pedidos.repository.PedidoJpaRepository;
import com.curso.pedidos.repository.ProductoJpaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Service
public class PedidoService {

    private final PedidoJpaRepository pedidoRepo;
    private final ProductoJpaRepository productoRepo;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafka;

    public PedidoService(PedidoJpaRepository pedidoRepo,
                         ProductoJpaRepository productoRepo,
                         RestTemplate restTemplate,
                         ObjectMapper objectMapper,
                         KafkaTemplate<String, String> kafka) {
        this.pedidoRepo = pedidoRepo;
        this.productoRepo = productoRepo;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.kafka = kafka;
    }

    @Transactional
    public PedidoEntity confirmarYCobrar(UUID pedidoId, String tokenTarjeta) {
        PedidoEntity pedido = pedidoRepo.findById(pedidoId)
                .orElseThrow(() -> new EntityNotFoundException("Pedido " + pedidoId));

        // Regla de negocio escondida entre strings
        if (!"BORRADOR".equals(pedido.getEstado())) {
            throw new IllegalStateException("Solo se confirman borradores");
        }
        if (pedido.getLineas().isEmpty()) {          // LAZY: dispara SQL aquí
            throw new IllegalStateException("Pedido vacío");
        }

        // Cálculo del total (¿y si otro servicio lo calcula distinto?)
        BigDecimal total = BigDecimal.ZERO;
        for (LineaPedidoEntity l : pedido.getLineas()) {
            var producto = productoRepo.findById(l.getSku()).orElseThrow();
            if (producto.getStock() < l.getCantidad()) {
                throw new IllegalStateException("Sin stock: " + l.getSku());
            }
            producto.setStock(producto.getStock() - l.getCantidad()); // stock "de paso"
            total = total.add(l.getPrecioUnitario()
                    .multiply(BigDecimal.valueOf(l.getCantidad())));
        }
        pedido.setTotal(total);

        // Llamada HTTP DENTRO de la transacción de BD
        Map<String, Object> peticion = Map.of(
                "amount", total.movePointRight(2).intValue(),
                "currency", "eur",
                "source", tokenTarjeta);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "https://api.pasarela.ejemplo/v1/charges", peticion, Map.class);
        if (!resp.getStatusCode().is2xxSuccessful()
                || !"succeeded".equals(resp.getBody().get("status"))) {
            throw new IllegalStateException("Pago rechazado");
        }

        pedido.setEstado("PAGADO");                  // ¿y CONFIRMADO? se lo salta
        PedidoEntity guardado = pedidoRepo.save(pedido);

        // Serializa la ENTIDAD JPA para publicarla como evento
        try {
            kafka.send("pedidos", objectMapper.writeValueAsString(guardado));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return guardado;                             // el controller la devuelve como JSON
    }
}
```

### 3.3 El controller que devuelve la entidad

```java
@RestController
@RequestMapping("/pedidos")
public class PedidoController {

    private final PedidoService service;

    public PedidoController(PedidoService service) { this.service = service; }

    @PostMapping("/{id}/confirmacion")
    public PedidoEntity confirmar(@PathVariable UUID id, @RequestParam String token) {
        return service.confirmarYCobrar(id, token);  // la tabla ES el contrato de la API
    }
}
```

---

## 4. ¿Qué duele?

### 4.1 Las dependencias apuntan a la base de datos

Mira los `import` de `PedidoService`: `jakarta.persistence`, Spring Data,
Jackson, `RestTemplate`, Kafka. **Cinco tecnologías** en la clase que debería
contener solo reglas de negocio.

```
                    ┌──────────────────┐
                    │  PedidoService   │
                    └───┬───┬───┬───┬──┘
           ┌────────────┘   │   │   └──────────────┐
           ▼                ▼   ▼                  ▼
   ┌──────────────┐ ┌────────┐ ┌──────────────┐ ┌───────┐
   │ JPA/Hibernate│ │Jackson │ │ RestTemplate │ │ Kafka │
   └──────────────┘ └────────┘ └──────────────┘ └───────┘
       el negocio depende de TODO lo que cambia
```

Consecuencia: cambiar `RestTemplate` por `RestClient`, la pasarela de pagos o
Kafka por RabbitMQ **obliga a tocar el servicio de negocio**.

### 4.2 Lógica de negocio filtrada por todas partes

- `"BORRADOR".equals(...)` en el servicio: la máquina de estados no existe; un
  `setEstado("ENVIADO")` desde cualquier sitio es perfectamente legal.
- El total se calcula en el servicio (y seguramente en otros tres sitios más).
- El descuento de stock vive aquí… y en el importador nocturno, con otra regla.
- Se salta el estado `CONFIRMADO`: **nadie lo impide**.

### 4.3 Modelo anémico

Martin Fowler lo describe así: objetos que **parecen** modelo de dominio
(tienen nombres de negocio) pero **no tienen comportamiento**, solo getters y
setters. Toda la lógica está en servicios procedurales (*Transaction Script*
disfrazado). Fowler lo llama directamente un **antipatrón**.

### 4.4 La entidad JPA es a la vez tabla, DTO y evento

`PedidoEntity` sirve para **tres** contratos distintos: el esquema SQL, el JSON
de la API y el mensaje de Kafka. Renombrar una columna **rompe a los
consumidores** de la API y del topic. `@JsonIgnore` y `@JsonProperty` en la
entidad son el síntoma.

### 4.5 Transacciones y llamadas remotas mezcladas

La llamada HTTP a la pasarela ocurre **dentro** de `@Transactional`:

- Si la pasarela tarda 10 s, la conexión de BD queda bloqueada 10 s.
- Si el cobro va bien pero `save` falla (p. ej. `OptimisticLockException`),
  **hemos cobrado y no tenemos el pedido pagado**. Inconsistencia real con
  dinero de por medio.
- `kafka.send` es asíncrono y no participa en la transacción: el evento puede
  salir aunque se haga *rollback*. (Lo resolveremos con *Transactional Outbox*
  en el día 6.)

### 4.6 Tests lentos y frágiles

Ahora viene lo bueno.

---

## 5. Testing: intentemos probar la regla "no se confirma un pedido vacío"

Una regla de **una línea**. Veamos qué cuesta probarla.

### Opción A: test unitario con Mockito

```java
@ExtendWith(MockitoExtension.class)
class PedidoServiceTest {

    @Mock PedidoJpaRepository pedidoRepo;
    @Mock ProductoJpaRepository productoRepo;
    @Mock RestTemplate restTemplate;
    @Mock ObjectMapper objectMapper;
    @Mock KafkaTemplate<String, String> kafka;

    @InjectMocks PedidoService service;

    @Test
    void no_confirma_pedido_vacio() {
        PedidoEntity pedido = new PedidoEntity();
        pedido.setId(UUID.randomUUID());
        pedido.setEstado("BORRADOR");                // hay que saber el String mágico
        pedido.setLineas(new ArrayList<>());
        when(pedidoRepo.findById(pedido.getId())).thenReturn(Optional.of(pedido));

        assertThatThrownBy(() -> service.confirmarYCobrar(pedido.getId(), "tok"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vacío");

        verifyNoInteractions(restTemplate, kafka);
    }
}
```

¿Qué duele?

- **Cinco mocks** para probar un `if`. El test conoce la estructura interna
  del servicio (qué repos usa, en qué orden).
- Cualquier refactor interno (p. ej. cambiar `findById` por una consulta
  `findByIdWithLineas`) **rompe el test** aunque la regla no haya cambiado.
- La excepción es `IllegalStateException`: no podemos distinguir "vacío" de
  "pago rechazado" salvo por el **texto del mensaje**.
- No prueba nada de lo importante: el `LAZY`, la transacción, la serialización.

### Opción B: test "de verdad" con contexto Spring

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PedidoServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean RestTemplate restTemplate;                         // no queremos cobrar de verdad
    @MockBean KafkaTemplate<String, String> kafka;               // ni levantar Kafka

    @Autowired PedidoService service;
    @Autowired PedidoJpaRepository pedidoRepo;

    @Test
    void no_confirma_pedido_vacio() {
        PedidoEntity pedido = new PedidoEntity();
        pedido.setClienteId(UUID.randomUUID());
        pedido.setEstado("BORRADOR");
        pedido = pedidoRepo.save(pedido);
        UUID id = pedido.getId();

        assertThatThrownBy(() -> service.confirmarYCobrar(id, "tok"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

¿Qué duele?

- Arranca Docker, PostgreSQL, Flyway, Hibernate y **todo** el contexto de
  Spring: de **5 a 20 segundos** para probar un `if`.
- Cada `@MockBean` distinto crea un **contexto nuevo** (adiós caché de
  contextos de Spring).
- Si la regla vive **en SQL o en un `@PrePersist`**, solo se puede probar así.

| Pregunta                                   | Arquitectura en capas         |
|--------------------------------------------|-------------------------------|
| ¿Puedo probar la regla sin Spring?          | No (o con 5 mocks)            |
| ¿Cuánto tarda el test fiable?               | Segundos                      |
| ¿El test sobrevive a un refactor interno?   | Rara vez                      |
| ¿El nombre de la excepción explica la regla?| No (`IllegalStateException`)  |

Compáralo con el test de [02-principios-de-diseno-clave.md](02-principios-de-diseno-clave.md):
`Pedido.crear(...)`, `confirmar()`, `assertThatThrownBy`. Milisegundos, cero
mocks, cero contexto.

---

## 6. "Pero esto se arregla con disciplina…"

Sí y no. Trampas habituales al intentar arreglarlo sin cambiar la arquitectura:

- **Trampa: "Meto interfaces a todo"** (`IPedidoService`, `PedidoServiceImpl`).
  Si la interfaz vive junto a la implementación y la de persistencia sigue
  siendo `JpaRepository`, no se ha invertido nada.
- **Trampa: "Pongo DTOs en el controller"**. Bien, arregla el borde de la API…
  pero el servicio sigue dependiendo de JPA y de la pasarela.
- **Trampa: "Muevo la lógica a la entidad JPA"**. Mejor que el modelo anémico,
  pero ahora el dominio tiene anotaciones de Hibernate, constructor vacío
  obligatorio, colecciones `LAZY` y proxies: el modelo queda **condicionado
  por el ORM**.

El problema de fondo es **estructural**: la dependencia de código fuente apunta
de negocio a infraestructura. Mientras eso no se invierta, la disciplina es
nadar contra corriente.

---

## 7. Resumen: síntomas y causas

| Síntoma                                    | Causa de fondo                                    |
|--------------------------------------------|---------------------------------------------------|
| Tests lentos o llenos de mocks              | El negocio depende de infraestructura concreta    |
| Reglas duplicadas o saltables               | Modelo anémico, lógica en servicios procedurales  |
| Cambiar de proveedor toca el servicio       | Sin abstracciones propiedad del negocio (no DIP)  |
| Renombrar columna rompe la API              | Entidad JPA usada como contrato externo           |
| Cobros sin pedido pagado                    | Transacción local + llamada remota mezcladas      |
| `IllegalStateException` para todo           | Sin excepciones de dominio con nombre             |

---

## Ejercicio

Coge el `PedidoService.confirmarYCobrar` de arriba y **haz inventario**:

1. Lista cada línea que sea **regla de negocio** (algo que diría un experto de
   negocio).
2. Lista cada línea que sea **detalle técnico**.
3. Para cada detalle técnico, inventa el **nombre de negocio** de la
   abstracción que lo ocultaría (será un futuro puerto de salida).

*Pista*: deberían salirte 4 o 5 abstracciones.

<details><summary>Solución</summary>

**Reglas de negocio**:

- Solo se confirman pedidos en `BORRADOR`.
- No se confirma un pedido vacío.
- El total es la suma de `precioUnitario × cantidad`.
- Debe haber stock suficiente de cada producto.
- Un pedido cobrado pasa a `PAGADO` (tras pasar por `CONFIRMADO`).

**Detalles técnicos** y su abstracción de negocio:

| Detalle técnico                         | Puerto de salida (nombre de negocio) |
|-----------------------------------------|--------------------------------------|
| `PedidoJpaRepository`                   | `PedidoRepository`                   |
| `ProductoJpaRepository` + stock         | `CatalogoProductos`                  |
| `RestTemplate` a la pasarela            | `PasarelaDePago`                     |
| `KafkaTemplate` + `ObjectMapper`        | `PublicadorDeEventos`                |
| Devolver `PedidoEntity` en el controller| DTO de respuesta en el adaptador web |

Y las reglas se mueven a `Pedido` (`confirmar()`, `marcarComoPagado()`,
`total()`). Es exactamente lo que haremos en el bloque 4.

</details>

---

## Referencias

- Martin Fowler, *AnemicDomainModel*: https://martinfowler.com/bliki/AnemicDomainModel.html
- Martin Fowler, *Patterns of Enterprise Application Architecture* (Layers, Transaction Script): https://martinfowler.com/eaaCatalog/
- Robert C. Martin, *Clean Architecture* (2017), cap. "The Database Is a Detail".
- Spring Data JPA: https://docs.spring.io/spring-data/jpa/reference/
- Spring Framework, transacciones: https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- Spring Framework, testing: https://docs.spring.io/spring-framework/reference/testing.html
- Testcontainers: https://java.testcontainers.org/
- Chris Richardson, *Transactional Outbox*: https://microservices.io/patterns/data/transactional-outbox.html

---

## Mantra

> *"Si para probar un `if` de negocio necesitas Docker, tu negocio vive en el sitio equivocado."*
