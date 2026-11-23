# Día 6 — Bloque 1: Persistencia y bases de datos

> *"The database is a detail."*
> — Robert C. Martin, *Clean Architecture* (2017)

Un detalle, sí. Pero un detalle que, mal resuelto, te tumba la aplicación en
producción. Este bloque va de cómo tratar la base de datos como detalle **sin
ser ingenuo** con ella.

---

## 1. El problema en una frase

El dominio quiere objetos con comportamiento e invariantes; la base de datos
quiere filas, columnas, claves foráneas y versiones. **¿Quién se adapta a
quién, y cuánto cuesta?**

En el día 4 ya construimos el `PedidoPersistenceAdapter` con un mapper. Hoy
respondemos a las preguntas que quedaron pendientes:

- ¿Es obligatorio tener dos modelos (dominio y JPA)?
- ¿Quién genera el `PedidoId`: el dominio o la base de datos?
- ¿Cómo guardo un agregado con sus líneas sin dejarlo a medias?
- ¿Qué hago con el *lazy loading*?
- ¿Cómo evoluciono el esquema? (Flyway)
- ¿Qué pasa si dos usuarios modifican el mismo pedido a la vez? (`@Version`)
- ¿Cómo lo pruebo contra una base de datos de verdad? (Testcontainers)

---

## 2. Modelo de dominio vs modelo de persistencia

Hay dos escuelas. Las dos son legítimas; lo que no es legítimo es elegir una
sin saber lo que cuesta.

### 2.1. Opción A — Modelos separados con mapper (la "pura")

```
┌────────────────────────────┐        ┌──────────────────────────────────┐
│ domain.model               │        │ infrastructure.adapter.out.      │
│                            │        │ persistence                      │
│  Pedido (sin anotaciones)  │◄──────►│  PedidoJpaEntity (@Entity)       │
│  LineaPedido               │ mapper │  LineaPedidoJpaEntity (@Entity)  │
│  PedidoId, Dinero...       │        │  PedidoJpaRepository (Spring)    │
└────────────────────────────┘        └──────────────────────────────────┘
          ▲                                          │
          │ implements PedidoRepository              │
          └──────────── PedidoPersistenceAdapter ◄───┘
```

El dominio no sabe que existe JPA. El adaptador traduce en las dos
direcciones.

**Lo que ganas:**

- El dominio se diseña para el negocio: constructores privados, *value
  objects* como `Dinero` o `Cantidad`, colecciones inmutables, sin setters.
- Puedes cambiar el esquema (renombrar columnas, partir tablas,
  desnormalizar) sin tocar el dominio.
- Los tests del dominio no arrastran Hibernate ni proxies.

**Lo que pagas:**

- Dos clases por concepto y un mapper que mantener.
- El mapper es código aburrido donde se cuelan bugs ("se me olvidó mapear
  `creadoEn`").
- Pierdes el *dirty checking* automático de JPA: siempre guardas el agregado
  entero (ya veremos que eso es, en realidad, una ventaja).

### 2.2. Opción B — Anotar el dominio con JPA (la "pragmática")

```java
@Entity
@Table(name = "pedidos")
public class Pedido {

    @EmbeddedId
    private PedidoId id;

    @Embedded
    @AttributeOverride(name = "valor", column = @Column(name = "cliente_id"))
    private ClienteId clienteId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "pedido_id")
    private List<LineaPedido> lineas = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private EstadoPedido estado;

    private Instant creadoEn;

    protected Pedido() { }   // ← JPA lo exige. El dominio no lo quería.
    // ...
}
```

**Lo que ganas:** una sola clase, nada de mapper, *dirty checking*, menos
código. En un microservicio pequeño con un dominio sencillo, es razonable.

**Lo que pagas (y no siempre se ve el primer día):**

| Coste                                  | Por qué duele                                                                                       |
|----------------------------------------|-----------------------------------------------------------------------------------------------------|
| Constructor vacío obligatorio          | Puedes crear un `Pedido` sin invariantes. JPA lo usa; alguien más lo usará.                         |
| Campos no `final`                      | Hibernate rellena por reflexión. Adiós a la inmutabilidad de los VO internos.                       |
| `records` no sirven como `@Embeddable` | Hasta Hibernate 6.2 no; y aún hoy con limitaciones. Los VO acaban siendo clases con constructor vacío. |
| Proxies y *lazy loading*               | El dominio puede recibir un proxy en vez de la clase real; `getClass()` y `equals()` sorprenden.    |
| Dependencia `jakarta.persistence`      | El dominio importa infraestructura. ArchUnit (día 5) empezará a protestar.                          |
| Esquema acoplado al modelo             | Refactorizar el dominio = migrar la BD. Y al revés.                                                 |

> **En cristiano:** anotar el dominio es pedir un préstamo. Al principio te
> da velocidad; los intereses los pagas cuando el dominio crece.

### 2.3. Criterio de decisión

| Situación                                                         | Recomendación                        |
|-------------------------------------------------------------------|--------------------------------------|
| CRUD con poca lógica, equipo pequeño, vida corta                  | Anotar (o ni siquiera hexagonal)     |
| Dominio rico, invariantes, agregados con VO                       | Separar con mapper                   |
| Esquema heredado que no puedes tocar                              | Separar sin dudarlo                  |
| Quieres empezar rápido pero dejar la puerta abierta               | Separar, pero con mapper generado (MapStruct) |

En este curso **separamos**. Es lo que permite que el resto del día
(versionado, outbox, eventos) se haga sin ensuciar el dominio.

---

## 3. El dominio preparado para persistirse (sin saber de JPA)

El agregado necesita dos cosas para ser persistible sin JPA:

1. Una **factoría de reconstitución** (distinta de `crear`) que permita al
   adaptador rehidratarlo desde la BD **sin disparar reglas de creación** ni
   eventos.
2. Opcionalmente, un campo `version` opaco para el bloqueo optimista
   (sección 7).

```java
package com.curso.pedidos.domain.model;

import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Pedido {

    private final PedidoId id;
    private final ClienteId clienteId;
    private final List<LineaPedido> lineas;
    private EstadoPedido estado;
    private final Instant creadoEn;
    private final Long version;        // null = nunca persistido

    private Pedido(PedidoId id, ClienteId clienteId, List<LineaPedido> lineas,
                   EstadoPedido estado, Instant creadoEn, Long version) {
        this.id = Objects.requireNonNull(id);
        this.clienteId = Objects.requireNonNull(clienteId);
        this.lineas = new ArrayList<>(lineas);
        this.estado = Objects.requireNonNull(estado);
        this.creadoEn = Objects.requireNonNull(creadoEn);
        this.version = version;
    }

    /** Creación de negocio: aplica reglas de alta. */
    public static Pedido crear(ClienteId clienteId, Instant ahora) {
        return new Pedido(PedidoId.nuevo(), clienteId, List.of(),
                          EstadoPedido.BORRADOR, ahora, null);
    }

    /** Reconstitución desde persistencia: NO es un caso de negocio. */
    public static Pedido reconstituir(PedidoId id, ClienteId clienteId,
                                      List<LineaPedido> lineas, EstadoPedido estado,
                                      Instant creadoEn, Long version) {
        return new Pedido(id, clienteId, lineas, estado, creadoEn, version);
    }

    public void anadirLinea(ProductoId productoId, Cantidad cantidad, Dinero precioUnitario) {
        if (estado != EstadoPedido.BORRADOR) {
            throw new TransicionDeEstadoInvalidaException(
                "No se pueden añadir líneas a un pedido en estado " + estado);
        }
        lineas.add(new LineaPedido(productoId, cantidad, precioUnitario));
    }

    // confirmar(), cancelar(), marcarComoPagado(), total() como en días anteriores

    public PedidoId id()                { return id; }
    public ClienteId clienteId()        { return clienteId; }
    public List<LineaPedido> lineas()   { return List.copyOf(lineas); }
    public EstadoPedido estado()        { return estado; }
    public Instant creadoEn()           { return creadoEn; }
    public Long version()               { return version; }
}
```

> **Trampa:** usar `crear(...)` desde el mapper para rehidratar. Si `crear`
> registra un evento `PedidoCreado` o valida "fecha no en el pasado", cada
> lectura de BD disparará eventos fantasma o fallará con pedidos antiguos.
> **Crear y reconstituir son cosas distintas.**

---

## 4. Identidad: ¿la genera el dominio o la base de datos?

### Bad: identidad generada por la BD

```java
@Entity
public class PedidoJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}
```

```java
// Servicio de aplicación
Pedido pedido = Pedido.crear(clienteId, ahora);   // ¿id? todavía no hay...
repository.guardar(pedido);                        // ...ahora sí, pero ¿cómo lo sé?
publicador.publicar(new PedidoConfirmado(pedido.id(), ahora)); // NPE o id = null
```

### ¿Qué duele?

- El agregado **no tiene identidad hasta que se guarda**. Un objeto de
  dominio sin identidad es un objeto a medias.
- `guardar` tiene que devolver el pedido "con id" o mutar el que le pasas:
  el puerto se contamina con detalles de la BD.
- No puedes emitir eventos ni construir referencias (`PedidoId` en una línea
  de outbox, en un log, en una respuesta HTTP 201) antes del `INSERT`.
- `equals`/`hashCode` basados en id no funcionan antes de persistir.

### Good: identidad generada por el dominio (UUID)

```java
package com.curso.pedidos.domain.model;

import java.util.Objects;
import java.util.UUID;

public record PedidoId(UUID valor) {
    public PedidoId {
        Objects.requireNonNull(valor, "El PedidoId no puede ser nulo");
    }
    public static PedidoId nuevo() {
        return new PedidoId(UUID.randomUUID());
    }
    public static PedidoId de(String texto) {
        return new PedidoId(UUID.fromString(texto));
    }
}
```

```java
@Entity
@Table(name = "pedidos")
public class PedidoJpaEntity {
    @Id
    private UUID id;          // asignado por el dominio; sin @GeneratedValue
    // ...
}
```

| Criterio                         | Generada por BD (`IDENTITY`/secuencia) | Generada por dominio (UUID)              |
|----------------------------------|----------------------------------------|------------------------------------------|
| Identidad antes de persistir     | No                                     | Sí                                       |
| Eventos/outbox en la misma tx    | Incómodo                               | Natural                                  |
| Tests sin BD                     | Hay que simular el id                  | Directo                                  |
| Tamaño del índice                | 8 bytes                                | 16 bytes                                 |
| Localidad del índice B-tree      | Excelente (secuencial)                 | Peor con UUIDv4 (aleatorio)              |
| Exponer ids en URLs              | Enumerables (`/pedidos/1`, `/2`...)    | No enumerables                           |

> **Trampa (rendimiento):** UUIDv4 es aleatorio y fragmenta el índice
> primario en tablas muy grandes. Si te preocupa, usa **UUIDv7** (ordenado
> por tiempo, RFC 9562). Se genera con una librería pequeña en el dominio o
> con una factoría inyectada; el tipo `PedidoId(UUID)` no cambia.

> **Trampa (Spring Data):** con id asignado, `save()` de Spring Data no sabe
> si la entidad es nueva y hace un `SELECT` previo (`merge`). Con `@Version`
> de tipo envoltorio (`Long`) a `null`, Spring Data la considera nueva y hace
> `persist` directamente. Otro motivo para tener versión.

---

## 5. Persistir agregados completos

Regla de Evans: **un repositorio por agregado raíz**, y se guarda y se carga
**el agregado entero**. No existe `LineaPedidoRepository`.

### Bad: guardar trozos

```java
// En el servicio de aplicación
lineaPedidoRepository.save(nuevaLinea);              // la línea se guarda...
pedidoRepository.actualizarEstado(id, CONFIRMADO);   // ...el estado aparte
```

¿Qué duele? Nadie comprueba la invariante "no se añaden líneas a un pedido
confirmado". Dos llamadas, dos posibles fallos, un agregado inconsistente.

### Good: el adaptador guarda el agregado como unidad

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pedidos")
public class PedidoJpaEntity {

    @Id
    private UUID id;

    @Column(name = "cliente_id", nullable = false)
    private UUID clienteId;

    @Column(nullable = false, length = 20)
    private String estado;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Version
    private Long version;

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderColumn(name = "posicion")
    private List<LineaPedidoJpaEntity> lineas = new ArrayList<>();

    protected PedidoJpaEntity() { }   // aquí sí: es infraestructura

    public void reemplazarLineas(List<LineaPedidoJpaEntity> nuevas) {
        lineas.clear();
        nuevas.forEach(l -> { l.setPedido(this); lineas.add(l); });
    }

    // getters/setters (aquí Lombok sería aceptable: es un adaptador)
}
```

```java
@Entity
@Table(name = "lineas_pedido")
public class LineaPedidoJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                       // id técnico: LineaPedido no tiene identidad global

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pedido_id")
    private PedidoJpaEntity pedido;

    @Column(name = "producto_sku", nullable = false)
    private String productoSku;

    private int cantidad;

    @Column(name = "precio_unitario", nullable = false, precision = 12, scale = 2)
    private BigDecimal precioUnitario;

    @Column(nullable = false, length = 3)
    private String moneda;

    protected LineaPedidoJpaEntity() { }
    // constructor, getters, setPedido(...)
}
```

El adaptador:

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class PedidoPersistenceAdapter implements PedidoRepository {

    private final PedidoJpaRepository jpa;
    private final PedidoPersistenceMapper mapper;

    PedidoPersistenceAdapter(PedidoJpaRepository jpa, PedidoPersistenceMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public Optional<Pedido> buscarPorId(PedidoId id) {
        return jpa.findConLineasById(id.valor()).map(mapper::aDominio);
    }

    @Override
    public void guardar(Pedido pedido) {
        PedidoJpaEntity entidad = jpa.findById(pedido.id().valor())
                .orElseGet(PedidoJpaEntity::new);
        mapper.copiarEn(pedido, entidad);   // copia campos + reemplaza líneas + version
        jpa.save(entidad);
    }
}
```

> **Detalle importante:** `copiarEn` copia también la `version` que trae el
> dominio. Si la versión de la BD ya es mayor, Hibernate lanzará
> `OptimisticLockException` al hacer *flush*. Lo vemos en la sección 7.

---

## 6. Lazy loading: por qué choca con el hexágono

El *lazy loading* de JPA funciona mientras la sesión (el
`EntityManager`) está abierta. Fuera de ella:

```
org.hibernate.LazyInitializationException:
  failed to lazily initialize a collection of role: PedidoJpaEntity.lineas
```

### ¿Por qué choca?

- El dominio **no debe saber** si una colección está cargada o no. Un
  `pedido.total()` no puede depender de que haya una transacción abierta.
- Si mapeas a dominio **dentro** del adaptador (como hacemos), el mapper
  recorre `lineas` y fuerza la carga: o hay transacción o explota.
- La "solución" tentadora, `spring.jpa.open-in-view=true` (activada por
  defecto en Spring Boot, con un *warning* al arrancar), mantiene la sesión
  abierta hasta que se pinta la respuesta HTTP: consultas escondidas en el
  controlador, N+1 y conexiones retenidas.

### Good: el agregado se carga entero y explícitamente

```java
interface PedidoJpaRepository extends JpaRepository<PedidoJpaEntity, UUID> {

    @Query("select p from PedidoJpaEntity p left join fetch p.lineas where p.id = :id")
    Optional<PedidoJpaEntity> findConLineasById(@Param("id") UUID id);
}
```

```properties
# application.properties
spring.jpa.open-in-view=false
```

Reglas prácticas:

1. **Desactiva `open-in-view`** en proyectos hexagonales. Siempre.
2. El adaptador carga el **agregado completo** con `join fetch` o
   `@EntityGraph`. Si el agregado es tan grande que no puedes cargarlo
   entero, el problema es el **diseño del agregado**, no JPA.
3. Para pantallas que necesitan datos de varios agregados, **no** cargues
   agregados: usa una consulta de lectura (bloque 2, CQRS ligero).

---

## 7. Migraciones con Flyway

`spring.jpa.hibernate.ddl-auto=update` es estupendo para una demo y un
peligro en producción: no versiona, no se revisa en un *pull request*, no
borra columnas, y cada entorno acaba con un esquema distinto.

Flyway ejecuta scripts SQL **versionados e inmutables** en orden y apunta en
la tabla `flyway_schema_history` cuáles se han aplicado.

```
src/main/resources/db/migration/
├── V1__crear_tablas_pedidos.sql
├── V2__anadir_version_a_pedidos.sql
└── V3__crear_tabla_outbox.sql
```

`V1__crear_tablas_pedidos.sql`:

```sql
CREATE TABLE pedidos (
    id          UUID         PRIMARY KEY,
    cliente_id  UUID         NOT NULL,
    estado      VARCHAR(20)  NOT NULL,
    creado_en   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE lineas_pedido (
    id               BIGSERIAL      PRIMARY KEY,
    pedido_id        UUID           NOT NULL REFERENCES pedidos(id) ON DELETE CASCADE,
    posicion         INT            NOT NULL,
    producto_sku     VARCHAR(64)    NOT NULL,
    cantidad         INT            NOT NULL CHECK (cantidad > 0),
    precio_unitario  NUMERIC(12,2)  NOT NULL,
    moneda           CHAR(3)        NOT NULL
);

CREATE INDEX idx_lineas_pedido_pedido ON lineas_pedido(pedido_id);
CREATE INDEX idx_pedidos_cliente ON pedidos(cliente_id);
```

`V2__anadir_version_a_pedidos.sql`:

```sql
ALTER TABLE pedidos ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

Configuración:

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.locations=classpath:db/migration
```

Con `ddl-auto=validate`, Hibernate **comprueba** al arrancar que las
entidades casan con el esquema que ha dejado Flyway, pero no lo toca.

> **Trampa:** editar un script ya aplicado. Flyway guarda un *checksum* y
> el arranque fallará en todos los entornos donde ya se ejecutó. Un script
> publicado es **inmutable**: si te equivocaste, escribe `V4__corregir...`.

> **Trampa:** migraciones que rompen la versión anterior de la app durante
> un despliegue sin parada (renombrar una columna de golpe). Usa el patrón
> *expand/contract*: añade la nueva, copia, despliega, y borra la vieja en
> una migración posterior.

¿Y el hexágono? Las migraciones son **infraestructura pura**: viven junto al
adaptador de persistencia (en `resources`), nunca las referencia el dominio.

---

## 8. Bloqueo optimista con `@Version`

### El problema

```
 t0  Ana carga Pedido 42 (v=3, BORRADOR)
 t1  Luis carga Pedido 42 (v=3, BORRADOR)
 t2  Ana confirma        → UPDATE ... estado=CONFIRMADO
 t3  Luis cancela        → UPDATE ... estado=CANCELADO     ← pisa a Ana
```

Sin control, gana el último que escribe (*lost update*). El pedido
confirmado (y quizá ya cobrado) aparece cancelado.

### La solución JPA

Con `@Version`, Hibernate genera:

```sql
UPDATE pedidos SET estado = ?, version = 4 WHERE id = ? AND version = 3
```

Si el `UPDATE` afecta a 0 filas, lanza `OptimisticLockException` (Spring la
traduce a `ObjectOptimisticLockingFailureException`).

### ¿Cómo se entera el dominio de la versión?

Con modelos separados hay un problema sutil: el adaptador hace
`findById` + copiar + `save` en la **misma** transacción, así que la entidad
JPA siempre tiene la versión más reciente. Si no llevamos la versión que
**leyó el usuario** hasta el `UPDATE`, el bloqueo optimista no protege nada
entre peticiones HTTP.

Solución: el dominio transporta la versión como **dato opaco** (`Long
version`, sección 3) y el mapper la copia de vuelta y la compara:

```java
@Component
class PedidoPersistenceMapper {

    void copiarEn(Pedido pedido, PedidoJpaEntity entidad) {
        if (pedido.version() != null && entidad.getVersion() != null
                && !pedido.version().equals(entidad.getVersion())) {
            throw new ConflictoDeConcurrenciaException(pedido.id(),
                    pedido.version(), entidad.getVersion());
        }
        entidad.setId(pedido.id().valor());
        entidad.setClienteId(pedido.clienteId().valor());
        entidad.setEstado(pedido.estado().name());
        entidad.setCreadoEn(pedido.creadoEn());
        entidad.reemplazarLineas(pedido.lineas().stream().map(this::aJpa).toList());
    }
    // aDominio(...), aJpa(LineaPedido) ...
}
```

> **¿Por qué comparo a mano?** Porque Hibernate **ignora** un `setVersion()`
> manual sobre una entidad gestionada: usa la versión que cargó. La
> comprobación explícita cubre el caso "el usuario leyó v3 hace dos minutos";
> `@Version` cubre el caso "dos transacciones simultáneas entre mi `SELECT` y
> mi `UPDATE`". Las dos juntas cierran el agujero.

`ConflictoDeConcurrenciaException` puede vivir en `application.port.out`
(es parte del contrato del puerto `PedidoRepository`) o en
`domain.exception` extendiendo `PedidoException`. El `ControllerAdvice` del
día 3 la traduce a **HTTP 409 Conflict**. Para que el cliente envíe la
versión que leyó, se expone como cabecera `ETag` y se exige `If-Match`.

```
 Cliente               Controller            Servicio            Adaptador          BD
   │ GET /pedidos/42       │                    │                    │               │
   │──────────────────────►│ ETag: "3"          │                    │               │
   │ POST /42/confirmar    │                    │                    │               │
   │ If-Match: "3"         │  cmd(v=3)          │ buscar + confirmar │ v_bd=4 ≠ 3    │
   │──────────────────────►│───────────────────►│───────────────────►│──► 409        │
```

| Estrategia                    | Cuándo                                           | Coste                          |
|-------------------------------|--------------------------------------------------|--------------------------------|
| Optimista (`@Version`)        | Conflictos raros, operaciones web                | Reintentar o avisar al usuario |
| Pesimista (`SELECT FOR UPDATE`) | Conflictos frecuentes y operaciones cortas     | Bloqueos, riesgo de *deadlock* |
| Ninguna                       | Datos que solo escribe un proceso                | *Lost updates* si te equivocas |

---

## 9. Testing

### 9.1. El dominio, sin base de datos

```java
class PedidoReconstitucionTest {

    @Test
    void reconstituir_no_aplica_reglas_de_creacion() {
        var id = PedidoId.nuevo();
        var pedido = Pedido.reconstituir(id, new ClienteId(UUID.randomUUID()),
                List.of(), EstadoPedido.ENVIADO,
                Instant.parse("2020-01-01T00:00:00Z"), 7L);

        assertThat(pedido.id()).isEqualTo(id);
        assertThat(pedido.estado()).isEqualTo(EstadoPedido.ENVIADO);
        assertThat(pedido.version()).isEqualTo(7L);
    }
}
```

### 9.2. El adaptador, contra PostgreSQL real con Testcontainers

H2 no es PostgreSQL: tipos `UUID`, `TIMESTAMPTZ`, `CHECK` y los *locks* se
comportan distinto. Para el adaptador de persistencia, **la base de datos
real**.

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import com.curso.pedidos.domain.model.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PedidoPersistenceAdapter.class, PedidoPersistenceMapper.class})
class PedidoPersistenceAdapterIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PedidoPersistenceAdapter adapter;

    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void guarda_y_recupera_el_agregado_completo() {
        var pedido = Pedido.crear(new ClienteId(UUID.randomUUID()), Instant.now());
        pedido.anadirLinea(new ProductoId("SKU-1"), new Cantidad(2),
                           new Dinero(new BigDecimal("10.00"), EUR));

        adapter.guardar(pedido);
        var recuperado = adapter.buscarPorId(pedido.id()).orElseThrow();

        assertThat(recuperado.lineas()).hasSize(1);
        assertThat(recuperado.total().importe()).isEqualByComparingTo("20.00");
        assertThat(recuperado.version()).isNotNull();
    }

    @Test
    void detecta_conflicto_si_la_version_leida_esta_obsoleta() {
        var pedido = Pedido.crear(new ClienteId(UUID.randomUUID()), Instant.now());
        adapter.guardar(pedido);

        var copiaAna  = adapter.buscarPorId(pedido.id()).orElseThrow();
        var copiaLuis = adapter.buscarPorId(pedido.id()).orElseThrow();

        copiaAna.anadirLinea(new ProductoId("SKU-1"), new Cantidad(1),
                             new Dinero(BigDecimal.ONE, EUR));
        adapter.guardar(copiaAna);
        // En @DataJpaTest todo va en una única transacción: forzamos el
        // flush para que la versión suba en BD y vaciamos la caché de 1er nivel
        em.flush();
        em.clear();

        copiaLuis.cancelar();
        assertThatThrownBy(() -> adapter.guardar(copiaLuis))
                .isInstanceOf(ConflictoDeConcurrenciaException.class);
    }
}
```

(Añade `@Autowired TestEntityManager em;` como campo del test,
`org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager`.)

> Lo importante es la idea: **sin flush, la versión no ha cambiado en BD** y
> el test daría un falso verde (o un falso rojo, según el caso).

Flyway se ejecuta automáticamente al arrancar el contexto del test, así que
este test **también valida las migraciones** contra el mismo motor que
producción. Dos pájaros de un tiro.

---

## 10. Resumen

| Decisión                     | Opción recomendada en hexagonal                         | Por qué                                   |
|------------------------------|---------------------------------------------------------|-------------------------------------------|
| Modelo de persistencia       | Separado del dominio, con mapper                        | Dominio libre de JPA y de su ciclo de vida |
| Rehidratación                | `Pedido.reconstituir(...)`, distinto de `crear(...)`    | No disparar reglas ni eventos al leer     |
| Identidad                    | `PedidoId(UUID)` generado en el dominio (UUIDv7 si escala) | Identidad desde el primer momento       |
| Granularidad                 | Un repositorio por agregado, se guarda entero           | Invariantes protegidas                    |
| Lazy loading                 | `open-in-view=false`, `join fetch` en el adaptador      | El dominio no sabe de sesiones            |
| Esquema                      | Flyway + `ddl-auto=validate`                            | Esquema versionado y revisable            |
| Concurrencia                 | `@Version` + versión opaca en dominio + 409/ETag        | Evita *lost updates* entre peticiones     |
| Tests del adaptador          | Testcontainers con PostgreSQL                           | Mismo motor que producción                |

---

## Ejercicio

Añade a `Pedido` y al adaptador el soporte para una nueva columna
`direccion_envio` (texto, obligatoria a partir de ahora, pero hay pedidos
antiguos sin ella).

1. Escribe la migración Flyway.
2. Modela la dirección en el dominio como *value object*.
3. Adapta el mapper.
4. Escribe un test de integración que guarde y recupere un pedido con
   dirección.

**Pista:** "obligatoria a partir de ahora" + "hay filas antiguas" = la
columna no puede ser `NOT NULL` en la primera migración… salvo que le des un
valor por defecto o rellenes antes.

<details><summary>Solución</summary>

```sql
-- V4__anadir_direccion_envio.sql
ALTER TABLE pedidos ADD COLUMN direccion_envio VARCHAR(500);
UPDATE pedidos SET direccion_envio = 'DESCONOCIDA' WHERE direccion_envio IS NULL;
ALTER TABLE pedidos ALTER COLUMN direccion_envio SET NOT NULL;
```

```java
public record DireccionEnvio(String valor) {
    public DireccionEnvio {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("La dirección de envío es obligatoria");
        }
    }
}
```

En el mapper: `entidad.setDireccionEnvio(pedido.direccionEnvio().valor())`
y, al rehidratar, `new DireccionEnvio(entidad.getDireccionEnvio())`. El test
es una copia de `guarda_y_recupera_el_agregado_completo` comprobando
`recuperado.direccionEnvio()`.

Discusión: `'DESCONOCIDA'` es un valor centinela. Alternativa más honesta:
dejar la columna *nullable* y modelar `Optional<DireccionEnvio>` en el
dominio solo para pedidos anteriores a la fecha X. Depende del negocio.

</details>

---

## Referencias

- Robert C. Martin, *Clean Architecture* (2017), cap. 30 "The Database Is a Detail".
- Eric Evans, *Domain-Driven Design* (2003), cap. 6: Aggregates, Repositories, Factories.
- Martin Fowler, *Optimistic Offline Lock* y *Data Mapper* en el catálogo PoEAA: https://martinfowler.com/eaaCatalog/
- Spring Data JPA, referencia: https://docs.spring.io/spring-data/jpa/reference/
- Flyway, documentación: https://documentation.red-gate.com/flyway
- Testcontainers para Java: https://java.testcontainers.org/
- Vlad Mihalcea, blog sobre JPA, Hibernate, bloqueo optimista y rendimiento: https://vladmihalcea.com/
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023), cap. sobre persistencia; repo https://github.com/thombergs/buckpal

---

## Mantra

> *"Crear no es reconstituir, guardar es guardar el agregado entero, y la
> versión que importa es la que leyó el usuario."*
