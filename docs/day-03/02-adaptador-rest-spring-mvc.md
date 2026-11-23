# Día 3 — Bloque 2: Adaptador REST con Spring MVC

> *"A REST API should spend almost all of its descriptive effort in defining the media type(s) used for representing resources."*
> — Roy T. Fielding (2008)

---

## 1. El problema en una frase

Queremos exponer los casos de uso de pedidos por HTTP **como una API REST decente** (recursos, verbos, códigos de estado, validación) y que, aun así, el hexágono no se entere de que existe Spring MVC.

---

## 2. Qué vamos a construir

| Operación de negocio   | Verbo + URI                              | Puerto de entrada          | Respuesta OK                    |
|------------------------|------------------------------------------|----------------------------|---------------------------------|
| Crear pedido           | `POST /api/pedidos`                      | `CrearPedidoUseCase`       | `201 Created` + `Location`      |
| Añadir línea           | `POST /api/pedidos/{id}/lineas`          | `AnadirLineaPedidoUseCase` | `204 No Content`                |
| Confirmar pedido       | `POST /api/pedidos/{id}/confirmacion`    | `ConfirmarPedidoUseCase`   | `204 No Content`                |
| Consultar pedido       | `GET /api/pedidos/{id}`                  | `ConsultarPedidoQuery`     | `200 OK` + `PedidoResponse`     |

Todo vive en `com.curso.pedidos.infrastructure.adapter.in.web`.

```
infrastructure.adapter.in.web
├── PedidoController.java          ← el adaptador
├── CrearPedidoRequest.java        ← DTO de entrada (record)
├── AnadirLineaRequest.java        ← DTO de entrada (record)
├── PedidoCreadoResponse.java      ← DTO de salida (record)
├── PedidoResponse.java            ← DTO de salida (record)
├── LineaPedidoResponse.java       ← DTO de salida (record)
├── ImporteResponse.java           ← DTO de salida (record)
└── GlobalExceptionHandler.java    ← bloque 4
```

### Dependencias Maven

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

> `spring-boot-starter-validation` **no** viene incluido en `starter-web` desde Boot 2.3. Si se te olvida, `@Valid` no hace nada y no da error. Trampa clásica.

---

## 3. Bad: el controlador "CRUD" que habla dominio y verbos genéricos

```java
@RestController
@RequestMapping("/pedidos")
public class PedidoController {

    private final CrearPedidoService crearPedidoService;          // clase concreta
    private final PedidoRepository pedidoRepository;              // ¡puerto de SALIDA!

    @PostMapping
    public Pedido crear(@RequestBody Pedido pedido) {             // dominio como DTO
        return crearPedidoService.crear(pedido);                  // 200 OK siempre
    }

    @PutMapping("/{id}")
    public Pedido actualizar(@PathVariable UUID id, @RequestBody Pedido pedido) {
        pedido.setEstado(EstadoPedido.CONFIRMADO);                // setter en el agregado
        return pedidoRepository.guardar(pedido);
    }

    @GetMapping("/{id}")
    public Pedido consultar(@PathVariable UUID id) {
        return pedidoRepository.buscarPorId(new PedidoId(id)).orElse(null);  // 200 con body vacío
    }
}
```

### ¿Qué duele?

- **El agregado `Pedido` como cuerpo de petición**: Jackson necesita constructor vacío y setters ⇒ el dominio se llena de anotaciones o pierde sus invariantes.
- **`PUT` genérico para "confirmar"**: el cliente manda el pedido entero con `estado=CONFIRMADO`. Es *CRUD sobre el agregado*, no un caso de uso. Las transiciones de estado se saltan `Pedido.confirmar()`.
- **El controlador usa un puerto de salida**: se salta el servicio de aplicación.
- **Códigos HTTP mentirosos**: `200` al crear (debería ser `201`), `200` con `null` cuando no existe (debería ser `404`).
- **Sin validación**: un `clienteId` nulo explota dentro del dominio con un `NullPointerException` → `500`.

---

## 4. Good: los DTOs como `record`

### 4.1 Entrada

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CrearPedidoRequest(
        @NotNull UUID clienteId
) { }
```

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AnadirLineaRequest(
        @NotBlank @Size(max = 32) String sku,
        @NotNull @Positive @Max(10_000) Integer cantidad
) { }
```

¿Por qué `Integer` y no `int`? Si el JSON no trae `cantidad`, un `int` se queda en `0` y el mensaje de error dice "debe ser positivo" en vez de "es obligatorio". Con `Integer` + `@NotNull` el error es preciso.

### 4.2 Salida

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import java.util.UUID;

public record PedidoCreadoResponse(UUID id) { }
```

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.domain.model.Dinero;
import java.math.BigDecimal;

public record ImporteResponse(BigDecimal importe, String moneda) {
    static ImporteResponse desde(Dinero dinero) {
        return new ImporteResponse(dinero.importe(), dinero.moneda().getCurrencyCode());
    }
}
```

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.domain.model.LineaPedido;

public record LineaPedidoResponse(
        String sku,
        int cantidad,
        ImporteResponse precioUnitario,
        ImporteResponse subtotal
) {
    static LineaPedidoResponse desde(LineaPedido linea) {
        return new LineaPedidoResponse(
                linea.productoId().sku(),
                linea.cantidad().valor(),
                ImporteResponse.desde(linea.precioUnitario()),
                ImporteResponse.desde(linea.subtotal()));
    }
}
```

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.domain.model.Pedido;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PedidoResponse(
        UUID id,
        UUID clienteId,
        String estado,
        Instant creadoEn,
        List<LineaPedidoResponse> lineas,
        ImporteResponse total
) {
    static PedidoResponse desde(Pedido pedido) {
        return new PedidoResponse(
                pedido.id().valor(),
                pedido.clienteId().valor(),
                pedido.estado().name(),
                pedido.creadoEn(),
                pedido.lineas().stream().map(LineaPedidoResponse::desde).toList(),
                ImporteResponse.desde(pedido.total()));
    }
}
```

> Usamos aquí la estrategia **"factoría estática en el DTO"** (`desde(...)`). En el bloque 3 la comparamos con un mapper manual y con MapStruct. Fíjate en que la dependencia va **DTO → dominio**, nunca al revés: `Pedido` no sabe que existe `PedidoResponse`.

> Accesores asumidos del día 2: `PedidoId.valor()`, `ClienteId.valor()`, `ProductoId.sku()`, `Cantidad.valor()`, `Dinero.importe()` / `moneda()`. Si en vuestra versión se llaman distinto, solo cambia esta capa: esa es precisamente la gracia.

---

## 5. Good: el controlador completo

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.application.port.in.*;
import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.ProductoId;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/pedidos")
class PedidoController {

    private final CrearPedidoUseCase crearPedido;
    private final AnadirLineaPedidoUseCase anadirLinea;
    private final ConfirmarPedidoUseCase confirmarPedido;
    private final ConsultarPedidoQuery consultarPedido;

    PedidoController(CrearPedidoUseCase crearPedido,
                     AnadirLineaPedidoUseCase anadirLinea,
                     ConfirmarPedidoUseCase confirmarPedido,
                     ConsultarPedidoQuery consultarPedido) {
        this.crearPedido = crearPedido;
        this.anadirLinea = anadirLinea;
        this.confirmarPedido = confirmarPedido;
        this.consultarPedido = consultarPedido;
    }

    @PostMapping
    ResponseEntity<PedidoCreadoResponse> crear(@Valid @RequestBody CrearPedidoRequest request,
                                               UriComponentsBuilder uriBuilder) {
        PedidoId id = crearPedido.ejecutar(
                new CrearPedidoCommand(new ClienteId(request.clienteId())));

        URI location = uriBuilder.path("/api/pedidos/{id}")
                                 .buildAndExpand(id.valor())
                                 .toUri();
        return ResponseEntity.created(location)
                             .body(new PedidoCreadoResponse(id.valor()));
    }

    @PostMapping("/{id}/lineas")
    ResponseEntity<Void> anadirLinea(@PathVariable UUID id,
                                     @Valid @RequestBody AnadirLineaRequest request) {
        anadirLinea.ejecutar(new AnadirLineaPedidoCommand(
                new PedidoId(id),
                new ProductoId(request.sku()),
                new Cantidad(request.cantidad())));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/confirmacion")
    ResponseEntity<Void> confirmar(@PathVariable UUID id) {
        confirmarPedido.ejecutar(new ConfirmarPedidoCommand(new PedidoId(id)));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    PedidoResponse consultar(@PathVariable UUID id) {
        return PedidoResponse.desde(consultarPedido.consultar(new PedidoId(id)));
    }
}
```

Detalles que merecen comentario:

- **Visibilidad de paquete** (`class`, sin `public`): nadie fuera de `adapter.in.web` debería usar el controlador. Spring lo encuentra igual.
- **Inyección por constructor** sin `@Autowired`: con un único constructor no hace falta.
- **`@PathVariable UUID id`**: Spring convierte el `String` a `UUID`; si no es válido devuelve `400` (lo refinamos en el bloque 4).
- **`UriComponentsBuilder` como parámetro**: Spring lo inyecta ya inicializado con el host, puerto y *context path* de la petición. Alternativa: `ServletUriComponentsBuilder.fromCurrentRequest()`, pero acopla a la petición actual y es menos cómodo de testear.
- **Sin `try/catch`**: las excepciones de dominio burbujean y las traduce el `@RestControllerAdvice` (bloque 4).
- **Acciones como sub-recurso** (`/confirmacion`): "confirmar" no es un CRUD, es una **transición de estado**. Modelarla como `POST` sobre un sub-recurso expresa la intención mejor que un `PUT` con el estado nuevo.

### Diagrama: de la petición al caso de uso

```
 POST /api/pedidos/{id}/lineas          {"sku":"LIB-001","cantidad":2}
            │
            ▼
 ┌─────────────────────────────────────────────────────────────────┐
 │ DispatcherServlet ─► HandlerMapping ─► PedidoController         │
 │                                                                 │
 │  HttpMessageConverter (Jackson) ─► AnadirLineaRequest           │
 │  @Valid  ─► Bean Validation ──✗──► MethodArgumentNotValid ─►400 │
 │                    │ ✓                                          │
 │                    ▼                                            │
 │  new AnadirLineaPedidoCommand(PedidoId, ProductoId, Cantidad)   │
 └────────────────────┬────────────────────────────────────────────┘
                      │ anadirLinea.ejecutar(cmd)
                      ▼
          ┌───────────────────────────┐
          │ AnadirLineaPedidoUseCase  │───► AnadirLineaPedidoService ───► Pedido.anadirLinea()
          └───────────────────────────┘
                      │ void                       │ TransicionDeEstadoInvalidaException
                      ▼                            ▼
               204 No Content          @RestControllerAdvice ─► 409 ProblemDetail
```

---

## 6. Bean Validation en el borde vs invariantes en el dominio

Esta es **la** pregunta del día: si `Cantidad` ya valida `> 0`, ¿para qué `@Positive` en el DTO?

| Aspecto                     | Bean Validation (DTO)                        | Invariante (dominio)                                 |
|-----------------------------|----------------------------------------------|------------------------------------------------------|
| Qué valida                  | **Forma**: presente, tipo, longitud, rango   | **Significado**: reglas de negocio                   |
| Ejemplo                     | `sku` no vacío, `cantidad` es número > 0     | No añadir líneas a un pedido confirmado              |
| Depende del protocolo       | Sí (JSON puede traer `null`, strings largos) | No: igual por HTTP, Kafka o test                     |
| Errores                     | Muchos a la vez, por campo, `400`            | Uno, el primero que se viola, `409`/`422`            |
| Dónde se ejecuta            | Antes de entrar al hexágono                  | Dentro del agregado / VO                             |
| ¿Se puede omitir?           | Sí, el dominio sigue protegido               | **Nunca**                                            |

Regla práctica:

1. **El dominio se protege solo, siempre.** `new Cantidad(0)` lanza excepción venga de donde venga. Es la red de seguridad.
2. **El borde valida para dar mejores errores**, no para proteger al dominio. Bean Validation acumula todos los fallos por campo y produce un `400` útil para el cliente; el dominio lanza la primera violación con un mensaje de negocio.
3. **Duplicar `> 0` no es violar DRY**: son dos conocimientos distintos (contrato de la API vs invariante del negocio) que hoy coinciden. Si mañana el negocio permite cantidades decimales para productos a granel, cambia el dominio; la API puede versionarse aparte.

### Trampa: Bean Validation en el dominio

```java
public record Cantidad(@Positive int valor) { }   // MAL
```

Las anotaciones de `jakarta.validation` en el dominio **no se ejecutan solas**: hace falta un `Validator` que alguien invoque. En un test unitario `new Cantidad(-3)` se crea tan feliz. Además el dominio pasa a depender de Jakarta. Usa el constructor compacto:

```java
public record Cantidad(int valor) {
    public Cantidad {
        if (valor <= 0) throw new IllegalArgumentException("La cantidad debe ser positiva: " + valor);
    }
}
```

### Trampa: `@Valid` en el puerto de entrada

Hay quien pone `@Validated` en el servicio de aplicación para validar comandos. Funciona (proxy AOP de Spring) pero **mete Spring en `application`**. Si quieres validar comandos dentro del hexágono, hazlo en el constructor compacto del `record` (como `CrearPedidoCommand` del día 2) o con un `Validator` propio sin Spring. Hombergs propone una clase base `SelfValidating<T>` que usa `jakarta.validation` sin Spring; es un compromiso aceptable si el equipo lo acuerda.

---

## 7. Códigos HTTP: chuleta para este adaptador

| Situación                                       | Código                     | Cabeceras / cuerpo                       |
|-------------------------------------------------|----------------------------|------------------------------------------|
| Pedido creado                                   | `201 Created`              | `Location: /api/pedidos/{id}` + `{id}`   |
| Acción ejecutada sin nada que devolver          | `204 No Content`           | —                                        |
| Consulta correcta                               | `200 OK`                   | `PedidoResponse`                         |
| JSON mal formado / campo inválido               | `400 Bad Request`          | `ProblemDetail` con errores por campo    |
| Pedido no existe                                | `404 Not Found`            | `ProblemDetail`                          |
| Transición de estado inválida                   | `409 Conflict`             | `ProblemDetail`                          |
| Stock insuficiente                              | `409 Conflict` (o `422`)   | `ProblemDetail`                          |
| Error inesperado                                | `500 Internal Server Error`| `ProblemDetail` genérico, sin stacktrace |

¿`201` + body o `201` sin body? Ambas son válidas según RFC 9110. Devolver el `id` en el body ahorra al cliente parsear `Location`. Lo importante es la cabecera `Location`.

¿`204` o `200` + recurso tras confirmar? Si el cliente casi siempre necesita el pedido actualizado, `200` con `PedidoResponse` ahorra un `GET`. Pero **no** hagas que el caso de uso devuelva `Pedido` solo para eso: el adaptador puede llamar después a `ConsultarPedidoQuery` (dos llamadas al hexágono, ambas legítimas: comando y luego consulta).

---

## 8. Testing: `@WebMvcTest` + MockMvc

`@WebMvcTest` levanta **solo la capa web**: `DispatcherServlet`, controladores, `@ControllerAdvice`, convertidores Jackson, validación. **No** levanta servicios, repositorios ni JPA. Los puertos de entrada se sustituyen por mocks.

### `@MockBean` (Boot 3.3) vs `@MockitoBean` (Boot 3.4+)

| Anotación      | Paquete                                                        | Disponible                    |
|----------------|----------------------------------------------------------------|-------------------------------|
| `@MockBean`    | `org.springframework.boot.test.mock.mockito`                   | Boot ≤ 3.3; *deprecated* 3.4  |
| `@MockitoBean` | `org.springframework.test.context.bean.override.mockito`       | Spring Framework 6.2 / Boot 3.4+ |

En el curso usamos Boot **3.3** ⇒ `@MockBean`. Al migrar a 3.4+ basta con cambiar el import y la anotación; el comportamiento es equivalente. (`@SpyBean` → `@MockitoSpyBean`, igual.)

### Fixture de dominio para los tests

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.domain.model.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

final class PedidoMother {

    static final Currency EUR = Currency.getInstance("EUR");
    static final UUID PEDIDO_ID = UUID.fromString("3f2a1c9e-0d4b-4a7e-9b1a-2c3d4e5f6a7b");
    static final UUID CLIENTE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final Instant AHORA = Instant.parse("2026-11-23T10:00:00Z");

    static Pedido pedidoConUnaLinea() {
        Pedido pedido = Pedido.crear(new PedidoId(PEDIDO_ID), new ClienteId(CLIENTE_ID), AHORA);
        pedido.anadirLinea(new ProductoId("LIB-001"), new Cantidad(2),
                           new Dinero(new BigDecimal("15.50"), EUR));
        return pedido;
    }

    private PedidoMother() { }
}
```

> Ajusta `Pedido.crear(...)` a la firma de la factoría que escribisteis en el día 2. Es el único punto de acoplamiento del test con el dominio.

### El test

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.application.port.in.*;
import com.curso.pedidos.domain.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;   // Boot 3.4+: @MockitoBean
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.curso.pedidos.infrastructure.adapter.in.web.PedidoMother.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PedidoController.class)
class PedidoControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean CrearPedidoUseCase crearPedido;
    @MockBean AnadirLineaPedidoUseCase anadirLinea;
    @MockBean ConfirmarPedidoUseCase confirmarPedido;
    @MockBean ConsultarPedidoQuery consultarPedido;

    @Test
    void crear_devuelve201ConLocationYElId() throws Exception {
        given(crearPedido.ejecutar(any())).willReturn(new PedidoId(PEDIDO_ID));

        mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clienteId": "%s"}
                                """.formatted(CLIENTE_ID)))
               .andExpect(status().isCreated())
               .andExpect(header().string("Location", "http://localhost/api/pedidos/" + PEDIDO_ID))
               .andExpect(jsonPath("$.id").value(PEDIDO_ID.toString()));

        verify(crearPedido).ejecutar(new CrearPedidoCommand(new ClienteId(CLIENTE_ID)));
    }

    @Test
    void crear_sinClienteId_devuelve400YNoLlamaAlPuerto() throws Exception {
        mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
               .andExpect(status().isBadRequest());

        verifyNoInteractions(crearPedido);
    }

    @Test
    void anadirLinea_traduceElJsonAComandoYDevuelve204() throws Exception {
        mockMvc.perform(post("/api/pedidos/{id}/lineas", PEDIDO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku": "LIB-001", "cantidad": 2}
                                """))
               .andExpect(status().isNoContent());

        verify(anadirLinea).ejecutar(new AnadirLineaPedidoCommand(
                new PedidoId(PEDIDO_ID), new ProductoId("LIB-001"), new Cantidad(2)));
    }

    @Test
    void anadirLinea_conCantidadCero_devuelve400() throws Exception {
        mockMvc.perform(post("/api/pedidos/{id}/lineas", PEDIDO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku": "LIB-001", "cantidad": 0}
                                """))
               .andExpect(status().isBadRequest());

        verifyNoInteractions(anadirLinea);
    }

    @Test
    void confirmar_devuelve204() throws Exception {
        mockMvc.perform(post("/api/pedidos/{id}/confirmacion", PEDIDO_ID))
               .andExpect(status().isNoContent());

        verify(confirmarPedido).ejecutar(new ConfirmarPedidoCommand(new PedidoId(PEDIDO_ID)));
    }

    @Test
    void consultar_devuelveElPedidoComoDto() throws Exception {
        given(consultarPedido.consultar(new PedidoId(PEDIDO_ID))).willReturn(pedidoConUnaLinea());

        mockMvc.perform(get("/api/pedidos/{id}", PEDIDO_ID))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.id").value(PEDIDO_ID.toString()))
               .andExpect(jsonPath("$.estado").value("BORRADOR"))
               .andExpect(jsonPath("$.lineas[0].sku").value("LIB-001"))
               .andExpect(jsonPath("$.lineas[0].cantidad").value(2))
               .andExpect(jsonPath("$.total.importe").value(31.00))
               .andExpect(jsonPath("$.total.moneda").value("EUR"));
    }

    @Test
    void idConFormatoInvalido_devuelve400() throws Exception {
        mockMvc.perform(get("/api/pedidos/{id}", "no-es-un-uuid"))
               .andExpect(status().isBadRequest());
    }
}
```

Observa:

- **Verificamos el comando exacto**: como los comandos y VOs son `record`, `equals` funciona por valor y `verify(...).ejecutar(new XxxCommand(...))` es legible y preciso. Otra ventaja de los records.
- **`verifyNoInteractions`** en los casos de `400`: demuestra que la validación corta **antes** de entrar al hexágono.
- **Text blocks** (`"""`) para JSON: Java 15+, legibles sin escapar comillas.
- Con `@WebMvcTest`, el `@RestControllerAdvice` del bloque 4 **se carga automáticamente**: los tests de errores irán ahí.

### Alternativa: `MockMvcTester` (Spring Framework 6.2 / Boot 3.4+)

En Boot 3.4+ existe `MockMvcTester`, con API fluida de AssertJ (`assertThat(mvc.get().uri(...)).hasStatusOk()`). Con 3.3 nos quedamos con `MockMvc` clásico, que sigue plenamente soportado.

---

## 9. Variantes y trampas

### Trampa: un controlador por caso de uso vs uno por recurso

Hombergs propone **un controlador por caso de uso** (`CrearPedidoController`, `ConfirmarPedidoController`…) para que cada uno tenga una única dependencia. Es muy limpio y encaja con *vertical slice* (día 7). Uno por recurso (`PedidoController`) es lo más habitual en equipos Spring. Ambos son correctos; lo que **no** es correcto es un controlador con 15 dependencias. Si pasas de 5–6 puertos, divide.

### Trampa: devolver `ResponseEntity<?>`

El comodín pierde el tipo y la documentación OpenAPI se vuelve inútil. Tipa siempre: `ResponseEntity<PedidoResponse>`, `ResponseEntity<Void>`.

### Trampa: `@RequestBody` sin `@Valid`

Sin `@Valid` (o `@Validated`) las anotaciones del DTO son decoración. Y no hay aviso.

### Variante: OpenAPI primero

Con `springdoc-openapi` o generadores desde un contrato OpenAPI (*API first*), los DTOs se generan. El adaptador sigue siendo el mismo: traduce DTO generado → comando. La regla de no exponer el dominio aplica **más** todavía.

---

## 10. Resumen

| Decisión                              | Elección                                    | Motivo                                       |
|---------------------------------------|---------------------------------------------|----------------------------------------------|
| DTOs                                  | `record` en `adapter.in.web`                | Inmutables, `equals` por valor, sin Lombok   |
| Validación de forma                   | Bean Validation + `@Valid`                  | Errores por campo, antes del hexágono        |
| Validación de negocio                 | Constructores de VO y métodos del agregado  | Una sola fuente de verdad                    |
| Dependencias del controlador          | Puertos de entrada (interfaces)             | Mockeables, DIP                              |
| Acciones de negocio                   | `POST` sobre sub-recurso                    | Expresa intención, no CRUD                   |
| Crear                                 | `201` + `Location`                          | Semántica HTTP correcta                      |
| Test                                  | `@WebMvcTest` + `@MockBean` + `MockMvc`     | Rápido, cubre URL/JSON/validación/advice     |

---

## Ejercicio

Añade el endpoint **`GET /api/pedidos?clienteId={uuid}`** que devuelva un listado resumido (`id`, `estado`, `total`) de los pedidos de un cliente.

1. Define el puerto `ListarPedidosDeClienteQuery` en `application.port.in` (sin Spring).
2. Crea `PedidoResumenResponse` (record).
3. Añade el método al controlador.
4. Escribe el test `@WebMvcTest`, incluido el caso de `clienteId` ausente (`400`).

**Pista**: `@RequestParam UUID clienteId` es obligatorio por defecto; si falta, Spring lanza `MissingServletRequestParameterException` ⇒ `400`.

<details>
<summary>Solución</summary>

```java
// application.port.in
public interface ListarPedidosDeClienteQuery {
    List<Pedido> listar(ClienteId clienteId);
}
```

```java
// infrastructure.adapter.in.web
public record PedidoResumenResponse(UUID id, String estado, ImporteResponse total) {
    static PedidoResumenResponse desde(Pedido p) {
        return new PedidoResumenResponse(p.id().valor(), p.estado().name(),
                                         ImporteResponse.desde(p.total()));
    }
}
```

```java
// En PedidoController (nueva dependencia por constructor: ListarPedidosDeClienteQuery listarPedidos)
@GetMapping
List<PedidoResumenResponse> listar(@RequestParam UUID clienteId) {
    return listarPedidos.listar(new ClienteId(clienteId)).stream()
                        .map(PedidoResumenResponse::desde)
                        .toList();
}
```

```java
@MockBean ListarPedidosDeClienteQuery listarPedidos;

@Test
void listar_devuelveResumenes() throws Exception {
    given(listarPedidos.listar(new ClienteId(CLIENTE_ID)))
            .willReturn(List.of(pedidoConUnaLinea()));

    mockMvc.perform(get("/api/pedidos").param("clienteId", CLIENTE_ID.toString()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value(PEDIDO_ID.toString()))
           .andExpect(jsonPath("$[0].estado").value("BORRADOR"))
           .andExpect(jsonPath("$[0].total.importe").value(31.00));
}

@Test
void listar_sinClienteId_devuelve400() throws Exception {
    mockMvc.perform(get("/api/pedidos"))
           .andExpect(status().isBadRequest());
    verifyNoInteractions(listarPedidos);
}
```

Recuerda registrar `ListarPedidosDeClienteQuery` como bean en `BeanConfiguration` (día 4) para que la aplicación real arranque.
</details>

---

## Referencias

- Spring Framework, *Spring Web MVC*: https://docs.spring.io/spring-framework/reference/web/webmvc.html
- Spring Framework, *Testing* (MockMvc): https://docs.spring.io/spring-framework/reference/testing.html
- Spring Boot reference: https://docs.spring.io/spring-boot/index.html
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023), "Implementing a Web Adapter"; https://github.com/thombergs/buckpal
- Alistair Cockburn, *Hexagonal Architecture*: https://alistair.cockburn.us/hexagonal-architecture/
- Martin Fowler, *The Practical Test Pyramid*: https://martinfowler.com/articles/practical-test-pyramid.html
- JUnit 5 User Guide: https://junit.org/junit5/docs/current/user-guide/ ; Mockito: https://site.mockito.org/ ; AssertJ: https://assertj.github.io/doc/

---

## Mantra

> *"El DTO valida la forma, el dominio valida el fondo. El controlador solo pone la cara."*
