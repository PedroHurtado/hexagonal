# Día 6 — Bloque 4: Eventos de dominio y consistencia

> *"Use a domain event to capture an occurrence of something that happened
> in the domain."*
> — Vaughn Vernon, *Implementing Domain-Driven Design* (2013)

---

## 1. El problema en una frase

Cuando un pedido se confirma tienen que pasar cosas en otros sitios
(reservar stock, mandar un email, avisar a logística). Si lo haces todo en
la misma transacción, acoplas y bloqueas; si lo haces fuera, **¿cómo
garantizas que no se pierde nada cuando algo falla a mitad?**

El escenario que hay que tener grabado:

```
 1. UPDATE pedidos SET estado='CONFIRMADO'   ✔
 2. COMMIT                                    ✔
 3. kafka.send(PedidoConfirmado)              ✘  (broker caído / el pod muere)
    → El pedido está confirmado y NADIE se ha enterado. Para siempre.
```

O al revés:

```
 1. kafka.send(PedidoConfirmado)              ✔
 2. COMMIT                                    ✘  (conflicto de @Version)
    → Logística prepara un envío de un pedido que NO está confirmado.
```

Este es el problema de la **escritura dual** (*dual write*): dos sistemas
(BD y broker) que no comparten transacción.

---

## 2. Eventos de dominio: el agregado los acumula

Un evento de dominio es un **hecho pasado**, inmutable, con nombre en
pasado y en lenguaje de negocio: `PedidoConfirmado`, no `PedidoUpdatedEvent`.

```java
package com.curso.pedidos.domain.event;

import java.time.Instant;

public sealed interface EventoDeDominio
        permits PedidoConfirmado, PedidoPagado, PedidoCancelado {
    Instant ocurridoEn();
}
```

```java
package com.curso.pedidos.domain.event;

import com.curso.pedidos.domain.model.PedidoId;
import java.time.Instant;

public record PedidoConfirmado(PedidoId pedidoId, Instant ocurridoEn) implements EventoDeDominio { }
public record PedidoPagado(PedidoId pedidoId, Instant ocurridoEn)     implements EventoDeDominio { }
public record PedidoCancelado(PedidoId pedidoId, Instant ocurridoEn)  implements EventoDeDominio { }
```

(Cada `record` en su fichero.)

El agregado **registra** los eventos cuando ocurre el cambio de estado, pero
**no los publica**: no sabe de brokers ni de transacciones.

```java
public final class Pedido {

    private final List<EventoDeDominio> eventos = new ArrayList<>();
    // ... resto de campos

    public void confirmar(Instant ahora) {
        if (estado != EstadoPedido.BORRADOR) {
            throw new TransicionDeEstadoInvalidaException("Solo se confirma un BORRADOR");
        }
        if (lineas.isEmpty()) {
            throw new PedidoException("No se puede confirmar un pedido vacío");
        }
        estado = EstadoPedido.CONFIRMADO;
        eventos.add(new PedidoConfirmado(id, ahora));
    }

    public void marcarComoPagado(Instant ahora) {
        if (estado != EstadoPedido.CONFIRMADO) {
            throw new TransicionDeEstadoInvalidaException("Solo se paga un pedido CONFIRMADO");
        }
        estado = EstadoPedido.PAGADO;
        eventos.add(new PedidoPagado(id, ahora));
    }

    /** Devuelve los eventos pendientes y vacía la lista. */
    public List<EventoDeDominio> extraerEventos() {
        List<EventoDeDominio> pendientes = List.copyOf(eventos);
        eventos.clear();
        return pendientes;
    }
}
```

> **Nota:** en días anteriores `confirmar()` no recibía parámetros. Al
> registrar eventos necesitamos el instante; pasarlo como argumento (obtenido
> de un `Clock` en el servicio) mantiene el dominio determinista y testeable.
> Alternativa equivalente: inyectar el `Clock` en la factoría del agregado.

> **Recordatorio (bloque 1):** `Pedido.reconstituir(...)` **no** registra
> eventos. Leer de la BD no es un hecho de negocio.

El servicio de aplicación publica **tras guardar**:

```java
public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {

    private final PedidoRepository repository;
    private final PublicadorDeEventos publicador;
    private final EjecutorTransaccional tx;
    private final Clock clock;

    // constructor...

    @Override
    public void ejecutar(ConfirmarPedidoCommand cmd) {
        tx.enTransaccion(() -> {
            Pedido pedido = repository.buscarPorId(cmd.pedidoId())
                    .orElseThrow(() -> new PedidoNoEncontradoException(cmd.pedidoId()));
            pedido.confirmar(clock.instant());
            repository.guardar(pedido);
            publicador.publicar(pedido.extraerEventos());   // ← misma transacción
        });
    }
}
```

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.event.EventoDeDominio;
import java.util.List;

public interface PublicadorDeEventos {
    void publicar(List<EventoDeDominio> eventos);
}
```

La gracia: el **puerto es el mismo** para las tres implementaciones que
vamos a ver. El caso de uso no cambia; cambia la fiabilidad.

---

## 3. Implementación 1 — Bad: publicar directamente al broker

```java
@Component
class KafkaPublicadorDeEventos implements PublicadorDeEventos {
    public void publicar(List<EventoDeDominio> eventos) {
        eventos.forEach(e -> kafka.send("pedidos", e));   // dentro de la tx de BD
    }
}
```

### ¿Qué duele?

- El mensaje sale **antes del commit**. Si la transacción hace rollback, el
  mundo ya se ha enterado de algo que no pasó (segundo escenario de la
  sección 1).
- Si Kafka está lento, la transacción de BD se alarga y retiene la conexión.
- Si Kafka está caído, el caso de uso falla aunque el negocio podría seguir.

---

## 4. Implementación 2 — `@TransactionalEventListener(AFTER_COMMIT)`

Spring permite publicar un evento en memoria y entregarlo **solo si la
transacción hace commit**.

```java
package com.curso.pedidos.infrastructure.adapter.out.messaging;

import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.EventoDeDominio;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
class SpringPublicadorDeEventosAdapter implements PublicadorDeEventos {

    private final ApplicationEventPublisher spring;

    SpringPublicadorDeEventosAdapter(ApplicationEventPublisher spring) { this.spring = spring; }

    @Override
    public void publicar(List<EventoDeDominio> eventos) {
        eventos.forEach(spring::publishEvent);   // Spring acepta cualquier objeto como evento
    }
}
```

Y un adaptador de entrada (sí, de **entrada**: reacciona a algo y dispara
un caso de uso) que escucha:

```java
package com.curso.pedidos.infrastructure.adapter.in.messaging;

import com.curso.pedidos.domain.event.PedidoConfirmado;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class NotificarConfirmacionListener {

    private final NotificadorPedidos notificador;

    NotificarConfirmacionListener(NotificadorPedidos notificador) { this.notificador = notificador; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void alConfirmarse(PedidoConfirmado evento) {
        notificador.notificarConfirmacion(evento.pedidoId());
    }
}
```

| Lo que resuelve                                 | Lo que **no** resuelve                                                 |
|-------------------------------------------------|------------------------------------------------------------------------|
| No se notifica nada si hay rollback             | Si el proceso muere entre el commit y el listener, el evento se pierde |
| La BD no espera al email                        | Si el listener falla (SMTP caído), no hay reintento                    |
| Sencillo, sin tablas extra                      | Solo funciona dentro del mismo proceso (no es mensajería)              |

> **Trampa:** dentro de un listener `AFTER_COMMIT`, la transacción original
> ya terminó. Si el listener escribe en BD necesita **su propia**
> transacción (`@Transactional(propagation = REQUIRES_NEW)` en el método del
> listener o un `EjecutorTransaccional`). Si no, los cambios no se guardan.

> **Trampa:** si no hay transacción activa al publicar, el listener **no se
> ejecuta** (salvo `fallbackExecution = true`). Otra razón por la que los
> tests con `@DataJpaTest` (rollback siempre) no lo disparan.

Es razonable para **efectos secundarios que se pueden perder** (refrescar
una caché, una métrica). Para cualquier cosa importante, outbox.

---

## 5. Implementación 3 — Transactional Outbox

Chris Richardson (microservices.io): **escribe el evento en una tabla de la
misma base de datos, en la misma transacción** que el cambio de negocio. Un
proceso aparte (*relay*) lee la tabla y publica en el broker.

```
┌─────────────────────── UNA transacción de BD ────────────────────────┐
│                                                                      │
│  ConfirmarPedidoService                                              │
│     │ guardar(pedido)        ┌────────────┐                          │
│     ├───────────────────────►│  pedidos   │                          │
│     │ publicar(eventos)      ├────────────┤                          │
│     └───────────────────────►│  outbox    │  (id, tipo, payload,     │
│                              └─────┬──────┘   creado_en, publicado_en)│
└────────────────────────────────────┼─────────────────────────────────┘
                                     │  commit atómico: o ambos o ninguno
                                     ▼
                       ┌───────────────────────────┐        ┌──────────┐
                       │ OutboxRelay (@Scheduled)  │───────►│  Kafka   │──► consumidores
                       │ lee pendientes, publica,  │ send   └──────────┘   (idempotentes)
                       │ marca publicado_en        │
                       └───────────────────────────┘
```

### 5.1. Migración

```sql
-- V3__crear_tabla_outbox.sql
CREATE TABLE outbox (
    id             UUID          PRIMARY KEY,
    agregado_tipo  VARCHAR(50)   NOT NULL,
    agregado_id    UUID          NOT NULL,
    tipo_evento    VARCHAR(100)  NOT NULL,
    payload        JSONB         NOT NULL,
    creado_en      TIMESTAMPTZ   NOT NULL,
    publicado_en   TIMESTAMPTZ,
    intentos       INT           NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_pendientes ON outbox (creado_en) WHERE publicado_en IS NULL;
```

### 5.2. El adaptador que escribe en el outbox

Implementa **el mismo puerto** `PublicadorDeEventos`. El caso de uso no se
entera del cambio.

```java
package com.curso.pedidos.infrastructure.adapter.out.messaging;

import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class OutboxPublicadorDeEventosAdapter implements PublicadorDeEventos {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    OutboxPublicadorDeEventosAdapter(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)   // solo dentro de la tx del caso de uso
    public void publicar(List<EventoDeDominio> eventos) {
        eventos.forEach(this::insertar);
    }

    private void insertar(EventoDeDominio evento) {
        jdbc.sql("""
                INSERT INTO outbox (id, agregado_tipo, agregado_id, tipo_evento, payload, creado_en)
                VALUES (:id, 'Pedido', :agregadoId, :tipo, CAST(:payload AS JSONB), :creadoEn)
                """)
            .param("id", UUID.randomUUID())
            .param("agregadoId", pedidoIdDe(evento))
            .param("tipo", evento.getClass().getSimpleName())
            .param("payload", aJson(evento))
            .param("creadoEn", java.sql.Timestamp.from(evento.ocurridoEn()))
            .update();
    }

    private UUID pedidoIdDe(EventoDeDominio evento) {
        if (evento instanceof PedidoConfirmado e) return e.pedidoId().valor();
        if (evento instanceof PedidoPagado e)     return e.pedidoId().valor();
        if (evento instanceof PedidoCancelado e)  return e.pedidoId().valor();
        throw new IllegalArgumentException("Evento desconocido: " + evento);
    }

    private String aJson(EventoDeDominio evento) {
        try {
            // DTO de mensaje: contrato público, NO el record de dominio serializado tal cual
            return json.writeValueAsString(Map.of(
                    "pedidoId", pedidoIdDe(evento).toString(),
                    "ocurridoEn", evento.ocurridoEn().toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

> **Por qué un mapa/DTO y no el `record` de dominio:** el payload es un
> **contrato público** con otros servicios. Si serializas el `record` de
> dominio, renombrar un campo del dominio rompe a los consumidores. El
> adaptador traduce, igual que el controlador traduce a DTOs de respuesta.

### 5.3. El relay

```java
package com.curso.pedidos.infrastructure.adapter.out.messaging;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    record MensajePendiente(UUID id, UUID agregadoId, String tipoEvento, String payload) { }

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.intervalo-ms:1000}")
    @Transactional
    public void publicarPendientes() {
        List<MensajePendiente> pendientes = jdbc.sql("""
                SELECT id, agregado_id, tipo_evento, payload::text AS payload
                  FROM outbox
                 WHERE publicado_en IS NULL
                 ORDER BY creado_en
                 LIMIT 100
                 FOR UPDATE SKIP LOCKED
                """)
                .query(MensajePendiente.class)
                .list();

        for (MensajePendiente m : pendientes) {
            try {
                var record = new org.apache.kafka.clients.producer.ProducerRecord<>(
                        "pedidos.eventos", m.agregadoId().toString(), m.payload());
                record.headers().add("mensaje-id", m.id().toString().getBytes());
                record.headers().add("tipo-evento", m.tipoEvento().getBytes());
                kafka.send(record).get();          // esperamos el ACK del broker
                jdbc.sql("UPDATE outbox SET publicado_en = now() WHERE id = :id")
                    .param("id", m.id()).update();
            } catch (Exception e) {
                log.warn("No se pudo publicar {}: {}", m.id(), e.getMessage());
                jdbc.sql("UPDATE outbox SET intentos = intentos + 1 WHERE id = :id")
                    .param("id", m.id()).update();
                break;                              // preserva el orden: no saltamos mensajes
            }
        }
    }
}
```

(Requiere `@EnableScheduling` en una clase de configuración.)

Detalles que importan:

- **`FOR UPDATE SKIP LOCKED`**: si hay varias réplicas de la app, cada una
  coge filas distintas sin bloquearse. Sin esto, dos réplicas publicarían
  el mismo mensaje.
- **Clave de partición = `agregadoId`**: Kafka garantiza orden por
  partición, así que todos los eventos de un pedido llegan en orden.
- **`mensaje-id` en cabecera**: el consumidor lo usa para deduplicar
  (sección 7).
- **Entrega *at-least-once***: si el relay publica y muere antes del
  `UPDATE`, el mensaje se publicará **otra vez**. El outbox no da
  *exactly-once*; da **"nunca se pierde"**. La duplicación la resuelve el
  consumidor.

> **Alternativas al relay por *polling*:** *Change Data Capture* con
> **Debezium** (lee el WAL de PostgreSQL y publica la tabla outbox; incluye
> un *Outbox Event Router*), o **Spring Modulith** con su *Event Publication
> Registry*, que persiste los eventos de `@TransactionalEventListener` y los
> reintenta. Mismo patrón, menos código propio.

> **Trampa:** no limpiar la tabla. Programa un borrado de filas publicadas
> con más de N días o la tabla crecerá sin fin.

---

## 6. Consistencia eventual

Con outbox, el pedido está confirmado **ya**, y el stock se reserva
**unos milisegundos (o minutos, si Kafka está caído) después**. Durante ese
intervalo, el sistema es **inconsistente a propósito**.

| Pregunta incómoda                                         | Respuesta de diseño                                                   |
|-----------------------------------------------------------|-----------------------------------------------------------------------|
| ¿Y si el usuario consulta y aún no ve el stock reservado? | La UI muestra estados intermedios ("Confirmado, preparando…")         |
| ¿Y si al reservar no hay stock?                           | Evento `StockNoDisponible` → compensación (cancelar, avisar)          |
| ¿Cuánto es "eventual"?                                    | Mídelo: métrica de antigüedad del mensaje pendiente más viejo en outbox |
| ¿Lo acepta el negocio?                                    | Pregúntalo. Casi siempre sí: el mundo real ya funciona así            |

> **En cristiano:** consistencia eventual no es "a veces funciona". Es
> "siempre acaba siendo consistente, y sé exactamente qué estados
> intermedios existen".

---

## 7. Consumidores idempotentes

Si la entrega es *at-least-once*, el consumidor **va a recibir duplicados**.
Debe ser **idempotente**: procesar el mismo mensaje dos veces tiene el mismo
efecto que procesarlo una.

Dos técnicas, combinables:

1. **Idempotencia natural** del caso de uso: `marcarComoPagado` sobre un
   pedido ya `PAGADO` no hace nada (o el servicio lo detecta y sale).
2. **Tabla de mensajes procesados**: se registra el `mensaje-id` en la
   **misma transacción** que el efecto. Si ya estaba, se ignora.

```sql
-- V5__crear_tabla_mensajes_procesados.sql
CREATE TABLE mensajes_procesados (
    mensaje_id     UUID         PRIMARY KEY,
    consumidor     VARCHAR(100) NOT NULL,
    procesado_en   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

```java
package com.curso.pedidos.infrastructure.adapter.in.messaging;

@Component
class PagoConfirmadoKafkaListener {

    private final RegistrarPagoUseCase registrarPago;
    private final EjecutorTransaccional tx;
    private final JdbcClient jdbc;

    // constructor...

    @KafkaListener(topics = "pagos.eventos", groupId = "pedidos")
    void alRecibir(@Payload PagoConfirmadoMensaje mensaje,
                   @Header("mensaje-id") String mensajeId) {
        tx.enTransaccion(() -> {
            int insertadas = jdbc.sql("""
                    INSERT INTO mensajes_procesados (mensaje_id, consumidor)
                    VALUES (:id, 'pedidos.pago-confirmado')
                    ON CONFLICT (mensaje_id) DO NOTHING
                    """)
                    .param("id", UUID.fromString(mensajeId))
                    .update();
            if (insertadas == 0) {
                return;                                    // duplicado: ya procesado
            }
            registrarPago.ejecutar(new RegistrarPagoCommand(
                    PedidoId.de(mensaje.pedidoId()), mensaje.referenciaPago()));
        });
    }
}
```

> Ojo: aquí `registrarPago.ejecutar(...)` abre su transacción **dentro** de la
> del listener; con propagación `REQUIRED` se une a ella, y el `INSERT` de
> deduplicación y el efecto se confirman juntos. Eso es lo que hace que
> funcione.

---

## 8. Sagas: transacciones de negocio que cruzan servicios

Cuando Pedidos, Pagos y Envíos son **servicios distintos con su propia BD**,
no hay transacción que los abrace (y el *two-phase commit* distribuido no es
opción práctica). Una **Saga** es una secuencia de transacciones locales;
si una falla, se ejecutan **transacciones compensatorias** que deshacen
semánticamente las anteriores.

Flujo feliz y compensación para Pedido → Pago → Envío:

```
           Pedidos                    Pagos                     Envíos
             │                          │                          │
 T1 confirmar│                          │                          │
  ──────────►│ PedidoConfirmado         │                          │
             │─────────────────────────►│ T2 cobrar                │
             │                          │──┐                       │
             │                          │◄─┘                       │
             │          PagoRealizado   │                          │
             │◄─────────────────────────│                          │
 T3 marcar   │                          │                          │
 Pagado ────►│ PedidoPagado             │                          │
             │────────────────────────────────────────────────────►│ T4 programar envío
             │                          │                          │──┐ ✘ sin transportista
             │                          │                          │◄─┘
             │                  EnvioRechazado                     │
             │◄────────────────────────────────────────────────────│
             │                          │                          │
 C3 cancelar │ PedidoCancelado          │                          │
 (compensa) ►│─────────────────────────►│ C2 reembolsar (compensa) │
             │                          │                          │
```

### 8.1. Coreografía vs orquestación

```
        COREOGRAFÍA                                  ORQUESTACIÓN
 (cada servicio reacciona a eventos)        (un orquestador manda comandos)

 ┌────────┐ PedidoConfirmado ┌──────┐          ┌───────────────────────┐
 │Pedidos │─────────────────►│Pagos │          │ PagoDePedidoSaga      │
 └────────┘◄─────────────────└──────┘          │ (estado: PAGANDO...)  │
     │      PagoRealizado        │             └──┬─────────┬────────┬─┘
     │ PedidoPagado              │      cobrar    │  marcar  │ programar
     ▼                           │                ▼  Pagado  ▼  envío  ▼
 ┌────────┐ EnvioRechazado       │          ┌──────┐   ┌────────┐  ┌────────┐
 │Envíos  │──────────────────────┘          │Pagos │   │Pedidos │  │Envíos  │
 └────────┘                                 └──────┘   └────────┘  └────────┘
```

| Criterio                         | Coreografía                                  | Orquestación                                 |
|----------------------------------|----------------------------------------------|----------------------------------------------|
| Acoplamiento                     | Bajo: cada uno conoce eventos, no servicios  | El orquestador conoce a todos                |
| Visibilidad del flujo            | Dispersa: hay que leer N servicios           | Centralizada en una clase/estado             |
| Añadir un paso                   | Fácil si solo escucha; difícil si cambia el orden | Se cambia el orquestador                |
| Riesgo                           | Ciclos de eventos, "¿quién compensa?"        | Orquestador como "dios" anémico              |
| Recomendado                      | 2–3 pasos, flujos estables                   | 4+ pasos, compensaciones complejas           |

En hexagonal, el **orquestador es un servicio de aplicación** (o un
agregado `SagaPagoPedido` con su estado persistido) que reacciona a
eventos (adaptador de entrada de mensajería) y envía comandos por puertos
de salida. Herramientas: Axon, Temporal, Camunda, Eventuate Tram; o un
agregado propio con una tabla de estado.

> **Trampa:** las compensaciones **no son rollbacks**. Un reembolso no borra
> el cobro: es otro movimiento. El cliente ve los dos en su extracto. Diseña
> las compensaciones con el negocio.

> **Trampa:** cada paso de la saga debe publicar sus eventos con **outbox**
> y sus consumidores deben ser **idempotentes**. Una saga sobre mensajería
> no fiable es una saga que se queda colgada.

---

## 9. Testing

### 9.1. Dominio: el agregado registra los eventos correctos

```java
class PedidoEventosTest {

    private final Instant ahora = Instant.parse("2026-11-23T10:00:00Z");

    @Test
    void confirmar_registra_PedidoConfirmado_y_extraer_vacia_la_lista() {
        Pedido pedido = Pedido.crear(new ClienteId(UUID.randomUUID()), ahora);
        pedido.anadirLinea(new ProductoId("SKU-1"), new Cantidad(1),
                           new Dinero(BigDecimal.TEN, Currency.getInstance("EUR")));

        pedido.confirmar(ahora);

        assertThat(pedido.extraerEventos())
                .containsExactly(new PedidoConfirmado(pedido.id(), ahora));
        assertThat(pedido.extraerEventos()).isEmpty();
    }

    @Test
    void reconstituir_no_genera_eventos() {
        Pedido p = Pedido.reconstituir(PedidoId.nuevo(), new ClienteId(UUID.randomUUID()),
                List.of(), EstadoPedido.CONFIRMADO, ahora, 1L);
        assertThat(p.extraerEventos()).isEmpty();
    }
}
```

### 9.2. Aplicación: el servicio publica tras guardar

```java
@ExtendWith(MockitoExtension.class)
class ConfirmarPedidoServiceEventosTest {

    @Mock PedidoRepository repository;
    @Mock PublicadorDeEventos publicador;
    EjecutorTransaccional tx = new EjecutorTransaccionalFake();
    Clock clock = Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void guarda_y_despues_publica() {
        Pedido pedido = pedidoBorradorConLinea();
        when(repository.buscarPorId(pedido.id())).thenReturn(Optional.of(pedido));
        var servicio = new ConfirmarPedidoService(repository, publicador, tx, clock);

        servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id()));

        InOrder orden = inOrder(repository, publicador);
        orden.verify(repository).guardar(pedido);
        orden.verify(publicador).publicar(List.of(new PedidoConfirmado(pedido.id(), clock.instant())));
    }
}
```

### 9.3. Integración: outbox atómico con el agregado

```java
@SpringBootTest
@Testcontainers
class OutboxIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean KafkaTemplate<String, String> kafka;        // el relay no nos interesa aquí
    @Autowired ConfirmarPedidoUseCase confirmar;
    @Autowired PedidoRepository repository;
    @Autowired EjecutorTransaccional tx;
    @Autowired JdbcClient jdbc;

    @Test
    void confirmar_deja_una_fila_en_outbox() {
        Pedido pedido = pedidoBorradorConLinea();
        tx.enTransaccion(() -> repository.guardar(pedido));

        confirmar.ejecutar(new ConfirmarPedidoCommand(pedido.id()));

        var tipos = jdbc.sql("SELECT tipo_evento FROM outbox WHERE agregado_id = :id")
                        .param("id", pedido.id().valor())
                        .query(String.class).list();
        assertThat(tipos).containsExactly("PedidoConfirmado");
    }
}
```

> En Spring Boot 3.4+ `@MockBean` pasa a ser `@MockitoBean`
> (`org.springframework.test.context.bean.override.mockito`). En 3.3 usamos
> `@MockBean`.

### 9.4. Consumidor idempotente

```java
@Test
void el_mismo_mensaje_dos_veces_registra_el_pago_una_sola_vez() {
    String mensajeId = UUID.randomUUID().toString();
    var mensaje = new PagoConfirmadoMensaje(pedido.id().valor().toString(), "PAY-123");

    listener.alRecibir(mensaje, mensajeId);
    listener.alRecibir(mensaje, mensajeId);

    verify(registrarPago, times(1)).ejecutar(any());
}
```

(Con `JdbcClient` real contra Testcontainers y `RegistrarPagoUseCase` como
`@MockBean`.)

---

## 10. Resumen

| Mecanismo                               | Garantía                                  | Úsalo para                                  |
|-----------------------------------------|-------------------------------------------|---------------------------------------------|
| Publicar al broker dentro de la tx      | Ninguna (escritura dual)                  | Nada                                        |
| `@TransactionalEventListener(AFTER_COMMIT)` | Solo tras commit; se puede perder     | Efectos prescindibles, mismo proceso        |
| Transactional Outbox + relay            | Nunca se pierde; *at-least-once*          | Cualquier evento que importe                |
| Consumidor idempotente                  | Duplicados inocuos                        | **Siempre** que consumas mensajes           |
| Saga (coreografía / orquestación)       | Consistencia eventual entre servicios     | Procesos de negocio que cruzan servicios    |

---

## Ejercicio

Al cancelar un pedido `PAGADO` hay que **reembolsar** el pago (servicio
Pagos) y **liberar el stock** (servicio Catálogo).

1. Modifica `Pedido.cancelar(...)` para que registre `PedidoCancelado`.
2. ¿Qué mecanismo de publicación usas y por qué?
3. Dibuja en ASCII la saga en coreografía.
4. Pagos recibe `PedidoCancelado` dos veces. ¿Qué pasa con tu diseño?

**Pista:** piensa qué pasa si el reembolso se ejecuta dos veces.

<details><summary>Solución</summary>

1.
```java
public void cancelar(Instant ahora) {
    if (estado == EstadoPedido.ENVIADO || estado == EstadoPedido.CANCELADO) {
        throw new TransicionDeEstadoInvalidaException("No se puede cancelar un pedido " + estado);
    }
    estado = EstadoPedido.CANCELADO;
    eventos.add(new PedidoCancelado(id, ahora));
}
```

2. **Outbox**: un reembolso que no se ejecuta es dinero del cliente
   retenido. No se puede perder.

3.
```
 Pedidos ──PedidoCancelado──┬──► Pagos:    ¿estaba pagado? → reembolsar → ReembolsoRealizado
                            └──► Catálogo: liberar stock   → StockLiberado
```

4. Pagos debe deduplicar por `mensaje-id` (tabla `mensajes_procesados`) **y**
   tener idempotencia natural: "reembolsar el pago PAY-123" comprueba si ya
   existe un reembolso para PAY-123. Además, al llamar a la pasarela externa
   para reembolsar, usa una **clave de idempotencia** derivada del pago
   (bloque 5), para que ni siquiera la pasarela devuelva el dinero dos veces.

</details>

---

## Referencias

- Chris Richardson, *Pattern: Transactional Outbox*: https://microservices.io/patterns/data/transactional-outbox.html
- Chris Richardson, *Pattern: Saga*: https://microservices.io/patterns/data/saga.html
- Martin Fowler, *Domain Event* y *Event Collaboration* (bliki): https://martinfowler.com/bliki/
- Spring Framework, *Transaction-bound Events* (`@TransactionalEventListener`): https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- Spring Modulith, *Event Publication Registry*: https://docs.spring.io/spring-modulith/reference/
- Vaughn Vernon, *Implementing Domain-Driven Design* (2013), cap. 8 "Domain Events".
- Testcontainers: https://java.testcontainers.org/
- Vlad Mihalcea, sobre `SKIP LOCKED` y colas en PostgreSQL: https://vladmihalcea.com/

---

## Mantra

> *"El evento se escribe donde se escribe el cambio, se publica cuando se
> puede y se consume como si llegara dos veces. Porque llegará."*
