# Día 3 — Bloque 4: Gestión de errores en el borde

> *"Errors should never pass silently. Unless explicitly silenced."*
> — Tim Peters, *The Zen of Python* (2004)

---

## 1. El problema en una frase

El dominio lanza `TransicionDeEstadoInvalidaException`, que **no sabe nada de HTTP**; el cliente necesita un `409 Conflict` con un cuerpo de error **estándar y útil**; y alguien tiene que traducir sin llenar cada controlador de `try/catch`.

---

## 2. El estándar: Problem Details (RFC 9457, antes RFC 7807)

La **RFC 9457** (*Problem Details for HTTP APIs*, julio 2023) define un formato JSON estándar para errores HTTP. **Sustituye** a la RFC 7807 (2016) sin cambiar el formato básico; aclara puntos y añade un registro de tipos de problema.

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{
  "type":     "https://example.com/problemas/transicion-invalida",
  "title":    "Transición de estado inválida",
  "status":   409,
  "detail":   "No se puede confirmar un pedido en estado CANCELADO",
  "instance": "/api/pedidos/3f2a1c9e-0d4b-4a7e-9b1a-2c3d4e5f6a7b/confirmacion",
  "codigo":   "PEDIDO_TRANSICION_INVALIDA"
}
```

| Campo      | Significado (RFC 9457)                                                        |
|------------|-------------------------------------------------------------------------------|
| `type`     | URI que identifica el **tipo** de problema (por defecto `about:blank`)        |
| `title`    | Resumen legible del tipo; **no** debería cambiar entre ocurrencias            |
| `status`   | Código HTTP (redundante con la cabecera, útil si se reenvía el cuerpo)        |
| `detail`   | Explicación **de esta ocurrencia** concreta                                   |
| `instance` | URI de esta ocurrencia concreta (Spring pone la ruta de la petición)          |
| *extensiones* | Campos propios (`codigo`, `errores`…) permitidos por la RFC               |

Spring Framework 6 (Boot 3) trae soporte nativo:

- `ProblemDetail` — la representación.
- `ErrorResponse` / `ErrorResponseException` — excepciones que ya saben su `ProblemDetail`.
- `ResponseEntityExceptionHandler` — clase base que traduce **todas las excepciones de Spring MVC** (validación, tipo incorrecto, método no soportado…) a `ProblemDetail`.
- Propiedad `spring.mvc.problemdetails.enabled=true` — activa un handler por defecto si **no** defines el tuyo.

---

## 3. Bad: `try/catch` en cada endpoint

```java
@PostMapping("/{id}/confirmacion")
ResponseEntity<?> confirmar(@PathVariable UUID id) {
    try {
        confirmarPedido.ejecutar(new ConfirmarPedidoCommand(new PedidoId(id)));
        return ResponseEntity.noContent().build();
    } catch (PedidoNoEncontradoException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    } catch (TransicionDeEstadoInvalidaException e) {
        return ResponseEntity.status(400).body("Error: " + e.getMessage());
    } catch (Exception e) {
        return ResponseEntity.status(500).body(e.toString());
    }
}
```

### ¿Qué duele?

- **Duplicación**: el mismo bloque en los cuatro endpoints, cada uno con su variante.
- **Formatos inconsistentes**: un `Map`, un `String`, un `toString()`… El cliente no puede programar contra eso.
- **Códigos incoherentes**: aquí la transición inválida es `400`; en otro endpoint alguien puso `409`.
- **Fuga de información**: `e.toString()` en un `500` expone clases internas (y a veces SQL).
- **`ResponseEntity<?>`**: tipos perdidos.

### Bad (variante): el dominio que conoce HTTP

```java
@ResponseStatus(HttpStatus.NOT_FOUND)                     // org.springframework.web...
public class PedidoNoEncontradoException extends PedidoException { ... }
```

Cómodo… y **rompe la regla de dependencias**: el dominio importa Spring Web. Y si mañana el adaptador es Kafka, ¿qué significa un 404?

---

## 4. Good: `@RestControllerAdvice` que traduce dominio → `ProblemDetail`

### 4.1 La jerarquía de excepciones de dominio (día 2, recordatorio)

```
RuntimeException
└── PedidoException                         (base del dominio)
    ├── PedidoNoEncontradoException
    ├── StockInsuficienteException
    └── TransicionDeEstadoInvalidaException
```

Más `IllegalArgumentException` lanzada por los constructores de value objects (`new Cantidad(0)`).

### 4.2 La tabla de traducción

| Excepción                                    | Origen          | HTTP                         | `codigo`                       |
|----------------------------------------------|-----------------|------------------------------|--------------------------------|
| `MethodArgumentNotValidException`            | Bean Validation | `400 Bad Request`            | `VALIDACION`                   |
| `HttpMessageNotReadableException`            | JSON mal formado| `400 Bad Request`            | (Spring)                       |
| `MethodArgumentTypeMismatchException`        | UUID inválido   | `400 Bad Request`            | (Spring)                       |
| `IllegalArgumentException`                   | Value object    | `400 Bad Request`            | `DATO_INVALIDO`                |
| `PedidoNoEncontradoException`                | Dominio         | `404 Not Found`              | `PEDIDO_NO_ENCONTRADO`         |
| `TransicionDeEstadoInvalidaException`        | Dominio         | `409 Conflict`               | `PEDIDO_TRANSICION_INVALIDA`   |
| `StockInsuficienteException`                 | Dominio         | `409 Conflict`               | `STOCK_INSUFICIENTE`           |
| `PedidoException` (otras)                    | Dominio         | `422 Unprocessable Content`  | `PEDIDO_REGLA_DE_NEGOCIO`      |
| `Exception` (resto)                          | Bug / infra     | `500 Internal Server Error`  | `ERROR_INTERNO`                |

¿`409` o `422` para reglas de negocio? Debate eterno. Criterio práctico:

- **`409 Conflict`**: la petición es correcta pero **choca con el estado actual** del recurso (confirmar un cancelado; no hay stock *ahora*). Reintentar más tarde podría funcionar.
- **`422 Unprocessable Content`** (RFC 9110): la petición está bien formada pero **semánticamente no se puede procesar** independientemente del estado.

Lo importante no es el número: es **decidirlo una vez, documentarlo y aplicarlo siempre**. Esta tabla es un artefacto del equipo.

### 4.3 El handler

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.exception.StockInsuficienteException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BASE_TYPE = "https://example.com/problemas/";

    // ── Excepciones de dominio ─────────────────────────────────────────────

    @ExceptionHandler(PedidoNoEncontradoException.class)
    ProblemDetail pedidoNoEncontrado(PedidoNoEncontradoException ex) {
        return problema(HttpStatus.NOT_FOUND, "pedido-no-encontrado",
                "Pedido no encontrado", ex.getMessage(), "PEDIDO_NO_ENCONTRADO");
    }

    @ExceptionHandler(TransicionDeEstadoInvalidaException.class)
    ProblemDetail transicionInvalida(TransicionDeEstadoInvalidaException ex) {
        return problema(HttpStatus.CONFLICT, "transicion-invalida",
                "Transición de estado inválida", ex.getMessage(), "PEDIDO_TRANSICION_INVALIDA");
    }

    @ExceptionHandler(StockInsuficienteException.class)
    ProblemDetail stockInsuficiente(StockInsuficienteException ex) {
        return problema(HttpStatus.CONFLICT, "stock-insuficiente",
                "Stock insuficiente", ex.getMessage(), "STOCK_INSUFICIENTE");
    }

    @ExceptionHandler(PedidoException.class)   // red para nuevas excepciones de dominio
    ProblemDetail reglaDeNegocio(PedidoException ex) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "regla-de-negocio",
                "Regla de negocio incumplida", ex.getMessage(), "PEDIDO_REGLA_DE_NEGOCIO");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail datoInvalido(IllegalArgumentException ex) {
        return problema(HttpStatus.BAD_REQUEST, "dato-invalido",
                "Dato de entrada inválido", ex.getMessage(), "DATO_INVALIDO");
    }

    // ── Red de seguridad: nunca filtrar detalles internos ──────────────────

    @ExceptionHandler(Exception.class)
    ProblemDetail inesperado(Exception ex) {
        log.error("Error no controlado en la API de pedidos", ex);   // el detalle va al log
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "error-interno",
                "Error interno", "Se ha producido un error inesperado", "ERROR_INTERNO");
    }

    // ── Bean Validation: añadimos los errores por campo ────────────────────

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail body = ex.getBody();     // Spring ya lo trae con status 400 y detail
        body.setType(URI.create(BASE_TYPE + "validacion"));
        body.setTitle("Petición inválida");
        body.setProperty("codigo", "VALIDACION");
        List<Map<String, String>> errores = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "campo", fe.getField(),
                        "mensaje", String.valueOf(fe.getDefaultMessage())))
                .toList();
        body.setProperty("errores", errores);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    // ── Utilidad ───────────────────────────────────────────────────────────

    private static ProblemDetail problema(HttpStatus status, String tipo, String titulo,
                                          String detalle, String codigo) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detalle);
        pd.setType(URI.create(BASE_TYPE + tipo));
        pd.setTitle(titulo);
        pd.setProperty("codigo", codigo);
        return pd;
    }
}
```

Puntos clave:

- **Extender `ResponseEntityExceptionHandler`** nos da gratis `ProblemDetail` para ~15 excepciones de Spring MVC (`HttpMessageNotReadableException`, `TypeMismatchException`, `HttpRequestMethodNotSupportedException`, `NoResourceFoundException`…). Solo sobrescribimos lo que queremos enriquecer.
- **Devolver `ProblemDetail` directamente** desde un `@ExceptionHandler`: Spring pone el status de la respuesta a partir de `pd.getStatus()`, el `Content-Type` `application/problem+json` y, si no lo hemos fijado, `instance` con la ruta de la petición.
- **Orden de resolución**: Spring elige el `@ExceptionHandler` **más específico** según la jerarquía. `PedidoNoEncontradoException` gana a `PedidoException`, que gana a `Exception`. No depende del orden en el fichero.
- **`codigo`** (extensión): un identificador estable y legible por máquina. El `detail` puede cambiar de redacción o idioma; el `codigo` no. El frontend debería ramificar por `codigo` o `type`, nunca por `detail`.
- **El `500` no expone nada**: el detalle real va al log (con stacktrace) y al cliente un mensaje genérico. En el día 6–7 añadiremos un `traceId` como extensión para correlacionar.

> ¿Por qué el handler está en `adapter.in.web` y es de paquete? Porque **es parte del adaptador REST**: traduce al protocolo HTTP. Un adaptador Kafka tendrá su propia traducción (reintento, DLT…), ver bloque 5.

### 4.4 Diagrama

```
                       ┌──────────────────────────┐
  POST /confirmacion ─►│ PedidoController         │
                       └────────────┬─────────────┘
                                    │ ejecutar(cmd)
                                    ▼
                       ┌──────────────────────────┐
                       │ ConfirmarPedidoService   │
                       │   pedido.confirmar() ────┼──► throw TransicionDeEstadoInvalidaException
                       └──────────────────────────┘          (dominio puro, sin HTTP)
                                    │ la excepción burbujea
                                    ▼
                       ┌──────────────────────────┐
                       │ GlobalExceptionHandler   │  @ExceptionHandler más específico
                       │  ─► ProblemDetail 409    │
                       └────────────┬─────────────┘
                                    ▼
             409 Conflict · application/problem+json · {"type":..., "codigo":...}
```

---

## 5. Variantes y trampas

### Trampa: capturar `PedidoException` "por si acaso" en el servicio

```java
try { pedido.confirmar(); } catch (PedidoException e) { return false; }
```

Convertir excepciones en `boolean` dentro de la aplicación destruye la información que el adaptador necesita para elegir el código. Deja que burbujeen; si prefieres no usar excepciones, usa un **resultado tipado** (abajo).

### Variante: resultados con `sealed interface` en vez de excepciones

```java
public sealed interface ResultadoConfirmacion
        permits ResultadoConfirmacion.Confirmado, ResultadoConfirmacion.NoEncontrado,
                ResultadoConfirmacion.TransicionInvalida {
    record Confirmado(PedidoId id) implements ResultadoConfirmacion { }
    record NoEncontrado(PedidoId id) implements ResultadoConfirmacion { }
    record TransicionInvalida(EstadoPedido actual) implements ResultadoConfirmacion { }
}
```

```java
// En el controlador (Java 21: pattern matching en switch; en Java 17 es preview)
return switch (confirmarPedido.ejecutar(cmd)) {
    case Confirmado c         -> ResponseEntity.noContent().build();
    case NoEncontrado n       -> ResponseEntity.of(ProblemDetail.forStatus(404)).build();
    case TransicionInvalida t -> ResponseEntity.of(ProblemDetail.forStatus(409)).build();
};
```

Ventaja: el compilador obliga a tratar todos los casos. Inconveniente: más verboso y, en **Java 17**, el `switch` con patrones de tipo es *preview* (estable en Java 21); en 17 tendrías que usar `instanceof` encadenados. En el curso usamos excepciones de dominio por ser lo idiomático en Spring, pero conviene conocer la alternativa.

### Trampa: `ResponseStatusException` en el dominio o en el servicio

Es de `org.springframework.web.server`. Solo es aceptable **dentro del adaptador**.

### Trampa: mensajes de dominio en el `detail` con datos sensibles

Si `StockInsuficienteException` incluye el stock exacto del almacén y eso es información comercial, no lo pongas en `detail`. El handler es el sitio para decidir qué se cuenta.

### Variante: i18n

`ProblemDetail` admite títulos/detalles desde `MessageSource` (`problemDetail.title.<FQCN>`, `problemDetail.<FQCN>` en `messages.properties`) para las excepciones que implementan `ErrorResponse`. Para las de dominio, resuelve el mensaje en el handler a partir del `codigo`.

---

## 6. Testing de la traducción de errores

Con `@WebMvcTest`, el `@RestControllerAdvice` se carga automáticamente. Hacemos que el puerto mockeado **lance** la excepción de dominio y verificamos el `ProblemDetail`.

> Constructores asumidos del día 2: `PedidoNoEncontradoException(PedidoId)`, `TransicionDeEstadoInvalidaException(String)`, `StockInsuficienteException(String)`. Ajustad a vuestra firma real.

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.application.port.in.*;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.exception.StockInsuficienteException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import com.curso.pedidos.domain.model.PedidoId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;   // Boot 3.4+: @MockitoBean
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static com.curso.pedidos.infrastructure.adapter.in.web.PedidoMother.PEDIDO_ID;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PedidoController.class)
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mockMvc;

    @MockBean CrearPedidoUseCase crearPedido;
    @MockBean AnadirLineaPedidoUseCase anadirLinea;
    @MockBean ConfirmarPedidoUseCase confirmarPedido;
    @MockBean ConsultarPedidoQuery consultarPedido;

    @Test
    void pedidoNoEncontrado_se_traduce_a_404_ProblemDetail() throws Exception {
        given(consultarPedido.consultar(new PedidoId(PEDIDO_ID)))
                .willThrow(new PedidoNoEncontradoException(new PedidoId(PEDIDO_ID)));

        mockMvc.perform(get("/api/pedidos/{id}", PEDIDO_ID))
               .andExpect(status().isNotFound())
               .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
               .andExpect(jsonPath("$.status").value(404))
               .andExpect(jsonPath("$.title").value("Pedido no encontrado"))
               .andExpect(jsonPath("$.type").value("https://example.com/problemas/pedido-no-encontrado"))
               .andExpect(jsonPath("$.codigo").value("PEDIDO_NO_ENCONTRADO"));
    }

    @Test
    void transicionInvalida_se_traduce_a_409() throws Exception {
        willThrow(new TransicionDeEstadoInvalidaException("No se puede confirmar un pedido CANCELADO"))
                .given(confirmarPedido).ejecutar(any());

        mockMvc.perform(post("/api/pedidos/{id}/confirmacion", PEDIDO_ID))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.detail").value("No se puede confirmar un pedido CANCELADO"))
               .andExpect(jsonPath("$.codigo").value("PEDIDO_TRANSICION_INVALIDA"));
    }

    @Test
    void stockInsuficiente_se_traduce_a_409() throws Exception {
        willThrow(new StockInsuficienteException("Stock insuficiente para LIB-001"))
                .given(anadirLinea).ejecutar(any());

        mockMvc.perform(post("/api/pedidos/{id}/lineas", PEDIDO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku": "LIB-001", "cantidad": 5}
                                """))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.codigo").value("STOCK_INSUFICIENTE"));
    }

    @Test
    void validacion_devuelve400ConErroresPorCampo() throws Exception {
        mockMvc.perform(post("/api/pedidos/{id}/lineas", PEDIDO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku": "", "cantidad": -1}
                                """))
               .andExpect(status().isBadRequest())
               .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
               .andExpect(jsonPath("$.codigo").value("VALIDACION"))
               .andExpect(jsonPath("$.errores", hasSize(2)))
               .andExpect(jsonPath("$.errores[*].campo", containsInAnyOrder("sku", "cantidad")));
    }

    @Test
    void jsonMalFormado_devuelve400ProblemDetail() throws Exception {
        mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ esto no es json"))
               .andExpect(status().isBadRequest())
               .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void errorInesperado_devuelve500SinFiltrarDetalles() throws Exception {
        given(consultarPedido.consultar(any()))
                .willThrow(new IllegalStateException("Connection refused: db-prod-01:5432"));

        mockMvc.perform(get("/api/pedidos/{id}", PEDIDO_ID))
               .andExpect(status().isInternalServerError())
               .andExpect(jsonPath("$.codigo").value("ERROR_INTERNO"))
               .andExpect(jsonPath("$.detail", not(containsString("db-prod-01"))));
    }
}
```

El último test es el más valioso de todos: **demuestra que no filtramos información de infraestructura**. Ponedlo en todos los proyectos.

> En la práctica, `PedidoControllerTest` (bloque 2) y este test se pueden fusionar. Separarlos ayuda a leer: uno prueba el *happy path* de la traducción, otro la traducción de errores.

### Test unitario del handler (sin Spring)

Para una tabla de traducción grande, un test parametrizado directo es más rápido:

```java
class GlobalExceptionHandlerUnitTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void reglaDeNegocioGenerica_es422() {
        ProblemDetail pd = handler.reglaDeNegocio(new PedidoException("regla X") { });
        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("codigo", "PEDIDO_REGLA_DE_NEGOCIO");
    }
}
```

(Asume que `PedidoException` tiene un constructor `(String)` accesible; la clase anónima `{ }` sirve si es abstracta.)

---

## 7. Resumen

| Decisión                          | Elección                                                      |
|-----------------------------------|---------------------------------------------------------------|
| Formato de error                  | `ProblemDetail` (RFC 9457) · `application/problem+json`       |
| Dónde se traduce                  | Un `@RestControllerAdvice` en `adapter.in.web`                |
| Base                              | `extends ResponseEntityExceptionHandler`                      |
| Dominio                           | Excepciones propias, **sin** anotaciones de Spring            |
| Identificador estable             | Extensión `codigo` + `type` URI                               |
| `500`                             | Mensaje genérico al cliente, detalle completo al log          |
| Testing                           | `@WebMvcTest` con puertos que lanzan excepciones + `jsonPath` |

---

## Ejercicio

Añade el endpoint `DELETE /api/pedidos/{id}` que **cancela** el pedido (`CancelarPedidoUseCase`). Según el dominio, no se puede cancelar un pedido `ENVIADO`.

1. ¿Qué código devuelve si se cancela con éxito? ¿Y si el pedido ya está enviado?
2. ¿Hace falta tocar el `GlobalExceptionHandler`?
3. Escribe los tests.

**Pista**: ¿qué excepción lanza `Pedido.cancelar()` cuando está `ENVIADO`?

<details>
<summary>Solución</summary>

1. Éxito ⇒ `204 No Content`. Enviado ⇒ `Pedido.cancelar()` lanza `TransicionDeEstadoInvalidaException` ⇒ `409`. No existe ⇒ `404`.
2. **No**. La tabla ya cubre esas excepciones: ese es el beneficio de centralizar.

> Debate: ¿`DELETE` o `POST /{id}/cancelacion`? Cancelar **no borra** el pedido (sigue existiendo en estado `CANCELADO`), así que `POST /cancelacion` es semánticamente más fiel. `DELETE` es aceptable si el equipo lo acuerda. En el fichero 06 se usa `POST /cancelacion`.

```java
@DeleteMapping("/{id}")
ResponseEntity<Void> cancelar(@PathVariable UUID id) {
    cancelarPedido.ejecutar(new CancelarPedidoCommand(new PedidoId(id)));
    return ResponseEntity.noContent().build();
}
```

```java
@MockBean CancelarPedidoUseCase cancelarPedido;

@Test
void cancelar_ok_204() throws Exception {
    mockMvc.perform(delete("/api/pedidos/{id}", PEDIDO_ID))
           .andExpect(status().isNoContent());
    verify(cancelarPedido).ejecutar(new CancelarPedidoCommand(new PedidoId(PEDIDO_ID)));
}

@Test
void cancelar_pedidoEnviado_409() throws Exception {
    willThrow(new TransicionDeEstadoInvalidaException("No se puede cancelar un pedido ENVIADO"))
            .given(cancelarPedido).ejecutar(any());
    mockMvc.perform(delete("/api/pedidos/{id}", PEDIDO_ID))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.codigo").value("PEDIDO_TRANSICION_INVALIDA"));
}
```

Recuerda añadir `@MockBean CancelarPedidoUseCase` también al resto de clases `@WebMvcTest(PedidoController.class)`: si el controlador tiene una dependencia nueva y el test no la mockea, el contexto **no arranca**.
</details>

---

## Referencias

- RFC 9457, *Problem Details for HTTP APIs* (2023): https://www.rfc-editor.org/rfc/rfc9457
- RFC 7807, *Problem Details for HTTP APIs* (2016, obsoleta por 9457): https://www.rfc-editor.org/rfc/rfc7807
- RFC 9110, *HTTP Semantics* (códigos de estado): https://www.rfc-editor.org/rfc/rfc9110
- Spring Framework, *Spring Web MVC* — Error Responses y `@ExceptionHandler`: https://docs.spring.io/spring-framework/reference/web/webmvc.html
- Spring Framework, *Testing*: https://docs.spring.io/spring-framework/reference/testing.html
- Spring Boot reference: https://docs.spring.io/spring-boot/index.html
- Alistair Cockburn, *Hexagonal Architecture*: https://alistair.cockburn.us/hexagonal-architecture/

---

## Mantra

> *"El dominio grita en su idioma; el adaptador traduce el grito a un código que el cliente entiende. Nunca al revés."*
