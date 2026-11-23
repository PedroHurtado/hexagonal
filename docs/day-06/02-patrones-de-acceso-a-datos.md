# Día 6 — Bloque 2: Patrones de acceso a datos

> *"A Repository mediates between the domain and data mapping layers using a
> collection-like interface for accessing domain objects."*
> — Martin Fowler, *Patterns of Enterprise Application Architecture* (2002)

---

## 1. El problema en una frase

Todo el mundo dice "repositorio", pero cada uno entiende una cosa distinta:
un `JpaRepository`, un DAO con 40 métodos `findByXAndYOrderByZ`, una clase
con SQL… **Si no nombramos bien los patrones, el puerto acaba siendo un
volcado de la base de datos.**

Y una segunda pregunta que aparece en cuanto hay pantallas reales: **¿tiene
que pasar un listado paginado por el agregado `Pedido`?** (Spoiler: no.)

---

## 2. Los cuatro patrones clásicos

Todos están en el catálogo de Fowler (PoEAA, 2002) salvo el Repository, que
Fowler recoge y Evans populariza en DDD (2003).

### 2.1. Table Data Gateway

Un objeto por **tabla** que encapsula el SQL. Devuelve filas, mapas o
`RecordSet`. Sin dominio.

```java
class PedidosTableGateway {
    List<Map<String, Object>> buscarPorCliente(UUID clienteId) { /* SQL */ }
    void insertar(UUID id, UUID clienteId, String estado, Instant creadoEn) { /* SQL */ }
    void actualizarEstado(UUID id, String estado) { /* SQL */ }
}
```

### 2.2. Active Record

El objeto de dominio **sabe guardarse a sí mismo**. Datos + lógica +
persistencia en una sola clase. Es el modelo de Rails o de Panache en
Quarkus.

```java
class Pedido extends ActiveRecord {
    void confirmar() { this.estado = CONFIRMADO; this.save(); }   // ← el dominio hace SQL
    static Pedido find(UUID id) { /* SQL */ }
}
```

### 2.3. DAO (Data Access Object)

Viene de los *Core J2EE Patterns* (Sun). Interfaz que abstrae el acceso a
una fuente de datos, típicamente **una por tabla/entidad persistente**, con
operaciones CRUD y consultas a demanda.

```java
interface PedidoDao {
    PedidoDto findById(UUID id);
    List<PedidoDto> findByClienteIdAndEstadoOrderByCreadoEnDesc(UUID c, String e);
    void update(PedidoDto pedido);
    void updateEstado(UUID id, String estado);
    void deleteLinea(Long lineaId);
}
```

### 2.4. Repository

Simula una **colección en memoria de agregados**. Habla el lenguaje del
dominio, devuelve objetos de dominio completos y no expone cómo se
persisten.

```java
package com.curso.pedidos.application.port.out;

public interface PedidoRepository {
    Optional<Pedido> buscarPorId(PedidoId id);
    void guardar(Pedido pedido);
}
```

### 2.5. Comparativa

| Patrón             | Unidad              | Devuelve             | Dónde está la lógica | Encaje en hexagonal                                  |
|--------------------|---------------------|----------------------|----------------------|------------------------------------------------------|
| Table Data Gateway | Tabla               | Filas / mapas        | Fuera (Transaction Script) | Dentro de un adaptador, como detalle            |
| Active Record      | Fila = objeto       | El propio objeto     | En el objeto, junto al SQL | **Choca**: el dominio depende de la BD          |
| DAO                | Entidad persistente | DTO / entidad        | Fuera                | Dentro de un adaptador (`PedidoJpaRepository` es un DAO) |
| Repository         | **Agregado**        | Agregado de dominio  | En el agregado       | **Es el puerto de salida natural**                   |

> **En cristiano:** el `PedidoJpaRepository` de Spring Data se llama
> "Repository" pero, en nuestro diseño, **se comporta como un DAO**: trabaja
> con `PedidoJpaEntity`, no con `Pedido`. El repositorio de verdad, el de
> Evans, es nuestro puerto `PedidoRepository`, implementado por
> `PedidoPersistenceAdapter`.

```
       application.port.out                    infrastructure.adapter.out.persistence
┌──────────────────────────────┐        ┌─────────────────────────────────────────────┐
│ «interface»                  │        │ PedidoPersistenceAdapter                    │
│ PedidoRepository             │◄───────│   usa ──► PedidoJpaRepository (≈ DAO)      │
│  buscarPorId / guardar       │ impl.  │   usa ──► PedidoPersistenceMapper          │
│  (Repository de Evans)       │        │   usa ──► JdbcClient (≈ Table Data Gateway)│
└──────────────────────────────┘        └─────────────────────────────────────────────┘
```

---

## 3. Bad: el puerto como volcado de la base de datos

```java
public interface PedidoRepository extends JpaRepository<PedidoJpaEntity, UUID> {
    List<PedidoJpaEntity> findByClienteIdAndEstadoAndCreadoEnBetweenOrderByCreadoEnDesc(
            UUID clienteId, String estado, Instant desde, Instant hasta);
    Page<PedidoJpaEntity> findByEstado(String estado, Pageable pageable);
    @Modifying @Query("update PedidoJpaEntity p set p.estado = :e where p.id = :id")
    void actualizarEstado(UUID id, String e);
}
```

### ¿Qué duele?

- El "puerto" importa Spring Data y JPA: el dominio y la aplicación ya no
  son independientes.
- `actualizarEstado` se salta el agregado: nadie comprueba que un pedido
  `ENVIADO` no se puede cancelar.
- Cada pantalla nueva añade un método. A los seis meses hay 30, la mitad sin
  uso.
- `Page` y `Pageable` se filtran hasta el controlador… y hasta el dominio.

---

## 4. Repositorio orientado a colección vs orientado a persistencia

Vaughn Vernon (IDDD, cap. 12) distingue dos estilos:

| Estilo                         | Interfaz                                   | Semántica                                                       |
|--------------------------------|--------------------------------------------|-----------------------------------------------------------------|
| **Orientado a colección**      | `anadir(pedido)`, `buscarPorId`, `eliminar` | Como un `Set`: añades una vez; los cambios se detectan solos (*dirty checking*). |
| **Orientado a persistencia**   | `guardar(pedido)`, `buscarPorId`           | Explícito: cada cambio se guarda llamando a `guardar`.          |

Con **modelos separados** (bloque 1) el estilo colección no funciona: el
`Pedido` de dominio no está gestionado por Hibernate, así que nadie detecta
sus cambios. Por eso nuestro puerto es **orientado a persistencia**:
`guardar` siempre, aunque el pedido ya existiera.

```java
// Servicio de aplicación
Pedido pedido = repository.buscarPorId(id).orElseThrow(...);
pedido.confirmar();
repository.guardar(pedido);   // explícito. Sin esto, el cambio se pierde.
```

> **Trampa:** olvidar el `guardar` final "porque con JPA funcionaba". Con
> dominio anotado y transacción abierta funcionaba por *dirty checking*; con
> modelos separados, no. Un test unitario del servicio con un repositorio en
> memoria lo detecta al instante.

---

## 5. Specification: consultas expresadas en el dominio

Evans y Fowler, *Specifications* (2002): un objeto que responde "¿este
candidato cumple el criterio?" y que se puede **combinar** con `and`, `or`,
`not`.

Útil cuando una regla de selección es **de negocio** y se usa en varios
sitios: "pedidos pendientes de pago desde hace más de 48 horas" lo usan un
job de recordatorios, una pantalla de back-office y una regla de
cancelación automática.

### 5.1. La especificación en el dominio (sin Spring)

```java
package com.curso.pedidos.domain.model.spec;

import com.curso.pedidos.domain.model.Pedido;

public interface Especificacion<T> {
    boolean seCumple(T candidato);

    default Especificacion<T> y(Especificacion<T> otra) {
        return c -> this.seCumple(c) && otra.seCumple(c);
    }
    default Especificacion<T> no() {
        return c -> !this.seCumple(c);
    }
}
```

Para que el adaptador pueda **traducirla a SQL** (no queremos cargar todos
los pedidos en memoria y filtrar), usamos especificaciones con nombre, como
`record`s, en una jerarquía cerrada:

```java
package com.curso.pedidos.domain.model.spec;

import com.curso.pedidos.domain.model.*;
import java.time.Duration;
import java.time.Instant;

public sealed interface EspecificacionPedido extends Especificacion<Pedido>
        permits EnEstado, CreadoAntesDe, DelCliente, Ambas {

    static EspecificacionPedido pendienteDePagoDesdeHace(Duration d, Instant ahora) {
        return new Ambas(new EnEstado(EstadoPedido.CONFIRMADO),
                         new CreadoAntesDe(ahora.minus(d)));
    }
}

public record EnEstado(EstadoPedido estado) implements EspecificacionPedido {
    public boolean seCumple(Pedido p) { return p.estado() == estado; }
}
public record CreadoAntesDe(Instant instante) implements EspecificacionPedido {
    public boolean seCumple(Pedido p) { return p.creadoEn().isBefore(instante); }
}
public record DelCliente(ClienteId clienteId) implements EspecificacionPedido {
    public boolean seCumple(Pedido p) { return p.clienteId().equals(clienteId); }
}
public record Ambas(EspecificacionPedido a, EspecificacionPedido b) implements EspecificacionPedido {
    public boolean seCumple(Pedido p) { return a.seCumple(p) && b.seCumple(p); }
}
```

(En código real, cada `record` va en su propio fichero.)

El puerto:

```java
public interface PedidoRepository {
    Optional<Pedido> buscarPorId(PedidoId id);
    void guardar(Pedido pedido);
    List<Pedido> buscarQueCumplan(EspecificacionPedido spec);
}
```

### 5.2. El adaptador traduce a Spring Data `Specification`

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

import com.curso.pedidos.domain.model.spec.*;
import org.springframework.data.jpa.domain.Specification;

final class TraductorEspecificaciones {

    static Specification<PedidoJpaEntity> aJpa(EspecificacionPedido spec) {
        return switch (spec) {
            case EnEstado e -> (root, q, cb) ->
                    cb.equal(root.get("estado"), e.estado().name());
            case CreadoAntesDe c -> (root, q, cb) ->
                    cb.lessThan(root.get("creadoEn"), c.instante());
            case DelCliente d -> (root, q, cb) ->
                    cb.equal(root.get("clienteId"), d.clienteId().valor());
            case Ambas a -> aJpa(a.a()).and(aJpa(a.b()));
        };
    }
}
```

> **Java 17 vs 21:** el `switch` con patrones de tipo sobre una `sealed
> interface` es definitivo en **Java 21**. En Java 17 existe solo como
> *preview* (`--enable-preview`). Para Java 17 estricto, usa una cadena de
> `if (spec instanceof EnEstado e) ...` (el *pattern matching* de
> `instanceof` sí es estándar en 17). Otra razón para recomendar Java 21 en
> proyectos nuevos.

`PedidoJpaRepository` debe extender también `JpaSpecificationExecutor<PedidoJpaEntity>`:

```java
interface PedidoJpaRepository extends JpaRepository<PedidoJpaEntity, UUID>,
                                      JpaSpecificationExecutor<PedidoJpaEntity> { ... }
```

```java
@Override
public List<Pedido> buscarQueCumplan(EspecificacionPedido spec) {
    return jpa.findAll(TraductorEspecificaciones.aJpa(spec)).stream()
              .map(mapper::aDominio)
              .toList();
}
```

> **Trampa:** usar directamente `org.springframework.data.jpa.domain.Specification`
> en el puerto. Es cómodo, pero el puerto queda atado a JPA Criteria. La
> versión de dominio + traductor es más código, y a cambio el repositorio en
> memoria de los tests usa `spec.seCumple(p)` sin más.

> **Trampa (N+1):** `findAll(spec)` no hace `join fetch` de las líneas. Si
> mapeas a dominio, tendrás una consulta por pedido. Añade un
> `@EntityGraph(attributePaths = "lineas")` al método o, mejor, plantéate si
> esa consulta debería ir por el **lado de lectura** (sección 6).

---

## 6. CQRS ligero: el lado de lectura se salta el dominio

### 6.1. La observación

El agregado `Pedido` existe para **proteger invariantes al modificar**. Una
pantalla "Mis pedidos" (número, fecha, estado, total, nº de líneas) no
modifica nada. Cargar 50 agregados completos con sus líneas, mapearlos a
dominio y luego a DTO es:

- lento (N+1, mapeos dobles),
- inútil (ninguna invariante que proteger),
- y obliga a añadir getters al dominio "para la pantalla".

Bertrand Meyer (CQS) y Greg Young (CQRS): **separa el modelo que escribe del
modelo que lee**. Fowler avisa: CQRS completo (dos bases de datos,
sincronización por eventos) añade mucha complejidad. Aquí hacemos la versión
**ligera**: misma base de datos, dos caminos.

```
                      ┌────────────────────────── ESCRITURA ───────────────────────────┐
 POST /pedidos/42/    │                                                                │
 confirmar ──────────►│ ConfirmarPedidoUseCase ─► Pedido (agregado) ─► PedidoRepository│──┐
                      └────────────────────────────────────────────────────────────────┘  │
                                                                                          ▼
                                                                                   ┌────────────┐
                                                                                   │ PostgreSQL │
                                                                                   └────────────┘
                      ┌─────────────────────────── LECTURA ────────────────────────────┐  ▲
 GET /pedidos?cliente │                                                                │  │
 =...&pagina=0 ──────►│ ConsultarPedidoQuery ─► (sin dominio) ─► JdbcClient + SQL      │──┘
                      │                         devuelve PedidoResumenView (record)    │
                      └────────────────────────────────────────────────────────────────┘
```

### 6.2. El puerto de entrada de consulta y su vista

```java
package com.curso.pedidos.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ConsultarPedidoQuery {

    Optional<PedidoResumenView> porId(UUID pedidoId);

    PaginaDe<PedidoResumenView> delCliente(UUID clienteId, int pagina, int tamano);

    record PedidoResumenView(UUID id, String estado, Instant creadoEn,
                             BigDecimal total, String moneda, int numeroLineas) { }
}
```

Paginación sin Spring Data (va en `application.port.in` o en un paquete
compartido de la aplicación):

```java
package com.curso.pedidos.application.port.in;

import java.util.List;

public record PaginaDe<T>(List<T> elementos, int pagina, int tamano, long totalElementos) {
    public PaginaDe {
        elementos = List.copyOf(elementos);
        if (pagina < 0 || tamano <= 0) throw new IllegalArgumentException("Paginación inválida");
    }
    public int totalPaginas() {
        return (int) Math.ceil((double) totalElementos / tamano);
    }
    public boolean hayMas() { return pagina + 1 < totalPaginas(); }
}
```

> **Por qué no `org.springframework.data.domain.Page`:** porque entonces la
> capa de aplicación depende de Spring Data, y el día que el lado de lectura
> salga de Elasticsearch o de otro microservicio, el contrato cambia. `PaginaDe`
> son 10 líneas y no tiene dependencias.

### 6.3. ¿Quién implementa la query?

Dos opciones, ambas válidas:

| Opción                                                          | Pro                               | Contra                                       |
|-----------------------------------------------------------------|-----------------------------------|----------------------------------------------|
| Un **adaptador de salida** de lectura implementa directamente `ConsultarPedidoQuery` | Mínimo código, sin servicio vacío | Un adaptador de salida implementa un puerto de entrada (choca a los puristas) |
| Servicio de aplicación delega en un puerto de salida `PedidoLecturas` | Simetría con la escritura; sitio para seguridad/autorización | Un servicio "pasamanos" sin lógica |

Usamos la segunda, que respeta las reglas del curso, y comentamos la primera
como atajo pragmático aceptable.

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.application.port.in.ConsultarPedidoQuery.PedidoResumenView;
import com.curso.pedidos.application.port.in.PaginaDe;
import java.util.Optional;
import java.util.UUID;

public interface PedidoLecturas {
    Optional<PedidoResumenView> resumenPorId(UUID pedidoId);
    PaginaDe<PedidoResumenView> resumenesDelCliente(UUID clienteId, int pagina, int tamano);
}
```

```java
package com.curso.pedidos.application.service;

public class ConsultarPedidoService implements ConsultarPedidoQuery {

    private final PedidoLecturas lecturas;

    public ConsultarPedidoService(PedidoLecturas lecturas) { this.lecturas = lecturas; }

    @Override
    public Optional<PedidoResumenView> porId(UUID pedidoId) {
        return lecturas.resumenPorId(pedidoId);
    }

    @Override
    public PaginaDe<PedidoResumenView> delCliente(UUID clienteId, int pagina, int tamano) {
        int tamanoSeguro = Math.min(tamano, 100);    // regla de aplicación: tope de página
        return lecturas.resumenesDelCliente(clienteId, pagina, tamanoSeguro);
    }
}
```

### 6.4. El adaptador de lectura con `JdbcClient`

`JdbcClient` (Spring Framework 6.1, incluido en Boot 3.2+) es una API fluida
sobre `JdbcTemplate`. Mapea directamente a `record`s.

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

import com.curso.pedidos.application.port.in.ConsultarPedidoQuery.PedidoResumenView;
import com.curso.pedidos.application.port.in.PaginaDe;
import com.curso.pedidos.application.port.out.PedidoLecturas;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class PedidoLecturasJdbcAdapter implements PedidoLecturas {

    private static final String SELECT_RESUMEN = """
            SELECT p.id, p.estado, p.creado_en,
                   COALESCE(SUM(l.cantidad * l.precio_unitario), 0) AS total,
                   COALESCE(MIN(l.moneda), 'EUR')                   AS moneda,
                   COUNT(l.id)                                      AS numero_lineas
              FROM pedidos p
              LEFT JOIN lineas_pedido l ON l.pedido_id = p.id
            """;

    private final JdbcClient jdbc;

    PedidoLecturasJdbcAdapter(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<PedidoResumenView> resumenPorId(UUID pedidoId) {
        return jdbc.sql(SELECT_RESUMEN + " WHERE p.id = :id GROUP BY p.id")
                   .param("id", pedidoId)
                   .query(PedidoResumenView.class)
                   .optional();
    }

    @Override
    public PaginaDe<PedidoResumenView> resumenesDelCliente(UUID clienteId, int pagina, int tamano) {
        List<PedidoResumenView> filas = jdbc.sql(SELECT_RESUMEN + """
                 WHERE p.cliente_id = :cliente
                 GROUP BY p.id
                 ORDER BY p.creado_en DESC
                 LIMIT :limite OFFSET :desplazamiento
                """)
                .param("cliente", clienteId)
                .param("limite", tamano)
                .param("desplazamiento", pagina * tamano)
                .query(PedidoResumenView.class)
                .list();

        long total = jdbc.sql("SELECT COUNT(*) FROM pedidos WHERE cliente_id = :cliente")
                         .param("cliente", clienteId)
                         .query(Long.class)
                         .single();

        return new PaginaDe<>(filas, pagina, tamano, total);
    }
}
```

> `query(PedidoResumenView.class)` usa un `DataClassRowMapper`: mapea
> columnas en *snake_case* (`creado_en`, `numero_lineas`) a los componentes
> del `record` en *camelCase*. Si los nombres no casan, falla en el test, no
> en producción (si tienes test, claro).

> **Trampa (OFFSET):** `LIMIT/OFFSET` se degrada con páginas muy altas (la
> BD recorre y descarta todas las filas anteriores). Para *scroll* infinito
> o tablas enormes, usa **paginación por cursor** (*keyset*): `WHERE
> creado_en < :ultimoVisto ORDER BY creado_en DESC LIMIT :n`.

### 6.5. ¿Cuándo merece la pena CQRS ligero?

| Señal                                                         | ¿CQRS ligero? |
|---------------------------------------------------------------|---------------|
| Listados, búsquedas, informes, dashboards                     | Sí            |
| La pantalla combina datos de varios agregados                 | Sí            |
| Estás añadiendo getters al dominio solo para la vista         | Sí            |
| Lecturas que deciden una escritura ("¿hay stock?" antes de reservar) | **No**: eso va por el agregado |
| Una entidad con 3 campos y una pantalla de detalle            | Probablemente no; reutiliza el agregado |

> **En cristiano:** **escribe por el dominio, lee por donde sea más rápido.**
> Las lecturas no rompen invariantes; las escrituras sí.

> **Trampa:** usar una vista de lectura para **decidir** una escritura. La
> vista puede estar desfasada (especialmente si mañana sale de una réplica o
> de una proyección asíncrona). Las decisiones se toman con el agregado
> cargado dentro de la transacción.

---

## 7. Testing

### 7.1. Repositorio en memoria que entiende especificaciones

```java
package com.curso.pedidos.application.service;

public class PedidoRepositoryEnMemoria implements PedidoRepository {

    private final Map<PedidoId, Pedido> pedidos = new LinkedHashMap<>();

    @Override public Optional<Pedido> buscarPorId(PedidoId id) {
        return Optional.ofNullable(pedidos.get(id));
    }
    @Override public void guardar(Pedido pedido) { pedidos.put(pedido.id(), pedido); }

    @Override public List<Pedido> buscarQueCumplan(EspecificacionPedido spec) {
        return pedidos.values().stream().filter(spec::seCumple).toList();
    }
}
```

### 7.2. Test unitario de la especificación (dominio puro)

```java
class EspecificacionPedidoTest {

    private final Instant ahora = Instant.parse("2026-11-23T12:00:00Z");

    @Test
    void pendiente_de_pago_desde_hace_48h() {
        var spec = EspecificacionPedido.pendienteDePagoDesdeHace(Duration.ofHours(48), ahora);

        var viejo = pedidoConfirmadoCreadoEn(ahora.minus(Duration.ofHours(72)));
        var reciente = pedidoConfirmadoCreadoEn(ahora.minus(Duration.ofHours(2)));

        assertThat(spec.seCumple(viejo)).isTrue();
        assertThat(spec.seCumple(reciente)).isFalse();
    }

    private Pedido pedidoConfirmadoCreadoEn(Instant creadoEn) {
        var p = Pedido.crear(new ClienteId(UUID.randomUUID()), creadoEn);
        p.anadirLinea(new ProductoId("SKU-1"), new Cantidad(1),
                      new Dinero(BigDecimal.TEN, Currency.getInstance("EUR")));
        p.confirmar();
        return p;
    }
}
```

### 7.3. Test de integración del adaptador de lectura

```java
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PedidoLecturasJdbcAdapter.class)
@Sql(statements = {
    "INSERT INTO pedidos(id, cliente_id, estado, creado_en, version) VALUES " +
    "('11111111-1111-1111-1111-111111111111','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','CONFIRMADO', now(), 0)",
    "INSERT INTO lineas_pedido(pedido_id, posicion, producto_sku, cantidad, precio_unitario, moneda) VALUES " +
    "('11111111-1111-1111-1111-111111111111', 0, 'SKU-1', 2, 10.00, 'EUR')," +
    "('11111111-1111-1111-1111-111111111111', 1, 'SKU-2', 1, 5.50, 'EUR')"
})
class PedidoLecturasJdbcAdapterIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PedidoLecturasJdbcAdapter adapter;

    private static final UUID CLIENTE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void calcula_el_resumen_en_sql() {
        var vista = adapter.resumenPorId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                           .orElseThrow();
        assertThat(vista.total()).isEqualByComparingTo("25.50");
        assertThat(vista.numeroLineas()).isEqualTo(2);
        assertThat(vista.estado()).isEqualTo("CONFIRMADO");
    }

    @Test
    void pagina_los_pedidos_del_cliente() {
        PaginaDe<PedidoResumenView> pagina = adapter.resumenesDelCliente(CLIENTE, 0, 10);
        assertThat(pagina.elementos()).hasSize(1);
        assertThat(pagina.totalElementos()).isEqualTo(1);
        assertThat(pagina.hayMas()).isFalse();
    }
}
```

> `@JdbcTest` no arranca JPA, pero sí Flyway (si está en el classpath) y
> autoconfigura `JdbcClient`. El test valida a la vez el SQL, el mapeo
> *snake_case* → `record` y las migraciones.

---

## 8. Resumen

| Necesidad                                | Patrón                                  | Dónde vive                              |
|------------------------------------------|-----------------------------------------|-----------------------------------------|
| Cargar y guardar un agregado             | Repository (Evans)                      | Puerto `PedidoRepository` + adaptador   |
| Acceso técnico a tablas                  | DAO / Table Data Gateway                | Dentro del adaptador (Spring Data, `JdbcClient`) |
| Objeto que se guarda solo                | Active Record                           | Evítalo en hexagonal                    |
| Criterio de negocio reutilizable         | Specification                           | Dominio + traductor en el adaptador     |
| Listados, informes, pantallas            | CQRS ligero (`ConsultarPedidoQuery`)    | Puerto de lectura + `JdbcClient`        |
| Paginación                               | `PaginaDe<T>` propio                    | Aplicación, nunca `Page` de Spring      |

---

## Ejercicio

Implementa la consulta "**pedidos pendientes de pago desde hace más de 48
horas**" para el back-office, que devuelva `id`, `clienteId`, `creadoEn` y
`total`, paginada.

1. ¿Va por el lado de escritura (especificación + agregado) o por el de
   lectura? Justifícalo.
2. Implementa el puerto y el adaptador.
3. Escribe el test.

**Pista:** el back-office solo **muestra** la lista. Pero el job que
**cancela** automáticamente esos pedidos sí modifica.

<details><summary>Solución</summary>

1. La pantalla del back-office **va por lectura**: no modifica nada. El job
   de cancelación automática **va por escritura**: usa
   `EspecificacionPedido.pendienteDePagoDesdeHace(...)` con
   `buscarQueCumplan`, carga cada agregado y llama a `cancelar()`, que
   comprueba invariantes.

2. Puerto de lectura:

```java
public interface PedidoLecturas {
    // ...
    PaginaDe<PedidoPendienteView> pendientesDePagoAntesDe(Instant limite, int pagina, int tamano);
}

public record PedidoPendienteView(UUID id, UUID clienteId, Instant creadoEn, BigDecimal total) { }
```

Adaptador:

```java
@Override
public PaginaDe<PedidoPendienteView> pendientesDePagoAntesDe(Instant limite, int pagina, int tamano) {
    var filas = jdbc.sql("""
            SELECT p.id, p.cliente_id, p.creado_en,
                   COALESCE(SUM(l.cantidad * l.precio_unitario), 0) AS total
              FROM pedidos p LEFT JOIN lineas_pedido l ON l.pedido_id = p.id
             WHERE p.estado = 'CONFIRMADO' AND p.creado_en < :limite
             GROUP BY p.id
             ORDER BY p.creado_en
             LIMIT :lim OFFSET :off
            """)
            .param("limite", Timestamp.from(limite))
            .param("lim", tamano).param("off", pagina * tamano)
            .query(PedidoPendienteView.class).list();
    long total = jdbc.sql("SELECT COUNT(*) FROM pedidos WHERE estado = 'CONFIRMADO' AND creado_en < :limite")
            .param("limite", Timestamp.from(limite)).query(Long.class).single();
    return new PaginaDe<>(filas, pagina, tamano, total);
}
```

3. Test con `@JdbcTest` + Testcontainers insertando un pedido de hace 72 h y
   otro de hace 2 h, y comprobando que solo sale el primero. El `Instant
   ahora` se pasa desde el servicio (que lo obtiene de un `Clock`), nunca
   `now()` en SQL, para que el test sea determinista.

</details>

---

## Referencias

- Martin Fowler, *Catalog of Patterns of Enterprise Application Architecture* (Repository, Table Data Gateway, Active Record, Data Mapper, Query Object): https://martinfowler.com/eaaCatalog/
- Martin Fowler, *CQRS*: https://martinfowler.com/bliki/CQRS.html
- Eric Evans & Martin Fowler, *Specifications* (2002); Eric Evans, *Domain-Driven Design* (2003), cap. 6 y 9.
- Vaughn Vernon, *Implementing Domain-Driven Design* (2013), cap. 12 "Repositories".
- Spring Data JPA, *Specifications* y proyecciones: https://docs.spring.io/spring-data/jpa/reference/
- Spring Framework, acceso a datos con JDBC (`JdbcClient`): https://docs.spring.io/spring-framework/reference/data-access/jdbc.html
- Vlad Mihalcea, sobre N+1, proyecciones DTO y paginación *keyset*: https://vladmihalcea.com/
- Testcontainers: https://java.testcontainers.org/

---

## Mantra

> *"Escribe por el dominio, lee por el camino más corto. El repositorio es
> una colección de agregados, no un volcado de la base de datos."*
