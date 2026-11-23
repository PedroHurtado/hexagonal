# Día 7 — Bloque 3: Vertical Slice Architecture (versión extrema: un fichero por feature)

> *"Minimize coupling between slices, and maximize coupling in a slice."*
> — Jimmy Bogard, *Vertical Slice Architecture* (2018)

---

## 1. El problema en una frase

En una arquitectura por capas (y hexagonal y Clean lo son, aunque las capas
sean concéntricas), **añadir una feature obliga a tocar todas las capas**:
puerto de entrada, comando, servicio, DTO, controller, configuración… El
código que cambia junto **vive separado**.

Vertical Slice Architecture (VSA) le da la vuelta: **organiza el código por
feature (por petición), no por capa técnica**. Lo que cambia junto, vive junto.

---

## 2. Qué dice Jimmy Bogard

Jimmy Bogard (autor de MediatR y AutoMapper en .NET) publicó en 2018 el post
*Vertical Slice Architecture*. Sus ideas principales, resumidas:

1. En una arquitectura por capas, cada petición atraviesa todas las capas, y
   cambiar una feature implica tocar código en muchos sitios. Las abstracciones
   compartidas (repositorios genéricos, servicios gordos) acaban acoplando
   features que no tienen nada que ver.
2. En lugar de eso, **cada petición es una "rebanada" vertical** que va desde
   la entrada hasta la base de datos. Cada slice decide **por sí misma** cómo
   resolver su petición: una puede usar el ORM, otra SQL a mano, otra un
   procedimiento almacenado.
3. Regla de oro: **minimizar el acoplamiento entre slices y maximizar el
   acoplamiento dentro de una slice**.
4. Añadir una feature = **añadir código**, no modificar código compartido.
5. No elimina el diseño: cuando el handler de una slice crece o la lógica se
   repite entre slices, se **refactoriza** (por ejemplo, empujando lógica hacia
   el modelo de dominio). VSA asume un equipo que sabe reconocer *code smells*.

```
   ARQUITECTURA POR CAPAS                  VERTICAL SLICES
   (rebanadas horizontales)                (rebanadas verticales)

   Crear Añadir Confirmar Cancelar          Crear Añadir Confirmar Cancelar
   ┌─────┬──────┬────────┬───────┐        ┌─────┐┌──────┐┌────────┐┌───────┐
   │ ░░░ │ ░░░░ │ ░░░░░░ │ ░░░░░ │ Web    │ ░░░ ││ ░░░░ ││ ░░░░░░ ││ ░░░░░ │
   ├─────┼──────┼────────┼───────┤        │     ││      ││        ││       │
   │ ▒▒▒ │ ▒▒▒▒ │ ▒▒▒▒▒▒ │ ▒▒▒▒▒ │ App    │ ▒▒▒ ││ ▒▒▒▒ ││ ▒▒▒▒▒▒ ││ ▒▒▒▒▒ │
   ├─────┼──────┼────────┼───────┤        │     ││      ││        ││       │
   │ ▓▓▓ │ ▓▓▓▓ │ ▓▓▓▓▓▓ │ ▓▓▓▓▓ │ Dom    │ ▓▓▓ ││ ▓▓▓▓ ││ ▓▓▓▓▓▓ ││ ▓▓▓▓▓ │
   ├─────┼──────┼────────┼───────┤        │     ││      ││        ││       │
   │ ███ │ ████ │ ██████ │ █████ │ Infra  │ ███ ││ ████ ││ ██████ ││ █████ │
   └─────┴──────┴────────┴───────┘        └─────┘└──────┘└────────┘└───────┘
     ▲ acoplamiento horizontal              ▲ acoplamiento vertical
     (capa compartida)                      (feature autocontenida)
```

---

## 3. El espectro de VSA y la versión extrema

VSA no es una sola cosa. Hay un espectro:

| Variante                         | Cómo se ve                                                          |
|----------------------------------|---------------------------------------------------------------------|
| **Paquete por feature suave**    | `features/confirmarpedido/` con 4-6 clases (controller, handler, dto, repo) |
| **Paquete por feature + dominio**| Lo anterior + un paquete `domain/` compartido con agregados          |
| **Extrema: 1 fichero por feature** | `features/confirmarpedido/ConfirmarPedido.java` con todo dentro, como clases anidadas |

Hoy vamos a la **versión extrema** a propósito: es la que más contrasta con
hexagonal y la que obliga a pensar qué estamos ganando y perdiendo.

---

## 4. Bad: la "capa de servicios" que todo lo acopla

```java
@Service
@Transactional
public class PedidoService {                        // 1.400 líneas en la vida real

    private final PedidoRepository repo;            // repositorio "para todo"
    private final NotificacionService notificaciones;
    private final StockService stock;
    private final PagoService pagos;
    private final PedidoMapper mapper;

    public PedidoDto crear(CrearPedidoDto dto) { ... }
    public PedidoDto anadirLinea(UUID id, LineaDto dto) { ... }
    public PedidoDto confirmar(UUID id) { ... }
    public PedidoDto cancelar(UUID id, String motivo) { ... }
    public Page<PedidoResumenDto> buscar(FiltroDto f, Pageable p) { ... }
    public InformeVentasDto informe(LocalDate desde, LocalDate hasta) { ... }
    // ... 25 métodos más
}
```

### ¿Qué duele?

- **Conflictos de merge**: cinco devs, cinco features, un fichero.
- Las dependencias del servicio son la **unión** de las de todos sus métodos:
  para testear `confirmar` necesitas mockear `PagoService`, que no usa.
- `PedidoDto` sirve para todo y por eso no encaja bien en nada (campos `null`
  según el caso).
- Las consultas (`buscar`, `informe`) pasan por el mismo modelo de dominio que
  las escrituras aunque no necesiten invariantes.

Ojo: hexagonal **bien hecha** ya evita parte de esto (un servicio por caso de
uso). VSA lleva la idea al límite: **ni siquiera comparten capa**.

---

## 5. Árbol de ficheros de TODA la aplicación en VSA extremo

```
pedidos-vsa/
├── pom.xml
└── src
    ├── main
    │   ├── java/com/curso/pedidos
    │   │   ├── PedidosApplication.java
    │   │   ├── features
    │   │   │   ├── crearpedido/CrearPedido.java          ← 1 fichero = 1 feature
    │   │   │   ├── anadirlinea/AnadirLinea.java
    │   │   │   ├── confirmarpedido/ConfirmarPedido.java
    │   │   │   ├── consultarpedido/ConsultarPedido.java
    │   │   │   └── cancelarpedido/CancelarPedido.java
    │   │   └── shared                                    ← mínimo imprescindible
    │   │       └── ClockConfig.java
    │   └── resources
    │       ├── application.yml
    │       └── db/migration/V1__pedidos.sql
    └── test/java/com/curso/pedidos
        ├── features
        │   ├── crearpedido/CrearPedidoTest.java
        │   ├── anadirlinea/AnadirLineaTest.java
        │   ├── confirmarpedido/ConfirmarPedidoTest.java
        │   ├── consultarpedido/ConsultarPedidoTest.java
        │   └── cancelarpedido/CancelarPedidoTest.java
        └── shared/PostgresTest.java                      ← anotación compuesta de test
```

Cinco features, **cinco ficheros de producción** más uno compartido. Abre la
carpeta `features/` y sabes qué hace la aplicación sin leer código: es lo que
Uncle Bob llama *Screaming Architecture*, curiosamente.

---

## 6. Good: `ConfirmarPedido.java` completo

### 6.1 Esquema de base de datos (el mismo para las tres arquitecturas)

```sql
-- src/main/resources/db/migration/V1__pedidos.sql
CREATE TABLE pedidos (
    id             UUID         PRIMARY KEY,
    cliente_id     UUID         NOT NULL,
    estado         VARCHAR(20)  NOT NULL,
    creado_en      TIMESTAMPTZ  NOT NULL,
    confirmado_en  TIMESTAMPTZ,
    version        BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE lineas_pedido (
    pedido_id        UUID           NOT NULL REFERENCES pedidos (id),
    sku              VARCHAR(40)    NOT NULL,
    cantidad         INT            NOT NULL CHECK (cantidad > 0),
    precio_unitario  NUMERIC(12, 2) NOT NULL,
    moneda           CHAR(3)        NOT NULL,
    PRIMARY KEY (pedido_id, sku)
);
```

### 6.2 El fichero de la feature

```java
package com.curso.pedidos.features.confirmarpedido;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feature: confirmar un pedido.
 *
 * Reglas de negocio:
 *  - solo se confirma un pedido en estado BORRADOR;
 *  - no se confirma un pedido sin líneas.
 *
 * Todo lo que necesita esta feature vive en este fichero.
 */
public final class ConfirmarPedido {

    private ConfirmarPedido() {
    }

    // ───────────────────────── 1. Contrato ─────────────────────────

    public record Command(UUID pedidoId) {
    }

    public record Response(UUID pedidoId, String estado, BigDecimal total,
                           String moneda, Instant confirmadoEn) {
    }

    /** Evento que esta feature publica. Otras slices pueden escucharlo. */
    public record PedidoConfirmado(UUID pedidoId, Instant ocurridoEn) {
    }

    // ───────────────────────── 2. Validación ───────────────────────

    static final class Validator {

        private Validator() {
        }

        static void validar(Command command) {
            if (command == null || command.pedidoId() == null) {
                throw new PeticionInvalida("pedidoId es obligatorio");
            }
        }
    }

    // ───────────────────────── 3. Endpoint HTTP ────────────────────

    @RestController
    public static class Endpoint {

        private final Handler handler;

        public Endpoint(Handler handler) {
            this.handler = handler;
        }

        @PostMapping("/pedidos/{id}/confirmacion")
        public Response confirmar(@PathVariable UUID id) {
            return handler.handle(new Command(id));
        }

        // Los @ExceptionHandler de un @RestController solo aplican a ESTE controller.
        @ExceptionHandler(NoEncontrado.class)
        ProblemDetail noEncontrado(NoEncontrado e) {
            return problema(HttpStatus.NOT_FOUND, "Pedido no encontrado", e);
        }

        @ExceptionHandler(Rechazado.class)
        ProblemDetail rechazado(Rechazado e) {
            return problema(HttpStatus.CONFLICT, "No se puede confirmar el pedido", e);
        }

        @ExceptionHandler(PeticionInvalida.class)
        ProblemDetail invalida(PeticionInvalida e) {
            return problema(HttpStatus.BAD_REQUEST, "Petición inválida", e);
        }

        private static ProblemDetail problema(HttpStatus status, String titulo, RuntimeException e) {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
            p.setTitle(titulo);
            return p;
        }
    }

    // ───────────────────────── 4. Handler (lógica + datos) ─────────

    @Component
    public static class Handler {

        private final JdbcClient jdbc;
        private final ApplicationEventPublisher eventos;
        private final Clock clock;

        public Handler(JdbcClient jdbc, ApplicationEventPublisher eventos, Clock clock) {
            this.jdbc = jdbc;
            this.eventos = eventos;
            this.clock = clock;
        }

        @Transactional
        public Response handle(Command command) {
            Validator.validar(command);
            UUID id = command.pedidoId();

            // Bloqueo pesimista de la fila: dos confirmaciones simultáneas no se pisan.
            String estado = jdbc.sql("SELECT estado FROM pedidos WHERE id = :id FOR UPDATE")
                    .param("id", id)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new NoEncontrado(id));

            if (!"BORRADOR".equals(estado)) {
                throw new Rechazado("Solo se puede confirmar un pedido en BORRADOR (estado actual: "
                        + estado + ")");
            }

            Resumen resumen = jdbc.sql("""
                            SELECT COUNT(*)                                     AS lineas,
                                   COALESCE(SUM(cantidad * precio_unitario), 0) AS total,
                                   MIN(moneda)                                  AS moneda
                              FROM lineas_pedido
                             WHERE pedido_id = :id
                            """)
                    .param("id", id)
                    .query((rs, fila) -> new Resumen(
                            rs.getInt("lineas"),
                            rs.getBigDecimal("total"),
                            rs.getString("moneda")))
                    .single();

            if (resumen.lineas() == 0) {
                throw new Rechazado("No se puede confirmar un pedido vacío");
            }

            Instant ahora = clock.instant();
            jdbc.sql("""
                            UPDATE pedidos
                               SET estado = 'CONFIRMADO', confirmado_en = :ahora, version = version + 1
                             WHERE id = :id
                            """)
                    .param("ahora", Timestamp.from(ahora))
                    .param("id", id)
                    .update();

            eventos.publishEvent(new PedidoConfirmado(id, ahora));

            return new Response(id, "CONFIRMADO", resumen.total(), resumen.moneda(), ahora);
        }

        private record Resumen(int lineas, BigDecimal total, String moneda) {
        }
    }

    // ───────────────────────── 5. Errores de la feature ────────────

    static final class NoEncontrado extends RuntimeException {
        NoEncontrado(UUID id) {
            super("No existe el pedido " + id);
        }
    }

    static final class Rechazado extends RuntimeException {
        Rechazado(String motivo) {
            super(motivo);
        }
    }

    static final class PeticionInvalida extends RuntimeException {
        PeticionInvalida(String motivo) {
            super(motivo);
        }
    }
}
```

Unas 185 líneas con imports y comentarios (unas 120 de código efectivo). **Una sola pestaña del IDE** para
entender la feature entera: HTTP, validación, reglas, SQL, transacción, evento
y errores.

### 6.3 Lo único compartido

```java
package com.curso.pedidos.shared;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

`JdbcClient` lo autoconfigura Spring Boot 3.2+ en cuanto hay un `DataSource`.
`ApplicationEventPublisher` viene de serie.

### 6.4 Detalles técnicos que hay que saber (y que se preguntan en clase)

- **¿Spring escanea clases anidadas?** Sí, siempre que sean **`static`**
  (clases independientes). Una clase interna no estática no se puede
  instanciar sin la externa y no se registra.
- **Nombres de bean**: el generador por defecto usa el nombre corto
  `ConfirmarPedido.Handler` → bean `confirmarPedido.Handler`. Cinco features con
  un `Handler` anidado **no colisionan**.
- **`@Transactional`** funciona porque `Handler` es pública, no final, y el
  método es público (proxy CGLIB).
- **Los `@ExceptionHandler` locales** del `Endpoint` solo afectan a este
  controller: cada feature decide su traducción de errores sin un advice global.
- **El `Validator`** aquí es casi trivial (Spring ya rechaza un UUID mal
  formado con 400). En `AnadirLinea` sí tiene chicha: cantidad > 0, SKU no
  vacío, precio positivo. Se puede usar Bean Validation (`@Valid` + `@NotNull`
  en el record `Command`) en vez de un validador a mano.

---

## 7. ¿Y el dominio? "Refactor hacia el dominio"

Fíjate en lo que ha pasado: **no hay clase `Pedido`**. Las reglas ("solo desde
BORRADOR", "no vacío") viven en el handler como `if` sobre un `String` y un
`COUNT(*)`. Para una feature aislada es perfectamente razonable. El problema
llega cuando las reglas **se repiten**.

### 7.1 El olor: la misma regla en varias slices

```java
// En CancelarPedido.Handler
if ("ENVIADO".equals(estado) || "CANCELADO".equals(estado)) {
    throw new Rechazado("No se puede cancelar un pedido en estado " + estado);
}

// En AnadirLinea.Handler
if (!"BORRADOR".equals(estado)) {
    throw new Rechazado("No se pueden añadir líneas a un pedido " + estado);
}

// En ConfirmarPedido.Handler
if (!"BORRADOR".equals(estado)) { ... }
```

La máquina de estados del pedido está **desperdigada en tres ficheros**. El día
que negocio diga "un pedido PAGADO también se puede cancelar con reembolso",
hay que encontrar todos los `if`.

### 7.2 Primer paso: extraer el concepto a `shared/domain`

```java
package com.curso.pedidos.shared.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum EstadoPedido {
    BORRADOR, CONFIRMADO, PAGADO, ENVIADO, CANCELADO;

    private static final Map<EstadoPedido, Set<EstadoPedido>> TRANSICIONES = Map.of(
            BORRADOR,   EnumSet.of(CONFIRMADO, CANCELADO),
            CONFIRMADO, EnumSet.of(PAGADO, CANCELADO),
            PAGADO,     EnumSet.of(ENVIADO, CANCELADO),
            ENVIADO,    EnumSet.noneOf(EstadoPedido.class),
            CANCELADO,  EnumSet.noneOf(EstadoPedido.class));

    public boolean puedePasarA(EstadoPedido destino) {
        return TRANSICIONES.get(this).contains(destino);
    }

    public boolean admiteLineas() {
        return this == BORRADOR;
    }
}
```

Y en el handler:

```java
EstadoPedido actual = EstadoPedido.valueOf(estado);
if (!actual.puedePasarA(EstadoPedido.CONFIRMADO)) {
    throw new Rechazado("No se puede confirmar un pedido en estado " + actual);
}
```

### 7.3 Segundo paso (si sigue creciendo): un agregado de verdad

Cuando aparecen más invariantes compartidas (total con descuentos, límite de
líneas, stock), el siguiente paso natural es un **agregado `Pedido`** en
`shared/domain` y que las slices de **escritura** lo carguen, invoquen
`pedido.confirmar()` y lo guarden. Las slices de **lectura** (`ConsultarPedido`)
siguen con SQL directo a un DTO: no necesitan invariantes.

```
         ANTES (VSA puro)                    DESPUÉS (VSA + dominio compartido)

  ┌──────────┐ ┌──────────┐ ┌──────────┐   ┌──────────┐ ┌──────────┐ ┌──────────┐
  │ Anadir   │ │Confirmar │ │ Cancelar │   │ Anadir   │ │Confirmar │ │ Cancelar │
  │ if(...)  │ │ if(...)  │ │ if(...)  │   │ handler  │ │ handler  │ │ handler  │
  │ SQL      │ │ SQL      │ │ SQL      │   └────┬─────┘ └────┬─────┘ └────┬─────┘
  └──────────┘ └──────────┘ └──────────┘        └───────┬────┴────────────┘
                                                        ▼
                                              ┌───────────────────┐
                                              │ shared/domain     │
                                              │ Pedido.confirmar()│
                                              │ EstadoPedido      │
                                              └───────────────────┘
```

> Esto es exactamente lo que Bogard describe: empezar simple dentro de la
> slice y **refactorizar hacia el dominio cuando la lógica lo pide**, no por
> anticipado. Y fíjate: si además metes una interfaz de repositorio delante del
> agregado… acabas de dibujar **un mini hexágono compartido** por las slices.
> Lo retomamos en los híbridos del bloque 4.

> Trampa: **no extraer nunca**. VSA sin refactor degenera en *Transaction
> Script* duplicado (Fowler, *PoEAA*). Bogard lo dice claro: VSA requiere que el
> equipo sepa reconocer los olores y refactorizar.

> Trampa inversa: **extraer a la primera**. Si a la segunda feature ya tienes
> `shared/BaseHandler`, `shared/GenericRepository<T>` y `shared/AbstractCommand`,
> has reconstruido la capa horizontal que querías evitar.

---

## 8. Testing en VSA: el test de integración por feature

En VSA el test principal **no es unitario**: es un test de la slice **de punta a
punta** (HTTP → SQL real). La lógica de una slice extrema es básicamente
orquestación de SQL; mockear `JdbcClient` sería testear que llamas a mocks.

### 8.1 Dependencias de test

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

### 8.2 `ConfirmarPedidoTest`

```java
package com.curso.pedidos.features.confirmarpedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@RecordApplicationEvents
class ConfirmarPedidoTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ApplicationEvents eventos;

    @BeforeEach
    void limpiar() {
        jdbc.sql("DELETE FROM lineas_pedido").update();
        jdbc.sql("DELETE FROM pedidos").update();
    }

    @Test
    void confirma_un_pedido_en_borrador_con_lineas() throws Exception {
        UUID id = pedido("BORRADOR");
        linea(id, "SKU-1", 2, "10.50");
        linea(id, "SKU-2", 1, "5.00");

        mvc.perform(post("/pedidos/{id}/confirmacion", id))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.estado").value("CONFIRMADO"))
           .andExpect(jsonPath("$.total").value(26.0))
           .andExpect(jsonPath("$.moneda").value("EUR"));

        assertThat(estadoEnBd(id)).isEqualTo("CONFIRMADO");
        assertThat(eventos.stream(ConfirmarPedido.PedidoConfirmado.class))
                .singleElement()
                .extracting(ConfirmarPedido.PedidoConfirmado::pedidoId)
                .isEqualTo(id);
    }

    @Test
    void devuelve_404_si_no_existe() throws Exception {
        mvc.perform(post("/pedidos/{id}/confirmacion", UUID.randomUUID()))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.title").value("Pedido no encontrado"));
    }

    @Test
    void devuelve_409_si_esta_vacio_y_no_cambia_nada() throws Exception {
        UUID id = pedido("BORRADOR");

        mvc.perform(post("/pedidos/{id}/confirmacion", id))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.detail").value("No se puede confirmar un pedido vacío"));

        assertThat(estadoEnBd(id)).isEqualTo("BORRADOR");
        assertThat(eventos.stream(ConfirmarPedido.PedidoConfirmado.class)).isEmpty();
    }

    @Test
    void devuelve_409_si_ya_estaba_confirmado() throws Exception {
        UUID id = pedido("CONFIRMADO");
        linea(id, "SKU-1", 1, "1.00");

        mvc.perform(post("/pedidos/{id}/confirmacion", id))
           .andExpect(status().isConflict());
    }

    // ── helpers de datos: SQL directo, igual que la feature ──

    private UUID pedido(String estado) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO pedidos (id, cliente_id, estado, creado_en) VALUES (:id, :c, :e, :t)")
            .param("id", id).param("c", UUID.randomUUID()).param("e", estado)
            .param("t", Timestamp.from(Instant.now()))
            .update();
        return id;
    }

    private void linea(UUID pedidoId, String sku, int cantidad, String precio) {
        jdbc.sql("""
                INSERT INTO lineas_pedido (pedido_id, sku, cantidad, precio_unitario, moneda)
                VALUES (:p, :s, :c, :pr, 'EUR')
                """)
            .param("p", pedidoId).param("s", sku).param("c", cantidad)
            .param("pr", new BigDecimal(precio))
            .update();
    }

    private String estadoEnBd(UUID id) {
        return jdbc.sql("SELECT estado FROM pedidos WHERE id = :id")
                   .param("id", id).query(String.class).single();
    }
}
```

### 8.3 Coste y cómo mitigarlo

- Cada test levanta contexto + contenedor. Spring **cachea el contexto** entre
  clases de test con la misma configuración, así que el arranque se paga una
  vez. Para compartir también el contenedor, se extrae a una anotación
  compuesta (`shared/PostgresTest.java`) o a una configuración
  `@TestConfiguration` con un `@Bean @ServiceConnection` del contenedor.
- La pirámide se invierte: **pocos unitarios, muchos de integración por
  slice**. Es más lenta que la pirámide hexagonal del día 5 (segundos vs
  milisegundos por test) pero cada test cubre **lo que el usuario ve**.
- Los unitarios vuelven cuando hay lógica de dominio extraída
  (`EstadoPedidoTest`, `PedidoTest`) o validadores con reglas no triviales.

---

## 9. ¿Y MediatR en Java?

En .NET, VSA suele ir de la mano de **MediatR** (del propio Bogard): el
controller envía un `Command` a un mediador que localiza su `Handler` y aplica
*behaviors* transversales (logging, validación, transacción) en pipeline.

En Java **no hace falta**:

- El `Endpoint` inyecta directamente su `Handler`: Spring ya es el "localizador".
- Lo transversal se resuelve con lo que Spring ya da: `@Transactional`, AOP,
  Bean Validation, `HandlerInterceptor`, Micrometer.
- Si se quiere el patrón mediador explícito, existen librerías pequeñas de la
  comunidad (por ejemplo PipelinR) o se escribe un `CommandBus` de 30 líneas
  que resuelva handlers por tipo genérico. Añade indirección; valora si compensa.

> Trampa: añadir un mediador "porque VSA lleva MediatR". Bogard creó MediatR,
> pero VSA es una forma de **organizar** código, no una librería.

---

## 10. Tabla resumen

| Aspecto                       | VSA extremo (`ConfirmarPedido.java`)                          |
|-------------------------------|---------------------------------------------------------------|
| Eje de organización           | Feature / petición                                            |
| Ficheros por feature          | 1 (+ su test)                                                 |
| Dónde está la regla de negocio| En el handler (hasta que se repite → se extrae al dominio)    |
| Acceso a datos                | Directo (`JdbcClient`, JPA, lo que convenga a esa feature)    |
| Acoplamiento a Spring         | Total, y asumido                                              |
| Test principal                | Integración por slice: `@SpringBootTest` + MockMvc + Testcontainers |
| Coste de añadir feature       | Mínimo: un fichero nuevo, cero ficheros modificados           |
| Riesgo principal              | Duplicación de reglas y Transaction Script si no se refactoriza |

---

## Ejercicio

Escribe `ConsultarPedido.java` en VSA extremo: `GET /pedidos/{id}` que devuelva
id, estado, total y líneas. Pista: es una **lectura**; no necesitas dominio,
ni transacción de escritura, ni eventos.

<details><summary>Solución</summary>

```java
package com.curso.pedidos.features.consultarpedido;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

public final class ConsultarPedido {

    private ConsultarPedido() {
    }

    public record Query(UUID pedidoId) {
    }

    public record Linea(String sku, int cantidad, BigDecimal precioUnitario, BigDecimal subtotal) {
    }

    public record Response(UUID id, String estado, BigDecimal total, List<Linea> lineas) {
    }

    @RestController
    public static class Endpoint {
        private final Handler handler;

        public Endpoint(Handler handler) {
            this.handler = handler;
        }

        @GetMapping("/pedidos/{id}")
        public Response consultar(@PathVariable UUID id) {
            return handler.handle(new Query(id));
        }
    }

    @Component
    public static class Handler {
        private final JdbcClient jdbc;

        public Handler(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional(readOnly = true)
        public Response handle(Query q) {
            String estado = jdbc.sql("SELECT estado FROM pedidos WHERE id = :id")
                    .param("id", q.pedidoId()).query(String.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "No existe el pedido " + q.pedidoId()));

            List<Linea> lineas = jdbc.sql("""
                    SELECT sku, cantidad, precio_unitario, cantidad * precio_unitario AS subtotal
                      FROM lineas_pedido WHERE pedido_id = :id ORDER BY sku
                    """)
                    .param("id", q.pedidoId())
                    .query((rs, n) -> new Linea(rs.getString("sku"), rs.getInt("cantidad"),
                            rs.getBigDecimal("precio_unitario"), rs.getBigDecimal("subtotal")))
                    .list();

            BigDecimal total = lineas.stream().map(Linea::subtotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Response(q.pedidoId(), estado, total, lineas);
        }
    }
}
```

Observa: aquí `ResponseStatusException` simplifica los errores (una sola
salida de error). Cada slice decide; esa libertad es la gracia de VSA y
también su riesgo de inconsistencia (otra slice podría devolver errores con
otro formato). Una convención de equipo (siempre `ProblemDetail`) lo resuelve.
</details>

---

## Referencias

- Jimmy Bogard, *Vertical Slice Architecture* (2018):
  https://www.jimmybogard.com/vertical-slice-architecture/
- Robert C. Martin, *Clean Architecture* (2017), capítulo *Screaming
  Architecture* y capítulo 34 *The Missing Chapter* (de Simon Brown) sobre
  paquete por capa, por feature, puertos y adaptadores y por componente.
- Martin Fowler, *Patterns of Enterprise Application Architecture* (Transaction
  Script, Domain Model): https://martinfowler.com/eaaCatalog/
- Martin Fowler, *AnemicDomainModel*: https://martinfowler.com/bliki/AnemicDomainModel.html
- Martin Fowler, *CQRS*: https://martinfowler.com/bliki/CQRS.html
- Spring Framework, `JdbcClient` y acceso a datos:
  https://docs.spring.io/spring-framework/reference/data-access/jdbc.html
- Spring Framework testing (`@RecordApplicationEvents`, MockMvc):
  https://docs.spring.io/spring-framework/reference/testing.html
- Testcontainers: https://java.testcontainers.org/ ;
  Spring Boot (`@ServiceConnection`): https://docs.spring.io/spring-boot/index.html

---

## Mantra

> *"Lo que cambia junto, vive junto. Y cuando se repite, se refactoriza hacia
> el dominio — no antes."*
