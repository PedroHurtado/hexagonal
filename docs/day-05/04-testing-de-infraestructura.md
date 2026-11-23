# Día 5 — Bloque 4: Testing de infraestructura

> *"Don't mock what you don't own."* — Steve Freeman & Nat Pryce, *Growing Object-Oriented Software, Guided by Tests* (2009)

---

## 1. El problema en una frase

Los adaptadores son **traductores** entre el mundo exterior (HTTP, SQL, JSON, Kafka) y nuestros puertos. Sus bugs son de traducción: un campo JSON mal nombrado, un código HTTP equivocado, una columna que no se mapea, un `BigDecimal` que pierde decimales. **Esos bugs no se ven con mocks**: hay que probar el adaptador contra la tecnología real, pero **solo** el adaptador.

---

## 2. Bad: mockear la tecnología

```java
@ExtendWith(MockitoExtension.class)
class PedidoPersistenceAdapterBadTest {

    @Mock PedidoJpaRepository jpaRepository;            // ✘ mock de Spring Data
    @InjectMocks PedidoPersistenceAdapter adapter;

    @Test
    void guarda() {
        Pedido pedido = PedidoMother.borradorConLineas();

        adapter.guardar(pedido);

        verify(jpaRepository).save(any(PedidoJpaEntity.class));   // ¿y el mapeo? ¿y la BD?
    }
}
```

### ¿Qué duele?

- Pasa aunque la entidad JPA tenga mal el `@OneToMany`, aunque falte una columna en Flyway o aunque el `cascade` duplique líneas.
- Prueba **que se llamó a `save`**, que es justo lo único que no puede fallar.
- Acopla el test a cómo usa el adaptador Spring Data (si cambias a `JdbcClient`, el test se rompe sin que el comportamiento cambie).

La regla: **los adaptadores de salida se prueban contra la infraestructura real (o un doble de protocolo como WireMock)**; los de entrada, contra el *framework* real (MockMvc) con el **puerto** doblado.

---

## 3. Los *slices* de Spring Boot Test

Un *slice* arranca **solo la parte del contexto** necesaria para una capa técnica. Es la herramienta natural para probar adaptadores en hexagonal: cada slice coincide con un tipo de adaptador.

```
                         @SpringBootTest  (contexto COMPLETO)
   ┌───────────────────────────────────────────────────────────────────────┐
   │                                                                       │
   │  ┌──────────────────┐   ┌───────────────────┐   ┌──────────────────┐  │
   │  │  @WebMvcTest     │   │  (nuestros        │   │  @DataJpaTest    │  │
   │  │  Controllers     │   │   servicios de    │   │  Entities        │  │
   │  │  @ControllerAdv. │   │   aplicación:     │   │  JpaRepositories │  │
   │  │  Jackson, Valid. │   │   NO se cargan    │   │  DataSource      │  │
   │  │  Filters, MockMvc│   │   en los slices)  │   │  Flyway, TestEM  │  │
   │  └──────────────────┘   └───────────────────┘   └──────────────────┘  │
   │                                                                       │
   │  ┌──────────────────┐   ┌───────────────────┐                         │
   │  │ @RestClientTest  │   │  @JsonTest        │                         │
   │  │ RestClient.Build.│   │  ObjectMapper,    │                         │
   │  │ MockRestService  │   │  JacksonTester    │                         │
   │  └──────────────────┘   └───────────────────┘                         │
   └───────────────────────────────────────────────────────────────────────┘
```

| Slice              | Qué carga                                                      | Qué NO carga                          | Adaptador hexagonal                   |
|--------------------|----------------------------------------------------------------|---------------------------------------|---------------------------------------|
| `@WebMvcTest`      | `@Controller`, `@ControllerAdvice`, `@JsonComponent`, `Converter`, `Filter`, `WebMvcConfigurer`, MockMvc | `@Service`, `@Component`, `@Repository`, JPA | `adapter.in.web` |
| `@WebFluxTest`     | Equivalente reactivo + `WebTestClient`                         | Igual                                 | `adapter.in.web` (WebFlux)            |
| `@DataJpaTest`     | Entidades, repositorios Spring Data, `DataSource`, Flyway/Liquibase, `TestEntityManager`. **Transaccional con rollback** | Web, servicios | `adapter.out.persistence` |
| `@JdbcTest`/`@DataJdbcTest` | `DataSource`, `JdbcTemplate`/`JdbcClient`, Spring Data JDBC | JPA, web                     | `adapter.out.persistence` (JDBC)      |
| `@RestClientTest`  | `RestClient.Builder`/`RestTemplateBuilder`, Jackson, `MockRestServiceServer` | El resto       | `adapter.out.payment` (alternativa a WireMock) |
| `@JsonTest`        | Jackson, `JacksonTester`                                       | Todo lo demás                         | DTOs de cualquier adaptador           |

> En cristiano: **un slice por anillo exterior**. El hexágono y los slices encajan como un guante porque ambos cortan por la misma línea: la tecnología.

---

## 4. Adaptadores de entrada: `@WebMvcTest` + MockMvc

### 4.1 El adaptador bajo prueba (del día 3, resumido)

```java
@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    private final CrearPedidoUseCase crearPedido;
    private final ConfirmarPedidoUseCase confirmarPedido;
    private final CancelarPedidoUseCase cancelarPedido;
    private final ConsultarPedidoQuery consultarPedido;

    // constructor ...

    @PostMapping
    public ResponseEntity<Void> crear(@Valid @RequestBody CrearPedidoRequest request) {
        PedidoId id = crearPedido.ejecutar(new CrearPedidoCommand(new ClienteId(request.clienteId())));
        return ResponseEntity.created(URI.create("/api/pedidos/" + id.valor())).build();
    }

    @PostMapping("/{id}/confirmacion")
    public ResponseEntity<Void> confirmar(@PathVariable UUID id) {
        confirmarPedido.ejecutar(new ConfirmarPedidoCommand(new PedidoId(id)));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public PedidoResponse consultar(@PathVariable UUID id) {
        return PedidoResponse.desde(consultarPedido.consultar(new PedidoId(id)));
    }
}

public record CrearPedidoRequest(@NotNull UUID clienteId) { }

// total como String "119.70": el dinero no viaja como double en JSON
public record PedidoResponse(UUID id, UUID clienteId, String estado, String total, String moneda) { ... }
```

Y el `@RestControllerAdvice` traduce excepciones de dominio a `ProblemDetail` (RFC 9457):

| Excepción                              | HTTP |
|----------------------------------------|------|
| `PedidoNoEncontradoException`          | 404  |
| `TransicionDeEstadoInvalidaException`  | 409  |
| `PedidoException` (resto de reglas)    | 422  |
| `MethodArgumentNotValidException`      | 400  |

### 4.2 El test

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.application.port.in.CancelarPedidoUseCase;
import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.in.ConsultarPedidoQuery;
import com.curso.pedidos.application.port.in.CrearPedidoCommand;
import com.curso.pedidos.application.port.in.CrearPedidoUseCase;
import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.PedidoMother;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PedidoController.class)
class PedidoControllerTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired MockMvc mockMvc;

    // Spring Boot 3.3: @MockBean. En 3.4+ está deprecated -> @MockitoBean (ver nota abajo)
    @MockBean CrearPedidoUseCase crearPedido;
    @MockBean ConfirmarPedidoUseCase confirmarPedido;
    @MockBean CancelarPedidoUseCase cancelarPedido;
    @MockBean ConsultarPedidoQuery consultarPedido;

    @Nested
    class CrearPedido {

        @Test
        void devuelve_201_con_Location() throws Exception {
            UUID clienteId = UUID.randomUUID();
            given(crearPedido.ejecutar(new CrearPedidoCommand(new ClienteId(clienteId))))
                    .willReturn(new PedidoId(ID));

            mockMvc.perform(post("/api/pedidos")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "clienteId": "%s" }
                                    """.formatted(clienteId)))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/pedidos/" + ID));
        }

        @Test
        void sin_clienteId_devuelve_400_y_no_invoca_el_caso_de_uso() throws Exception {
            mockMvc.perform(post("/api/pedidos")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

            then(crearPedido).shouldHaveNoInteractions();
        }
    }

    @Nested
    class ConfirmarPedido {

        @Test
        void devuelve_204_y_traduce_la_ruta_a_comando() throws Exception {
            mockMvc.perform(post("/api/pedidos/{id}/confirmacion", ID))
                    .andExpect(status().isNoContent());

            then(confirmarPedido).should().ejecutar(new ConfirmarPedidoCommand(new PedidoId(ID)));
        }

        @Test
        void pedido_inexistente_devuelve_404_problem_detail() throws Exception {
            willThrow(new PedidoNoEncontradoException(new PedidoId(ID)))
                    .given(confirmarPedido).ejecutar(any());

            mockMvc.perform(post("/api/pedidos/{id}/confirmacion", ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.title").exists());
        }

        @Test
        void transicion_invalida_devuelve_409() throws Exception {
            willThrow(new TransicionDeEstadoInvalidaException("Solo se confirma un pedido en BORRADOR"))
                    .given(confirmarPedido).ejecutar(any());

            mockMvc.perform(post("/api/pedidos/{id}/confirmacion", ID))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("Solo se confirma un pedido en BORRADOR"));
        }

        @Test
        void regla_de_negocio_violada_devuelve_422() throws Exception {
            willThrow(new PedidoException("No se puede confirmar un pedido vacío"))
                    .given(confirmarPedido).ejecutar(any());

            mockMvc.perform(post("/api/pedidos/{id}/confirmacion", ID))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void un_id_que_no_es_uuid_devuelve_400() throws Exception {
            mockMvc.perform(post("/api/pedidos/{id}/confirmacion", "no-soy-un-uuid"))
                    .andExpect(status().isBadRequest());

            then(confirmarPedido).shouldHaveNoInteractions();
        }
    }

    @Nested
    class ConsultarPedido {

        @Test
        void serializa_el_pedido_con_el_dinero_como_texto() throws Exception {
            Pedido pedido = PedidoMother.borradorConLineas();   // total 119.70 EUR
            given(consultarPedido.consultar(new PedidoId(ID))).willReturn(pedido);

            mockMvc.perform(get("/api/pedidos/{id}", ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.estado").value("BORRADOR"))
                    .andExpect(jsonPath("$.total").value("119.70"))
                    .andExpect(jsonPath("$.moneda").value("EUR"));
        }
    }
}
```

¿Qué está probando **de verdad** este test?

- **Routing**: verbos, rutas y `@PathVariable`.
- **Deserialización** del JSON a `CrearPedidoRequest` y **validación** (`@Valid`).
- **Traducción** request → comando del puerto de entrada (con `record` el `given(...)`/`should()` compara por valor).
- **Mapeo de errores** del `@RestControllerAdvice` (que `@WebMvcTest` carga automáticamente).
- **Serialización** de la respuesta: nombres de campos y formato del dinero.

Y **no** prueba lógica de negocio: esa ya está cubierta en dominio y aplicación.

### 4.3 `@MockBean` (3.3) vs `@MockitoBean` (3.4+)

| Spring Boot | Anotación                                                                    | Paquete                                                     |
|-------------|------------------------------------------------------------------------------|-------------------------------------------------------------|
| ≤ 3.3       | `@MockBean`, `@SpyBean`                                                      | `org.springframework.boot.test.mock.mockito`                |
| 3.4+        | `@MockitoBean`, `@MockitoSpyBean` (Spring Framework 6.2). `@MockBean` queda *deprecated* y se elimina en Boot 4 | `org.springframework.test.context.bean.override.mockito` |

La migración es mecánica: cambiar import y nombre de la anotación. En este curso (Spring Boot 3.3) usamos `@MockBean`.

### 4.4 Variante sociable: controlador + caso de uso real + fake

Si quieres probar el corte vertical "HTTP → caso de uso → dominio" sin BD, importa una configuración de test que cablee el servicio real con el fake:

```java
@WebMvcTest(PedidoController.class)
@Import(PedidoControllerSociableTest.Config.class)
class PedidoControllerSociableTest {

    @TestConfiguration
    static class Config {
        @Bean InMemoryPedidoRepository pedidoRepository() { return new InMemoryPedidoRepository(); }
        @Bean ConfirmarPedidoUseCase confirmarPedido(InMemoryPedidoRepository repo) {
            return new ConfirmarPedidoService(repo, evento -> { }, new NotificadorNoOp(),
                    Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC));
        }
        // ... resto de casos de uso que necesite el controller
    }

    @Autowired MockMvc mockMvc;
    @Autowired InMemoryPedidoRepository repositorio;

    @Test
    void confirmar_un_pedido_vacio_devuelve_422() throws Exception {
        Pedido vacio = PedidoMother.borradorVacio();
        repositorio.guardar(vacio);

        mockMvc.perform(post("/api/pedidos/{id}/confirmacion", vacio.id().valor()))
                .andExpect(status().isUnprocessableEntity());
    }
}
```

> Útil para unos pocos tests "de aceptación rápida". No sustituye a los tests con `@MockBean`, que aíslan mejor los fallos del adaptador.

---

## 5. Adaptadores de salida: `@DataJpaTest` + Testcontainers

### 5.1 ¿Por qué no H2?

| Aspecto                         | H2 (modo compatibilidad)          | PostgreSQL en Testcontainers        |
|---------------------------------|-----------------------------------|-------------------------------------|
| Tipos (`jsonb`, `uuid`, `timestamptz`) | Parcial o emulado          | Reales                              |
| Migraciones Flyway de producción| A menudo hay que duplicarlas      | Las mismas                          |
| Precisión de `timestamp`        | Distinta                          | La de producción (microsegundos)    |
| `ON CONFLICT`, secuencias, índices parciales | Diferencias sutiles  | Reales                              |
| Velocidad                       | Muy rápida                        | 2-5 s de arranque (una vez)         |
| Confianza                       | "Funciona en H2"                  | "Funciona en PostgreSQL"            |

### 5.2 Testcontainers en 30 segundos

[Testcontainers](https://java.testcontainers.org/) arranca contenedores Docker **desde el test**, con puertos aleatorios, y los destruye al terminar (con la ayuda del contenedor *Ryuk*).

- `@Testcontainers` (extensión JUnit 5): gestiona el ciclo de vida de los campos `@Container`.
- `@Container` en campo **`static`**: un contenedor por clase de test (se arranca antes del primer test y se para al final). En campo de instancia: ¡uno por test! (lentísimo).
- `@ServiceConnection` (**Spring Boot 3.1+**): Spring Boot lee host, puerto, usuario y contraseña del contenedor y crea los `ConnectionDetails` (`JdbcConnectionDetails`). **Adiós a `@DynamicPropertySource`.**

```java
// ANTES de Spring Boot 3.1
@DynamicPropertySource
static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
}

// DESDE Spring Boot 3.1
@Container
@ServiceConnection
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
```

### 5.3 Test específico del adaptador (además del contrato)

El test de contrato del bloque 3 ya ejecuta la batería del puerto contra `PedidoPersistenceAdapter`. Aquí añadimos tests **propios de la tecnología**: esquema, mapeo y consultas.

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.Pedido;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.curso.pedidos.domain.model.PedidoBuilder.unPedido;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")   // Flyway crea, Hibernate valida
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PedidoPersistenceAdapter.class, PedidoPersistenceMapper.class})
class PedidoJpaMappingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PedidoPersistenceAdapter adapter;
    @Autowired TestEntityManager entityManager;
    @Autowired JdbcClient jdbcClient;

    @Test
    void el_esquema_de_flyway_coincide_con_las_entidades() {
        // Si el contexto arranca con ddl-auto=validate, entidades y migraciones están alineadas.
        assertThat(adapter).isNotNull();
    }

    @Test
    void persiste_el_estado_como_texto_y_las_lineas_en_su_tabla() {
        Pedido pedido = unPedido().conIdAleatorio()
                .enEstado(EstadoPedido.CONFIRMADO)
                .conLinea("SKU-A", 2, "10.00")
                .conLinea("SKU-B", 1, "5.50")
                .build();

        adapter.guardar(pedido);
        entityManager.flush();

        String estado = jdbcClient.sql("select estado from pedidos where id = :id")
                .param("id", pedido.id().valor())
                .query(String.class).single();
        Integer lineas = jdbcClient.sql("select count(*) from lineas_pedido where pedido_id = :id")
                .param("id", pedido.id().valor())
                .query(Integer.class).single();

        assertThat(estado).isEqualTo("CONFIRMADO");     // @Enumerated(STRING), no ORDINAL
        assertThat(lineas).isEqualTo(2);
    }
}
```

> **Trampa clásica que este test caza:** `@Enumerated` sin `EnumType.STRING` guarda el **ordinal**. Si mañana alguien inserta `EN_PREPARACION` entre `PAGADO` y `ENVIADO`, todos los pedidos enviados de la BD pasan a "en preparación". Silenciosamente.

> `@DataJpaTest` no carga tus `@Component`: por eso el `@Import` del adaptador y su mapper. Si el mapper es de **MapStruct** (`componentModel = "spring"`), impórtalo igual (`PedidoPersistenceMapperImpl.class`).

### 5.4 Reutilizar el contenedor: `@TestConfiguration` con `@ServiceConnection`

Repetir el campo `@Container` en cada clase tiene dos problemas: arranca un PostgreSQL **por clase** y, al ser contenedores distintos, la **caché de contextos de Spring** no puede reutilizar contextos. Desde Spring Boot 3.1, el contenedor puede ser un **bean**:

```java
package com.curso.pedidos.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
```

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestcontainersConfig.class, PedidoPersistenceAdapter.class, PedidoPersistenceMapper.class})
class PedidoPersistenceAdapterIT extends PedidoRepositoryContractTest { ... }
```

Spring gestiona el ciclo de vida del contenedor junto al del contexto: mientras el contexto esté en caché, el contenedor sigue vivo y se reutiliza entre clases.

> Bonus: con esa misma configuración puedes arrancar la app en local con PostgreSQL en Docker sin instalar nada: una clase `TestPedidosApplication` en `src/test/java` con `SpringApplication.from(PedidosApplication::main).with(PostgresTestcontainersConfig.class).run(args)` (Spring Boot 3.1+).

---

## 6. Clientes HTTP: WireMock

### 6.1 El adaptador

```java
package com.curso.pedidos.infrastructure.adapter.out.payment;

public class StripeLikePasarelaAdapter implements PasarelaDePago {

    private final RestClient restClient;

    public StripeLikePasarelaAdapter(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ResultadoPago cobrar(PedidoId pedidoId, Dinero importe) {
        var peticion = new CargoRequest(
                importe.importe().movePointRight(2).longValueExact(),   // céntimos, como Stripe
                importe.moneda().getCurrencyCode().toLowerCase(),
                pedidoId.valor().toString());
        try {
            CargoResponse respuesta = restClient.post()
                    .uri("/v1/charges")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(peticion)
                    .retrieve()
                    .body(CargoResponse.class);
            return new ResultadoPago.Aprobado(respuesta.id());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 402) {
                return new ResultadoPago.Rechazado("Pago rechazado por la pasarela");
            }
            throw new PasarelaDePagoNoDisponibleException("Error " + e.getStatusCode(), e);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new PasarelaDePagoNoDisponibleException("Pasarela no disponible", e);
        }
    }

    record CargoRequest(long amount, String currency, String reference) { }
    record CargoResponse(String id, String status) { }
}
```

`ResultadoPago` es una `sealed interface` del puerto con `Aprobado(String referencia)` y `Rechazado(String motivo)`.

### 6.2 El test con WireMock (sin Spring)

```java
package com.curso.pedidos.infrastructure.adapter.out.payment;

import com.curso.pedidos.application.port.out.PasarelaDePagoNoDisponibleException;
import com.curso.pedidos.application.port.out.ResultadoPago;
import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.PedidoId;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeLikePasarelaAdapterIT {

    @RegisterExtension
    static WireMockExtension pasarela = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final PedidoId PEDIDO =
            new PedidoId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    private StripeLikePasarelaAdapter adapter;

    @BeforeEach
    void setUp() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(500));
        requestFactory.setReadTimeout(Duration.ofMillis(500));

        RestClient restClient = RestClient.builder()
                .baseUrl(pasarela.baseUrl())
                .requestFactory(requestFactory)
                .build();
        adapter = new StripeLikePasarelaAdapter(restClient);
    }

    @Test
    void un_cobro_aceptado_devuelve_la_referencia_y_envia_el_importe_en_centimos() {
        pasarela.stubFor(post("/v1/charges")
                .willReturn(okJson("""
                        { "id": "ch_123", "status": "succeeded" }
                        """)));

        ResultadoPago resultado = adapter.cobrar(PEDIDO, Dinero.euros("99.80"));

        assertThat(resultado).isEqualTo(new ResultadoPago.Aprobado("ch_123"));
        pasarela.verify(postRequestedFor(urlEqualTo("/v1/charges"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        { "amount": 9980, "currency": "eur",
                          "reference": "00000000-0000-0000-0000-000000000001" }
                        """)));
    }

    @Test
    void un_402_se_traduce_a_pago_rechazado() {
        pasarela.stubFor(post("/v1/charges")
                .willReturn(aResponse().withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ \"error\": { \"code\": \"card_declined\" } }")));

        ResultadoPago resultado = adapter.cobrar(PEDIDO, Dinero.euros("10.00"));

        assertThat(resultado).isInstanceOf(ResultadoPago.Rechazado.class);
    }

    @Test
    void un_500_se_traduce_a_pasarela_no_disponible() {
        pasarela.stubFor(post("/v1/charges").willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> adapter.cobrar(PEDIDO, Dinero.euros("10.00")))
                .isInstanceOf(PasarelaDePagoNoDisponibleException.class);
    }

    @Test
    void un_timeout_se_traduce_a_pasarela_no_disponible() {
        pasarela.stubFor(post("/v1/charges")
                .willReturn(okJson("{ \"id\": \"ch_lento\", \"status\": \"succeeded\" }")
                        .withFixedDelay(2_000)));

        assertThatThrownBy(() -> adapter.cobrar(PEDIDO, Dinero.euros("10.00")))
                .isInstanceOf(PasarelaDePagoNoDisponibleException.class);
    }
}
```

Lo que este test protege y un mock de `RestClient` nunca protegería:

- El **JSON exacto** que enviamos (nombres de campo, céntimos, moneda en minúsculas).
- La **traducción de códigos HTTP** a conceptos del puerto (`Aprobado`, `Rechazado`, excepción).
- El comportamiento ante **timeouts** reales de red.

> `equalToJson` compara JSON semánticamente (ignora espacios y orden de campos). Si quieres tolerar campos extra, usa `equalToJson(json, true, true)` (ignorar orden de arrays y campos extra).

> Alternativa sin WireMock: `@RestClientTest` + `MockRestServiceServer`. Funciona bien, pero se engancha al `RestClient.Builder` de Spring y no prueba la capa HTTP real (timeouts, cabeceras a nivel de socket). Para adaptadores de salida HTTP preferimos WireMock.

---

## 7. Mensajería: Kafka con Testcontainers (mención)

El adaptador `KafkaPublicadorDeEventos` se prueba igual que la BD: contenedor real + `@ServiceConnection` (Spring Boot 3.1+ soporta `KafkaContainer`).

```java
@SpringBootTest(classes = { KafkaPublicadorDeEventos.class, KafkaAutoConfiguration.class })
@Testcontainers
class KafkaPublicadorDeEventosIT {

    @Container
    @ServiceConnection
    static KafkaContainer kafka =                       // org.testcontainers.containers.KafkaContainer
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Autowired KafkaPublicadorDeEventos publicador;

    @Test
    void publica_PedidoConfirmado_en_el_topic_pedidos() {
        // 1. publicador.publicar(new PedidoConfirmado(...))
        // 2. Consumir con un KafkaConsumer de test (o KafkaTestUtils.getSingleRecord)
        // 3. Afirmar clave = pedidoId y payload JSON
        // Para esperas asíncronas: Awaitility -> await().atMost(5, SECONDS).untilAsserted(...)
    }
}
```

> Dependencias: `org.testcontainers:kafka` (versión gestionada por Spring Boot) y, para esperas asíncronas, `org.awaitility:awaitility` (también gestionada). **Nunca `Thread.sleep` en un test asíncrono.**

---

## 8. Trampas y errores comunes

- **Trampa: `@SpringBootTest` para probar un controller.** Carga todo (BD incluida). `@WebMvcTest` tarda una fracción y aísla el fallo.
- **Trampa: olvidar mockear un caso de uso en `@WebMvcTest`.** El contexto no arranca: `NoSuchBeanDefinitionException`. Hay que dar un `@MockBean` por **cada** dependencia del controller.
- **Trampa: `@Container` no estático.** Un contenedor por método de test. Minutos perdidos.
- **Trampa: confiar en el `@Transactional` de `@DataJpaTest` sin `flush()`.** Los `INSERT` pueden no ejecutarse nunca (rollback antes del flush) y las violaciones de constraint no aparecen.
- **Trampa: imagen `postgres:latest`.** Fija la versión (la misma que producción). `latest` hace los tests no reproducibles.
- **Trampa: Docker no disponible en CI.** Testcontainers necesita un Docker accesible (o Testcontainers Cloud). Por eso los `*IT` van en Failsafe y se pueden saltar con `-DskipITs` en un apuro.

---

## 9. Tabla resumen

| Adaptador                     | Test                         | Real                               | Doble                     | Sufijo  |
|-------------------------------|------------------------------|------------------------------------|---------------------------|---------|
| `PedidoController`            | `@WebMvcTest` + MockMvc      | MVC, Jackson, Validation, Advice   | Casos de uso (`@MockBean`)| `*Test` |
| `PedidoPersistenceAdapter`    | `@DataJpaTest` + Testcontainers | JPA, Flyway, PostgreSQL         | Nada                      | `*IT`   |
| `StripeLikePasarelaAdapter`   | JUnit + WireMock             | `RestClient`, HTTP, JSON           | Servidor de la pasarela   | `*IT`   |
| `KafkaPublicadorDeEventos`    | Slice mínimo + Testcontainers Kafka | Kafka                       | Nada                      | `*IT`   |
| DTOs / JSON                   | `@JsonTest`                  | Jackson                            | Nada                      | `*Test` |

---

## Ejercicio

1. Añade a `PedidoControllerTest` el endpoint `POST /api/pedidos/{id}/cancelacion` (204; 404 si no existe; 409 si está enviado).
2. Escribe un test WireMock que compruebe que el adaptador de pago envía la cabecera `Idempotency-Key` con el id del pedido.

**Pista:** para el 2, `.withHeader("Idempotency-Key", equalTo(PEDIDO.valor().toString()))` dentro del `verify`.

<details><summary>Solución</summary>

```java
@Nested
class CancelarPedido {

    @Test
    void devuelve_204() throws Exception {
        mockMvc.perform(post("/api/pedidos/{id}/cancelacion", ID))
                .andExpect(status().isNoContent());

        then(cancelarPedido).should().ejecutar(new CancelarPedidoCommand(new PedidoId(ID)));
    }

    @Test
    void pedido_enviado_devuelve_409() throws Exception {
        willThrow(new TransicionDeEstadoInvalidaException("No se puede cancelar un pedido ENVIADO"))
                .given(cancelarPedido).ejecutar(any());

        mockMvc.perform(post("/api/pedidos/{id}/cancelacion", ID))
                .andExpect(status().isConflict());
    }

    @Test
    void pedido_inexistente_devuelve_404() throws Exception {
        willThrow(new PedidoNoEncontradoException(new PedidoId(ID)))
                .given(cancelarPedido).ejecutar(any());

        mockMvc.perform(post("/api/pedidos/{id}/cancelacion", ID))
                .andExpect(status().isNotFound());
    }
}
```

```java
// Producción: añadir la cabecera
restClient.post().uri("/v1/charges")
        .header("Idempotency-Key", pedidoId.valor().toString())
        ...

// Test
@Test
void envia_la_cabecera_de_idempotencia() {
    pasarela.stubFor(post("/v1/charges").willReturn(okJson("{ \"id\": \"ch_1\", \"status\": \"succeeded\" }")));

    adapter.cobrar(PEDIDO, Dinero.euros("1.00"));

    pasarela.verify(postRequestedFor(urlEqualTo("/v1/charges"))
            .withHeader("Idempotency-Key", equalTo(PEDIDO.valor().toString())));
}
```

</details>

---

## Referencias

- Spring Boot, Testing (slices, `@ServiceConnection`, Testcontainers): https://docs.spring.io/spring-boot/index.html (sección *Testing*)
- Spring Framework, Testing (MockMvc, `@MockitoBean`): https://docs.spring.io/spring-framework/reference/testing.html
- Spring Data JPA: https://docs.spring.io/spring-data/jpa/reference/
- Testcontainers for Java: https://java.testcontainers.org/
- WireMock docs: https://wiremock.org/docs/
- Mockito: https://site.mockito.org/
- Freeman & Pryce, *Growing Object-Oriented Software, Guided by Tests* (2009).
- Ham Vocke, *The Practical Test Pyramid* — secciones *Integration Tests* y *Contract Tests*: https://martinfowler.com/articles/practical-test-pyramid.html

---

## Mantra

> *"El adaptador se prueba contra la tecnología real; el puerto, contra un doble. Nunca al revés."*
