# Día 8 — Bloque 1: Enunciado del proyecto final

> *"To me, legacy code is simply code without tests."*
> — Michael Feathers, *Working Effectively with Legacy Code* (2004)

---

## 1. El problema en una frase

Heredas el backend de pedidos de la tienda online: **funciona, factura y nadie
se atreve a tocarlo**. Tu misión es convertirlo en un hexágono sin parar el
negocio y sin romper el API que consumen la web y la app móvil.

---

## 2. Contexto (la historia que nos cuenta el cliente)

"La tienda se montó hace cuatro años con Spring Boot en tres capas:
controlador, servicio, repositorio. Al principio iba como un tiro. Ahora:

- Cada vez que cambiamos el proveedor de pagos tardamos **tres semanas**.
- Cuando el servidor de correo va lento, **los pedidos tardan 20 segundos** en
  confirmarse y a veces se quedan bloqueadas filas en la base de datos.
- Hubo un incidente: se **cobró a clientes cuyo pedido luego hizo rollback**.
- No hay tests. El último que tocó `PedidoService` se fue de la empresa.
- Queremos sacar notificaciones a Kafka y quizá extraer pagos a un
  microservicio, pero nadie sabe por dónde cortar."

En cristiano: es un monolito en capas **normal**. No es un desastre exótico;
es lo que os vais a encontrar.

---

## 3. El código heredado

> El código está en `code/pedidos-legacy`. Lo reproducimos aquí para poder
> analizarlo sin abrir el IDE. Es feo **a propósito**, pero cada fealdad la
> hemos visto en proyectos reales.

### 3.1 Estructura de paquetes

```
com.curso.pedidos
├── PedidosApplication.java
├── controller
│   └── PedidoController.java
├── service
│   ├── PedidoService.java          (≈300 líneas)
│   └── EmailService.java
├── entity
│   ├── PedidoEntity.java
│   ├── LineaPedidoEntity.java
│   └── ProductoEntity.java
├── repository
│   ├── PedidoRepository.java       (Spring Data JPA, JpaRepository<PedidoEntity, UUID>)
│   └── ProductoRepository.java
└── util
    └── Constantes.java
```

### 3.2 La entidad JPA… que también es el JSON del API

```java
package com.curso.pedidos.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pedidos")
public class PedidoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)    // Hibernate 6: UUID generado al persistir
    private UUID id;

    private UUID clienteId;
    private String clienteEmail;
    private String estado;              // "BORRADOR", "CONFIRMADO", "PAGADO", "ENVIADO", "CANCELADO", "confirmado" (sic)
    private BigDecimal total;           // se calcula... a veces
    private String moneda = "EUR";
    private Instant creadoEn;
    private String referenciaPago;

    @JsonIgnore                         // para que no salga en el JSON (se añadió tras un incidente)
    private String tokenTarjeta;

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.EAGER)
    @JsonManagedReference
    private List<LineaPedidoEntity> lineas = new ArrayList<>();

    // getters y setters públicos para TODO (generados por el IDE)
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public List<LineaPedidoEntity> getLineas() { return lineas; }
    public void setLineas(List<LineaPedidoEntity> lineas) { this.lineas = lineas; }
    // ... 14 getters/setters más
}
```

```java
@Entity
@Table(name = "lineas_pedido")
public class LineaPedidoEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne @JoinColumn(name = "pedido_id")
    @JsonBackReference
    private PedidoEntity pedido;

    private String sku;
    private int cantidad;               // puede ser 0 o negativo: nadie lo valida aquí
    private BigDecimal precioUnitario;
    // getters/setters
}
```

### 3.3 El controlador con lógica de negocio dentro

```java
package com.curso.pedidos.controller;

@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    @Autowired private PedidoService pedidoService;
    @Autowired private PedidoRepository pedidoRepository;   // ¡el controlador va directo al repo!

    @PostMapping
    public ResponseEntity<PedidoEntity> crear(@RequestBody PedidoEntity pedido) {
        // "validación"
        if (pedido.getLineas() == null || pedido.getLineas().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        for (LineaPedidoEntity l : pedido.getLineas()) {
            l.setPedido(pedido);                            // arreglo para la relación bidireccional
        }
        pedido.setEstado("BORRADOR");
        pedido.setCreadoEn(Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(pedidoService.guardar(pedido));
    }

    @GetMapping("/{id}")
    public PedidoEntity obtener(@PathVariable UUID id) {
        return pedidoRepository.findById(id).orElse(null);  // devuelve 200 con body vacío si no existe
    }

    @PostMapping("/{id}/confirmar")
    public ResponseEntity<?> confirmar(@PathVariable UUID id,
                                       @RequestParam(required = false) String tokenTarjeta) {
        PedidoEntity p = pedidoRepository.findById(id).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        if ("CANCELADO".equals(p.getEstado())) {
            return ResponseEntity.status(409).body("Pedido cancelado");
        }
        // descuento de Black Friday metido "temporalmente" en 2023
        if (LocalDate.now().getMonth() == Month.NOVEMBER && p.getLineas().size() > 3) {
            p.setTotal(p.getTotal().multiply(new BigDecimal("0.90")));
        }
        try {
            return ResponseEntity.ok(pedidoService.confirmarYCobrar(p, tokenTarjeta));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());   // filtra mensajes internos al cliente
        }
    }

    @PostMapping("/{id}/cancelar")
    public PedidoEntity cancelar(@PathVariable UUID id) {
        PedidoEntity p = pedidoRepository.findById(id).get();       // NoSuchElementException → 500
        p.setEstado("CANCELADO");
        return pedidoRepository.save(p);                            // ¿y si estaba ENVIADO?
    }
}
```

### 3.4 El `PedidoService` de 300 líneas (extracto representativo)

```java
package com.curso.pedidos.service;

import com.curso.pedidos.entity.*;
import com.curso.pedidos.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@Transactional                       // TODO el servicio es transaccional, incluidas las llamadas HTTP
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    @Autowired private PedidoRepository pedidoRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private EmailService emailService;
    @Autowired private RestTemplate restTemplate;

    @Value("${pagos.url}")
    private String pagosUrl;

    @Value("${pagos.apiKey}")
    private String apiKey;

    public PedidoEntity guardar(PedidoEntity pedido) {
        BigDecimal total = BigDecimal.ZERO;
        for (LineaPedidoEntity l : pedido.getLineas()) {
            ProductoEntity prod = productoRepository.findBySku(l.getSku());
            if (prod == null) {
                throw new RuntimeException("Producto no existe: " + l.getSku());
            }
            if (prod.getStock() < l.getCantidad()) {
                throw new RuntimeException("No hay stock de " + l.getSku());
            }
            // el precio que manda el cliente se ignora... salvo que venga informado (!)
            if (l.getPrecioUnitario() == null) {
                l.setPrecioUnitario(prod.getPrecio());
            }
            total = total.add(l.getPrecioUnitario().multiply(BigDecimal.valueOf(l.getCantidad())));
        }
        pedido.setTotal(total.setScale(2, RoundingMode.HALF_UP));
        return pedidoRepository.save(pedido);
    }

    public PedidoEntity confirmarYCobrar(PedidoEntity p, String tokenTarjeta) {
        log.info("Confirmando pedido " + p.getId());

        if (p.getLineas().isEmpty()) {
            throw new IllegalStateException("Pedido vacío");
        }
        if (!"BORRADOR".equalsIgnoreCase(p.getEstado())) {
            throw new IllegalStateException("Estado no válido: " + p.getEstado());
        }

        // 1) descontar stock
        for (LineaPedidoEntity l : p.getLineas()) {
            ProductoEntity prod = productoRepository.findBySku(l.getSku());
            prod.setStock(prod.getStock() - l.getCantidad());       // puede quedar negativo
            productoRepository.save(prod);
        }

        p.setEstado("CONFIRMADO");
        pedidoRepository.save(p);

        // 2) cobrar con la pasarela, DENTRO de la transacción
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", apiKey);
        Map<String, Object> body = new HashMap<>();
        body.put("amount", p.getTotal().multiply(new BigDecimal(100)).intValue()); // céntimos
        body.put("currency", p.getMoneda());
        body.put("token", tokenTarjeta);
        body.put("reference", "PED-" + p.getId());

        ResponseEntity<Map> resp = restTemplate.postForEntity(
                pagosUrl + "/charges", new HttpEntity<>(body, headers), Map.class);

        if (resp.getStatusCode() != HttpStatus.OK || resp.getBody() == null) {
            throw new RuntimeException("Pago rechazado");
            // rollback del stock y del estado... pero el cargo YA se ha hecho si el 200 llegó tarde
        }
        p.setReferenciaPago((String) resp.getBody().get("id"));
        p.setEstado("PAGADO");

        // 3) email DENTRO de la transacción (SMTP lento = transacción abierta 20 s)
        try {
            emailService.enviar(p.getClienteEmail(),
                    "Tu pedido " + p.getId() + " está confirmado",
                    plantillaConfirmacion(p));
        } catch (Exception e) {
            log.error("Error enviando email", e);                    // se traga el error
        }

        // 4) fidelización: puntos por compra (añadido en 2024)
        if (p.getTotal().compareTo(new BigDecimal("100")) > 0) {
            // ... 40 líneas de cálculo de puntos con if anidados
        }

        return pedidoRepository.save(p);
    }

    public PedidoEntity reembolsar(UUID id) {
        // ... 60 líneas: llamada a /refunds, cambio de estado, email, otro RestTemplate
        return null;
    }

    private String plantillaConfirmacion(PedidoEntity p) {
        StringBuilder sb = new StringBuilder("<html><body><h1>Gracias</h1><ul>");
        for (LineaPedidoEntity l : p.getLineas()) {
            sb.append("<li>").append(l.getSku()).append(" x ").append(l.getCantidad()).append("</li>");
        }
        return sb.append("</ul><p>Total: ").append(p.getTotal()).append("</p></body></html>").toString();
    }

    // ... informes de ventas, exportación CSV, cálculo de puntos, reintentos manuales:
    //     hasta llegar a ~300 líneas
}
```

```java
@Service
public class EmailService {
    @Autowired private JavaMailSender mailSender;

    public void enviar(String to, String asunto, String html) throws MessagingException {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
        h.setTo(to);
        h.setSubject(asunto);
        h.setText(html, true);
        mailSender.send(msg);
    }
}
```

### 3.5 Los tests

```
src/test/java/com/curso/pedidos/
└── PedidosApplicationTests.java     → @SpringBootTest void contextLoads() {}
```

Y ese test está `@Disabled` porque "necesita el servidor de pagos".

---

## 4. ¿Qué duele? (diagnóstico)

Antes de escribir una línea, el equipo hace el diagnóstico. Esta tabla es lo
que esperamos que el alumno encuentre en los primeros 10 minutos:

| # | Síntoma                                                        | Dónde                         | Consecuencia                                                         | Lo arregla…                         |
|---|----------------------------------------------------------------|-------------------------------|----------------------------------------------------------------------|-------------------------------------|
| 1 | Entidad JPA usada como modelo **y** como DTO JSON              | `PedidoEntity`                | Cambiar una columna rompe el API; `@JsonIgnore` como parche de seguridad | Dominio + DTOs + mapper (fases 1, 4) |
| 2 | Modelo anémico: getters/setters públicos, estado como `String` | `PedidoEntity`                | Invariantes dispersas; `"confirmado"` en minúsculas en BD            | `Pedido` rico + `EstadoPedido` (fase 1) |
| 3 | Lógica de negocio en el controlador                             | descuento, cancelación        | Imposible reutilizar desde Kafka/CLI; imposible testear sin HTTP     | Casos de uso (fase 3)               |
| 4 | Controlador accede al repositorio                               | `PedidoController`            | Se salta el servicio y sus reglas (cancelar un pedido enviado)       | Puertos de entrada (fase 2)         |
| 5 | `RestTemplate` dentro de `@Transactional`                       | `confirmarYCobrar`            | Cobro sin pedido si hay rollback; conexión BD retenida durante HTTP  | `PasarelaDePago` + orden correcto (fases 2–4) |
| 6 | Email SMTP dentro de la transacción                             | `confirmarYCobrar`            | Transacciones de 20 s, bloqueos; error silenciado                    | `NotificadorPedidos` tras commit (fase 4) |
| 7 | Excepciones genéricas `RuntimeException` → 500 con mensaje      | todo                          | El cliente no distingue "sin stock" de "error interno"               | Excepciones de dominio + `@ControllerAdvice` |
| 8 | Configuración y secretos inyectados en el servicio              | `@Value` en `PedidoService`   | El servicio conoce URLs y API keys                                   | Adaptador de pago (fase 4)          |
| 9 | Inyección por campo `@Autowired`                                | todo                          | No se puede instanciar sin Spring; dependencias ocultas             | Constructores + `BeanConfiguration` |
| 10 | Cero tests                                                     | —                             | Miedo al cambio                                                      | Golden master (fase 0)              |

```
                     ESTADO ACTUAL (monolito en capas)

   HTTP ──► ┌──────────────────┐        ┌─────────────────────────────────┐
            │ PedidoController │──────► │ PedidoService  @Transactional   │
            │ (lógica, descto.)│        │  ├─ ProductoRepository (JPA)    │
            └────────┬─────────┘        │  ├─ RestTemplate ──► Pagos HTTP │
                     │                  │  ├─ EmailService ──► SMTP       │
                     │                  │  └─ PedidoRepository (JPA)      │
                     ▼                  └───────────────┬─────────────────┘
            ┌──────────────────┐                        │
            │ PedidoRepository │ ◄──────────────────────┘
            └────────┬─────────┘
                     ▼
            ┌──────────────────┐   PedidoEntity viaja de la BD al JSON
            │   PostgreSQL     │   sin pasar por ningún modelo propio
            └──────────────────┘
```

---

## 5. Objetivo

Llegar a la arquitectura canónica del curso:

```
com.curso.pedidos
├── domain          (Pedido, LineaPedido, EstadoPedido, VOs, eventos, excepciones)
├── application     (port.in, port.out, service)
└── infrastructure  (adapter.in.web, adapter.out.persistence/payment/notification, config)
```

Con estas **restricciones** (como en la vida real):

1. **El contrato REST no cambia** salvo en los errores, que pasan de 500
   genéricos a códigos correctos (documentado en el ADR). Rutas, verbos y
   campos JSON de éxito se mantienen.
2. **La base de datos no cambia** de esquema en este proyecto (las tablas
   `pedidos` y `lineas_pedido` siguen igual). Si necesitas migrar datos, se
   hace con Flyway en otra iteración.
3. **Siempre en verde**: cada commit compila y pasa los tests.
4. **Nada de big bang**: la versión vieja y la nueva conviven hasta que la
   nueva demuestra que funciona.

### Fuera de alcance (para no perdernos)

- Reembolsos, informes de ventas y exportación CSV: se quedan en el monolito
  (y eso está bien: Strangler Fig no exige migrar todo).
- Fidelización por puntos: solo se aísla detrás de un puerto si da tiempo.

---

## 6. Entregables

| # | Entregable                                                                 | Formato                                    |
|---|----------------------------------------------------------------------------|--------------------------------------------|
| 1 | Suite de **tests de caracterización** del API REST (golden master)          | `src/test/.../characterization/`           |
| 2 | **Dominio puro**: `Pedido`, `LineaPedido`, `EstadoPedido`, VOs, excepciones, eventos | `domain/` sin imports de Spring/JPA/Jackson |
| 3 | **Puertos** de entrada y salida                                             | `application/port/in`, `application/port/out` |
| 4 | **Servicios de aplicación** (`CrearPedidoService`, `ConfirmarPedidoService`, …) | `application/service`                     |
| 5 | **Adaptadores**: web, persistencia, pago, notificación                      | `infrastructure/adapter/...`               |
| 6 | **Tests**: unitarios de dominio, casos de uso con dobles, adaptadores (slice/WireMock) | `src/test`                          |
| 7 | **Fitness functions** con ArchUnit                                          | `ArquitecturaHexagonalTest`                |
| 8 | **ADR-0001** "Adoptar arquitectura hexagonal"                               | `docs/adr/0001-adoptar-arquitectura-hexagonal.md` |
| 9 | Historial de **commits pequeños** con mensajes con intención                | `git log --oneline`                        |

---

## 7. Criterios de evaluación (rúbrica)

| Criterio                          | Peso | Insuficiente (0–4)                                    | Adecuado (5–7)                                            | Excelente (8–10)                                                        |
|-----------------------------------|------|-------------------------------------------------------|-----------------------------------------------------------|-------------------------------------------------------------------------|
| **Red de seguridad**              | 15 % | Sin tests de caracterización o no cubren confirmar    | Cubren crear/confirmar/cancelar con casos felices          | Cubren éxito y errores; pasan antes y después de migrar; pago con WireMock |
| **Dominio rico y puro**           | 20 % | `Pedido` sigue anémico o con anotaciones JPA          | Invariantes en `Pedido`; VOs para id y dinero              | Todas las invariantes del curso; eventos; 0 imports de framework; tests unitarios de cada regla |
| **Puertos bien definidos**        | 15 % | Puertos con nombres técnicos (`JpaXxx`) o inexistentes | Puertos de entrada y salida con lenguaje de negocio        | Puertos mínimos y cohesivos; comandos `record`; sin fugas de tipos de infraestructura |
| **Casos de uso y transacciones**  | 15 % | Lógica en controlador o adaptadores                   | Lógica en servicios de aplicación                          | Transacción correcta; pago fuera de transacción; email tras commit/outbox |
| **Adaptadores**                   | 10 % | Controlador devuelve entidades JPA                    | DTOs y mappers; adaptador de persistencia y pago           | `@ControllerAdvice` con errores correctos; `RestClient`; adaptadores probados con slice/WireMock |
| **Evaluación objetiva**           | 10 % | Sin ArchUnit                                          | Reglas de capas en ArchUnit                                | Suite ArchUnit completa + JaCoCo en dominio ≥ 90 % + PIT ejecutado       |
| **Proceso incremental**           | 10 % | Un único commit gigante                               | Varios commits, alguno en rojo                             | Commits pequeños siempre en verde; Strangler/Branch by Abstraction visibles |
| **ADR y comunicación**            | 5 %  | Sin ADR                                               | ADR con contexto y decisión                                | ADR con consecuencias positivas y negativas, alternativas descartadas    |

> Trampa: puntuar "número de clases". Un hexágono con 60 clases vacías no es
> mejor que uno con 25 útiles. Se puntúa **dónde vive cada responsabilidad**.

---

## 8. Timing por fases (práctica guiada)

| Fase | Qué se hace                                                        | Tiempo  | Commit esperado (ejemplo)                                  |
|------|--------------------------------------------------------------------|---------|------------------------------------------------------------|
| 0    | Tests de caracterización sobre el API (golden master + WireMock)   | 20 min  | `test: añade golden master del API de pedidos`             |
| 1    | Extraer `Pedido` rico y VOs de `PedidoEntity`                      | 20 min  | `refactor: extrae modelo de dominio Pedido`                |
| 2    | Definir puertos de entrada y salida                                | 15 min  | `refactor: define puertos de pedidos`                      |
| —    | *Descanso*                                                         | 15 min  |                                                            |
| 3    | Mover lógica a `CrearPedidoService` / `ConfirmarPedidoService`     | 20 min  | `refactor: mueve confirmación a caso de uso`               |
| 4    | Adaptadores: persistencia, pago (`RestClient`), email              | 20 min  | `feat: adaptador de pasarela de pago con RestClient`       |
| 5    | Cablear (`BeanConfiguration`), conmutar y retirar código viejo     | 10 min  | `chore: retira PedidoService legacy`                       |

Detalle paso a paso en [02-migracion-arquitectonica.md](02-migracion-arquitectonica.md).

---

## 9. Testing: cómo se valida el entregable

```bash
mvn verify                          # unitarios + integración + ArchUnit
mvn jacoco:report                   # cobertura → target/site/jacoco/index.html
mvn org.pitest:pitest-maven:mutationCoverage   # mutation score del dominio
```

Criterio mínimo para "entregado":

- [ ] Los tests de caracterización pasan **sin modificar** los ficheros de
      golden master de los casos de éxito.
- [ ] `ArquitecturaHexagonalTest` en verde.
- [ ] `grep -r "import org.springframework" src/main/java/com/curso/pedidos/domain` → vacío.
- [ ] La suite completa tarda menos de 60 s en local.

---

## Ejercicio

Antes de tocar nada, **haz tu propio diagnóstico** del monolito: lista al menos
**8 problemas**, clasifícalos según el riesgo que suponen (negocio, técnico,
seguridad) y decide **en qué orden los atacarías**.

Pista: empieza por el que tiene **impacto económico** (cobrar sin pedido) y
por el que **impide** arreglar todo lo demás (no hay tests).

<details><summary>Solución</summary>

Orden recomendado:

1. **Sin tests** → golden master (fase 0). Sin esto, todo lo demás es ruleta.
2. **Cobro dentro de la transacción** (riesgo económico) → puerto
   `PasarelaDePago`, cobrar *después* de persistir la confirmación y marcar
   como pagado en una segunda transacción corta (o saga, día 6).
3. **Email dentro de la transacción** (riesgo de disponibilidad) →
   `NotificadorPedidos` invocado tras el commit (evento + listener
   `@TransactionalEventListener(phase = AFTER_COMMIT)` o outbox).
4. **Entidad como DTO** (riesgo de seguridad: `tokenTarjeta`) → DTOs.
5. **Cancelar un pedido enviado** (bug de negocio) → invariante en `Pedido.cancelar()`.
6. **Stock negativo** → `StockInsuficienteException` validado en el caso de uso.
7. **Descuento en el controlador** → servicio de dominio `CalculadoraDePrecios`.
8. **500 con mensaje interno** → excepciones de dominio + `@ControllerAdvice`.
9. **Estado como `String`** → `EstadoPedido` enum (con mapper tolerante a
   `"confirmado"` en minúsculas para datos antiguos).
10. **`@Autowired` en campos** → constructores (sale solo al crear adaptadores).

</details>

---

## Referencias

- Michael Feathers, *Working Effectively with Legacy Code* (Prentice Hall, 2004).
- Martin Fowler, *Anemic Domain Model*: https://martinfowler.com/bliki/AnemicDomainModel.html
- Martin Fowler, *Strangler Fig Application*: https://martinfowler.com/bliki/StranglerFigApplication.html
- Spring Framework, *Transaction Management*: https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- Alistair Cockburn, *Hexagonal Architecture* (2005): https://alistair.cockburn.us/hexagonal-architecture/
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023).

## Mantra

> *"Antes de cambiar el código, entiende qué hace. Antes de entenderlo,
> escribe un test que lo diga."*
