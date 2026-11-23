# Día 8 — Bloque 2: Migración arquitectónica paso a paso

> *"The most important reason to consider a strangler fig application over a
> cut-over rewrite is reduced risk."*
> — Martin Fowler, *Strangler Fig Application*

---

## 1. El problema en una frase

Tenemos que cambiar la arquitectura de un sistema **que está en producción y
sin tests**, sin pararlo y sin un "big bang" de tres meses que nadie va a
aprobar.

En cristiano: **no se reescribe; se sustituye por trozos, con una red debajo.**

---

## 2. Dos estrategias que vamos a combinar

### 2.1 Strangler Fig (Fowler)

La higuera estranguladora crece alrededor de un árbol, lo va envolviendo y,
cuando el árbol muere, la higuera ya se sostiene sola. Aplicado al software:

> Construyes el sistema nuevo **alrededor** del viejo, desviando
> funcionalidad poco a poco, hasta que el viejo puede retirarse.

En nuestro monolito el "desvío" es **por endpoint**: primero
`POST /api/pedidos/{id}/confirmar` lo atiende el hexágono; los demás siguen en
el código viejo. Luego crear, luego cancelar… Reembolsos e informes pueden
quedarse donde están durante meses, y no pasa nada.

### 2.2 Branch by Abstraction (Fowler / Paul Hammant)

Para cambiar una pieza **dentro** del mismo código sin ramas de larga vida:

1. Crea una **abstracción** delante de la pieza vieja.
2. Haz que todos los clientes usen la abstracción.
3. Escribe la implementación **nueva** detrás de la misma abstracción.
4. Conmuta (flag/configuración) y verifica.
5. Borra la implementación vieja (y, si ya no aporta, la abstracción).

¿A que la abstracción os suena? **Es un puerto.** Branch by Abstraction y
arquitectura hexagonal encajan como un guante: el puerto que creamos para
migrar es el mismo que se queda para siempre.

```
 Branch by Abstraction aplicado a "confirmar pedido"

 Paso 1-2                         Paso 3-4                        Paso 5
┌────────────┐                  ┌────────────┐                  ┌────────────┐
│ Controller │                  │ Controller │                  │ Controller │
└─────┬──────┘                  └─────┬──────┘                  └─────┬──────┘
      ▼                               ▼                               ▼
┌──────────────────┐            ┌──────────────────┐            ┌──────────────────┐
│ConfirmarPedido-  │            │ConfirmarPedido-  │            │ConfirmarPedido-  │
│UseCase (puerto)  │            │UseCase (puerto)  │            │UseCase (puerto)  │
└─────┬────────────┘            └──┬───────────┬───┘            └─────┬────────────┘
      ▼                    flag=legacy│        │flag=hexagonal        ▼
┌──────────────────┐         ┌───────▼──┐  ┌───▼─────────────┐  ┌─────────────────┐
│LegacyConfirmar-  │         │ Legacy…  │  │ConfirmarPedido- │  │ConfirmarPedido- │
│PedidoAdapter ──► │         │ Adapter  │  │Service (nuevo)  │  │Service          │
│PedidoService viejo│        └──────────┘  └─────────────────┘  └─────────────────┘
└──────────────────┘
```

---

## 3. Mapa de la migración

```
 FASE 0            FASE 1            FASE 2            FASE 3            FASE 4            FASE 5
 Red de            Dominio           Puertos           Casos de uso      Adaptadores       Cablear y
 seguridad         puro                                                                    retirar
┌────────┐       ┌────────┐       ┌────────┐       ┌────────┐       ┌────────┐       ┌────────┐
│ golden │       │ Pedido │       │ in/out │       │Confirmar│      │JPA, Pago│      │ Bean-  │
│ master │──────►│ rico + │──────►│ ports  │──────►│Pedido-  │─────►│ Email,  │─────►│ Config │
│+WireMock│      │  VOs   │       │+legacy │       │Service  │      │ Web     │      │ borrar │
└────────┘       └────────┘       │ adapter│       └────────┘       └────────┘       │ legacy │
    ▲                             └────────┘                                         └────────┘
    │                                                                                     │
    └─────────────── los tests de caracterización se ejecutan en TODAS las fases ─────────┘
```

Regla de oro: **cada flecha es uno o varios commits en verde**.

---

## 4. Fase 0 — Red de seguridad: tests de caracterización

### 4.1 Qué es un test de caracterización

Feathers lo define como un test que **describe el comportamiento real** del
sistema, no el deseado. No juzga: fotografía. Si hoy confirmar un pedido
cancelado devuelve un 409 con el texto `"Pedido cancelado"`, el test lo fija.

La variante *golden master* (o *approval testing*) es perfecta para un API
REST: lanzas peticiones, guardas la respuesta normalizada en un fichero la
primera vez y, a partir de ahí, comparas.

### 4.2 Antes de nada: costuras (*seams*) mínimas

El monolito usa `Instant.now()` y `LocalDate.now()`. Y hoy es **noviembre**,
así que el descuento de Black Friday del controlador se dispara con más de tres
líneas. Un golden master no determinista no sirve.

Primer cambio de código, el más pequeño posible (Feathers lo llama
*parameterize constructor* / introducir una costura):

```java
// ANTES (PedidoController legacy)
if (LocalDate.now().getMonth() == Month.NOVEMBER && p.getLineas().size() > 3) { ... }

// DESPUÉS: misma lógica, el tiempo entra por una costura
@Autowired private Clock clock;
...
if (LocalDate.now(clock).getMonth() == Month.NOVEMBER && p.getLineas().size() > 3) { ... }
```

```java
@Configuration
class ClockConfig {
    @Bean
    Clock clock() { return Clock.systemDefaultZone(); }
}
```

> Trampa: "aprovechar" este commit para limpiar más cosas. No. El único
> cambio sin red que nos permitimos es el que **crea** la red.

### 4.3 Dependencias de test

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <version>3.9.1</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

> En la solución de referencia la suite de integración usa Testcontainers con
> PostgreSQL (día 5). Para el golden master del aula usamos H2 por velocidad;
> si el monolito usa SQL nativo de PostgreSQL, salta directamente a
> Testcontainers.

### 4.4 El ayudante de golden master

```java
package com.curso.pedidos.characterization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Compara una respuesta HTTP con un fichero aprobado. La primera vez lo crea y falla. */
final class GoldenMaster {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DIRECTORIO = Path.of("src/test/resources/golden");

    /** Campos no deterministas o fuera de contrato: se eliminan a cualquier profundidad. */
    private static final Set<String> VOLATILES = Set.of("id", "creadoEn");

    private GoldenMaster() { }

    static void verificar(String nombre, int status, String cuerpo) throws IOException {
        ObjectNode actual = MAPPER.createObjectNode();
        actual.put("status", status);
        actual.set("body", cuerpo == null || cuerpo.isBlank()
                ? MAPPER.nullNode()
                : normalizar(MAPPER.readTree(cuerpo)));

        Path fichero = DIRECTORIO.resolve(nombre + ".json");
        if (Files.notExists(fichero)) {
            Files.createDirectories(DIRECTORIO);
            Files.writeString(fichero,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(actual));
            fail("Golden master creado en %s. Revísalo, haz commit y vuelve a ejecutar.", fichero);
        }
        JsonNode esperado = MAPPER.readTree(Files.readString(fichero));
        assertThat(actual).isEqualTo(esperado);
    }

    static void verificarSoloStatus(String nombre, int status) throws IOException {
        verificar(nombre, status, null);
    }

    private static JsonNode normalizar(JsonNode nodo) {
        if (nodo.isObject()) {
            ObjectNode objeto = (ObjectNode) nodo;
            objeto.remove(VOLATILES);
            objeto.forEach(GoldenMaster::normalizar);
        } else if (nodo.isArray()) {
            nodo.forEach(GoldenMaster::normalizar);
        }
        return nodo;
    }
}
```

> Decisión consciente: eliminamos `id` también dentro de `lineas[]`. Antes lo
> hemos comprobado con el equipo de front (y en los logs de acceso): nadie usa
> el id de línea. Esa decisión va al ADR.

### 4.5 La suite de caracterización

```java
package com.curso.pedidos.characterization;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Sql(scripts = "/sql/catalogo-y-limpieza.sql")
class PedidoApiCharacterizationTest {

    @RegisterExtension
    static WireMockExtension pagos = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("pagos.url", pagos::baseUrl);
        registro.add("pagos.apiKey", () -> "test-key");
    }

    @TestConfiguration
    static class RelojFijo {
        @Bean @Primary
        Clock clockDeTest() {            // un martes de marzo: sin Black Friday
            return Clock.fixed(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @MockBean            // Spring Boot 3.3. En 3.4+ se usa @MockitoBean
    JavaMailSender mailSender;

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        pagos.stubFor(post("/charges")
                .withHeader("X-Api-Key", equalTo("test-key"))
                .willReturn(okJson("""
                        {"id": "ch_123", "status": "succeeded"}
                        """)));
    }

    private static final String PEDIDO_VALIDO = """
            {
              "clienteId": "8f14e45f-ceea-467a-9d3b-2f1c6a1e2b10",
              "clienteEmail": "ana@example.com",
              "lineas": [
                { "sku": "LIBRO-DDD",  "cantidad": 1 },
                { "sku": "TAZA-HEX",   "cantidad": 2 }
              ]
            }
            """;

    @Test
    void crear_pedido() throws Exception {
        ResponseEntity<String> r = http.postForEntity("/api/pedidos", json(PEDIDO_VALIDO), String.class);
        GoldenMaster.verificar("crear-pedido", r.getStatusCode().value(), r.getBody());
    }

    @Test
    void crear_pedido_sin_lineas() throws Exception {
        ResponseEntity<String> r = http.postForEntity("/api/pedidos",
                json("""
                     {"clienteId":"8f14e45f-ceea-467a-9d3b-2f1c6a1e2b10","lineas":[]}
                     """), String.class);
        GoldenMaster.verificarSoloStatus("crear-pedido-sin-lineas", r.getStatusCode().value());
    }

    @Test
    void confirmar_pedido_cobra_y_notifica() throws Exception {
        String id = crearPedido();

        ResponseEntity<String> r = http.postForEntity(
                "/api/pedidos/{id}/confirmar?tokenTarjeta=tok_visa", null, String.class, id);

        GoldenMaster.verificar("confirmar-pedido", r.getStatusCode().value(), r.getBody());
        pagos.verify(postRequestedFor(urlEqualTo("/charges"))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("5990")))
                .withRequestBody(matchingJsonPath("$.token", equalTo("tok_visa"))));
    }

    @Test
    void confirmar_con_pago_rechazado() throws Exception {
        pagos.stubFor(post("/charges").willReturn(aResponse().withStatus(402)));
        String id = crearPedido();

        ResponseEntity<String> r = http.postForEntity(
                "/api/pedidos/{id}/confirmar?tokenTarjeta=tok_rechazo", null, String.class, id);

        GoldenMaster.verificarSoloStatus("confirmar-pago-rechazado", r.getStatusCode().value());
        // Y el pedido sigue en BORRADOR (el rollback del legacy lo dejaba así)
        GoldenMaster.verificar("pedido-tras-pago-rechazado", 200,
                http.getForObject("/api/pedidos/{id}", String.class, id));
    }

    @Test
    void cancelar_pedido_confirmado() throws Exception {
        String id = crearPedido();
        http.postForEntity("/api/pedidos/{id}/confirmar?tokenTarjeta=tok_visa", null, String.class, id);

        ResponseEntity<String> r = http.postForEntity("/api/pedidos/{id}/cancelar", null, String.class, id);
        GoldenMaster.verificar("cancelar-pedido", r.getStatusCode().value(), r.getBody());
    }

    @Test
    void obtener_pedido_inexistente() throws Exception {
        ResponseEntity<String> r = http.getForEntity(
                "/api/pedidos/{id}", String.class, "00000000-0000-0000-0000-000000000000");
        GoldenMaster.verificar("obtener-inexistente", r.getStatusCode().value(), r.getBody());
    }

    // --- utilidades ---
    private String crearPedido() {
        @SuppressWarnings("unchecked")
        Map<String, Object> creado = http.postForObject("/api/pedidos", json(PEDIDO_VALIDO), Map.class);
        return (String) creado.get("id");
    }

    private static org.springframework.http.HttpEntity<String> json(String cuerpo) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new org.springframework.http.HttpEntity<>(cuerpo, headers);
    }
}
```

```sql
-- src/test/resources/sql/catalogo-y-limpieza.sql
DELETE FROM lineas_pedido;
DELETE FROM pedidos;
DELETE FROM productos;
INSERT INTO productos (sku, nombre, precio, stock) VALUES ('LIBRO-DDD', 'Domain-Driven Design', 45.90, 10);
INSERT INTO productos (sku, nombre, precio, stock) VALUES ('TAZA-HEX',  'Taza hexagonal',        7.00, 50);
```

Primera ejecución: todos fallan **a propósito** y crean los ficheros en
`src/test/resources/golden/`. Se revisan a mano (¿tiene sentido lo que dice?),
y se hace commit:

```bash
git add src/test
git commit -m "test: añade golden master del API de pedidos"
```

### 4.6 ¿Qué descubrimos al caracterizar?

Lo mejor del golden master es lo que **revela**:

| Petición                               | Comportamiento real del legacy    | ¿Lo mantenemos?                                         |
|----------------------------------------|-----------------------------------|---------------------------------------------------------|
| `GET` de un id inexistente             | `200` con cuerpo vacío            | **No**: pasa a `404` (cambio documentado en ADR-0001)   |
| Confirmar con pago rechazado           | `500` con texto de excepción      | **No**: pasa a `402` con `ProblemDetail`                |
| Confirmar un pedido cancelado          | `409` texto plano                 | Sí el `409`; el cuerpo pasa a `ProblemDetail`           |
| Cancelar un pedido `ENVIADO`           | `200` y lo cancela (¡bug!)        | **No**: `409` (invariante de dominio)                   |
| Crear con `precioUnitario` en el JSON  | Usa el precio del cliente (¡fraude!) | **No**: siempre precio de catálogo                   |
| Crear y confirmar (casos felices)      | `201` / `200` con JSON del pedido | **Sí, idéntico**                                        |

Por eso en los errores solo fijamos el `status`: sabemos que el cuerpo va a
cambiar. Y cada cambio de comportamiento deliberado se hace en **un commit
propio** que actualiza su golden master, nunca mezclado con un refactor.

> Trampa: regenerar todos los golden masters "porque fallan". Si un caso feliz
> falla durante un refactor, **el refactor está mal**, no el fichero.

---

## 5. Fase 1 — Extraer el dominio puro de la entidad JPA

### 5.1 Antes

`PedidoEntity`: anotaciones JPA y Jackson, setters públicos, estado `String`,
total que "a veces" se calcula, reglas repartidas entre controlador y servicio.

### 5.2 Después: value objects

```java
package com.curso.pedidos.domain.model;

import java.util.Objects;
import java.util.UUID;

public record PedidoId(UUID valor) {
    public PedidoId { Objects.requireNonNull(valor, "PedidoId requiere valor"); }
    public static PedidoId nuevo() { return new PedidoId(UUID.randomUUID()); }
}
```

```java
public record ClienteId(UUID valor) {
    public ClienteId { Objects.requireNonNull(valor, "ClienteId requiere valor"); }
}

public record ProductoId(String sku) {
    public ProductoId {
        if (sku == null || sku.isBlank()) throw new IllegalArgumentException("SKU obligatorio");
    }
}

public record Cantidad(int valor) {
    public Cantidad {
        if (valor <= 0) throw new IllegalArgumentException("La cantidad debe ser > 0: " + valor);
    }
}

public record Email(String valor) {
    public Email {
        if (valor == null || !valor.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new IllegalArgumentException("Email no válido: " + valor);
    }
}
```

```java
package com.curso.pedidos.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Dinero(BigDecimal importe, Currency moneda) {

    public static final Currency EUR = Currency.getInstance("EUR");

    public Dinero {
        Objects.requireNonNull(importe, "importe");
        Objects.requireNonNull(moneda, "moneda");
        if (importe.signum() < 0) throw new IllegalArgumentException("Importe negativo");
        importe = importe.setScale(2, RoundingMode.HALF_UP);
    }

    public static Dinero euros(String importe) { return new Dinero(new BigDecimal(importe), EUR); }
    public static Dinero cero(Currency moneda) { return new Dinero(BigDecimal.ZERO, moneda); }

    public Dinero sumar(Dinero otro) {
        exigirMismaMoneda(otro);
        return new Dinero(importe.add(otro.importe), moneda);
    }

    public Dinero multiplicar(int factor) {
        return new Dinero(importe.multiply(BigDecimal.valueOf(factor)), moneda);
    }

    public Dinero aplicarPorcentajeDescuento(int porcentaje) {
        BigDecimal factor = BigDecimal.valueOf(100 - porcentaje).movePointLeft(2);
        return new Dinero(importe.multiply(factor), moneda);
    }

    public long enCentimos() {
        return importe.movePointRight(2).longValueExact();
    }

    private void exigirMismaMoneda(Dinero otro) {
        if (!moneda.equals(otro.moneda))
            throw new IllegalArgumentException("Monedas distintas: " + moneda + " / " + otro.moneda);
    }
}
```

### 5.3 Después: el agregado `Pedido`

```java
package com.curso.pedidos.domain.model;

public enum EstadoPedido { BORRADOR, CONFIRMADO, PAGADO, ENVIADO, CANCELADO }
```

```java
package com.curso.pedidos.domain.model;

import java.util.Objects;

public final class LineaPedido {

    private final ProductoId productoId;
    private final Cantidad cantidad;
    private final Dinero precioUnitario;

    public LineaPedido(ProductoId productoId, Cantidad cantidad, Dinero precioUnitario) {
        this.productoId = Objects.requireNonNull(productoId);
        this.cantidad = Objects.requireNonNull(cantidad);
        this.precioUnitario = Objects.requireNonNull(precioUnitario);
    }

    public Dinero subtotal() { return precioUnitario.multiplicar(cantidad.valor()); }

    public ProductoId productoId() { return productoId; }
    public Cantidad cantidad() { return cantidad; }
    public Dinero precioUnitario() { return precioUnitario; }
}
```

```java
package com.curso.pedidos.domain.model;

import com.curso.pedidos.domain.event.EventoDeDominio;
import com.curso.pedidos.domain.event.PedidoCancelado;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.event.PedidoPagado;
import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Pedido {

    private final PedidoId id;
    private final ClienteId clienteId;
    private final Email emailContacto;
    private final List<LineaPedido> lineas;
    private EstadoPedido estado;
    private final Instant creadoEn;
    private String referenciaPago;
    private final List<EventoDeDominio> eventos = new ArrayList<>();

    private Pedido(PedidoId id, ClienteId clienteId, Email emailContacto, List<LineaPedido> lineas,
                   EstadoPedido estado, Instant creadoEn, String referenciaPago) {
        this.id = Objects.requireNonNull(id);
        this.clienteId = Objects.requireNonNull(clienteId);
        this.emailContacto = Objects.requireNonNull(emailContacto);
        this.lineas = new ArrayList<>(lineas);
        this.estado = Objects.requireNonNull(estado);
        this.creadoEn = Objects.requireNonNull(creadoEn);
        this.referenciaPago = referenciaPago;
    }

    /** Factoría para pedidos nuevos. */
    public static Pedido crear(ClienteId clienteId, Email emailContacto, Instant ahora) {
        return new Pedido(PedidoId.nuevo(), clienteId, emailContacto, List.of(),
                EstadoPedido.BORRADOR, ahora, null);
    }

    /** Factoría para reconstruir desde persistencia: no valida transiciones ni emite eventos. */
    public static Pedido reconstituir(PedidoId id, ClienteId clienteId, Email emailContacto,
                                      List<LineaPedido> lineas, EstadoPedido estado,
                                      Instant creadoEn, String referenciaPago) {
        return new Pedido(id, clienteId, emailContacto, lineas, estado, creadoEn, referenciaPago);
    }

    public void anadirLinea(ProductoId productoId, Cantidad cantidad, Dinero precioUnitario) {
        if (estado != EstadoPedido.BORRADOR) {
            throw new TransicionDeEstadoInvalidaException(estado, "añadir líneas");
        }
        lineas.add(new LineaPedido(productoId, cantidad, precioUnitario));
    }

    public void confirmar(Instant ahora) {
        if (estado != EstadoPedido.BORRADOR) {
            throw new TransicionDeEstadoInvalidaException(estado, "confirmar");
        }
        if (lineas.isEmpty()) {
            throw new PedidoException("No se puede confirmar un pedido vacío");
        }
        estado = EstadoPedido.CONFIRMADO;
        eventos.add(new PedidoConfirmado(id, ahora));
    }

    public void marcarComoPagado(String referenciaPago, Instant ahora) {
        if (estado != EstadoPedido.CONFIRMADO) {
            throw new TransicionDeEstadoInvalidaException(estado, "marcar como pagado");
        }
        this.referenciaPago = Objects.requireNonNull(referenciaPago);
        estado = EstadoPedido.PAGADO;
        eventos.add(new PedidoPagado(id, ahora));
    }

    public void cancelar(Instant ahora) {
        if (estado == EstadoPedido.ENVIADO || estado == EstadoPedido.CANCELADO) {
            throw new TransicionDeEstadoInvalidaException(estado, "cancelar");
        }
        estado = EstadoPedido.CANCELADO;
        eventos.add(new PedidoCancelado(id, ahora));
    }

    public Dinero total() {
        return lineas.stream()
                .map(LineaPedido::subtotal)
                .reduce(Dinero::sumar)
                .orElse(Dinero.cero(Dinero.EUR));
    }

    /** Devuelve y vacía los eventos pendientes de publicar. */
    public List<EventoDeDominio> extraerEventos() {
        List<EventoDeDominio> pendientes = List.copyOf(eventos);
        eventos.clear();
        return pendientes;
    }

    public PedidoId id() { return id; }
    public ClienteId clienteId() { return clienteId; }
    public Email emailContacto() { return emailContacto; }
    public List<LineaPedido> lineas() { return Collections.unmodifiableList(lineas); }
    public EstadoPedido estado() { return estado; }
    public Instant creadoEn() { return creadoEn; }
    public String referenciaPago() { return referenciaPago; }
}
```

Notas de diseño (para comentar en clase):

- **`emailContacto` en el agregado.** El monolito guarda el email en el pedido
  (es el email de contacto *de ese pedido*, no el del perfil del cliente).
  Respetamos el dato; si mañana viene de un servicio de clientes, se cambia.
- **El tiempo entra como parámetro** (`Instant ahora`): el dominio no llama a
  `Instant.now()`. El servicio de aplicación lo obtiene de un `Clock`.
- **`referenciaPago`** se guarda al pagar porque el API ya la devolvía.
- Sin Lombok: queremos que cada método público sea **una decisión**, no un
  getter/setter generado.

```java
package com.curso.pedidos.domain.event;

import com.curso.pedidos.domain.model.PedidoId;
import java.time.Instant;

public sealed interface EventoDeDominio permits PedidoConfirmado, PedidoPagado, PedidoCancelado {
    PedidoId pedidoId();
    Instant ocurridoEn();
}

public record PedidoConfirmado(PedidoId pedidoId, Instant ocurridoEn) implements EventoDeDominio { }
public record PedidoPagado(PedidoId pedidoId, Instant ocurridoEn) implements EventoDeDominio { }
public record PedidoCancelado(PedidoId pedidoId, Instant ocurridoEn) implements EventoDeDominio { }
```

```java
package com.curso.pedidos.domain.exception;

public class PedidoException extends RuntimeException {
    public PedidoException(String mensaje) { super(mensaje); }
}

public class PedidoNoEncontradoException extends PedidoException {
    public PedidoNoEncontradoException(PedidoId id) { super("Pedido no encontrado: " + id.valor()); }
}

public class StockInsuficienteException extends PedidoException {
    public StockInsuficienteException(ProductoId p) { super("Stock insuficiente de " + p.sku()); }
}

public class TransicionDeEstadoInvalidaException extends PedidoException {
    public TransicionDeEstadoInvalidaException(EstadoPedido actual, String accion) {
        super("No se puede " + accion + " un pedido en estado " + actual);
    }
}

public class PagoRechazadoException extends PedidoException {
    public PagoRechazadoException(PedidoId id, String motivo) {
        super("Pago rechazado para el pedido " + id.valor() + ": " + motivo);
    }
}
```

> (En el proyecto real cada tipo va en su fichero; aquí se agrupan para leer.)

### 5.4 Servicio de dominio: el descuento que vivía en el controlador

```java
package com.curso.pedidos.domain.service;

import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.Pedido;
import java.time.LocalDate;
import java.time.Month;

/** Regla de negocio descubierta con el golden master: 10 % en noviembre con más de 3 líneas. */
public class CalculadoraDePrecios {

    public Dinero importeACobrar(Pedido pedido, LocalDate fecha) {
        Dinero total = pedido.total();
        boolean campanaNoviembre = fecha.getMonth() == Month.NOVEMBER && pedido.lineas().size() > 3;
        return campanaNoviembre ? total.aplicarPorcentajeDescuento(10) : total;
    }
}
```

### 5.5 Testing de la fase 1: dominio sin Spring, en milisegundos

```java
package com.curso.pedidos.domain.model;

import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class PedidoTest {

    private static final Instant AHORA = Instant.parse("2026-03-10T10:00:00Z");

    private Pedido pedidoConUnaLinea() {
        Pedido p = Pedido.crear(new ClienteId(UUID.randomUUID()), new Email("ana@example.com"), AHORA);
        p.anadirLinea(new ProductoId("TAZA-HEX"), new Cantidad(2), Dinero.euros("7.00"));
        return p;
    }

    @Test
    void calcula_el_total_sumando_subtotales() {
        Pedido p = pedidoConUnaLinea();
        p.anadirLinea(new ProductoId("LIBRO-DDD"), new Cantidad(1), Dinero.euros("45.90"));
        assertThat(p.total()).isEqualTo(Dinero.euros("59.90"));
    }

    @Test
    void no_se_confirma_un_pedido_vacio() {
        Pedido vacio = Pedido.crear(new ClienteId(UUID.randomUUID()), new Email("a@b.es"), AHORA);
        assertThatThrownBy(() -> vacio.confirmar(AHORA))
                .isInstanceOf(PedidoException.class)
                .hasMessageContaining("vacío");
    }

    @Test
    void confirmar_emite_evento_y_cambia_estado() {
        Pedido p = pedidoConUnaLinea();
        p.confirmar(AHORA);
        assertThat(p.estado()).isEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(p.extraerEventos()).containsExactly(new PedidoConfirmado(p.id(), AHORA));
        assertThat(p.extraerEventos()).isEmpty();
    }

    @Test
    void no_se_anaden_lineas_a_un_pedido_confirmado() {
        Pedido p = pedidoConUnaLinea();
        p.confirmar(AHORA);
        assertThatThrownBy(() -> p.anadirLinea(new ProductoId("X"), new Cantidad(1), Dinero.euros("1")))
                .isInstanceOf(TransicionDeEstadoInvalidaException.class);
    }

    @Test
    void no_se_cancela_un_pedido_enviado() {
        Pedido enviado = Pedido.reconstituir(PedidoId.nuevo(), new ClienteId(UUID.randomUUID()),
                new Email("a@b.es"), pedidoConUnaLinea().lineas(), EstadoPedido.ENVIADO, AHORA, "ch_1");
        assertThatThrownBy(() -> enviado.cancelar(AHORA))
                .isInstanceOf(TransicionDeEstadoInvalidaException.class);
    }

    @Test
    void solo_se_paga_un_pedido_confirmado() {
        Pedido p = pedidoConUnaLinea();
        assertThatThrownBy(() -> p.marcarComoPagado("ch_1", AHORA))
                .isInstanceOf(TransicionDeEstadoInvalidaException.class);
    }
}
```

```java
class CalculadoraDePreciosTest {
    private final CalculadoraDePrecios calc = new CalculadoraDePrecios();

    @Test
    void aplica_diez_por_ciento_en_noviembre_con_mas_de_tres_lineas() {
        Pedido p = Pedido.crear(new ClienteId(UUID.randomUUID()), new Email("a@b.es"), Instant.EPOCH);
        for (int i = 1; i <= 4; i++) p.anadirLinea(new ProductoId("SKU-" + i), new Cantidad(1), Dinero.euros("10"));

        assertThat(calc.importeACobrar(p, LocalDate.of(2026, 11, 23))).isEqualTo(Dinero.euros("36.00"));
        assertThat(calc.importeACobrar(p, LocalDate.of(2026, 12, 1))).isEqualTo(Dinero.euros("40.00"));
    }
}
```

Aún **nadie usa** `Pedido`: hemos añadido código, no cambiado comportamiento.
Golden master en verde, commit:

```bash
git commit -m "refactor: extrae modelo de dominio Pedido con invariantes y tests"
```

> Trampa: poner `@Entity` en `Pedido` "para no duplicar". Es la tentación más
> común del día. El día que una columna cambie, tu dominio cambiará con ella.
> La duplicación entre `Pedido` y `PedidoJpaEntity` es **deliberada**: son dos
> modelos con dos razones para cambiar (día 6).

---

## 6. Fase 2 — Definir los puertos

### 6.1 Primero, un renombrado que evita un choque

El monolito ya tiene una interfaz llamada `PedidoRepository`… que es un
`JpaRepository`. Nuestro puerto de salida se va a llamar igual. Antes de nada:

```bash
# Refactor > Rename del IDE: PedidoRepository (Spring Data) → PedidoJpaRepository
git commit -m "refactor: renombra repositorio Spring Data a PedidoJpaRepository"
```

### 6.2 Puertos de entrada

```java
package com.curso.pedidos.application.port.in;

public interface CrearPedidoUseCase {
    PedidoId ejecutar(CrearPedidoCommand comando);

    record CrearPedidoCommand(ClienteId clienteId, Email emailContacto, List<LineaSolicitada> lineas) {
        public CrearPedidoCommand {
            lineas = List.copyOf(lineas);
            if (lineas.isEmpty()) throw new IllegalArgumentException("Al menos una línea");
        }
    }

    record LineaSolicitada(ProductoId productoId, Cantidad cantidad) { }
}
```

```java
public interface ConfirmarPedidoUseCase {
    void ejecutar(ConfirmarPedidoCommand comando);

    record ConfirmarPedidoCommand(PedidoId pedidoId, String tokenTarjeta) {
        public ConfirmarPedidoCommand {
            Objects.requireNonNull(pedidoId);
            if (tokenTarjeta == null || tokenTarjeta.isBlank())
                throw new IllegalArgumentException("tokenTarjeta obligatorio");
        }
    }
}

public interface CancelarPedidoUseCase {
    void ejecutar(PedidoId pedidoId);
}

public interface ConsultarPedidoQuery {
    Pedido porId(PedidoId pedidoId);     // lanza PedidoNoEncontradoException
}
```

> Decisión pragmática: `ConsultarPedidoQuery` devuelve el agregado `Pedido` y
> el adaptador web lo mapea a DTO. Si en tu equipo preferís no exponer el
> agregado fuera de la aplicación, devolved un `PedidoVista` (record de solo
> lectura). Ambas son válidas; elegid una y escribidla en un ADR.

### 6.3 Puertos de salida

```java
package com.curso.pedidos.application.port.out;

public interface PedidoRepository {
    void guardar(Pedido pedido);
    Optional<Pedido> buscarPorId(PedidoId id);
}

public interface CatalogoProductos {
    Optional<Producto> buscarPorId(ProductoId id);
    /** Descuenta stock de forma atómica; lanza StockInsuficienteException si no hay. */
    void descontarStock(ProductoId id, Cantidad cantidad);
}

public interface PasarelaDePago {
    ResultadoPago cobrar(PedidoId pedidoId, Dinero importe, String tokenTarjeta);
    void anularCobro(String referenciaPago);

    sealed interface ResultadoPago permits Aprobado, Rechazado { }
    record Aprobado(String referencia) implements ResultadoPago { }
    record Rechazado(String motivo) implements ResultadoPago { }
}

public interface NotificadorPedidos {
    void notificarConfirmacion(Pedido pedido);
}

public interface PublicadorDeEventos {
    void publicar(List<EventoDeDominio> eventos);
}

/** Límite transaccional expresado sin Spring. */
public interface UnidadDeTrabajo {
    <T> T ejecutar(Supplier<T> trabajo);

    default void ejecutar(Runnable trabajo) {
        ejecutar(() -> { trabajo.run(); return null; });
    }
}
```

`Producto` vive en el dominio (`domain.model`):

```java
public record Producto(ProductoId id, String nombre, Dinero precio, int stock) {
    public boolean hayStockPara(Cantidad cantidad) { return stock >= cantidad.valor(); }
}
```

> ¿Por qué `UnidadDeTrabajo` y no `@Transactional` en el servicio? Porque la
> regla del curso es que `application` no importa Spring, y porque aquí
> **necesitamos dos transacciones cortas con una llamada HTTP en medio**. La
> alternativa pragmática (anotar los servicios con `@Transactional` y aceptar
> la dependencia) se discutió en el día 6; ambas son defendibles.

### 6.4 Branch by Abstraction: el primer adaptador es el código viejo

Antes de escribir la lógica nueva, ponemos el puerto delante del código viejo:

```java
package com.curso.pedidos.infrastructure.adapter.legacy;

/** Implementación TEMPORAL del puerto que delega en el PedidoService heredado. */
public class LegacyConfirmarPedidoAdapter implements ConfirmarPedidoUseCase {

    private final PedidoService pedidoServiceLegacy;
    private final PedidoJpaRepository pedidoJpaRepository;

    public LegacyConfirmarPedidoAdapter(PedidoService legacy, PedidoJpaRepository repo) {
        this.pedidoServiceLegacy = legacy;
        this.pedidoJpaRepository = repo;
    }

    @Override
    public void ejecutar(ConfirmarPedidoCommand comando) {
        PedidoEntity entidad = pedidoJpaRepository.findById(comando.pedidoId().valor())
                .orElseThrow(() -> new PedidoNoEncontradoException(comando.pedidoId()));
        pedidoServiceLegacy.confirmarYCobrar(entidad, comando.tokenTarjeta());
    }
}
```

Y el controlador viejo empieza a hablar con el puerto:

```java
// LegacyPedidoController (antes PedidoController), método confirmar:
confirmarPedidoUseCase.ejecutar(new ConfirmarPedidoCommand(new PedidoId(id), tokenTarjeta));
return ResponseEntity.ok(pedidoJpaRepository.findById(id).orElseThrow());
```

> Trampa: el descuento de noviembre seguía en el controlador. Al pasar por el
> puerto **se pierde**… salvo que lo movamos al `PedidoService` legacy en el
> mismo commit. El golden master no lo detecta (reloj de marzo): añade un test
> de caracterización con reloj de noviembre y 4 líneas **antes** de este paso.

Golden master en verde. Commit:

```bash
git commit -m "refactor: introduce puerto ConfirmarPedidoUseCase delante del servicio legacy"
```

---

## 7. Fase 3 — Mover la lógica al servicio de aplicación

### 7.1 Antes

`PedidoService.confirmarYCobrar`: una sola `@Transactional` que descuenta
stock, cambia estado, llama por HTTP al cobro y envía el email por SMTP.

### 7.2 Después

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.out.*;
import com.curso.pedidos.application.port.out.PasarelaDePago.Aprobado;
import com.curso.pedidos.application.port.out.PasarelaDePago.Rechazado;
import com.curso.pedidos.domain.exception.*;
import com.curso.pedidos.domain.model.*;
import com.curso.pedidos.domain.service.CalculadoraDePrecios;

import java.time.Clock;
import java.time.LocalDate;

public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {

    private final PedidoRepository pedidos;
    private final CatalogoProductos catalogo;
    private final PasarelaDePago pasarela;
    private final NotificadorPedidos notificador;
    private final PublicadorDeEventos publicador;
    private final UnidadDeTrabajo unidadDeTrabajo;
    private final CalculadoraDePrecios calculadora;
    private final Clock clock;

    public ConfirmarPedidoService(PedidoRepository pedidos, CatalogoProductos catalogo,
                                  PasarelaDePago pasarela, NotificadorPedidos notificador,
                                  PublicadorDeEventos publicador, UnidadDeTrabajo unidadDeTrabajo,
                                  CalculadoraDePrecios calculadora, Clock clock) {
        this.pedidos = pedidos;
        this.catalogo = catalogo;
        this.pasarela = pasarela;
        this.notificador = notificador;
        this.publicador = publicador;
        this.unidadDeTrabajo = unidadDeTrabajo;
        this.calculadora = calculadora;
        this.clock = clock;
    }

    @Override
    public void ejecutar(ConfirmarPedidoCommand comando) {
        // 1. Cargar y validar EN MEMORIA (sin transacción abierta, sin bloquear filas)
        Pedido pedido = pedidos.buscarPorId(comando.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(comando.pedidoId()));
        pedido.confirmar(clock.instant());
        comprobarStock(pedido);

        // 2. Cobrar FUERA de la transacción
        Dinero importe = calculadora.importeACobrar(pedido, LocalDate.now(clock));
        var resultado = pasarela.cobrar(pedido.id(), importe, comando.tokenTarjeta());
        if (resultado instanceof Rechazado rechazado) {
            // No se ha persistido nada: el pedido sigue en BORRADOR (mismo efecto que el rollback legacy)
            throw new PagoRechazadoException(pedido.id(), rechazado.motivo());
        }
        String referencia = ((Aprobado) resultado).referencia();

        // 3. Transacción corta: stock + estado + eventos
        try {
            unidadDeTrabajo.ejecutar(() -> {
                pedido.lineas().forEach(l -> catalogo.descontarStock(l.productoId(), l.cantidad()));
                pedido.marcarComoPagado(referencia, clock.instant());
                pedidos.guardar(pedido);
                publicador.publicar(pedido.extraerEventos());
            });
        } catch (RuntimeException e) {
            pasarela.anularCobro(referencia);      // compensación: nunca cobrar sin pedido
            throw e;
        }

        // 4. Efectos secundarios no críticos, DESPUÉS del commit
        notificador.notificarConfirmacion(pedido);
    }

    private void comprobarStock(Pedido pedido) {
        for (LineaPedido linea : pedido.lineas()) {
            Producto producto = catalogo.buscarPorId(linea.productoId())
                    .orElseThrow(() -> new StockInsuficienteException(linea.productoId()));
            if (!producto.hayStockPara(linea.cantidad())) {
                throw new StockInsuficienteException(linea.productoId());
            }
        }
    }
}
```

¿Qué hemos ganado respecto al legacy?

| Aspecto                         | Legacy                                     | Hexagonal                                               |
|---------------------------------|--------------------------------------------|---------------------------------------------------------|
| Cobro y rollback                | Puede cobrar y luego hacer rollback        | Cobra antes; si la transacción falla, **anula el cobro** |
| Duración de la transacción      | HTTP + SMTP dentro (hasta 20 s)            | Solo escrituras en BD (milisegundos)                    |
| Email                           | Dentro de la transacción, error tragado    | Tras el commit; fallo aislado en el adaptador           |
| Stock negativo                  | Posible                                    | `descontarStock` atómico con condición                  |
| Reglas de estado                | `if` con `String`                          | Invariantes en `Pedido`                                 |
| Testeable sin Spring            | No                                         | Sí, en milisegundos                                     |

> Trampa: creer que "anular el cobro" es gratis. Si `anularCobro` también
> falla, necesitas reintentos o una saga con estado persistido (día 6,
> microservices.io). Para el proyecto final basta con dejarlo registrado y
> alertado; en producción, **outbox + reintentos**.

### 7.3 `CrearPedidoService`, `CancelarPedidoService` y `ConsultarPedidoService`

```java
public class CrearPedidoService implements CrearPedidoUseCase {

    private final PedidoRepository pedidos;
    private final CatalogoProductos catalogo;
    private final UnidadDeTrabajo unidadDeTrabajo;
    private final Clock clock;

    public CrearPedidoService(PedidoRepository pedidos, CatalogoProductos catalogo,
                              UnidadDeTrabajo unidadDeTrabajo, Clock clock) {
        this.pedidos = pedidos;
        this.catalogo = catalogo;
        this.unidadDeTrabajo = unidadDeTrabajo;
        this.clock = clock;
    }

    @Override
    public PedidoId ejecutar(CrearPedidoCommand comando) {
        Pedido pedido = Pedido.crear(comando.clienteId(), comando.emailContacto(), clock.instant());
        for (LineaSolicitada linea : comando.lineas()) {
            Producto producto = catalogo.buscarPorId(linea.productoId())
                    .orElseThrow(() -> new PedidoException("Producto no existe: " + linea.productoId().sku()));
            if (!producto.hayStockPara(linea.cantidad())) {
                throw new StockInsuficienteException(linea.productoId());
            }
            pedido.anadirLinea(producto.id(), linea.cantidad(), producto.precio()); // precio SIEMPRE de catálogo
        }
        unidadDeTrabajo.ejecutar(() -> pedidos.guardar(pedido));
        return pedido.id();
    }
}
```

```java
public class CancelarPedidoService implements CancelarPedidoUseCase {
    private final PedidoRepository pedidos;
    private final PublicadorDeEventos publicador;
    private final UnidadDeTrabajo unidadDeTrabajo;
    private final Clock clock;

    // constructor omitido

    @Override
    public void ejecutar(PedidoId pedidoId) {
        unidadDeTrabajo.ejecutar(() -> {
            Pedido pedido = pedidos.buscarPorId(pedidoId)
                    .orElseThrow(() -> new PedidoNoEncontradoException(pedidoId));
            pedido.cancelar(clock.instant());       // aquí muere el bug de "cancelar enviados"
            pedidos.guardar(pedido);
            publicador.publicar(pedido.extraerEventos());
        });
    }
}

public class ConsultarPedidoService implements ConsultarPedidoQuery {
    private final PedidoRepository pedidos;
    public ConsultarPedidoService(PedidoRepository pedidos) { this.pedidos = pedidos; }

    @Override
    public Pedido porId(PedidoId id) {
        return pedidos.buscarPorId(id).orElseThrow(() -> new PedidoNoEncontradoException(id));
    }
}
```

### 7.4 Testing de la fase 3: casos de uso con dobles

Usamos **fakes en memoria** para los repositorios (más legibles que mocks para
estado) y **Mockito** para los puertos con efectos (pago, notificación).

```java
package com.curso.pedidos.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConfirmarPedidoServiceTest {

    private static final Clock MARZO = Clock.fixed(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC);

    private final PedidoRepositoryEnMemoria pedidos = new PedidoRepositoryEnMemoria();
    private final CatalogoEnMemoria catalogo = new CatalogoEnMemoria();
    private final PasarelaDePago pasarela = mock(PasarelaDePago.class);
    private final NotificadorPedidos notificador = mock(NotificadorPedidos.class);
    private final PublicadorDeEventos publicador = mock(PublicadorDeEventos.class);
    private final UnidadDeTrabajo sinTransaccion = new UnidadDeTrabajo() {
        @Override public <T> T ejecutar(Supplier<T> trabajo) { return trabajo.get(); }
    };

    private ConfirmarPedidoService servicio;
    private Pedido pedido;

    @BeforeEach
    void setUp() {
        servicio = new ConfirmarPedidoService(pedidos, catalogo, pasarela, notificador,
                publicador, sinTransaccion, new CalculadoraDePrecios(), MARZO);
        catalogo.alta(new Producto(new ProductoId("TAZA-HEX"), "Taza", Dinero.euros("7.00"), 5));
        pedido = Pedido.crear(new ClienteId(UUID.randomUUID()), new Email("ana@example.com"), MARZO.instant());
        pedido.anadirLinea(new ProductoId("TAZA-HEX"), new Cantidad(2), Dinero.euros("7.00"));
        pedidos.guardar(pedido);
    }

    @Test
    void cobra_marca_como_pagado_descuenta_stock_y_notifica() {
        when(pasarela.cobrar(any(), any(), any())).thenReturn(new PasarelaDePago.Aprobado("ch_123"));

        servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id(), "tok_visa"));

        Pedido guardado = pedidos.buscarPorId(pedido.id()).orElseThrow();
        assertThat(guardado.estado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(guardado.referenciaPago()).isEqualTo("ch_123");
        assertThat(catalogo.stockDe("TAZA-HEX")).isEqualTo(3);
        verify(pasarela).cobrar(pedido.id(), Dinero.euros("14.00"), "tok_visa");
        verify(notificador).notificarConfirmacion(argThat(p -> p.id().equals(pedido.id())));
        verify(publicador).publicar(argThat(ev -> ev.size() == 2));   // Confirmado + Pagado
    }

    @Test
    void si_el_pago_se_rechaza_no_persiste_nada_ni_notifica() {
        when(pasarela.cobrar(any(), any(), any())).thenReturn(new PasarelaDePago.Rechazado("fondos"));

        assertThatThrownBy(() -> servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id(), "tok")))
                .isInstanceOf(PagoRechazadoException.class);

        assertThat(pedidos.buscarPorId(pedido.id()).orElseThrow().estado()).isEqualTo(EstadoPedido.BORRADOR);
        assertThat(catalogo.stockDe("TAZA-HEX")).isEqualTo(5);
        verifyNoInteractions(notificador);
    }

    @Test
    void si_falla_la_transaccion_anula_el_cobro() {
        when(pasarela.cobrar(any(), any(), any())).thenReturn(new PasarelaDePago.Aprobado("ch_999"));
        catalogo.fallarAlDescontar();     // simula que otro cliente se llevó el stock

        assertThatThrownBy(() -> servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id(), "tok")))
                .isInstanceOf(StockInsuficienteException.class);

        verify(pasarela).anularCobro("ch_999");
        verifyNoInteractions(notificador);
    }

    @Test
    void pedido_inexistente() {
        assertThatThrownBy(() -> servicio.ejecutar(new ConfirmarPedidoCommand(PedidoId.nuevo(), "tok")))
                .isInstanceOf(PedidoNoEncontradoException.class);
        verifyNoInteractions(pasarela);
    }
}
```

```java
/** Fake de test: guarda una "foto" para que los cambios no persistidos no se filtren. */
class PedidoRepositoryEnMemoria implements PedidoRepository {
    private final Map<PedidoId, Pedido> datos = new HashMap<>();

    @Override public void guardar(Pedido p) { datos.put(p.id(), copia(p)); }
    @Override public Optional<Pedido> buscarPorId(PedidoId id) { return Optional.ofNullable(datos.get(id)).map(this::copia); }

    private Pedido copia(Pedido p) {
        return Pedido.reconstituir(p.id(), p.clienteId(), p.emailContacto(), p.lineas(),
                p.estado(), p.creadoEn(), p.referenciaPago());
    }
}

class CatalogoEnMemoria implements CatalogoProductos {
    private final Map<ProductoId, Producto> productos = new HashMap<>();
    private boolean fallar;

    void alta(Producto p) { productos.put(p.id(), p); }
    void fallarAlDescontar() { fallar = true; }
    int stockDe(String sku) { return productos.get(new ProductoId(sku)).stock(); }

    @Override public Optional<Producto> buscarPorId(ProductoId id) { return Optional.ofNullable(productos.get(id)); }

    @Override public void descontarStock(ProductoId id, Cantidad c) {
        Producto p = productos.get(id);
        if (fallar || p.stock() < c.valor()) throw new StockInsuficienteException(id);
        productos.put(id, new Producto(id, p.nombre(), p.precio(), p.stock() - c.valor()));
    }
}
```

> Ojo con el fake: si `PedidoRepositoryEnMemoria` guardase la **misma
> referencia**, el test del pago rechazado pasaría a `CONFIRMADO` en memoria
> sin haber llamado a `guardar`. La copia imita lo que hace una BD real.

Commit:

```bash
git commit -m "refactor: mueve confirmación de pedido a ConfirmarPedidoService"
```

---

## 8. Fase 4 — Crear los adaptadores

### 8.1 Persistencia: la entidad JPA se muda a infraestructura

`PedidoEntity` se mueve (Refactor > Move) a
`infrastructure.adapter.out.persistence` y se renombra a `PedidoJpaEntity`. El
código legacy que aún la usa (reembolsos, informes) solo cambia el `import`.
Las anotaciones Jackson se quitarán en la fase 5, cuando ningún controlador la
devuelva.

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

@Component
class PedidoPersistenceAdapter implements PedidoRepository {

    private final PedidoJpaRepository jpa;
    private final PedidoPersistenceMapper mapper;

    PedidoPersistenceAdapter(PedidoJpaRepository jpa, PedidoPersistenceMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public void guardar(Pedido pedido) {
        PedidoJpaEntity entidad = jpa.findById(pedido.id().valor())
                .orElseGet(PedidoJpaEntity::new);
        mapper.copiarEn(pedido, entidad);
        jpa.save(entidad);
    }

    @Override
    public Optional<Pedido> buscarPorId(PedidoId id) {
        return jpa.findById(id.valor()).map(mapper::aDominio);
    }
}
```

```java
@Component
class PedidoPersistenceMapper {

    Pedido aDominio(PedidoJpaEntity e) {
        Currency moneda = Currency.getInstance(e.getMoneda());
        List<LineaPedido> lineas = e.getLineas().stream()
                .map(l -> new LineaPedido(new ProductoId(l.getSku()), new Cantidad(l.getCantidad()),
                        new Dinero(l.getPrecioUnitario(), moneda)))
                .toList();
        return Pedido.reconstituir(new PedidoId(e.getId()), new ClienteId(e.getClienteId()),
                new Email(e.getClienteEmail()), lineas,
                EstadoPedido.valueOf(e.getEstado().trim().toUpperCase()),   // tolera "confirmado" histórico
                e.getCreadoEn(), e.getReferenciaPago());
    }

    void copiarEn(Pedido p, PedidoJpaEntity e) {
        e.setId(p.id().valor());
        e.setClienteId(p.clienteId().valor());
        e.setClienteEmail(p.emailContacto().valor());
        e.setEstado(p.estado().name());
        e.setTotal(p.total().importe());
        e.setMoneda(p.total().moneda().getCurrencyCode());
        e.setCreadoEn(p.creadoEn());
        e.setReferenciaPago(p.referenciaPago());
        e.getLineas().clear();                                 // orphanRemoval = true
        p.lineas().forEach(l -> {
            LineaPedidoJpaEntity le = new LineaPedidoJpaEntity();
            le.setPedido(e);
            le.setSku(l.productoId().sku());
            le.setCantidad(l.cantidad().valor());
            le.setPrecioUnitario(l.precioUnitario().importe());
            e.getLineas().add(le);
        });
    }
}
```

> Trampa: la entidad legacy tenía `@GeneratedValue(strategy = UUID)`. Ahora el
> id lo genera el **dominio** (`PedidoId.nuevo()`), así que quitamos
> `@GeneratedValue`. Con un id asignado, `save()` hace `merge` (un `SELECT`
> previo): por eso buscamos primero la entidad. Si te preocupa, implementa
> `Persistable<UUID>` (día 6).

Catálogo con descuento atómico:

```java
interface ProductoJpaRepository extends JpaRepository<ProductoJpaEntity, Long> {
    Optional<ProductoJpaEntity> findBySku(String sku);

    @Modifying
    @Query("update ProductoJpaEntity p set p.stock = p.stock - :cantidad " +
           "where p.sku = :sku and p.stock >= :cantidad")
    int descontarStock(@Param("sku") String sku, @Param("cantidad") int cantidad);
}

@Component
class CatalogoProductosPersistenceAdapter implements CatalogoProductos {
    private final ProductoJpaRepository jpa;

    CatalogoProductosPersistenceAdapter(ProductoJpaRepository jpa) { this.jpa = jpa; }

    @Override
    public Optional<Producto> buscarPorId(ProductoId id) {
        return jpa.findBySku(id.sku()).map(e -> new Producto(id, e.getNombre(),
                new Dinero(e.getPrecio(), Dinero.EUR), e.getStock()));
    }

    @Override
    public void descontarStock(ProductoId id, Cantidad cantidad) {
        if (jpa.descontarStock(id.sku(), cantidad.valor()) == 0) {
            throw new StockInsuficienteException(id);
        }
    }
}
```

Transacciones con `TransactionTemplate`:

```java
@Component
class SpringUnidadDeTrabajoAdapter implements UnidadDeTrabajo {
    private final TransactionTemplate tx;

    SpringUnidadDeTrabajoAdapter(PlatformTransactionManager tm) { this.tx = new TransactionTemplate(tm); }

    @Override
    public <T> T ejecutar(Supplier<T> trabajo) { return tx.execute(status -> trabajo.get()); }
}
```

### 8.2 Pago: de `RestTemplate` en el servicio a `RestClient` en un adaptador

```java
package com.curso.pedidos.infrastructure.adapter.out.payment;

@ConfigurationProperties(prefix = "pagos")
record PagosProperties(String url, String apiKey, Duration timeout) { }
```

```java
@Component
class StripeLikePasarelaAdapter implements PasarelaDePago {

    private static final Logger log = LoggerFactory.getLogger(StripeLikePasarelaAdapter.class);
    private final RestClient http;

    StripeLikePasarelaAdapter(RestClient.Builder builder, PagosProperties props) {
        this.http = builder
                .baseUrl(props.url())
                .defaultHeader("X-Api-Key", props.apiKey())
                .build();
    }

    @Override
    public ResultadoPago cobrar(PedidoId pedidoId, Dinero importe, String tokenTarjeta) {
        try {
            CargoResponse r = http.post().uri("/charges")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CargoRequest(importe.enCentimos(), importe.moneda().getCurrencyCode(),
                            tokenTarjeta, "PED-" + pedidoId.valor()))
                    .retrieve()
                    .body(CargoResponse.class);
            return new Aprobado(r.id());
        } catch (HttpClientErrorException e) {          // 4xx = rechazo de negocio
            return new Rechazado(e.getStatusCode().toString());
        }
        // 5xx y timeouts se propagan: no son "rechazos", son fallos técnicos
    }

    @Override
    public void anularCobro(String referenciaPago) {
        try {
            http.post().uri("/charges/{id}/void", referenciaPago).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.error("ALERTA: no se pudo anular el cobro {}. Requiere intervención.", referenciaPago, e);
        }
    }

    record CargoRequest(long amount, String currency, String token, String reference) { }
    record CargoResponse(String id, String status) { }
}
```

> Nota: el timeout se configura en la `ClientHttpRequestFactory` (por ejemplo
> con `ClientHttpRequestFactorySettings` en Boot 3.3). Sin timeout, un proveedor
> lento te tumba el pool de hilos.

Test del adaptador con WireMock (sin Spring completo):

```java
class StripeLikePasarelaAdapterTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private StripeLikePasarelaAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StripeLikePasarelaAdapter(RestClient.builder(),
                new PagosProperties(wm.baseUrl(), "k", Duration.ofSeconds(2)));
    }

    @Test
    void cobro_aceptado_devuelve_referencia() {
        wm.stubFor(post("/charges").withHeader("X-Api-Key", equalTo("k"))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("1400")))
                .willReturn(okJson("{\"id\":\"ch_1\",\"status\":\"succeeded\"}")));

        var r = adapter.cobrar(PedidoId.nuevo(), Dinero.euros("14.00"), "tok");

        assertThat(r).isEqualTo(new PasarelaDePago.Aprobado("ch_1"));
    }

    @Test
    void un_402_es_un_rechazo() {
        wm.stubFor(post("/charges").willReturn(aResponse().withStatus(402)));
        assertThat(adapter.cobrar(PedidoId.nuevo(), Dinero.euros("1"), "tok"))
                .isInstanceOf(PasarelaDePago.Rechazado.class);
    }
}
```

### 8.3 Notificación: el email sale de la transacción

```java
package com.curso.pedidos.infrastructure.adapter.out.notification;

@Component
class EmailNotificadorAdapter implements NotificadorPedidos {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificadorAdapter.class);
    private final JavaMailSender mailSender;

    EmailNotificadorAdapter(JavaMailSender mailSender) { this.mailSender = mailSender; }

    @Override
    public void notificarConfirmacion(Pedido pedido) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
            h.setTo(pedido.emailContacto().valor());
            h.setSubject("Tu pedido " + pedido.id().valor() + " está confirmado");
            h.setText(PlantillaConfirmacion.html(pedido), true);
            mailSender.send(msg);
        } catch (MessagingException | MailException e) {
            // No crítico: el pedido ya está pagado. Se registra y se reintentará (outbox, día 6).
            log.warn("No se pudo notificar el pedido {}", pedido.id().valor(), e);
        }
    }
}
```

### 8.4 Web: el controlador nuevo (Strangler por endpoint)

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

@RestController
@RequestMapping("/api/pedidos")
class PedidoController {

    private final CrearPedidoUseCase crearPedido;
    private final ConfirmarPedidoUseCase confirmarPedido;
    private final CancelarPedidoUseCase cancelarPedido;
    private final ConsultarPedidoQuery consultarPedido;

    PedidoController(CrearPedidoUseCase crearPedido, ConfirmarPedidoUseCase confirmarPedido,
                     CancelarPedidoUseCase cancelarPedido, ConsultarPedidoQuery consultarPedido) {
        this.crearPedido = crearPedido;
        this.confirmarPedido = confirmarPedido;
        this.cancelarPedido = cancelarPedido;
        this.consultarPedido = consultarPedido;
    }

    @PostMapping
    ResponseEntity<PedidoResponse> crear(@Valid @RequestBody CrearPedidoRequest req) {
        PedidoId id = crearPedido.ejecutar(req.aComando());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PedidoResponse.desde(consultarPedido.porId(id)));
    }

    @GetMapping("/{id}")
    PedidoResponse obtener(@PathVariable UUID id) {
        return PedidoResponse.desde(consultarPedido.porId(new PedidoId(id)));
    }

    @PostMapping("/{id}/confirmar")
    PedidoResponse confirmar(@PathVariable UUID id, @RequestParam String tokenTarjeta) {
        confirmarPedido.ejecutar(new ConfirmarPedidoCommand(new PedidoId(id), tokenTarjeta));
        return PedidoResponse.desde(consultarPedido.porId(new PedidoId(id)));
    }

    @PostMapping("/{id}/cancelar")
    PedidoResponse cancelar(@PathVariable UUID id) {
        cancelarPedido.ejecutar(new PedidoId(id));
        return PedidoResponse.desde(consultarPedido.porId(new PedidoId(id)));
    }
}
```

```java
record CrearPedidoRequest(@NotNull UUID clienteId, @NotBlank String clienteEmail,
                          @NotEmpty List<@Valid LineaRequest> lineas) {
    record LineaRequest(@NotBlank String sku, @Positive int cantidad) { }

    CrearPedidoCommand aComando() {
        return new CrearPedidoCommand(new ClienteId(clienteId), new Email(clienteEmail),
                lineas.stream()
                      .map(l -> new LineaSolicitada(new ProductoId(l.sku()), new Cantidad(l.cantidad())))
                      .toList());
    }
}

/** Mismos nombres de campo que el JSON legacy: el contrato no cambia. */
record PedidoResponse(UUID id, UUID clienteId, String clienteEmail, String estado,
                      BigDecimal total, String moneda, Instant creadoEn, String referenciaPago,
                      List<LineaResponse> lineas) {
    record LineaResponse(String sku, int cantidad, BigDecimal precioUnitario) { }

    static PedidoResponse desde(Pedido p) {
        return new PedidoResponse(p.id().valor(), p.clienteId().valor(), p.emailContacto().valor(),
                p.estado().name(), p.total().importe(), p.total().moneda().getCurrencyCode(),
                p.creadoEn(), p.referenciaPago(),
                p.lineas().stream()
                        .map(l -> new LineaResponse(l.productoId().sku(), l.cantidad().valor(),
                                l.precioUnitario().importe()))
                        .toList());
    }
}
```

```java
@RestControllerAdvice
class PedidoExceptionHandler {

    @ExceptionHandler(PedidoNoEncontradoException.class)
    ProblemDetail noEncontrado(PedidoNoEncontradoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({TransicionDeEstadoInvalidaException.class, StockInsuficienteException.class})
    ProblemDetail conflicto(PedidoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(PagoRechazadoException.class)
    ProblemDetail pagoRechazado(PagoRechazadoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
    }

    @ExceptionHandler({PedidoException.class, IllegalArgumentException.class})
    ProblemDetail invalido(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
```

> Nota: el total que devuelve `PedidoResponse` es `pedido.total()` sin
> descuento, igual que hacía el legacy al crear. En confirmación el legacy
> persistía el total descontado; si el golden master de noviembre lo exige,
> guardad el importe cobrado en el pedido (es un dato más del pago).

> Trampa: dos clases `PedidoController` en paquetes distintos = **conflicto de
> nombre de bean** (`pedidoController`) al arrancar. Durante la convivencia,
> renombra la vieja a `LegacyPedidoController`. Y cuidado con mapear el mismo
> verbo+ruta en ambas: Spring falla con *Ambiguous mapping*. Mueve los
> métodos **uno a uno** del viejo al nuevo (eso es el Strangler).

Test del adaptador web con slice:

```java
@WebMvcTest(PedidoController.class)
@Import(PedidoExceptionHandler.class)
class PedidoControllerTest {

    @Autowired MockMvc mvc;
    @MockBean CrearPedidoUseCase crearPedido;
    @MockBean ConfirmarPedidoUseCase confirmarPedido;
    @MockBean CancelarPedidoUseCase cancelarPedido;
    @MockBean ConsultarPedidoQuery consultarPedido;

    @Test
    void pago_rechazado_devuelve_402_problem_detail() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new PagoRechazadoException(new PedidoId(id), "fondos"))
                .when(confirmarPedido).ejecutar(any());

        mvc.perform(post("/api/pedidos/{id}/confirmar", id).param("tokenTarjeta", "tok"))
           .andExpect(status().isPaymentRequired())
           .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("fondos")));
    }

    @Test
    void pedido_inexistente_devuelve_404() throws Exception {
        when(consultarPedido.porId(any())).thenThrow(new PedidoNoEncontradoException(PedidoId.nuevo()));
        mvc.perform(get("/api/pedidos/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
```

---

## 9. Fase 5 — Cablear y retirar el código viejo

### 9.1 `BeanConfiguration` con conmutador (Branch by Abstraction, paso 4)

```java
package com.curso.pedidos.infrastructure.config;

@Configuration
@EnableConfigurationProperties(PagosProperties.class)
class BeanConfiguration {

    @Bean Clock clock() { return Clock.systemDefaultZone(); }

    @Bean CalculadoraDePrecios calculadoraDePrecios() { return new CalculadoraDePrecios(); }

    @Bean
    CrearPedidoUseCase crearPedidoUseCase(PedidoRepository pedidos, CatalogoProductos catalogo,
                                          UnidadDeTrabajo uow, Clock clock) {
        return new CrearPedidoService(pedidos, catalogo, uow, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "pedidos.confirmacion.motor", havingValue = "hexagonal", matchIfMissing = true)
    ConfirmarPedidoUseCase confirmarPedidoUseCase(PedidoRepository pedidos, CatalogoProductos catalogo,
                                                  PasarelaDePago pasarela, NotificadorPedidos notificador,
                                                  PublicadorDeEventos publicador, UnidadDeTrabajo uow,
                                                  CalculadoraDePrecios calculadora, Clock clock) {
        return new ConfirmarPedidoService(pedidos, catalogo, pasarela, notificador,
                publicador, uow, calculadora, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "pedidos.confirmacion.motor", havingValue = "legacy")
    ConfirmarPedidoUseCase confirmarPedidoLegacy(PedidoService legacy, PedidoJpaRepository repo) {
        return new LegacyConfirmarPedidoAdapter(legacy, repo);
    }

    @Bean
    CancelarPedidoUseCase cancelarPedidoUseCase(PedidoRepository pedidos, PublicadorDeEventos publicador,
                                                UnidadDeTrabajo uow, Clock clock) {
        return new CancelarPedidoService(pedidos, publicador, uow, clock);
    }

    @Bean
    ConsultarPedidoQuery consultarPedidoQuery(PedidoRepository pedidos) {
        return new ConsultarPedidoService(pedidos);
    }
}
```

```yaml
# application.yml
pedidos:
  confirmacion:
    motor: hexagonal     # 'legacy' para volver atrás sin desplegar código nuevo
pagos:
  url: https://pagos.example.com
  api-key: ${PAGOS_API_KEY}
  timeout: 3s
```

Ejecutamos la suite de caracterización **con los dos valores**:

```java
@TestPropertySource(properties = "pedidos.confirmacion.motor=legacy")
class PedidoApiCharacterizationLegacyTest extends PedidoApiCharacterizationTest { }
```

Si ambos pasan, la conmutación es segura. En producción se despliega con
`legacy`, se cambia a `hexagonal` en un porcentaje de instancias, se observan
las métricas (bloque 3) y se completa.

### 9.2 Retirada del código viejo

Una vez estable (días o semanas, no minutos):

1. Borrar `LegacyConfirmarPedidoAdapter` y el `@ConditionalOnProperty`.
2. Borrar `PedidoService.confirmarYCobrar` y `EmailService` si nadie más los usa.
3. Borrar `LegacyPedidoController` cuando todos sus métodos se hayan mudado.
4. Quitar anotaciones Jackson de `PedidoJpaEntity`.
5. Actualizar deliberadamente los golden master de los errores que cambian
   (404, 402, 409) en **un commit propio** que referencia el ADR.

```bash
git log --oneline
a9c1e02 chore: retira PedidoService.confirmarYCobrar y adaptador legacy
7d2b4f1 fix: errores del API como ProblemDetail (404/402/409) — ADR-0001
5e8a3c9 feat: conmutador pedidos.confirmacion.motor (branch by abstraction)
4b71d0e feat: adaptador web PedidoController hexagonal (strangler: confirmar)
3f0a9b2 feat: EmailNotificadorAdapter fuera de la transacción
2c55e81 feat: StripeLikePasarelaAdapter con RestClient y test WireMock
19ab7c4 refactor: PedidoPersistenceAdapter y mapper
0fe2d33 refactor: mueve confirmación de pedido a ConfirmarPedidoService
e4c1a90 refactor: introduce puerto ConfirmarPedidoUseCase delante del servicio legacy
d0b3f57 refactor: renombra repositorio Spring Data a PedidoJpaRepository
c8a2e16 refactor: extrae modelo de dominio Pedido con invariantes y tests
b71f0d4 test: añade golden master del API de pedidos
a0e5c11 refactor: introduce Clock como costura para tests deterministas
```

> Trampa: no borrar nunca el código viejo. Una migración que deja dos caminos
> vivos para siempre es **peor** que el monolito original: ahora hay dos
> sitios donde arreglar cada bug. La fase 5 no es opcional.

---

## 10. La evolución completa

```
 ANTES                                         DESPUÉS
                                                        ┌──────────────── infrastructure ───────────────┐
 ┌──────────────┐                                       │ adapter.in.web                                │
 │ Controller   │ (lógica, repo directo)                │  PedidoController ─► DTOs ─► ControllerAdvice │
 └──────┬───────┘                                       └──────────────┬────────────────────────────────┘
        ▼                                                              ▼
 ┌──────────────┐                                       ┌──────────── application ──────────────┐
 │ PedidoService│ @Transactional                        │ port.in   CrearPedidoUseCase ...       │
 │  300 líneas  │  ├─ RestTemplate                      │ service   ConfirmarPedidoService       │
 │              │  ├─ SMTP                              │ port.out  PedidoRepository, Pasarela...│
 └──────┬───────┘  └─ JPA                               └───────┬──────────────────────┬─────────┘
        ▼                                                       ▼                      ▲ implementan
 ┌──────────────┐                                       ┌──── domain ────┐   ┌──── infrastructure ──────┐
 │ PedidoEntity │ (= modelo = DTO)                      │ Pedido, VOs,   │   │ PedidoPersistenceAdapter │
 └──────────────┘                                       │ eventos, excep.│   │ StripeLikePasarelaAdapter│
                                                        │ 0 imports fwk  │   │ EmailNotificadorAdapter  │
                                                        └────────────────┘   └──────────────────────────┘
```

---

## 11. Resumen

| Fase | Técnica                         | Riesgo que elimina                          | Señal de "hecho"                              |
|------|---------------------------------|---------------------------------------------|-----------------------------------------------|
| 0    | Golden master + costura `Clock` | Romper sin enterarse                        | Suite verde y determinista                    |
| 1    | Extraer dominio                 | Reglas dispersas                            | `PedidoTest` cubre todas las invariantes      |
| 2    | Puertos + Branch by Abstraction | Big bang                                    | Controlador habla con el puerto               |
| 3    | Servicio de aplicación          | Transacciones largas, cobro sin pedido      | Tests de caso de uso con dobles               |
| 4    | Adaptadores                     | Acoplamiento a JPA/HTTP/SMTP                | Tests de adaptador (slice, WireMock)          |
| 5    | Conmutar y retirar              | Dos caminos vivos                           | Código legacy borrado, ADR actualizado        |

---

## Ejercicio

Migra el endpoint `POST /api/pedidos/{id}/cancelar` siguiendo las mismas
fases, pero añadiendo una regla que hoy no existe: **si el pedido estaba
`PAGADO`, cancelar implica solicitar el reembolso** a la pasarela.

Pista: el reembolso es un efecto externo, así que **no** va dentro de la
transacción. Piensa qué pasa si el reembolso falla después de haber guardado
el pedido como `CANCELADO`.

<details><summary>Solución</summary>

1. Añade a `PasarelaDePago` el método `void reembolsar(String referenciaPago, Dinero importe)`.
2. En `CancelarPedidoService`:

```java
@Override
public void ejecutar(PedidoId pedidoId) {
    Pedido cancelado = unidadDeTrabajo.ejecutar(() -> {
        Pedido pedido = pedidos.buscarPorId(pedidoId)
                .orElseThrow(() -> new PedidoNoEncontradoException(pedidoId));
        pedido.cancelar(clock.instant());
        pedidos.guardar(pedido);
        publicador.publicar(pedido.extraerEventos());   // PedidoCancelado
        return pedido;
    });
    if (cancelado.referenciaPago() != null) {
        pasarela.reembolsar(cancelado.referenciaPago(), cancelado.total());
    }
}
```

3. Si el reembolso puede fallar, la opción robusta es **no** llamarlo aquí:
   publicar `PedidoCancelado` mediante outbox y que un consumidor idempotente
   solicite el reembolso con reintentos (día 6, saga coreografiada).
4. Tests: caso de uso con `PasarelaDePago` mockeada (se reembolsa solo si
   estaba pagado), test de dominio (no se cancela un enviado) y nuevo golden
   master del caso feliz.

</details>

---

## Referencias

- Martin Fowler, *Strangler Fig Application*: https://martinfowler.com/bliki/StranglerFigApplication.html
- Martin Fowler, *Branch by Abstraction*: https://martinfowler.com/bliki/BranchByAbstraction.html
- Michael Feathers, *Working Effectively with Legacy Code* (Prentice Hall, 2004) — caps. sobre *characterization tests* y *seams*.
- Spring Framework, *Transaction Management* (`TransactionTemplate`): https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- Spring Framework testing: https://docs.spring.io/spring-framework/reference/testing.html
- WireMock: https://wiremock.org/docs/
- Chris Richardson, *Saga* y *Transactional Outbox*: https://microservices.io/patterns/data/saga.html , https://microservices.io/patterns/data/transactional-outbox.html
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023): https://github.com/thombergs/buckpal

## Mantra

> *"El puerto que creas para migrar es el mismo que se queda para siempre:
> Branch by Abstraction y hexágono son la misma idea vista en el tiempo."*
