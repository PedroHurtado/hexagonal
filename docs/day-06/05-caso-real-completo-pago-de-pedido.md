# Día 6 — Bloque 5: Caso real completo — pago de pedido

> *"Everything fails, all the time."*
> — Werner Vogels, CTO de Amazon

---

## 1. El problema en una frase

Cobrar un pedido parece una línea (`pasarela.cobrar(total)`), pero mezcla
**todo** lo del día: una base de datos con transacciones, un sistema externo
lento que no participa en ellas, un evento que no se puede perder, y un
usuario impaciente que pulsa "Pagar" dos veces. **¿Cómo garantizas que se
cobra exactamente una vez y que el pedido refleja la realidad, falle lo que
falle?**

---

## 2. Requisitos del caso de uso `PagarPedido`

| #  | Requisito                                                                                         |
|----|---------------------------------------------------------------------------------------------------|
| R1 | Solo se paga un pedido `CONFIRMADO`. Tras cobrar, pasa a `PAGADO` y se emite `PedidoPagado`.      |
| R2 | **Nunca** se cobra dos veces el mismo pedido, aunque el cliente reintente o haga doble clic.      |
| R3 | La llamada a la pasarela **no** se hace dentro de la transacción de base de datos.                |
| R4 | `PedidoPagado` se publica con garantía (outbox).                                                  |
| R5 | Si la pasarela no responde (timeout), el sistema no sabe si se cobró: debe poder **resolverlo después** sin cobrar de nuevo. |
| R6 | Si el pedido cambia de estado mientras se cobraba (p. ej. se canceló), el dinero se **reembolsa**. |

---

## 3. Bad: la versión ingenua

```java
@Transactional
public void pagar(UUID pedidoId, String tokenTarjeta) {
    Pedido pedido = repository.buscarPorId(new PedidoId(pedidoId)).orElseThrow();
    String ref = pasarela.cobrar(pedido.total(), tokenTarjeta);   // 3 s, dentro de la tx
    pedido.marcarComoPagado(Instant.now());
    repository.guardar(pedido);
    kafka.send("pedidos", new PedidoPagado(pedido.id(), Instant.now()));
}
```

### ¿Qué duele?

| Línea                     | Fallo                                                                                           |
|---------------------------|-------------------------------------------------------------------------------------------------|
| `@Transactional` + pasarela | La conexión a BD queda retenida 3 s. Con un pool de 10 conexiones: **máx. ~3 pagos/s** en toda la app. En Black Friday, pool agotado y caída general (también de las lecturas). |
| `pasarela.cobrar` dentro  | Si después falla el `guardar` (conflicto de `@Version`), la BD hace rollback… **pero el cobro no**. Cliente cobrado, pedido sin pagar. |
| Sin clave de idempotencia | Doble clic = dos peticiones = **dos cobros**. Timeout + reintento del cliente = dos cobros.      |
| `kafka.send` dentro       | Escritura dual (bloque 4): evento enviado aunque la tx haga rollback, o perdido si Kafka cae.   |
| Timeout de la pasarela    | Excepción → rollback → el pedido sigue `CONFIRMADO`. ¿Se cobró? Nadie lo sabe. El cliente reintenta → segundo cobro. |
| `Instant.now()`           | No testeable; dos instantes distintos para el mismo hecho.                                      |

> **En cristiano:** una transacción de BD **no puede deshacer el mundo
> exterior**. Si metes una llamada externa dentro, la transacción te da una
> falsa sensación de atomicidad y encima te come el pool de conexiones.

---

## 4. El diseño: tres fases y una clave de idempotencia

```
 Cliente            PagarPedidoService                  BD (pedidos, intentos_pago, outbox)     Pasarela
   │ POST /pagos         │                                          │                               │
   │ Idempotency-Key: K  │                                          │                               │
   │────────────────────►│ ╔═ FASE 1: tx corta ═══════════════════╗ │                               │
   │                     │ ║ ¿existe intento K? ─────────────────────►│                               │
   │                     │ ║ cargar pedido, comprobar CONFIRMADO ────►│                               │
   │                     │ ║ INSERT intento K (EN_CURSO, importe) ───►│                               │
   │                     │ ╚═══════════════════════ commit ═══════╝ │                               │
   │                     │                                          │                               │
   │                     │ ── FASE 2: SIN transacción ─────────────────────────────────────────────►│
   │                     │    cobrar(importe, token, Idempotency-Key: K)                            │
   │                     │◄─────────────────────────────────────── Aprobado / Rechazado / timeout ──│
   │                     │                                          │                               │
   │                     │ ╔═ FASE 3: tx corta ═══════════════════╗ │                               │
   │                     │ ║ recargar pedido                       ║ │                               │
   │                     │ ║ marcarComoPagado → PedidoPagado       ║ │                               │
   │                     │ ║ guardar pedido ─────────────────────────►│                               │
   │                     │ ║ INSERT outbox (PedidoPagado) ───────────►│                               │
   │                     │ ║ UPDATE intento K = APROBADO ────────────►│                               │
   │                     │ ╚═══════════════════════ commit ═══════╝ │                               │
   │◄────────────────────│ 200 Pagado / 402 Rechazado / 202 EnProceso                               │
```

### ¿Por qué la clave de idempotencia la genera el cliente?

Porque el cliente es el único que sabe que **dos peticiones son la misma
intención** ("pagar este pedido, este intento"). El frontend genera un UUID
al mostrar el botón "Pagar" y lo reenvía en cada reintento. Es el mismo
mecanismo que usan Stripe, Adyen o PayPal (`Idempotency-Key`).

La misma clave se reenvía **a la pasarela**: si nuestro servicio reintenta
el cobro (por un timeout anterior), la pasarela devuelve el resultado del
primer intento en lugar de cobrar de nuevo. **La idempotencia se propaga de
punta a punta.**

---

## 5. Puertos

### 5.1. Puerto de entrada

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;
import java.util.Objects;

public interface PagarPedidoUseCase {

    ResultadoPagoPedido ejecutar(PagarPedidoCommand cmd);

    record PagarPedidoCommand(PedidoId pedidoId, String claveIdempotencia, String tokenPago) {
        public PagarPedidoCommand {
            Objects.requireNonNull(pedidoId);
            if (claveIdempotencia == null || claveIdempotencia.isBlank()) {
                throw new IllegalArgumentException("La clave de idempotencia es obligatoria");
            }
            if (tokenPago == null || tokenPago.isBlank()) {
                throw new IllegalArgumentException("El token de pago es obligatorio");
            }
        }
    }

    sealed interface ResultadoPagoPedido {
        record Pagado(PedidoId pedidoId, String referenciaPago) implements ResultadoPagoPedido { }
        record Rechazado(PedidoId pedidoId, String motivo)      implements ResultadoPagoPedido { }
        record EnProceso(PedidoId pedidoId)                     implements ResultadoPagoPedido { }
    }
}
```

> **Nota:** el `tokenPago` lo genera el SDK de la pasarela en el navegador.
> **Nunca** pasan datos de tarjeta por nuestro backend (PCI-DSS).

### 5.2. Puertos de salida

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.Dinero;

public interface PasarelaDePago {

    ResultadoCobro cobrar(SolicitudCobro solicitud);

    record SolicitudCobro(String claveIdempotencia, String referenciaPedido,
                          Dinero importe, String tokenPago) { }

    sealed interface ResultadoCobro {
        record Aprobado(String referenciaPago) implements ResultadoCobro { }
        record Rechazado(String motivo)        implements ResultadoCobro { }
        /** Timeout, 5xx, red: NO sabemos si se cobró. */
        record Indeterminado(String detalle)   implements ResultadoCobro { }
    }
}
```

Fíjate en `Indeterminado`: es la clave del diseño. Un timeout **no es un
rechazo**. Tratarlo como rechazo es la forma más rápida de cobrar dos veces.

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.PedidoId;
import java.util.Optional;

public interface RegistroDeIntentosDePago {

    Optional<IntentoDePago> buscar(String claveIdempotencia);

    /** @return false si ya existía un intento con esa clave (carrera entre peticiones). */
    boolean registrarInicio(IntentoDePago intento);

    void actualizar(IntentoDePago intento);

    enum EstadoIntento { EN_CURSO, APROBADO, RECHAZADO, REQUIERE_REEMBOLSO }

    record IntentoDePago(String claveIdempotencia, PedidoId pedidoId, Dinero importe,
                         EstadoIntento estado, String referenciaPago, String motivo) {

        public static IntentoDePago iniciar(String clave, PedidoId pedidoId, Dinero importe) {
            return new IntentoDePago(clave, pedidoId, importe, EstadoIntento.EN_CURSO, null, null);
        }
        public IntentoDePago aprobado(String referencia) {
            return new IntentoDePago(claveIdempotencia, pedidoId, importe, EstadoIntento.APROBADO, referencia, null);
        }
        public IntentoDePago rechazado(String motivo) {
            return new IntentoDePago(claveIdempotencia, pedidoId, importe, EstadoIntento.RECHAZADO, null, motivo);
        }
        public IntentoDePago requiereReembolso(String referencia, String motivo) {
            return new IntentoDePago(claveIdempotencia, pedidoId, importe,
                                     EstadoIntento.REQUIERE_REEMBOLSO, referencia, motivo);
        }
    }
}
```

> **¿Por qué el importe se guarda en el intento?** Porque si en la fase 2
> se reintenta (mismo `Idempotency-Key`), hay que enviar **exactamente el
> mismo importe**. Las pasarelas rechazan una clave reutilizada con
> parámetros distintos.

Reutilizamos `PedidoRepository`, `PublicadorDeEventos` (con el adaptador
outbox del bloque 4) y `EjecutorTransaccional` (bloque 3).

---

## 6. El servicio de aplicación

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.PagarPedidoUseCase;
import com.curso.pedidos.application.port.out.*;
import com.curso.pedidos.application.port.out.PasarelaDePago.ResultadoCobro;
import com.curso.pedidos.application.port.out.PasarelaDePago.SolicitudCobro;
import com.curso.pedidos.application.port.out.RegistroDeIntentosDePago.IntentoDePago;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.Pedido;
import java.time.Clock;

public class PagarPedidoService implements PagarPedidoUseCase {

    private static final int MAX_REINTENTOS_CONFLICTO = 3;

    private final PedidoRepository pedidos;
    private final RegistroDeIntentosDePago intentos;
    private final PasarelaDePago pasarela;
    private final PublicadorDeEventos publicador;
    private final EjecutorTransaccional tx;
    private final Clock clock;

    public PagarPedidoService(PedidoRepository pedidos, RegistroDeIntentosDePago intentos,
                              PasarelaDePago pasarela, PublicadorDeEventos publicador,
                              EjecutorTransaccional tx, Clock clock) {
        this.pedidos = pedidos;
        this.intentos = intentos;
        this.pasarela = pasarela;
        this.publicador = publicador;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public ResultadoPagoPedido ejecutar(PagarPedidoCommand cmd) {
        // FASE 1 — tx corta: idempotencia + validación + registrar intento
        Preparacion prep = tx.enTransaccion(() -> preparar(cmd));
        if (prep.resultadoFinal() != null) {
            return prep.resultadoFinal();
        }

        // FASE 2 — SIN transacción: llamada externa
        ResultadoCobro cobro = pasarela.cobrar(new SolicitudCobro(
                cmd.claveIdempotencia(), cmd.pedidoId().valor().toString(),
                prep.intento().importe(), cmd.tokenPago()));

        // FASE 3 — tx corta: reflejar el resultado (con reintento ante conflicto)
        return registrarResultadoConReintentos(cmd, prep.intento(), cobro);
    }

    // ---------------------------------------------------------------- fase 1

    private record Preparacion(IntentoDePago intento, ResultadoPagoPedido resultadoFinal) { }

    private Preparacion preparar(PagarPedidoCommand cmd) {
        var previo = intentos.buscar(cmd.claveIdempotencia());
        if (previo.isPresent()) {
            IntentoDePago i = previo.get();
            if (!i.pedidoId().equals(cmd.pedidoId())) {
                throw new IllegalArgumentException("Clave de idempotencia reutilizada para otro pedido");
            }
            return switch (i.estado()) {
                case APROBADO  -> new Preparacion(i, new ResultadoPagoPedido.Pagado(i.pedidoId(), i.referenciaPago()));
                case RECHAZADO -> new Preparacion(i, new ResultadoPagoPedido.Rechazado(i.pedidoId(), i.motivo()));
                case REQUIERE_REEMBOLSO -> new Preparacion(i, new ResultadoPagoPedido.Rechazado(i.pedidoId(), i.motivo()));
                case EN_CURSO  -> new Preparacion(i, null);   // reintentar cobro con la MISMA clave
            };
        }

        Pedido pedido = pedidos.buscarPorId(cmd.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(cmd.pedidoId()));
        if (pedido.estado() != EstadoPedido.CONFIRMADO) {
            throw new TransicionDeEstadoInvalidaException(
                    "Solo se paga un pedido CONFIRMADO; estado actual: " + pedido.estado());
        }

        IntentoDePago nuevo = IntentoDePago.iniciar(cmd.claveIdempotencia(), cmd.pedidoId(), pedido.total());
        if (!intentos.registrarInicio(nuevo)) {
            // otra petición con la misma clave se nos adelantó por milisegundos
            return new Preparacion(nuevo, new ResultadoPagoPedido.EnProceso(cmd.pedidoId()));
        }
        return new Preparacion(nuevo, null);
    }

    // ---------------------------------------------------------------- fase 3

    private ResultadoPagoPedido registrarResultadoConReintentos(PagarPedidoCommand cmd,
                                                                IntentoDePago intento,
                                                                ResultadoCobro cobro) {
        for (int i = 1; ; i++) {
            try {
                return tx.enTransaccion(() -> registrarResultado(intento, cobro));
            } catch (ConflictoDeConcurrenciaException e) {
                if (i >= MAX_REINTENTOS_CONFLICTO) {
                    // El cobro está hecho; lo resolverá el job de conciliación (sección 8)
                    return new ResultadoPagoPedido.EnProceso(cmd.pedidoId());
                }
            }
        }
    }

    private ResultadoPagoPedido registrarResultado(IntentoDePago intento, ResultadoCobro cobro) {
        if (cobro instanceof ResultadoCobro.Rechazado r) {
            intentos.actualizar(intento.rechazado(r.motivo()));
            return new ResultadoPagoPedido.Rechazado(intento.pedidoId(), r.motivo());
        }
        if (cobro instanceof ResultadoCobro.Indeterminado) {
            // No sabemos si se cobró: el intento sigue EN_CURSO
            return new ResultadoPagoPedido.EnProceso(intento.pedidoId());
        }

        var aprobado = (ResultadoCobro.Aprobado) cobro;
        Pedido pedido = pedidos.buscarPorId(intento.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(intento.pedidoId()));

        if (pedido.estado() == EstadoPedido.PAGADO) {
            // Otra petición con la misma clave ya lo registró: idempotente
            intentos.actualizar(intento.aprobado(aprobado.referenciaPago()));
            return new ResultadoPagoPedido.Pagado(pedido.id(), aprobado.referenciaPago());
        }
        if (pedido.estado() != EstadoPedido.CONFIRMADO) {
            // R6: el pedido cambió (p. ej. cancelado) mientras cobrábamos
            String motivo = "El pedido pasó a " + pedido.estado() + " durante el pago; se reembolsará";
            intentos.actualizar(intento.requiereReembolso(aprobado.referenciaPago(), motivo));
            return new ResultadoPagoPedido.Rechazado(pedido.id(), motivo);
        }

        pedido.marcarComoPagado(clock.instant());
        pedidos.guardar(pedido);
        publicador.publicar(pedido.extraerEventos());          // outbox, misma tx
        intentos.actualizar(intento.aprobado(aprobado.referenciaPago()));
        return new ResultadoPagoPedido.Pagado(pedido.id(), aprobado.referenciaPago());
    }
}
```

Notas de diseño:

- El `switch` sobre el `enum` `EstadoIntento` es una *switch expression*
  estándar en Java 17 (no es *pattern matching*). Sobre los `sealed` de
  `ResultadoCobro` usamos `instanceof` con patrón, también estándar en 17.
  En Java 21 lo escribirías con un `switch` de patrones exhaustivo.
- `ConflictoDeConcurrenciaException` es la del bloque 1. Para que llegue
  también cuando Hibernate detecta el conflicto **en el commit**, el
  adaptador `SpringEjecutorTransaccionalAdapter` traduce
  `OptimisticLockingFailureException` de Spring a ella:

```java
@Override
public <T> T enTransaccion(Supplier<T> trabajo) {
    try {
        return template.execute(status -> trabajo.get());
    } catch (org.springframework.dao.OptimisticLockingFailureException e) {
        throw new ConflictoDeConcurrenciaException(e.getMessage(), e);
    }
}
```

  (Añade a `ConflictoDeConcurrenciaException` un constructor
  `(String mensaje, Throwable causa)` junto al del bloque 1.)

- La fase 3 **recarga** el pedido: el que leímos en la fase 1 puede estar
  obsoleto (han pasado segundos).

---

## 7. Por qué la llamada externa va FUERA de la transacción

Resumen para la pizarra:

| Motivo                            | Explicación                                                                                                   |
|-----------------------------------|---------------------------------------------------------------------------------------------------------------|
| **El rollback no deshace el cobro** | La transacción solo abarca la BD. Meter la pasarela dentro da una atomicidad **falsa**.                     |
| **Pool de conexiones**            | Una conexión retenida durante la llamada. `pool / latencia` = techo de throughput. 10 / 3 s ≈ 3,3 pagos/s.    |
| **Bloqueos**                      | Si cargaste el pedido con `SELECT FOR UPDATE` o hubo `UPDATE`, las filas quedan bloqueadas segundos.          |
| **Timeouts encadenados**          | Timeout de tx, de la pasarela, del balanceador y del cliente compiten. Gana el peor.                          |
| **Fallos parciales explícitos**   | Fuera de la tx, el código **tiene** que decir qué pasa en cada caso (aprobado, rechazado, indeterminado).     |

Regla general: **nunca hagas I/O remoto (HTTP, SMTP, colas síncronas) con
una transacción de BD abierta**. Si necesitas que algo remoto ocurra "a la
vez" que la escritura, apúntalo en el outbox y que ocurra después.

> **Trampa:** con `@Transactional` en el servicio (opción A del bloque 3),
> esta separación **no se puede expresar**: todo el método es una
> transacción. Por eso este caso de uso es el que justifica el puerto
> `EjecutorTransaccional`. Alternativa con anotaciones: tres beans distintos
> (fase 1 y fase 3 `@Transactional`, orquestador sin transacción). Funciona,
> pero el flujo queda repartido.

---

## 8. Manejo de fallos: la matriz completa

| Momento del fallo                               | Estado tras el fallo                        | Qué pasa después                                                                            |
|-------------------------------------------------|---------------------------------------------|---------------------------------------------------------------------------------------------|
| Fase 1 falla (BD caída, pedido no confirmado)   | Nada cambiado                               | Error al cliente (4xx/5xx). Puede reintentar con la misma clave.                            |
| Doble clic: dos peticiones con la misma clave   | Una inserta el intento, la otra no          | La segunda recibe `EnProceso` (202). No se llama dos veces a la pasarela (o, si ocurre, la pasarela deduplica por `Idempotency-Key`). |
| Pasarela rechaza                                | Intento `RECHAZADO`, pedido `CONFIRMADO`    | 402 al cliente. Para reintentar con otra tarjeta, **nueva clave**.                          |
| Pasarela timeout / 5xx                          | Intento `EN_CURSO`                          | 202 al cliente. Reintento con la misma clave → la pasarela devuelve el resultado original. Job de conciliación resuelve los `EN_CURSO` antiguos. |
| El proceso muere entre fase 2 y fase 3          | Intento `EN_CURSO`, cobrado en la pasarela  | Igual que el timeout: reintento o conciliación lo completan **sin cobrar otra vez**.        |
| Fase 3: conflicto de versión                    | Rollback de fase 3                          | Reintento automático de la fase 3 (hasta 3 veces); si persiste, `EnProceso` + conciliación. |
| Pedido cancelado durante el cobro               | Intento `REQUIERE_REEMBOLSO`                | Job de compensación llama a `reembolsar` en la pasarela (con su propia clave de idempotencia). |
| Kafka caído tras fase 3                         | `PedidoPagado` en outbox sin publicar       | El relay lo publica cuando Kafka vuelva (bloque 4).                                         |

### El job de conciliación (adaptador de entrada *scheduler*)

```java
package com.curso.pedidos.infrastructure.adapter.in.scheduler;

@Component
class ConciliacionPagosScheduler {

    private final ConciliarPagosPendientesUseCase conciliar;

    ConciliacionPagosScheduler(ConciliarPagosPendientesUseCase conciliar) {
        this.conciliar = conciliar;
    }

    @Scheduled(fixedDelayString = "${pagos.conciliacion.intervalo-ms:60000}")
    void conciliar() {
        conciliar.ejecutar(Duration.ofMinutes(5));   // intentos EN_CURSO de hace más de 5 min
    }
}
```

El caso de uso `ConciliarPagosPendientesService` busca intentos `EN_CURSO`
antiguos y, para cada uno, **vuelve a llamar a `pasarela.cobrar` con la
misma clave e importe** (que es idempotente) y aplica la misma fase 3. Es
decir: reutiliza `registrarResultado`. Si la pasarela ofrece una consulta
por clave (`GET /cobros?idempotency_key=K`), mejor todavía.

---

## 9. Adaptadores

### 9.1. Pasarela con `RestClient`

```java
package com.curso.pedidos.infrastructure.adapter.out.payment;

import com.curso.pedidos.application.port.out.PasarelaDePago;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

public class StripeLikePasarelaAdapter implements PasarelaDePago {

    record CobroRequest(BigDecimal importe, String moneda, String token, String referencia) { }
    record CobroResponse(String id, String estado, String motivoRechazo) { }

    private final RestClient http;

    public StripeLikePasarelaAdapter(RestClient.Builder builder, String baseUrl,
                                     Duration connectTimeout, Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.http = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public ResultadoCobro cobrar(SolicitudCobro s) {
        try {
            CobroResponse r = http.post()
                    .uri("/v1/cobros")
                    .header("Idempotency-Key", s.claveIdempotencia())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CobroRequest(s.importe().importe(),
                                           s.importe().moneda().getCurrencyCode(),
                                           s.tokenPago(), s.referenciaPedido()))
                    .retrieve()
                    .body(CobroResponse.class);
            return "aprobado".equals(r.estado())
                    ? new ResultadoCobro.Aprobado(r.id())
                    : new ResultadoCobro.Rechazado(r.motivoRechazo());
        } catch (HttpClientErrorException.Conflict e) {
            // 409: misma clave en curso en la pasarela → aún no sabemos el resultado
            return new ResultadoCobro.Indeterminado("Cobro en curso en la pasarela");
        } catch (HttpClientErrorException e) {
            // 4xx (p. ej. 402 tarjeta rechazada): definitivo
            return new ResultadoCobro.Rechazado("HTTP " + e.getStatusCode().value());
        } catch (HttpServerErrorException | ResourceAccessException e) {
            // 5xx o timeout/red: NO sabemos si se cobró
            return new ResultadoCobro.Indeterminado(e.getMessage());
        }
    }
}
```

Registro en `BeanConfiguration` (los timeouts vienen de propiedades):

```java
@Bean
PasarelaDePago pasarelaDePago(RestClient.Builder builder,
                              @Value("${pasarela.url}") String url) {
    return new StripeLikePasarelaAdapter(builder, url, Duration.ofSeconds(2), Duration.ofSeconds(5));
}
```

> **Trampa:** sin `readTimeout`, un socket colgado bloquea el hilo para
> siempre. **Toda** llamada remota lleva timeout. Y no metas reintentos
> automáticos (Resilience4j `@Retry`) sobre un `POST` de cobro **sin clave de
> idempotencia**: estarías automatizando el doble cobro.

### 9.2. Registro de intentos con `JdbcClient`

```sql
-- V6__crear_tabla_intentos_pago.sql
CREATE TABLE intentos_pago (
    clave_idempotencia  VARCHAR(100)   PRIMARY KEY,
    pedido_id           UUID           NOT NULL REFERENCES pedidos(id),
    importe             NUMERIC(12,2)  NOT NULL,
    moneda              CHAR(3)        NOT NULL,
    estado              VARCHAR(30)    NOT NULL,
    referencia_pago     VARCHAR(100),
    motivo              VARCHAR(500),
    creado_en           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    actualizado_en      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_intentos_pago_en_curso ON intentos_pago (creado_en) WHERE estado = 'EN_CURSO';
```

```java
@Override
public boolean registrarInicio(IntentoDePago i) {
    int filas = jdbc.sql("""
            INSERT INTO intentos_pago (clave_idempotencia, pedido_id, importe, moneda, estado)
            VALUES (:clave, :pedido, :importe, :moneda, 'EN_CURSO')
            ON CONFLICT (clave_idempotencia) DO NOTHING
            """)
            .param("clave", i.claveIdempotencia())
            .param("pedido", i.pedidoId().valor())
            .param("importe", i.importe().importe())
            .param("moneda", i.importe().moneda().getCurrencyCode())
            .update();
    return filas == 1;
}
```

El `PRIMARY KEY` sobre la clave es lo que **de verdad** impide la carrera
del doble clic: dos `INSERT` simultáneos, la base de datos deja pasar uno.
La comprobación `buscar(...)` previa es una optimización, no la garantía.

### 9.3. Controlador

```java
@PostMapping("/pedidos/{id}/pago")
ResponseEntity<PagoResponse> pagar(@PathVariable UUID id,
                                   @RequestHeader("Idempotency-Key") String clave,
                                   @RequestBody PagoRequest body) {
    var resultado = pagarPedido.ejecutar(
            new PagarPedidoCommand(new PedidoId(id), clave, body.tokenPago()));

    if (resultado instanceof ResultadoPagoPedido.Pagado p) {
        return ResponseEntity.ok(PagoResponse.pagado(p.referenciaPago()));
    }
    if (resultado instanceof ResultadoPagoPedido.Rechazado r) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(PagoResponse.rechazado(r.motivo()));
    }
    return ResponseEntity.accepted().body(PagoResponse.enProceso());   // EnProceso
}
```

---

## 10. Testing

### 10.1. Unitario del servicio con *fakes* (sin Spring, milisegundos)

Fakes:

```java
/** Sabe si estamos dentro de una "transacción" para verificar R3. */
class EjecutorTransaccionalEspia implements EjecutorTransaccional {
    boolean dentro = false;
    @Override public <T> T enTransaccion(Supplier<T> trabajo) {
        dentro = true;
        try { return trabajo.get(); } finally { dentro = false; }
    }
}

/** Pasarela idempotente por clave, como las de verdad. */
class PasarelaDePagoFake implements PasarelaDePago {
    final Map<String, ResultadoCobro> porClave = new HashMap<>();
    final EjecutorTransaccionalEspia tx;
    ResultadoCobro siguiente = new ResultadoCobro.Aprobado("PAY-1");
    int cobrosReales = 0;

    PasarelaDePagoFake(EjecutorTransaccionalEspia tx) { this.tx = tx; }

    @Override public ResultadoCobro cobrar(SolicitudCobro s) {
        if (tx.dentro) throw new AssertionError("¡Llamada a la pasarela dentro de una transacción!");
        if (siguiente instanceof ResultadoCobro.Indeterminado) {
            cobrosReales++;                                   // se cobró, pero no nos enteramos
            porClave.put(s.claveIdempotencia(), new ResultadoCobro.Aprobado("PAY-1"));
            var r = siguiente;
            siguiente = new ResultadoCobro.Aprobado("PAY-1");
            return r;
        }
        return porClave.computeIfAbsent(s.claveIdempotencia(), k -> {
            if (siguiente instanceof ResultadoCobro.Aprobado) cobrosReales++;
            return siguiente;
        });
    }
}
```

(`RegistroDeIntentosDePagoEnMemoria` es un `Map<String, IntentoDePago>` con
`putIfAbsent` en `registrarInicio`; `PublicadorDeEventosFake` acumula los
eventos en una lista.)

```java
class PagarPedidoServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC);
    private final PedidoRepositoryEnMemoria pedidos = new PedidoRepositoryEnMemoria();
    private final RegistroDeIntentosDePagoEnMemoria intentos = new RegistroDeIntentosDePagoEnMemoria();
    private final EjecutorTransaccionalEspia tx = new EjecutorTransaccionalEspia();
    private final PasarelaDePagoFake pasarela = new PasarelaDePagoFake(tx);
    private final PublicadorDeEventosFake publicador = new PublicadorDeEventosFake();
    private final PagarPedidoService servicio =
            new PagarPedidoService(pedidos, intentos, pasarela, publicador, tx, clock);

    private Pedido pedido;

    @BeforeEach
    void pedidoConfirmado() {
        pedido = Pedido.crear(new ClienteId(UUID.randomUUID()), clock.instant());
        pedido.anadirLinea(new ProductoId("SKU-1"), new Cantidad(2),
                           new Dinero(new BigDecimal("10.00"), Currency.getInstance("EUR")));
        pedido.confirmar(clock.instant());
        pedido.extraerEventos();
        pedidos.guardar(pedido);
    }

    private PagarPedidoCommand cmd(String clave) {
        return new PagarPedidoCommand(pedido.id(), clave, "tok_visa");
    }

    @Test
    void pago_aprobado_marca_pagado_y_publica_PedidoPagado() {
        var resultado = servicio.ejecutar(cmd("K1"));

        assertThat(resultado).isEqualTo(new ResultadoPagoPedido.Pagado(pedido.id(), "PAY-1"));
        assertThat(pedidos.buscarPorId(pedido.id()).orElseThrow().estado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(publicador.publicados()).containsExactly(new PedidoPagado(pedido.id(), clock.instant()));
    }

    @Test
    void la_misma_clave_dos_veces_cobra_una_sola_vez() {
        servicio.ejecutar(cmd("K1"));
        var segundo = servicio.ejecutar(cmd("K1"));

        assertThat(segundo).isInstanceOf(ResultadoPagoPedido.Pagado.class);
        assertThat(pasarela.cobrosReales).isEqualTo(1);
        assertThat(publicador.publicados()).hasSize(1);
    }

    @Test
    void rechazo_deja_el_pedido_confirmado() {
        pasarela.siguiente = new ResultadoCobro.Rechazado("fondos insuficientes");

        var resultado = servicio.ejecutar(cmd("K1"));

        assertThat(resultado).isInstanceOf(ResultadoPagoPedido.Rechazado.class);
        assertThat(pedidos.buscarPorId(pedido.id()).orElseThrow().estado()).isEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(publicador.publicados()).isEmpty();
    }

    @Test
    void timeout_devuelve_EnProceso_y_el_reintento_completa_sin_cobrar_otra_vez() {
        pasarela.siguiente = new ResultadoCobro.Indeterminado("read timeout");

        var primero = servicio.ejecutar(cmd("K1"));
        var reintento = servicio.ejecutar(cmd("K1"));

        assertThat(primero).isInstanceOf(ResultadoPagoPedido.EnProceso.class);
        assertThat(reintento).isInstanceOf(ResultadoPagoPedido.Pagado.class);
        assertThat(pasarela.cobrosReales).isEqualTo(1);
    }

    @Test
    void si_el_pedido_se_cancela_durante_el_cobro_se_marca_para_reembolso() {
        pasarela.siguiente = new ResultadoCobro.Aprobado("PAY-1");
        // Simulamos la cancelación "entre fases" con un repositorio que cancela al recargar
        var servicioConCarrera = new PagarPedidoService(
                new RepositorioQueCancelaEnLaSegundaLectura(pedidos),
                intentos, pasarela, publicador, tx, clock);

        var resultado = servicioConCarrera.ejecutar(cmd("K1"));

        assertThat(resultado).isInstanceOf(ResultadoPagoPedido.Rechazado.class);
        assertThat(intentos.buscar("K1").orElseThrow().estado())
                .isEqualTo(RegistroDeIntentosDePago.EstadoIntento.REQUIERE_REEMBOLSO);
    }
}
```

El `AssertionError` de `PasarelaDePagoFake` convierte el requisito **R3**
("la pasarela no se llama dentro de una transacción") en algo que **un test
comprueba**, no en un comentario que alguien olvidará.

### 10.2. Adaptador de pasarela con WireMock

```java
class StripeLikePasarelaAdapterTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private StripeLikePasarelaAdapter adapter() {
        return new StripeLikePasarelaAdapter(RestClient.builder(), wm.baseUrl(),
                Duration.ofMillis(500), Duration.ofMillis(500));
    }

    private final SolicitudCobro solicitud = new SolicitudCobro("K1", "pedido-42",
            new Dinero(new BigDecimal("20.00"), Currency.getInstance("EUR")), "tok_visa");

    @Test
    void envia_la_clave_de_idempotencia_y_mapea_aprobado() {
        wm.stubFor(post("/v1/cobros").willReturn(okJson(
                "{\"id\":\"PAY-1\",\"estado\":\"aprobado\"}")));

        assertThat(adapter().cobrar(solicitud)).isEqualTo(new ResultadoCobro.Aprobado("PAY-1"));
        wm.verify(postRequestedFor(urlEqualTo("/v1/cobros"))
                .withHeader("Idempotency-Key", equalTo("K1"))
                .withRequestBody(matchingJsonPath("$.token", equalTo("tok_visa")))
                .withRequestBody(matchingJsonPath("$.moneda", equalTo("EUR"))));
    }

    @Test
    void timeout_es_indeterminado_no_rechazado() {
        wm.stubFor(post("/v1/cobros").willReturn(okJson("{}").withFixedDelay(2_000)));

        assertThat(adapter().cobrar(solicitud)).isInstanceOf(ResultadoCobro.Indeterminado.class);
    }

    @Test
    void error_402_es_rechazo_definitivo() {
        wm.stubFor(post("/v1/cobros").willReturn(aResponse().withStatus(402)));

        assertThat(adapter().cobrar(solicitud)).isInstanceOf(ResultadoCobro.Rechazado.class);
    }

    @Test
    void error_503_es_indeterminado() {
        wm.stubFor(post("/v1/cobros").willReturn(aResponse().withStatus(503)));

        assertThat(adapter().cobrar(solicitud)).isInstanceOf(ResultadoCobro.Indeterminado.class);
    }
}
```

### 10.3. Integración de punta a punta (resumen)

Un `@SpringBootTest` con Testcontainers (PostgreSQL) y WireMock para la
pasarela que:

1. Crea y confirma un pedido.
2. Hace `POST /pedidos/{id}/pago` con `Idempotency-Key: K1` dos veces.
3. Verifica: pedido `PAGADO` en BD, **una** fila `PedidoPagado` en `outbox`,
   intento `APROBADO`, y que WireMock recibió las peticiones con la misma
   clave (`wm.verify(...)`).

Es el test más caro; basta con uno o dos. Los escenarios de fallo se cubren
en los unitarios (10.1), que tardan milisegundos.

---

## 11. Resumen: lo que hemos combinado

| Pieza del día                              | Dónde aparece en `PagarPedido`                             |
|--------------------------------------------|------------------------------------------------------------|
| Bloque 1: versión optimista                | Conflicto en fase 3 → reintento                            |
| Bloque 1: Flyway                           | `intentos_pago`, `outbox`                                  |
| Bloque 2: Repository orientado a persistencia | `guardar` explícito en fase 3                           |
| Bloque 3: `EjecutorTransaccional`          | Dos transacciones cortas y la llamada externa fuera        |
| Bloque 4: eventos de dominio + outbox      | `PedidoPagado` registrado por el agregado y publicado vía outbox |
| Bloque 4: idempotencia                     | Clave de idempotencia de punta a punta (cliente → BD → pasarela) |
| Bloque 4: compensación                     | `REQUIERE_REEMBOLSO` + job de compensación                 |
| Día 5: testing                             | Fakes con espía de transacción, WireMock, Testcontainers   |

---

## Ejercicio

Implementa el caso de uso `ReembolsarPagoUseCase` que procesa los intentos
en estado `REQUIERE_REEMBOLSO`:

1. Añade al puerto `PasarelaDePago` la operación de reembolso.
2. ¿Qué clave de idempotencia usas para el reembolso?
3. Escribe el servicio con las mismas tres fases.
4. Escribe un test que demuestre que, si el job se ejecuta dos veces, solo
   se reembolsa una.

**Pista:** la clave del reembolso debe ser **determinista** a partir del
intento original, para que el segundo job genere la misma.

<details><summary>Solución</summary>

```java
// En PasarelaDePago
ResultadoCobro reembolsar(String claveIdempotencia, String referenciaPago, Dinero importe);
```

Clave: `"reembolso-" + intento.claveIdempotencia()`. Determinista: dos
ejecuciones del job generan la misma clave y la pasarela deduplica.

```java
public void ejecutar(String claveIntento) {
    IntentoDePago intento = tx.enTransaccion(() -> intentos.buscar(claveIntento)
            .filter(i -> i.estado() == EstadoIntento.REQUIERE_REEMBOLSO)
            .orElse(null));
    if (intento == null) return;                                   // ya procesado

    var resultado = pasarela.reembolsar("reembolso-" + intento.claveIdempotencia(),
                                        intento.referenciaPago(), intento.importe());   // fuera de tx

    if (resultado instanceof ResultadoCobro.Aprobado) {
        tx.enTransaccion(() -> intentos.actualizar(intento.reembolsado()));   // nuevo estado REEMBOLSADO
    }
    // Rechazado → alerta a operaciones; Indeterminado → el siguiente job reintenta con la misma clave
}
```

Test: con `PasarelaDePagoFake` contando reembolsos por clave, ejecutar el
servicio dos veces y comprobar `reembolsosReales == 1` y estado final
`REEMBOLSADO`. Añade `REEMBOLSADO` al `enum EstadoIntento` y el método
`reembolsado()` al `record`.

</details>

---

## Referencias

- Chris Richardson, *Pattern: Transactional Outbox*: https://microservices.io/patterns/data/transactional-outbox.html
- Chris Richardson, *Pattern: Saga*: https://microservices.io/patterns/data/saga.html
- Spring Framework, *Transaction Management* (gestión programática con `TransactionTemplate`): https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- Spring Boot, `RestClient` y clientes HTTP: https://docs.spring.io/spring-boot/index.html
- WireMock (simular retrasos y fallos): https://wiremock.org/docs/
- Testcontainers: https://java.testcontainers.org/
- Martin Fowler, catálogo PoEAA (Unit of Work, Optimistic Offline Lock): https://martinfowler.com/eaaCatalog/
- Vlad Mihalcea, sobre transacciones largas y pools de conexiones: https://vladmihalcea.com/
- Stripe, *Idempotent requests* (referencia del mecanismo `Idempotency-Key`): https://docs.stripe.com/api/idempotent_requests

---

## Mantra

> *"La base de datos no puede deshacer el mundo. Cobra fuera de la
> transacción, apunta dentro, y que la misma clave viaje de punta a punta."*
