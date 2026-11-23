# Día 4 — Bloque 7: Ejercicios y repaso

> *"I hear and I forget. I see and I remember. I do and I understand."*
> — Proverbio atribuido a Confucio

Cuatro ejercicios graduados (de ★ a ★★★★). Los dos primeros son el núcleo del
día; el 3 y el 4 son para quien vaya rápido o para casa. Cada uno incluye su
**test**: un adaptador sin test no está terminado.

| # | Ejercicio                                               | Dificultad | Tiempo  | Practica                                |
|---|---------------------------------------------------------|------------|---------|-----------------------------------------|
| 1 | `CatalogoProductos` sobre un API REST externo           | ★★         | ~25 min | `RestClient`, traducción de errores, WireMock |
| 2 | Adaptador de persistencia alternativo con `JdbcClient`  | ★★★        | ~35 min | Segundo adaptador del mismo puerto, perfiles |
| 3 | Fake de `PasarelaDePago` para el perfil `local`         | ★★         | ~15 min | Fakes, `@ConditionalOnProperty`, `ApplicationContextRunner` |
| 4 | Blindar los nuevos adaptadores con ArchUnit             | ★★★★       | ~15 min | ArchUnit, reglas propias                |

---

## Ejercicio 1 — `CatalogoProductos` sobre un API REST (★★)

### Enunciado

El catálogo de productos lo gestiona otro equipo y lo expone así:

```http
GET /api/v2/products/{sku}
200 OK
{
  "sku": "SKU-1",
  "title": "Teclado mecánico",
  "price": { "amount": "79.90", "currency": "EUR" },
  "availableUnits": 12
}

404 Not Found        → el producto no existe
503 / timeout        → el catálogo está caído
```

El puerto ya existe:

```java
package com.curso.pedidos.application.port.out;

public interface CatalogoProductos {
    Optional<Producto> buscarPorId(ProductoId id);
}
```

Implementa `CatalogoProductosHttpAdapter` en
`com.curso.pedidos.infrastructure.adapter.out.catalog` con `RestClient`:

1. 200 → `Optional.of(Producto)` (traduce `title` → nombre,
   `availableUnits` → stock, `price` → `Dinero`).
2. 404 → `Optional.empty()` (no es un error: es "no existe").
3. 5xx o timeout → `CatalogoNoDisponibleException` (excepción del puerto).
4. Tests con WireMock para los tres casos.

**Pista:** el JSON del otro equipo es **su** modelo: records package-private
en el adaptador. `exchange(...)` te permite decidir por código de estado.

<details><summary>Solución</summary>

```java
package com.curso.pedidos.application.port.out;

public class CatalogoNoDisponibleException extends RuntimeException {
    public CatalogoNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
```

```java
package com.curso.pedidos.infrastructure.adapter.out.catalog;

import java.math.BigDecimal;

record ProductoResponse(String sku, String title, Precio price, int availableUnits) {
    record Precio(BigDecimal amount, String currency) {}
}
```

```java
package com.curso.pedidos.infrastructure.adapter.out.catalog;

import com.curso.pedidos.application.port.out.CatalogoNoDisponibleException;
import com.curso.pedidos.application.port.out.CatalogoProductos;
import com.curso.pedidos.domain.model.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Currency;
import java.util.Optional;

public class CatalogoProductosHttpAdapter implements CatalogoProductos {

    private final RestClient restClient;

    public CatalogoProductosHttpAdapter(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<Producto> buscarPorId(ProductoId id) {
        try {
            return restClient.get()
                    .uri("/api/v2/products/{sku}", id.sku())
                    .exchange((req, res) -> {
                        if (res.getStatusCode().is2xxSuccessful()) {
                            return Optional.of(aDominio(res.bodyTo(ProductoResponse.class)));
                        }
                        if (res.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                            return Optional.<Producto>empty();
                        }
                        throw new CatalogoNoDisponibleException(
                                "Catálogo respondió HTTP " + res.getStatusCode().value(), null);
                    });
        } catch (ResourceAccessException e) {
            throw new CatalogoNoDisponibleException("Catálogo sin respuesta", e);
        }
    }

    private static Producto aDominio(ProductoResponse r) {
        return new Producto(
                new ProductoId(r.sku()),
                r.title(),
                new Dinero(r.price().amount(), Currency.getInstance(r.price().currency())),
                r.availableUnits());
    }
}
```

```java
package com.curso.pedidos.infrastructure.adapter.out.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;

class CatalogoProductosHttpAdapterTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private CatalogoProductosHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(300));
        adapter = new CatalogoProductosHttpAdapter(RestClient.builder()
                .baseUrl(wm.baseUrl()).requestFactory(factory).build());
    }

    @Test
    void traduce_el_producto_al_modelo_de_dominio() {
        wm.stubFor(get("/api/v2/products/SKU-1").willReturn(okJson("""
                {"sku":"SKU-1","title":"Teclado mecánico",
                 "price":{"amount":"79.90","currency":"EUR"},"availableUnits":12}
                """)));

        Producto p = adapter.buscarPorId(new ProductoId("SKU-1")).orElseThrow();

        assertThat(p.nombre()).isEqualTo("Teclado mecánico");
        assertThat(p.precio()).isEqualTo(
                new Dinero(new BigDecimal("79.90"), Currency.getInstance("EUR")));
        assertThat(p.stock()).isEqualTo(12);
    }

    @Test
    void un_404_es_producto_inexistente() {
        wm.stubFor(get(urlPathMatching("/api/v2/products/.*")).willReturn(notFound()));

        assertThat(adapter.buscarPorId(new ProductoId("NO-EXISTE"))).isEmpty();
    }

    @Test
    void un_503_es_catalogo_no_disponible() {
        wm.stubFor(get(urlPathMatching("/api/v2/products/.*"))
                .willReturn(serviceUnavailable()));

        assertThatThrownBy(() -> adapter.buscarPorId(new ProductoId("SKU-1")))
                .isInstanceOf(CatalogoNoDisponibleException.class);
    }

    @Test
    void un_timeout_es_catalogo_no_disponible() {
        wm.stubFor(get(urlPathMatching("/api/v2/products/.*"))
                .willReturn(okJson("{}").withFixedDelay(1_000)));

        assertThatThrownBy(() -> adapter.buscarPorId(new ProductoId("SKU-1")))
                .isInstanceOf(CatalogoNoDisponibleException.class)
                .hasCauseInstanceOf(ResourceAccessException.class);
    }
}
```

Puntos a comentar en clase:

- `Optional.<Producto>empty()` con el tipo explícito: dentro de la lambda el
  compilador necesita ayuda para inferir `Optional<Producto>`.
- **404 ≠ error**: el caso de uso decidirá si "producto inexistente" es un
  `ProductoNoEncontradoException` o una línea que se ignora. El adaptador no
  decide política, solo traduce.
- Si el catálogo se consulta mucho, un **decorador con caché**
  (`CatalogoProductosCacheado implements CatalogoProductos`) se añade sin
  tocar ni el adaptador ni el caso de uso.

</details>

---

## Ejercicio 2 — Persistencia alternativa con `JdbcClient` (★★★)

### Enunciado

El equipo de plataforma quiere evaluar quitar JPA. Implementa
`PedidoJdbcAdapter implements PedidoRepository` en
`com.curso.pedidos.infrastructure.adapter.out.persistence.jdbc` usando
**`JdbcClient`** (Spring 6.1) sobre **las mismas tablas** del
[bloque 2](02-adaptador-de-persistencia-jpa.md).

1. `guardar`: actualiza el pedido si existe, si no lo inserta; reemplaza
   las líneas.
2. `buscarPorId`: carga pedido + líneas y reconstituye el agregado.
3. Se elige con `pedidos.persistencia=jdbc` (por defecto, `jpa`).
4. Test con `@JdbcTest` y H2.
5. **Bonus:** el **mismo** test de comportamiento debe pasar contra el
   adaptador JPA y el JDBC.

**Pista:** `jdbcClient.sql("...").param("id", uuid).update()` devuelve el
número de filas afectadas: si es 0, inserta. Para leer,
`.query((rs, n) -> ...).optional()` o `.list()`.

<details><summary>Solución</summary>

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence.jdbc;

import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.model.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PedidoJdbcAdapter implements PedidoRepository {

    private final JdbcClient jdbc;

    public PedidoJdbcAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Pedido guardar(Pedido pedido) {
        UUID id = pedido.id().valor();
        String moneda = pedido.total().moneda().getCurrencyCode();

        int actualizadas = jdbc.sql("""
                UPDATE pedidos SET estado = :estado, version = version + 1
                WHERE id = :id
                """)
                .param("estado", pedido.estado().name())
                .param("id", id)
                .update();

        if (actualizadas == 0) {
            jdbc.sql("""
                    INSERT INTO pedidos (id, cliente_id, estado, moneda, creado_en, version)
                    VALUES (:id, :clienteId, :estado, :moneda, :creadoEn, 0)
                    """)
                    .param("id", id)
                    .param("clienteId", pedido.clienteId().valor())
                    .param("estado", pedido.estado().name())
                    .param("moneda", moneda)
                    .param("creadoEn", Timestamp.from(pedido.creadoEn()))
                    .update();
        }

        jdbc.sql("DELETE FROM lineas_pedido WHERE pedido_id = :id").param("id", id).update();
        for (LineaPedido l : pedido.lineas()) {
            jdbc.sql("""
                    INSERT INTO lineas_pedido (pedido_id, producto_sku, cantidad, precio_unitario)
                    VALUES (:pedidoId, :sku, :cantidad, :precio)
                    """)
                    .param("pedidoId", id)
                    .param("sku", l.productoId().sku())
                    .param("cantidad", l.cantidad().valor())
                    .param("precio", l.precioUnitario().importe())
                    .update();
        }
        return pedido;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Pedido> buscarPorId(PedidoId pedidoId) {
        UUID id = pedidoId.valor();
        return jdbc.sql("SELECT id, cliente_id, estado, moneda, creado_en FROM pedidos WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new FilaPedido(
                        rs.getObject("id", UUID.class),
                        rs.getObject("cliente_id", UUID.class),
                        rs.getString("estado"),
                        Currency.getInstance(rs.getString("moneda")),
                        rs.getTimestamp("creado_en").toInstant()))
                .optional()
                .map(fila -> Pedido.reconstituir(
                        new PedidoId(fila.id()),
                        new ClienteId(fila.clienteId()),
                        cargarLineas(id, fila.moneda()),
                        EstadoPedido.valueOf(fila.estado()),
                        fila.creadoEn()));
    }

    private List<LineaPedido> cargarLineas(UUID pedidoId, Currency moneda) {
        return jdbc.sql("""
                SELECT producto_sku, cantidad, precio_unitario
                FROM lineas_pedido WHERE pedido_id = :id ORDER BY id
                """)
                .param("id", pedidoId)
                .query((rs, n) -> new LineaPedido(
                        new ProductoId(rs.getString("producto_sku")),
                        new Cantidad(rs.getInt("cantidad")),
                        new Dinero(rs.getBigDecimal("precio_unitario"), moneda)))
                .list();
    }

    private record FilaPedido(UUID id, UUID clienteId, String estado,
                              Currency moneda, java.time.Instant creadoEn) {}
}
```

Cableado intercambiable (en el mismo paquete que el adaptador JPA, ver
[bloque 5](05-gestion-de-dependencias.md#7-intercambiar-adaptadores-profile-vs-conditionalonproperty)):

```java
@Bean
@ConditionalOnProperty(name = "pedidos.persistencia", havingValue = "jdbc")
PedidoRepository pedidoRepositoryJdbc(JdbcClient jdbcClient) {
    return new PedidoJdbcAdapter(jdbcClient);
}
```

Spring Boot 3.2+ autoconfigura un bean `JdbcClient` cuando hay `DataSource`.

**Bonus — test de contrato del puerto** (lo generalizaremos en el día 5):

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

/** Comportamiento que TODO adaptador de PedidoRepository debe cumplir. */
public abstract class PedidoRepositoryContractTest {

    protected abstract PedidoRepository repositorio();

    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void guarda_y_recupera_con_sus_lineas() {
        Pedido pedido = Pedido.crear(new ClienteId(UUID.randomUUID()));
        pedido.anadirLinea(new ProductoId("SKU-1"), new Cantidad(3),
                new Dinero(new BigDecimal("2.50"), EUR));

        repositorio().guardar(pedido);

        Pedido leido = repositorio().buscarPorId(pedido.id()).orElseThrow();
        assertThat(leido.lineas()).hasSize(1);
        assertThat(leido.total()).isEqualTo(new Dinero(new BigDecimal("7.50"), EUR));
    }

    @Test
    void guardar_dos_veces_actualiza_el_estado() {
        Pedido pedido = Pedido.crear(new ClienteId(UUID.randomUUID()));
        pedido.anadirLinea(new ProductoId("SKU-1"), new Cantidad(1),
                new Dinero(new BigDecimal("1.00"), EUR));
        repositorio().guardar(pedido);

        pedido.confirmar();
        repositorio().guardar(pedido);

        assertThat(repositorio().buscarPorId(pedido.id()))
                .get().extracting(Pedido::estado).isEqualTo(EstadoPedido.CONFIRMADO);
    }

    @Test
    void id_inexistente_devuelve_vacio() {
        assertThat(repositorio().buscarPorId(new PedidoId(UUID.randomUUID()))).isEmpty();
    }
}
```

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence.jdbc;

@JdbcTest(properties = "spring.datasource.url=jdbc:h2:mem:pedidos;MODE=PostgreSQL")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PedidoJdbcAdapterTest extends PedidoRepositoryContractTest {

    @Autowired DataSource dataSource;
    private PedidoJdbcAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PedidoJdbcAdapter(JdbcClient.create(dataSource));
    }

    @Override
    protected PedidoRepository repositorio() { return adapter; }
}
```

Y `PedidoPersistenceAdapterTest` (JPA) también puede extender
`PedidoRepositoryContractTest`. Si ambos pasan, los adaptadores son
**intercambiables** de verdad: eso es el principio de sustitución de Liskov
aplicado a la arquitectura.

> `@JdbcTest` ejecuta Flyway, así que el esquema es el mismo que en
> producción. Igual que en el bloque 2: `replace = NONE` para respetar
> nuestra URL de H2 en modo PostgreSQL.

</details>

---

## Ejercicio 3 — Fake de `PasarelaDePago` para el perfil local (★★)

### Enunciado

En local nadie tiene credenciales de la pasarela. Crea
`PasarelaDePagoFake implements PasarelaDePago` que:

- Aprueba cualquier cobro de hasta 1000 € con referencia `"FAKE-" + pedidoId`.
- Rechaza con motivo `"importe_excesivo"` los cobros mayores de 1000 €
  (útil para probar el camino de rechazo a mano).
- Se activa con `pedidos.pasarela=fake`; con `pedidos.pasarela=http` (o
  ausente) se usa `StripeLikePasarelaAdapter`.

Prueba el fake con un test unitario y el cableado con
`ApplicationContextRunner`.

<details><summary>Solución</summary>

```java
package com.curso.pedidos.infrastructure.adapter.out.payment;

public class PasarelaDePagoFake implements PasarelaDePago {

    private static final BigDecimal LIMITE = new BigDecimal("1000");

    @Override
    public ResultadoPago cobrar(PedidoId pedidoId, Dinero importe) {
        return importe.importe().compareTo(LIMITE) > 0
                ? new ResultadoPago.Rechazado("importe_excesivo")
                : new ResultadoPago.Aprobado("FAKE-" + pedidoId.valor());
    }
}
```

```java
@Configuration
class PasarelaConfiguration {

    @Bean
    @ConditionalOnProperty(name = "pedidos.pasarela", havingValue = "fake")
    PasarelaDePago pasarelaFake() {
        return new PasarelaDePagoFake();
    }

    @Bean
    @ConditionalOnProperty(name = "pedidos.pasarela", havingValue = "http",
                           matchIfMissing = true)
    PasarelaDePago pasarelaHttp(RestClient.Builder builder, PasarelaProperties props) {
        // igual que en el bloque 3 (timeouts, baseUrl, Authorization...)
        return new StripeLikePasarelaAdapter(builder.baseUrl(props.baseUrl()).build());
    }
}
```

```java
class PasarelaDePagoFakeTest {

    private final PasarelaDePagoFake fake = new PasarelaDePagoFake();
    private final PedidoId id = new PedidoId(UUID.randomUUID());
    private final Currency eur = Currency.getInstance("EUR");

    @Test
    void aprueba_hasta_el_limite() {
        assertThat(fake.cobrar(id, new Dinero(new BigDecimal("1000.00"), eur)))
                .isInstanceOf(ResultadoPago.Aprobado.class);
    }

    @Test
    void rechaza_por_encima_del_limite() {
        assertThat(fake.cobrar(id, new Dinero(new BigDecimal("1000.01"), eur)))
                .isEqualTo(new ResultadoPago.Rechazado("importe_excesivo"));
    }
}

class PasarelaConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PasarelaConfiguration.class);

    @Test
    void con_fake_se_registra_el_fake() {
        runner.withPropertyValues("pedidos.pasarela=fake")
              .run(ctx -> assertThat(ctx).getBean(PasarelaDePago.class)
                      .isInstanceOf(PasarelaDePagoFake.class));
    }
}
```

> El fake vive en `src/main` porque se usa en un perfil de ejecución, no solo
> en tests. Trampa: que llegue a producción por error. Mitigación:
> `matchIfMissing = true` en el adaptador real (por defecto, lo real) y un
> test de cableado del perfil `prod`.

</details>

---

## Ejercicio 4 — Blindar los nuevos adaptadores con ArchUnit (★★★★)

### Enunciado

Tras los ejercicios anteriores hay dos paquetes nuevos (`out.catalog` y
`out.persistence.jdbc`). Escribe reglas ArchUnit que garanticen:

1. Ninguna clase de `infrastructure.adapter.out..` depende de otra de
   `infrastructure.adapter.out..` **de otro adaptador** (el catálogo no usa
   la pasarela, la pasarela no usa la persistencia…). Ojo: `jdbc` es un
   subpaquete de `persistence`.
2. Solo las clases de `infrastructure.adapter.out.persistence..` pueden usar
   `org.springframework.jdbc..` y `jakarta.persistence..`.
3. Solo las clases de `infrastructure.adapter.out.payment..` y
   `infrastructure.adapter.out.catalog..` pueden usar
   `org.springframework.web.client..`.

<details><summary>Solución</summary>

```java
@AnalyzeClasses(packages = "com.curso.pedidos",
                importOptions = ImportOption.DoNotIncludeTests.class)
class ReglasDeTecnologiaTest {

    // (*) captura solo el primer nivel: persistence y persistence.jdbc son el MISMO slice
    @ArchTest
    static final ArchRule adaptadores_independientes =
            slices().matching("com.curso.pedidos.infrastructure.adapter.out.(*)..")
                    .should().notDependOnEachOther();

    @ArchTest
    static final ArchRule acceso_a_datos_solo_en_persistencia =
            noClasses().that().resideOutsideOfPackage(
                            "com.curso.pedidos.infrastructure.adapter.out.persistence..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.jdbc..", "jakarta.persistence..")
                    .because("la tecnología de persistencia es un detalle del adaptador");

    @ArchTest
    static final ArchRule clientes_http_solo_en_adaptadores_http =
            noClasses().that().resideOutsideOfPackages(
                            "com.curso.pedidos.infrastructure.adapter.out.payment..",
                            "com.curso.pedidos.infrastructure.adapter.out.catalog..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework.web.client..")
                    .because("solo los adaptadores HTTP de salida hablan con APIs externas");
}
```

Discusión: la regla 2 fallará si `PersistenciaConfiguration` está en
`infrastructure.config` y recibe `JdbcClient` como parámetro. Es una buena
excusa para recordar por qué en el bloque 5 pusimos esa configuración
**dentro** del paquete del adaptador. Y la regla 3 fallará si
`infrastructure.config` construye `RestClient` (¡bien visto!): o mueves la
configuración HTTP a su adaptador (como `PasarelaHttpConfig`) o amplías la
regla de forma explícita.

</details>

---

## Preguntas de repaso

1. ¿Quién define el puerto de salida, el hexágono o la infraestructura? ¿Por
   qué importa?
2. ¿Qué es una *Anticorruption Layer* y qué relación tiene con un adaptador
   de salida?
3. Da tres razones para tener `PedidoJpaEntity` separada de `Pedido`. ¿Cuándo
   aceptarías un único modelo?
4. ¿Por qué `PedidoRepository extends JpaRepository<...>` rompe la
   arquitectura hexagonal?
5. En el adaptador de pagos, ¿por qué un HTTP 402 es un `ResultadoPago` y un
   HTTP 503 es una excepción?
6. ¿Qué condición debe cumplir una operación para poder reintentarla con
   seguridad? ¿Cómo lo conseguimos en `cobrar`?
7. ¿Qué diferencia hay entre un mock y un fake? ¿Cuándo prefieres cada uno?
8. `@Profile` vs `@ConditionalOnProperty`: ¿cuándo usarías cada uno?
9. Compara `@Bean` en `BeanConfiguration`, `@Service` y `@UseCase` + scan.
   ¿Qué perdemos y qué ganamos en cada una?
10. ¿Qué garantiza un proyecto Maven multi-módulo que no garantizan los
    paquetes? ¿Y qué garantiza ArchUnit que no garantizan los módulos?

<details><summary>Respuestas</summary>

1. **El hexágono.** Así la dependencia de código apunta hacia dentro (DIP):
   el puerto expresa lo que el negocio necesita, con sus tipos, y la
   tecnología se adapta a él. Si lo definiera la infraestructura, el núcleo
   dependería de ella.
2. Una capa (Evans, DDD) que traduce entre nuestro modelo y el de un sistema
   externo para que sus conceptos no contaminen nuestro lenguaje ubicuo. Un
   adaptador de salida hacia un sistema externo **es** una ACL.
3. (a) El dominio no se deforma con anotaciones, constructores vacíos ni
   setters; (b) value objects intactos; (c) esquema y negocio evolucionan por
   separado; (d) sin lazy loading en métodos de negocio. Un único modelo es
   aceptable en CRUD simples sin invariantes relevantes.
4. Porque el puerto hereda decenas de métodos técnicos de Spring Data, la capa
   de aplicación pasa a depender de Spring y el adaptador desaparece (no hay
   nada que traducir).
5. El 402 es una **respuesta de negocio** (pago rechazado): sabemos qué ha
   pasado y el caso de uso decide. El 503 es un **fallo técnico**: no sabemos
   si se cobró; se modela como excepción para reintentar o abortar.
6. Debe ser **idempotente**. Lo conseguimos con la cabecera
   `Idempotency-Key` = id del pedido, de modo que el proveedor no cobra dos
   veces la misma clave.
7. Un **mock** (Mockito) verifica interacciones; un **fake** es una
   implementación real y simple (en memoria). Mock para "¿se llamó a X con
   Y?"; fake para tests de comportamiento, dev local y demos.
8. `@Profile` para conmutar un **entorno completo**; `@ConditionalOnProperty`
   para decidir **adaptador a adaptador** (p. ej. Kafka real y email fake en
   staging).
9. `@Bean`: aplicación sin Spring y grafo explícito, a cambio de más código.
   `@Service`: cero cableado, pero Spring en la aplicación. `@UseCase` + scan:
   aplicación sin Spring y poco código, pero el grafo vuelve a ser implícito.
10. Los módulos hacen que el **compilador** rechace dependencias no
    declaradas (p. ej. JPA en `application`). ArchUnit verifica reglas
    **dentro** de un mismo módulo (adaptadores que no se llaman entre sí,
    Spring Data solo en persistencia, nombres, anotaciones…).

</details>

---

## Referencias

- Spring Framework — REST Clients:
  https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- Spring Framework — Data Access / Transactions:
  https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- Spring Boot docs: https://docs.spring.io/spring-boot/index.html
- WireMock: https://wiremock.org/docs/
- ArchUnit User Guide: https://www.archunit.org/userguide/html/000_Index.html
- Resilience4j: https://resilience4j.readme.io/
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023);
  https://github.com/thombergs/buckpal
- Martin Fowler, *The Practical Test Pyramid*:
  https://martinfowler.com/articles/practical-test-pyramid.html

---

## Mantra

> *"Si puedes cambiar el adaptador sin tocar el caso de uso, y un test te lo
> demuestra, has entendido el día 4."*
