# Día 3 — Bloque 6: Ejercicios y preguntas de repaso

> *"Tell me and I forget. Teach me and I remember. Involve me and I learn."*
> — Atribuida a Benjamin Franklin

---

## Cómo trabajar estos ejercicios

Cuatro ejercicios **graduados**. En clase hacemos el 1 y el 2 (≈25 min); el 3 y el 4 quedan como deberes o para quien vaya rápido.

| # | Ejercicio                                      | Nivel        | Tiempo  | Practica                                         |
|---|------------------------------------------------|--------------|---------|--------------------------------------------------|
| 1 | Endpoint de cancelar + mapeo de errores        | Básico       | ~15 min | REST, códigos HTTP, `@WebMvcTest`, advice        |
| 2 | Adaptador batch CSV                            | Intermedio   | ~20 min | Nuevo adaptador reutilizando puertos, errores    |
| 3 | `RealizarPedidoUseCase`: saldar la deuda Kafka | Intermedio+  | ~25 min | Diseño de puertos, adaptadores finos             |
| 4 | Adaptador GraphQL                              | Avanzado     | ~30 min | Spring for GraphQL, `@GraphQlTest`               |

Regla común a todos: **no se toca `domain`**. Si sientes la necesidad de hacerlo, para y pregúntate por qué.

---

## Ejercicio 1 — Cancelar un pedido por REST (básico)

### Enunciado

Expón la cancelación de pedidos:

```
POST /api/pedidos/{id}/cancelacion
```

1. Respuesta `204` si se cancela.
2. `404` (`PEDIDO_NO_ENCONTRADO`) si no existe.
3. `409` (`PEDIDO_TRANSICION_INVALIDA`) si el pedido ya está `ENVIADO` (invariante del día 2: *no se cancela un pedido enviado*).
4. Tests `@WebMvcTest` para los tres casos.

**Pista**: el puerto `CancelarPedidoUseCase` ya existe y el `GlobalExceptionHandler` ya sabe traducir las excepciones. ¿Cuántas líneas nuevas de producción esperas escribir?

<details>
<summary>Solución</summary>

**Producción** (en `PedidoController`, añadiendo la dependencia al constructor):

```java
private final CancelarPedidoUseCase cancelarPedido;

@PostMapping("/{id}/cancelacion")
ResponseEntity<Void> cancelar(@PathVariable UUID id) {
    cancelarPedido.ejecutar(new CancelarPedidoCommand(new PedidoId(id)));
    return ResponseEntity.noContent().build();
}
```

Tres líneas en el método. **Cero** cambios en el advice: la tabla excepción → HTTP del bloque 4 ya cubre ambos errores.

**Tests** (añadir `@MockBean CancelarPedidoUseCase cancelarPedido;` a **todas** las clases `@WebMvcTest(PedidoController.class)`, o el contexto no arranca):

```java
@Test
void cancelar_ok_devuelve204() throws Exception {
    mockMvc.perform(post("/api/pedidos/{id}/cancelacion", PEDIDO_ID))
           .andExpect(status().isNoContent());

    verify(cancelarPedido).ejecutar(new CancelarPedidoCommand(new PedidoId(PEDIDO_ID)));
}

@Test
void cancelar_noExiste_devuelve404() throws Exception {
    willThrow(new PedidoNoEncontradoException(new PedidoId(PEDIDO_ID)))
            .given(cancelarPedido).ejecutar(any());

    mockMvc.perform(post("/api/pedidos/{id}/cancelacion", PEDIDO_ID))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.codigo").value("PEDIDO_NO_ENCONTRADO"));
}

@Test
void cancelar_pedidoEnviado_devuelve409() throws Exception {
    willThrow(new TransicionDeEstadoInvalidaException("No se puede cancelar un pedido ENVIADO"))
            .given(cancelarPedido).ejecutar(any());

    mockMvc.perform(post("/api/pedidos/{id}/cancelacion", PEDIDO_ID))
           .andExpect(status().isConflict())
           .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
           .andExpect(jsonPath("$.codigo").value("PEDIDO_TRANSICION_INVALIDA"));
}
```

**Truco** para no repetir los `@MockBean` en cada clase: una clase base abstracta anotada o una anotación compuesta. Con Boot 3.4+ y `@MockitoBean` se pueden declarar a nivel de clase: `@MockitoBean(types = {CrearPedidoUseCase.class, ...})`.

**Reflexión**: si la invariante "no cancelar un enviado" estuviera en el controlador, el job de borradores abandonados (bloque 5) **no la respetaría**. Está en `Pedido.cancelar()`, así que la respetan todos.
</details>

---

## Ejercicio 2 — Adaptador batch CSV (intermedio)

### Enunciado

El departamento comercial recibe pedidos de clientes B2B en un CSV:

```
clienteId;sku;cantidad
11111111-2222-3333-4444-555555555555;LIB-001;2
11111111-2222-3333-4444-555555555555;LIB-002;1
99999999-8888-7777-6666-555555555555;LIB-003;0
```

Cada fila es **un pedido de una línea**. Crea un adaptador de entrada `ImportarPedidosCsvAdapter` en `infrastructure.adapter.in.batch` que:

1. Reciba un `java.io.Reader` (así se prueba sin ficheros).
2. Por cada fila cree el pedido y añada su línea usando los puertos existentes.
3. **No se detenga** si una fila falla; devuelva un `ResultadoImportacion(int correctos, List<String> errores)`.
4. Ignore la cabecera y líneas en blanco.
5. Tests con `StringReader`.

**Pista**: ¿qué errores son "de la fila" (dato inválido, negocio) y cuáles deben abortar todo (I/O)?

<details>
<summary>Solución</summary>

```java
package com.curso.pedidos.infrastructure.adapter.in.batch;

import com.curso.pedidos.application.port.in.*;
import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.model.*;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
class ImportarPedidosCsvAdapter {

    record ResultadoImportacion(int correctos, List<String> errores) { }

    private final CrearPedidoUseCase crearPedido;
    private final AnadirLineaPedidoUseCase anadirLinea;

    ImportarPedidosCsvAdapter(CrearPedidoUseCase crearPedido, AnadirLineaPedidoUseCase anadirLinea) {
        this.crearPedido = crearPedido;
        this.anadirLinea = anadirLinea;
    }

    ResultadoImportacion importar(Reader origen) {
        int correctos = 0;
        List<String> errores = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(origen)) {
            String linea;
            int numero = 0;
            while ((linea = reader.readLine()) != null) {
                numero++;
                if (numero == 1 || linea.isBlank()) continue;           // cabecera / vacías
                try {
                    procesar(linea);
                    correctos++;
                } catch (IllegalArgumentException | PedidoException e) { // errores de la fila
                    errores.add("Línea " + numero + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el CSV", e); // aborta: problema de infra
        }
        return new ResultadoImportacion(correctos, List.copyOf(errores));
    }

    private void procesar(String linea) {
        String[] campos = linea.split(";");
        if (campos.length != 3) {
            throw new IllegalArgumentException("se esperaban 3 columnas y hay " + campos.length);
        }
        PedidoId id = crearPedido.ejecutar(
                new CrearPedidoCommand(new ClienteId(UUID.fromString(campos[0].trim()))));
        anadirLinea.ejecutar(new AnadirLineaPedidoCommand(
                id, new ProductoId(campos[1].trim()), new Cantidad(Integer.parseInt(campos[2].trim()))));
    }
}
```

> `NumberFormatException` y el error de `UUID.fromString` son subclases de `IllegalArgumentException`, así que entran en el mismo `catch`.

> Igual que en Kafka, si la línea falla al añadirla queda un pedido vacío en `BORRADOR`. El job de borradores abandonados lo limpiará… o mejor, usa el `RealizarPedidoUseCase` del ejercicio 3.

```java
class ImportarPedidosCsvAdapterTest {

    private final CrearPedidoUseCase crearPedido = mock(CrearPedidoUseCase.class);
    private final AnadirLineaPedidoUseCase anadirLinea = mock(AnadirLineaPedidoUseCase.class);
    private final ImportarPedidosCsvAdapter adapter = new ImportarPedidosCsvAdapter(crearPedido, anadirLinea);

    @Test
    void importaLasFilasValidasYAcumulaLosErrores() {
        when(crearPedido.ejecutar(any())).thenAnswer(inv -> new PedidoId(UUID.randomUUID()));
        String csv = """
                clienteId;sku;cantidad
                11111111-2222-3333-4444-555555555555;LIB-001;2

                99999999-8888-7777-6666-555555555555;LIB-003;0
                no-es-uuid;LIB-004;1
                """;

        var resultado = adapter.importar(new StringReader(csv));

        assertThat(resultado.correctos()).isEqualTo(1);
        assertThat(resultado.errores()).hasSize(2)
                .anySatisfy(e -> assertThat(e).startsWith("Línea 4"))
                .anySatisfy(e -> assertThat(e).startsWith("Línea 5"));
        verify(anadirLinea, times(1)).ejecutar(any());
    }

    @Test
    void unErrorDeNegocioNoDetieneLaImportacion() {
        when(crearPedido.ejecutar(any())).thenAnswer(inv -> new PedidoId(UUID.randomUUID()));
        willThrow(new StockInsuficienteException("sin stock de LIB-001"))
                .willDoNothing()
                .given(anadirLinea).ejecutar(any());
        String csv = """
                clienteId;sku;cantidad
                11111111-2222-3333-4444-555555555555;LIB-001;2
                11111111-2222-3333-4444-555555555555;LIB-002;1
                """;

        var resultado = adapter.importar(new StringReader(csv));

        assertThat(resultado.correctos()).isEqualTo(1);
        assertThat(resultado.errores()).singleElement()
                .satisfies(e -> assertThat(e).contains("sin stock"));
    }
}
```

¿Quién llama a `importar`? Otro adaptador más externo: un `CommandLineRunner` que abre el fichero, un endpoint `POST /api/importaciones` con `MultipartFile`, o un `@Scheduled` que vigila una carpeta. Para volúmenes grandes (millones de filas, reinicio, *chunks*), **Spring Batch** es la herramienta; el `ItemWriter` invocaría el mismo puerto.
</details>

---

## Ejercicio 3 — `RealizarPedidoUseCase`: saldar la deuda del listener (intermedio+)

### Enunciado

En el bloque 5 el listener Kafka (y el CSV del ejercicio 2) encadenan `crear` + `anadirLinea` N veces. Eso **no es atómico** y es lógica de aplicación en el adaptador.

1. Diseña un puerto de entrada `RealizarPedidoUseCase` que cree un pedido con todas sus líneas **en una sola operación**.
2. Esboza `RealizarPedidoService` (sin Spring; la transacción la veremos en el día 6).
3. Refactoriza `PedidoSolicitadoKafkaListener` para usarlo.
4. Actualiza su test.

**Pista**: el comando lleva una lista de líneas como records anidados. ¿Dónde se valida que la lista no esté vacía?

<details>
<summary>Solución</summary>

**Puerto** (`application.port.in`):

```java
public interface RealizarPedidoUseCase {
    PedidoId ejecutar(RealizarPedidoCommand command);
}

public record RealizarPedidoCommand(ClienteId clienteId, List<Linea> lineas) {
    public record Linea(ProductoId productoId, Cantidad cantidad) { }

    public RealizarPedidoCommand {
        Objects.requireNonNull(clienteId, "clienteId");
        if (lineas == null || lineas.isEmpty()) {
            throw new IllegalArgumentException("Un pedido debe tener al menos una línea");
        }
        lineas = List.copyOf(lineas);   // inmutabilidad defensiva
    }
}
```

**Servicio** (`application.service`), reutilizando los puertos de salida del día 2:

```java
public class RealizarPedidoService implements RealizarPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final CatalogoProductos catalogo;
    private final Clock clock;

    public RealizarPedidoService(PedidoRepository pedidoRepository, CatalogoProductos catalogo, Clock clock) {
        this.pedidoRepository = pedidoRepository;
        this.catalogo = catalogo;
        this.clock = clock;
    }

    @Override
    public PedidoId ejecutar(RealizarPedidoCommand cmd) {
        Pedido pedido = Pedido.crear(PedidoId.nuevo(), cmd.clienteId(), Instant.now(clock));
        for (var linea : cmd.lineas()) {
            Dinero precio = catalogo.precioDe(linea.productoId());    // firma asumida del puerto out
            pedido.anadirLinea(linea.productoId(), linea.cantidad(), precio);
        }
        pedidoRepository.guardar(pedido);   // una sola escritura: todo o nada
        return pedido.id();
    }
}
```

(`PedidoId.nuevo()` y `CatalogoProductos.precioDe(...)`: ajustad a los nombres de vuestro día 2.)

**Listener refactorizado**: vuelve a ser una traducción pura.

```java
@KafkaListener(topics = "${pedidos.kafka.topic-solicitados:pedidos.solicitados}")
void onPedidoSolicitado(PedidoSolicitadoMessage mensaje) {
    realizarPedido.ejecutar(new RealizarPedidoCommand(
            new ClienteId(mensaje.clienteId()),
            mensaje.lineas().stream()
                   .map(l -> new RealizarPedidoCommand.Linea(new ProductoId(l.sku()), new Cantidad(l.cantidad())))
                   .toList()));
}
```

**Test**:

```java
@Test
void traduceElMensajeAUnUnicoComando() {
    UUID clienteId = UUID.randomUUID();

    listener.onPedidoSolicitado(new PedidoSolicitadoMessage(clienteId, List.of(
            new PedidoSolicitadoMessage.Linea("LIB-001", 2),
            new PedidoSolicitadoMessage.Linea("LIB-002", 1))));

    verify(realizarPedido).ejecutar(new RealizarPedidoCommand(new ClienteId(clienteId), List.of(
            new RealizarPedidoCommand.Linea(new ProductoId("LIB-001"), new Cantidad(2)),
            new RealizarPedidoCommand.Linea(new ProductoId("LIB-002"), new Cantidad(1)))));
}
```

**Lección**: el adaptador "gordo" era un **síntoma** de que faltaba un caso de uso. Arreglado el puerto, el adaptador adelgaza solo. Y el `PedidoController` puede ofrecer también `POST /api/pedidos` con líneas usando el mismo puerto.
</details>

---

## Ejercicio 4 — Adaptador GraphQL (avanzado)

### Enunciado

El equipo de frontend quiere GraphQL. Con **Spring for GraphQL** expón:

```graphql
type Query    { pedido(id: ID!): Pedido }
type Mutation { confirmarPedido(id: ID!): Boolean! }
```

1. Esquema en `src/main/resources/graphql/schema.graphqls`.
2. `PedidoGraphQlController` en `infrastructure.adapter.in.graphql` usando `ConsultarPedidoQuery` y `ConfirmarPedidoUseCase`.
3. Traducir `PedidoNoEncontradoException` a un error GraphQL `NOT_FOUND`.
4. Test con `@GraphQlTest`.

**Pista**: dependencia `spring-boot-starter-graphql`; anotaciones `@QueryMapping`, `@MutationMapping`, `@Argument`; para errores, `DataFetcherExceptionResolverAdapter`.

<details>
<summary>Solución</summary>

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-graphql</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.graphql</groupId>
    <artifactId>spring-graphql-test</artifactId>
    <scope>test</scope>
</dependency>
```

```graphql
# src/main/resources/graphql/schema.graphqls
type Query {
    pedido(id: ID!): Pedido
}

type Mutation {
    confirmarPedido(id: ID!): Boolean!
}

type Pedido {
    id: ID!
    estado: String!
    lineas: [Linea!]!
    total: Float!
    moneda: String!
}

type Linea {
    sku: String!
    cantidad: Int!
}
```

DTOs propios del adaptador GraphQL (no reutilizamos los REST: protocolos distintos, contratos distintos):

```java
package com.curso.pedidos.infrastructure.adapter.in.graphql;

record PedidoGql(String id, String estado, List<LineaGql> lineas, BigDecimal total, String moneda) {
    static PedidoGql desde(Pedido p) {
        return new PedidoGql(p.id().valor().toString(), p.estado().name(),
                p.lineas().stream().map(l -> new LineaGql(l.productoId().sku(), l.cantidad().valor())).toList(),
                p.total().importe(), p.total().moneda().getCurrencyCode());
    }
}

record LineaGql(String sku, int cantidad) { }
```

```java
package com.curso.pedidos.infrastructure.adapter.in.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
class PedidoGraphQlController {

    private final ConsultarPedidoQuery consultarPedido;
    private final ConfirmarPedidoUseCase confirmarPedido;

    PedidoGraphQlController(ConsultarPedidoQuery consultarPedido, ConfirmarPedidoUseCase confirmarPedido) {
        this.consultarPedido = consultarPedido;
        this.confirmarPedido = confirmarPedido;
    }

    @QueryMapping
    PedidoGql pedido(@Argument String id) {
        return PedidoGql.desde(consultarPedido.consultar(new PedidoId(UUID.fromString(id))));
    }

    @MutationMapping
    boolean confirmarPedido(@Argument String id) {
        confirmarPedido.ejecutar(new ConfirmarPedidoCommand(new PedidoId(UUID.fromString(id))));
        return true;
    }
}
```

Traducción de errores (el "`@RestControllerAdvice`" de GraphQL):

```java
package com.curso.pedidos.infrastructure.adapter.in.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

@Component
class PedidoGraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        ErrorType tipo;
        if (ex instanceof PedidoNoEncontradoException) {
            tipo = ErrorType.NOT_FOUND;
        } else if (ex instanceof PedidoException || ex instanceof IllegalArgumentException) {
            tipo = ErrorType.BAD_REQUEST;
        } else {
            return null;   // deja que Spring lo trate como INTERNAL_ERROR genérico
        }
        return GraphqlErrorBuilder.newError(env)
                .errorType(tipo)
                .message(ex.getMessage())
                .build();
    }
}
```

> En GraphQL la respuesta HTTP suele ser `200` incluso con errores: van en el array `errors` del cuerpo. Otra vez: **misma excepción de dominio, semántica distinta según el protocolo**.

Test:

```java
package com.curso.pedidos.infrastructure.adapter.in.graphql;

import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.boot.test.mock.mockito.MockBean;   // Boot 3.4+: @MockitoBean
import org.springframework.context.annotation.Import;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;

@GraphQlTest(PedidoGraphQlController.class)
@Import(PedidoGraphQlExceptionResolver.class)
class PedidoGraphQlControllerTest {

    @Autowired GraphQlTester graphQlTester;

    @MockBean ConsultarPedidoQuery consultarPedido;
    @MockBean ConfirmarPedidoUseCase confirmarPedido;

    @Test
    void consultaUnPedido() {
        given(consultarPedido.consultar(new PedidoId(PEDIDO_ID))).willReturn(pedidoConUnaLinea());

        graphQlTester.document("""
                        { pedido(id: "%s") { estado lineas { sku cantidad } total } }
                        """.formatted(PEDIDO_ID))
                .execute()
                .path("pedido.estado").entity(String.class).isEqualTo("BORRADOR")
                .path("pedido.lineas[0].sku").entity(String.class).isEqualTo("LIB-001")
                .path("pedido.total").entity(Double.class).isEqualTo(31.0);
    }

    @Test
    void confirmarPedido_invocaElPuerto() {
        graphQlTester.document("""
                        mutation { confirmarPedido(id: "%s") }
                        """.formatted(PEDIDO_ID))
                .execute()
                .path("confirmarPedido").entity(Boolean.class).isEqualTo(true);

        verify(confirmarPedido).ejecutar(new ConfirmarPedidoCommand(new PedidoId(PEDIDO_ID)));
    }

    @Test
    void pedidoInexistente_devuelveErrorNotFound() {
        given(consultarPedido.consultar(any()))
                .willThrow(new PedidoNoEncontradoException(new PedidoId(PEDIDO_ID)));

        graphQlTester.document("""
                        { pedido(id: "%s") { estado } }
                        """.formatted(PEDIDO_ID))
                .execute()
                .errors()
                .expect(e -> e.getErrorType() == ErrorType.NOT_FOUND);
    }
}
```

(`PedidoMother` está en el paquete `web`; cópialo o muévelo a un paquete de *test fixtures* compartido, p. ej. `com.curso.pedidos.testfixtures`.)

**Balance**: un protocolo entero nuevo, cero cambios en `domain` y `application`. Los mismos `ConsultarPedidoQuery` y `ConfirmarPedidoUseCase` que usa REST.
</details>

---

## Preguntas de repaso

Responde mentalmente (o en voz alta en clase) antes de abrir la respuesta.

**1.** ¿De qué depende un adaptador de entrada: del puerto de entrada, del servicio de aplicación o de un puerto de salida?

<details><summary>Respuesta</summary>
Del <b>puerto de entrada</b> (la interfaz <code>XxxUseCase</code>/<code>XxxQuery</code>). Nunca del servicio concreto (acoplamiento y tests más pesados) ni de un puerto de salida (se saltaría el caso de uso).
</details>

**2.** Si `Cantidad` ya valida que sea positiva, ¿por qué poner `@Positive` en el DTO?

<details><summary>Respuesta</summary>
Para dar <b>mejores errores en el borde</b> (todos los campos a la vez, <code>400</code> con detalle por campo) y no entrar al hexágono con datos basura. El dominio sigue siendo la red de seguridad que protege la invariante venga de donde venga. Son dos conocimientos distintos que hoy coinciden.
</details>

**3.** Da tres razones para no devolver el agregado `Pedido` como JSON.

<details><summary>Respuesta</summary>
(1) Acopla el contrato público al modelo interno: un refactor rompe clientes. (2) Obliga a meter anotaciones de Jackson en el dominio o expone detalles internos/forma accidental. (3) En entrada permite <i>mass assignment</i> y obliga a tener setters/constructor vacío, rompiendo invariantes.
</details>

**4.** ¿Dónde se traduce `TransicionDeEstadoInvalidaException` a `409`? ¿Por qué no con `@ResponseStatus` en la propia excepción?

<details><summary>Respuesta</summary>
En el <code>@RestControllerAdvice</code> del adaptador web. <code>@ResponseStatus</code> haría que el dominio dependa de Spring Web y le asignaría semántica HTTP a algo que también llega por Kafka, CLI o GraphQL, donde la traducción es otra.
</details>

**5.** ¿Qué diferencia hay entre RFC 7807 y RFC 9457?

<details><summary>Respuesta</summary>
La RFC 9457 (2023) <b>sustituye</b> a la 7807 (2016) manteniendo el mismo formato (<code>type</code>, <code>title</code>, <code>status</code>, <code>detail</code>, <code>instance</code> + extensiones). Aclara su uso, cómo tratar múltiples problemas y crea un registro IANA de tipos de problema. El <code>ProblemDetail</code> de Spring 6 es compatible con ambas.
</details>

**6.** ¿Qué carga `@WebMvcTest` y qué no? ¿Por qué hace falta `@MockBean` para los puertos?

<details><summary>Respuesta</summary>
Carga la capa MVC: controladores, <code>@ControllerAdvice</code>, Jackson, validación, filtros y convertidores. <b>No</b> carga <code>@Component</code>/<code>@Service</code> generales ni JPA. Como el controlador necesita los puertos por constructor y no hay beans que los implementen, hay que aportar mocks (<code>@MockBean</code> en Boot 3.3; <code>@MockitoBean</code> desde Boot 3.4 / Spring Framework 6.2).
</details>

**7.** Un listener de Kafka captura todas las excepciones y las registra en el log. ¿Qué problema hay?

<details><summary>Respuesta</summary>
El contenedor considera el mensaje procesado y avanza el offset: el mensaje <b>se pierde</b>. Hay que propagar la excepción y configurar un <code>DefaultErrorHandler</code> con reintentos y Dead Letter Topic, sin reintentar los errores de negocio.
</details>

**8.** ¿Por qué el job `@Scheduled` recibe un `Clock` por constructor?

<details><summary>Respuesta</summary>
Para que el tiempo sea una dependencia sustituible: en tests se usa <code>Clock.fixed(...)</code> y el resultado es determinista. Sin él, el test depende de la hora real del sistema.
</details>

**9.** Tu controlador llama a tres casos de uso seguidos para atender una petición. ¿Qué te dice eso del diseño?

<details><summary>Respuesta</summary>
Que falta un caso de uso que represente esa operación de negocio completa (p. ej. <code>RealizarPedidoUseCase</code>). Orquestar en el adaptador esconde lógica de aplicación, no es atómico y se duplicará en cada adaptador que lo necesite.
</details>

**10.** Has añadido Kafka, un job y una CLI. ¿Cuántos ficheros de `domain` has tocado? ¿Qué principio lo explica?

<details><summary>Respuesta</summary>
Ninguno. Es el <b>Open/Closed</b> a escala arquitectónica, posible gracias a la <b>inversión de dependencias</b>: los adaptadores dependen de los puertos, no al revés. Es exactamente la promesa de Cockburn: la aplicación es indiferente a quién la conduce.
</details>

**11.** MapStruct o mapeo manual: ¿cuándo elegirías cada uno?

<details><summary>Respuesta</summary>
Manual por defecto: pocos tipos por adaptador, explícito, sin dependencias y natural con VOs que validan en el constructor. MapStruct cuando hay muchos DTOs grandes y "planos" con nombres coincidentes; siempre con <code>unmappedTargetPolicy=ERROR</code>. Evitar mapeadores por reflexión en runtime.
</details>

**12.** Clasifica: "la cantidad máxima por línea es 50 unidades" y "el tamaño máximo de página del listado es 100". ¿Dominio o adaptador?

<details><summary>Respuesta</summary>
La primera es <b>negocio</b> (aplica igual por HTTP, Kafka o CSV) ⇒ dominio. La segunda es una protección del <b>protocolo</b> REST ⇒ adaptador (Bean Validation o lógica del controlador).
</details>

---

## Para la próxima sesión (día 4)

Hoy hemos dejado los puertos **de salida** sin implementar: los tests mockean los puertos de entrada y nadie ha tocado una base de datos. Mañana cruzamos al lado derecho del hexágono: `PedidoPersistenceAdapter` con Spring Data JPA, `StripeLikePasarelaAdapter` con `RestClient`, notificaciones y el cableado completo en `BeanConfiguration`.

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture* (2005): https://alistair.cockburn.us/hexagonal-architecture/
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023); https://github.com/thombergs/buckpal
- Spring Framework, *Spring Web MVC*: https://docs.spring.io/spring-framework/reference/web/webmvc.html
- Spring Framework, *Testing*: https://docs.spring.io/spring-framework/reference/testing.html
- RFC 9457, *Problem Details for HTTP APIs*: https://www.rfc-editor.org/rfc/rfc9457
- Spring for GraphQL, Reference: https://docs.spring.io/spring-graphql/reference/
- Spring for Apache Kafka, Reference: https://docs.spring.io/spring-kafka/reference/
- Spring Batch, Reference: https://docs.spring.io/spring-batch/reference/
- Martin Fowler, *The Practical Test Pyramid*: https://martinfowler.com/articles/practical-test-pyramid.html

---

## Mantra

> *"Cada protocolo nuevo es un adaptador nuevo, nunca un dominio nuevo. Si el hexágono se mueve cuando llega un cliente, no era un hexágono."*
