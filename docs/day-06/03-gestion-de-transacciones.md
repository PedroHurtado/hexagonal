# Día 6 — Bloque 3: Gestión de transacciones

> *"A Unit of Work keeps track of everything you do during a business
> transaction that can affect the database."*
> — Martin Fowler, *Patterns of Enterprise Application Architecture* (2002)

---

## 1. El problema en una frase

Un caso de uso tiene que ser **todo o nada**, pero la transacción es un
concepto de infraestructura (JDBC, JPA, Spring). **¿Dónde se abre y se cierra
la transacción sin meter Spring en la capa de aplicación… o metiéndolo con
conocimiento de causa?**

---

## 2. Por qué el límite transaccional es el caso de uso

El caso de uso es la **unidad de trabajo de negocio**: "confirmar el pedido
42". Todo lo que ocurre dentro (cargar, validar, cambiar estado, guardar,
apuntar un evento en el outbox) debe confirmarse junto o revertirse junto.

```
 Adaptador de entrada        Servicio de aplicación (caso de uso)         Adaptadores de salida
┌──────────────────┐     ┌──────────────────────────────────────────┐    ┌────────────────────┐
│ PedidoController │────►│ ╔══════════ TRANSACCIÓN ══════════════╗  │    │                    │
│                  │     │ ║ buscarPorId ─────────────────────────╫──┼───►│ PedidoRepository   │
│                  │     │ ║ pedido.confirmar()   (dominio puro)  ║  │    │                    │
│                  │     │ ║ guardar ─────────────────────────────╫──┼───►│ PedidoRepository   │
│                  │     │ ║ outbox.registrar(evento) ────────────╫──┼───►│ Outbox             │
│                  │     │ ╚══════════════ commit / rollback ═════╝  │    │                    │
└──────────────────┘     └──────────────────────────────────────────┘    └────────────────────┘
```

- **No** en el controlador: un controlador puede llamar a varios casos de uso
  o a ninguno; y mezclaría HTTP con transacciones.
- **No** en el adaptador de persistencia: cada `guardar` sería su propia
  transacción y el caso de uso dejaría de ser atómico.
- **No** en el dominio: el dominio no sabe que existe una base de datos.

---

## 3. Bad: transacciones en el sitio equivocado

```java
@Component
class PedidoPersistenceAdapter implements PedidoRepository {

    @Transactional                       // ← cada método, su transacción
    public void guardar(Pedido pedido) { ... }
}

public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {
    public void ejecutar(ConfirmarPedidoCommand cmd) {
        Pedido pedido = repository.buscarPorId(cmd.pedidoId()).orElseThrow(...);
        pedido.confirmar();
        repository.guardar(pedido);          // tx 1: commit
        outbox.registrar(PedidoConfirmado…); // tx 2: si falla, el pedido ya está confirmado
    }
}
```

### ¿Qué duele?

- Dos transacciones donde debería haber una: el pedido queda confirmado sin
  su evento. Inconsistencia silenciosa.
- La lectura (`buscarPorId`) y la escritura van en transacciones distintas:
  entre medias, otro proceso puede cambiar el pedido.
- Nadie puede saber mirando el servicio qué es atómico y qué no.

---

## 4. Tres formas correctas de colocar la transacción

### 4.1. Opción A — `@Transactional` en el servicio de aplicación (pragmática)

```java
package com.curso.pedidos.application.service;

import org.springframework.transaction.annotation.Transactional;

public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {

    private final PedidoRepository repository;
    private final Clock clock;

    public ConfirmarPedidoService(PedidoRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void ejecutar(ConfirmarPedidoCommand cmd) {
        Pedido pedido = repository.buscarPorId(cmd.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(cmd.pedidoId()));
        pedido.confirmar();
        repository.guardar(pedido);
    }
}
```

**Funciona aunque el bean se cree con `@Bean` en `BeanConfiguration`**: el
proxy transaccional de Spring se aplica a cualquier bean del contexto, no
solo a los anotados con `@Service`.

| Pro                                                      | Contra                                                           |
|----------------------------------------------------------|------------------------------------------------------------------|
| Es lo que todo desarrollador Spring entiende al instante | La capa de aplicación importa `org.springframework.transaction`  |
| Cero código extra                                        | ArchUnit "aplicación sin Spring" necesita una excepción          |
| Atributos declarativos (`readOnly`, `timeout`, `rollbackFor`) | Las trampas de proxies (sección 6) siguen ahí                  |

> **Matiz:** puedes usar `jakarta.transaction.Transactional` (JTA), que
> Spring también respeta. Sigue siendo una dependencia de infraestructura,
> pero de un estándar, no de Spring. Algunos equipos lo aceptan como "mal
> menor".

### 4.2. Opción B — Decorador transaccional en la configuración

La aplicación queda **limpia**; la transacción se añade desde fuera, en
`infrastructure.config`, envolviendo el caso de uso con un decorador (GoF)
que usa `TransactionTemplate`.

```java
package com.curso.pedidos.infrastructure.config;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import org.springframework.transaction.support.TransactionTemplate;

final class ConfirmarPedidoTransaccional implements ConfirmarPedidoUseCase {

    private final ConfirmarPedidoUseCase delegado;
    private final TransactionTemplate tx;

    ConfirmarPedidoTransaccional(ConfirmarPedidoUseCase delegado, TransactionTemplate tx) {
        this.delegado = delegado;
        this.tx = tx;
    }

    @Override
    public void ejecutar(ConfirmarPedidoCommand cmd) {
        tx.executeWithoutResult(status -> delegado.ejecutar(cmd));
    }
}
```

```java
@Configuration
public class BeanConfiguration {

    @Bean
    ConfirmarPedidoUseCase confirmarPedidoUseCase(PedidoRepository repo, Clock clock,
                                                  PlatformTransactionManager txManager) {
        var servicio = new ConfirmarPedidoService(repo, clock);   // sin Spring dentro
        return new ConfirmarPedidoTransaccional(servicio, new TransactionTemplate(txManager));
    }
}
```

**Trampa:** un decorador por caso de uso es mucho código repetido. Si todos
los casos de uso siguen la convención `ejecutar(XxxCommand)`, puedes
generalizar con un `java.lang.reflect.Proxy` o con un aspecto
(`@Around` sobre un paquete). Pero entonces vuelves a la "magia" de la
opción A con más complejidad. Elige con criterio.

### 4.3. Opción C — Un puerto de transacción propio (Unit of Work)

La aplicación **sí** decide qué es atómico (información de negocio), pero lo
expresa con un puerto propio, sin Spring:

```java
package com.curso.pedidos.application.port.out;

import java.util.function.Supplier;

/** Unit of Work: ejecuta un bloque de forma atómica. */
public interface EjecutorTransaccional {

    <T> T enTransaccion(Supplier<T> trabajo);

    default void enTransaccion(Runnable trabajo) {
        enTransaccion(() -> { trabajo.run(); return null; });
    }
}
```

```java
package com.curso.pedidos.application.service;

public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {

    private final PedidoRepository repository;
    private final EjecutorTransaccional tx;

    public ConfirmarPedidoService(PedidoRepository repository, EjecutorTransaccional tx) {
        this.repository = repository;
        this.tx = tx;
    }

    @Override
    public void ejecutar(ConfirmarPedidoCommand cmd) {
        tx.enTransaccion(() -> {
            Pedido pedido = repository.buscarPorId(cmd.pedidoId())
                    .orElseThrow(() -> new PedidoNoEncontradoException(cmd.pedidoId()));
            pedido.confirmar();
            repository.guardar(pedido);
        });
    }
}
```

El adaptador:

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

import com.curso.pedidos.application.port.out.EjecutorTransaccional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class SpringEjecutorTransaccionalAdapter implements EjecutorTransaccional {

    private final TransactionTemplate template;

    SpringEjecutorTransaccionalAdapter(PlatformTransactionManager txManager) {
        this.template = new TransactionTemplate(txManager);
    }

    @Override
    public <T> T enTransaccion(Supplier<T> trabajo) {
        return template.execute(status -> trabajo.get());
    }
}
```

Esta opción brilla cuando **un caso de uso necesita varias transacciones o
partes fuera de transacción**. Es exactamente lo que pasa en `PagarPedido`
(bloque 5): leer en una transacción, llamar a la pasarela **fuera**, y
guardar el resultado en otra.

### 4.4. Comparativa

| Criterio                                 | A: `@Transactional` en servicio | B: Decorador en config | C: Puerto `EjecutorTransaccional` |
|------------------------------------------|---------------------------------|------------------------|-----------------------------------|
| Aplicación libre de Spring               | No                              | Sí                     | Sí                                |
| Control fino (varias tx en un caso de uso) | Difícil                       | No                     | **Sí**                            |
| Código extra                             | Ninguno                         | Un decorador por caso  | Un puerto + un adaptador          |
| Tests unitarios del servicio             | La anotación se ignora          | Servicio sin tx        | *Fake* que ejecuta el bloque      |
| Visibilidad del límite en el código      | Anotación                       | En la config           | **Explícita en el servicio**      |
| Recomendado para                         | Equipos Spring, mayoría de casos | Puristas, pocos casos | Casos con llamadas externas       |

> **En cristiano:** si tu equipo no va a cambiar de Spring en la vida, la A
> es perfectamente defendible (Tom Hombergs la usa en *BuckPal*). Si quieres
> el núcleo limpio o tienes casos de uso con llamadas remotas, la C.

---

## 5. Propagación, aislamiento y `readOnly`

### 5.1. Propagación (lo que necesitas saber)

| Propagación     | Comportamiento                                                       | Uso típico                                    |
|-----------------|----------------------------------------------------------------------|-----------------------------------------------|
| `REQUIRED` (defecto) | Se une a la transacción existente o crea una                    | Casi siempre                                  |
| `REQUIRES_NEW`  | Suspende la actual y abre una nueva independiente                    | Auditoría/log que debe persistir aunque falle lo demás |
| `MANDATORY`     | Exige una transacción existente; si no, excepción                    | Adaptadores que **nunca** deben ir sueltos    |
| `NOT_SUPPORTED` | Suspende la transacción actual                                       | Llamadas lentas que no deben retener conexión |
| `NESTED`        | *Savepoint* dentro de la actual (solo JDBC, no JPA en general)       | Raro                                          |

> **Truco hexagonal:** anota el adaptador de persistencia con
> `@Transactional(propagation = Propagation.MANDATORY)`. Si alguien llama a
> `guardar` sin haber abierto la transacción en el caso de uso, falla en el
> primer test en lugar de funcionar "a medias" en producción.

> **Trampa (`REQUIRES_NEW`):** abre **otra conexión** mientras la primera
> sigue retenida. Bajo carga, con un pool de 10 conexiones y 10 hilos
> esperando la segunda, tienes un *deadlock* del pool.

### 5.2. Aislamiento

| Nivel             | Evita                                   | Notas                                         |
|-------------------|-----------------------------------------|-----------------------------------------------|
| `READ_COMMITTED`  | Lecturas sucias                         | Defecto en PostgreSQL. Lo normal.             |
| `REPEATABLE_READ` | + lecturas no repetibles                | En PostgreSQL, *snapshot isolation*           |
| `SERIALIZABLE`    | + fantasmas, *write skew*               | Hay que reintentar por fallos de serialización |

Con bloqueo optimista (`@Version`, bloque 1) `READ_COMMITTED` suele bastar
para agregados. Sube el aislamiento solo con un motivo concreto.

### 5.3. `readOnly = true`

```java
@Transactional(readOnly = true)
public Optional<PedidoResumenView> porId(UUID id) { ... }
```

- Hibernate desactiva el *dirty checking* y el *flush* (menos memoria y CPU).
- El driver puede enrutar a una réplica de lectura (si configuras un
  `RoutingDataSource`).
- **No** impide escribir a nivel de SQL en todos los motores: es una pista,
  no un candado.

En el lado de lectura con `JdbcClient` (bloque 2), a menudo **no necesitas
transacción** salvo que hagas varias consultas que deban ver la misma foto
(como el `SELECT` + `COUNT` de la paginación).

---

## 6. La trampa del *self-invocation* y los proxies

`@Transactional` funciona porque Spring envuelve tu bean en un **proxy**. La
transacción se abre cuando la llamada **pasa por el proxy**.

```
  Llamada externa                               Llamada interna (this.metodo())
┌────────────┐   ┌──────────────┐   ┌──────────┐    ┌──────────────────────────┐
│ Controller │──►│ Proxy (tx)   │──►│ Servicio │    │ Servicio                 │
└────────────┘   │ begin/commit │   └──────────┘    │  procesar() ──► this.    │
                 └──────────────┘                   │    guardarTodo()  ← SIN  │
                                                    │    @Transactional real   │
                                                    └──────────────────────────┘
```

### Bad

```java
public class ImportarPedidosService {

    public void importar(List<CrearPedidoCommand> cmds) {
        for (var cmd : cmds) {
            importarUno(cmd);             // this.importarUno(): NO pasa por el proxy
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void importarUno(CrearPedidoCommand cmd) { ... }   // nunca abre transacción
}
```

### ¿Qué duele?

El código **parece** correcto, compila y los tests unitarios pasan (no hay
proxy en un test unitario). En producción, cada `importarUno` corre **sin
transacción** (o dentro de la del llamador). Si uno falla, no se revierte lo
que querías.

Otras variantes de la misma trampa:

- `@Transactional` en un método `private`: el proxy no lo intercepta (con
  proxies de CGLIB, Spring 6 sí intercepta métodos `protected` y de
  paquete, pero nunca `private`).
- Bean creado con `new` fuera del contexto: no hay proxy.
- Excepciones *checked*: por defecto **no** provocan rollback (solo
  `RuntimeException` y `Error`). Usa `rollbackFor` o, mejor, excepciones de
  dominio que extiendan `RuntimeException` (como `PedidoException`).

### Good

Extrae el método a otro bean, o usa el puerto `EjecutorTransaccional`
(opción C), que no depende de proxies:

```java
public void importar(List<CrearPedidoCommand> cmds) {
    for (var cmd : cmds) {
        tx.enTransaccion(() -> crearPedido.ejecutar(cmd));   // una tx por pedido, explícita
    }
}
```

---

## 7. Un agregado por transacción

Regla de Vernon (*Effective Aggregate Design*): **una transacción modifica
un solo agregado**. Los demás se actualizan por **consistencia eventual**
(bloque 4).

### Bad

```java
@Transactional
public void ejecutar(ConfirmarPedidoCommand cmd) {
    Pedido pedido = pedidos.buscarPorId(cmd.pedidoId()).orElseThrow(...);
    pedido.confirmar();
    pedidos.guardar(pedido);

    for (LineaPedido l : pedido.lineas()) {
        Producto p = catalogo.buscar(l.productoId());
        p.reservarStock(l.cantidad());          // ← otro agregado
        catalogo.guardar(p);                    // ← en la misma tx
    }
    Cliente c = clientes.buscar(pedido.clienteId());
    c.sumarPuntosFidelidad(pedido.total());     // ← y otro más
    clientes.guardar(c);
}
```

### ¿Qué duele?

- Bloqueos en cadena: el producto "SKU-1" está en miles de pedidos; cada
  confirmación lo bloquea y los conflictos de `@Version` se disparan.
- Transacciones largas = conexiones retenidas = pool agotado.
- Si mañana `Producto` vive en otro microservicio, el caso de uso entero
  hay que reescribirlo.

### Good

```java
tx.enTransaccion(() -> {
    Pedido pedido = pedidos.buscarPorId(cmd.pedidoId()).orElseThrow(...);
    pedido.confirmar();                     // registra PedidoConfirmado
    pedidos.guardar(pedido);
    outbox.registrar(pedido.extraerEventos());
});
// Otros casos de uso, disparados por PedidoConfirmado, reservan stock y
// suman puntos, cada uno en SU transacción.
```

> **Excepción razonable:** cuando la regla de negocio exige consistencia
> inmediata entre dos agregados y ambos viven en la misma base de datos,
> puedes romper la regla conscientemente. Documenta el porqué (un ADR).

---

## 8. Testing

### 8.1. Unitario: un *fake* del `EjecutorTransaccional`

```java
/** Ejecuta el bloque directamente y cuenta cuántas transacciones se abren. */
class EjecutorTransaccionalFake implements EjecutorTransaccional {
    int transaccionesAbiertas = 0;

    @Override
    public <T> T enTransaccion(Supplier<T> trabajo) {
        transaccionesAbiertas++;
        return trabajo.get();
    }
}
```

```java
class ConfirmarPedidoServiceTest {

    private final PedidoRepositoryEnMemoria repo = new PedidoRepositoryEnMemoria();
    private final EjecutorTransaccionalFake tx = new EjecutorTransaccionalFake();
    private final ConfirmarPedidoService servicio = new ConfirmarPedidoService(repo, tx);

    @Test
    void confirma_dentro_de_una_unica_transaccion() {
        Pedido pedido = pedidoConUnaLinea();
        repo.guardar(pedido);

        servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id()));

        assertThat(repo.buscarPorId(pedido.id()).orElseThrow().estado())
                .isEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(tx.transaccionesAbiertas).isEqualTo(1);
    }
}
```

### 8.2. Integración: el rollback ocurre de verdad

Para probar un **rollback** no puedes anotar el test con `@Transactional`
(el propio test revertiría todo y no verías nada). Usa `@SpringBootTest` con
Testcontainers y comprueba el estado con una consulta independiente.

```java
@SpringBootTest
@Testcontainers
class RollbackTransaccionalIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired EjecutorTransaccional tx;
    @Autowired PedidoRepository repository;
    @Autowired JdbcClient jdbc;

    @Test
    void si_el_bloque_falla_no_queda_nada_en_bd() {
        var pedido = Pedido.crear(new ClienteId(UUID.randomUUID()), Instant.now());

        assertThatThrownBy(() -> tx.enTransaccion(() -> {
            repository.guardar(pedido);
            throw new IllegalStateException("fallo simulado tras guardar");
        })).isInstanceOf(IllegalStateException.class);

        Long filas = jdbc.sql("SELECT COUNT(*) FROM pedidos WHERE id = :id")
                         .param("id", pedido.id().valor())
                         .query(Long.class).single();
        assertThat(filas).isZero();
    }

    @Test
    void el_adaptador_exige_transaccion() {   // si usas MANDATORY en el adaptador
        var pedido = Pedido.crear(new ClienteId(UUID.randomUUID()), Instant.now());
        assertThatThrownBy(() -> repository.guardar(pedido))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
```

> **Trampa clásica de test:** `@DataJpaTest` es `@Transactional` por defecto
> y hace rollback al final de cada test. Genial para aislar tests,
> **terrible** para probar comportamiento transaccional: todo pasa dentro de
> una sola transacción que nunca hace commit. Los `@TransactionalEventListener
> (AFTER_COMMIT)` del bloque 4, por ejemplo, **nunca** se disparan ahí.

---

## 9. Resumen

| Pregunta                                      | Respuesta corta                                                       |
|-----------------------------------------------|-----------------------------------------------------------------------|
| ¿Dónde empieza y acaba la transacción?        | En el caso de uso                                                     |
| ¿Cómo sin Spring en aplicación?               | Decorador en config o puerto `EjecutorTransaccional`                  |
| ¿Y con Spring en aplicación?                  | `@Transactional` en el servicio; aceptable y muy común                |
| ¿Por qué no funciona mi `@Transactional`?     | *Self-invocation*, método `private`, bean sin proxy, excepción *checked* |
| ¿Cuántos agregados por transacción?           | Uno; el resto, por eventos                                            |
| ¿Cómo pruebo el rollback?                     | `@SpringBootTest` sin `@Transactional` + consulta independiente       |

---

## Ejercicio

El servicio `CancelarPedidoService` tiene este código:

```java
public class CancelarPedidoService implements CancelarPedidoUseCase {
    public void ejecutar(CancelarPedidoCommand cmd) {
        Pedido p = repo.buscarPorId(cmd.pedidoId()).orElseThrow(...);
        p.cancelar();
        repo.guardar(p);
        notificador.notificarCancelacion(p.clienteId(), p.id());   // envía email (SMTP, 2 s)
        auditoria.registrar("CANCELADO", p.id());                  // debe quedar SIEMPRE
    }
}
```

Requisitos: la cancelación es atómica; el email **no** debe retener la
conexión a BD; la auditoría debe quedar registrada **aunque falle la
cancelación**. Rediséñalo con `EjecutorTransaccional`.

**Pista:** son tres bloques con necesidades transaccionales distintas.

<details><summary>Solución</summary>

```java
public void ejecutar(CancelarPedidoCommand cmd) {
    try {
        Pedido p = tx.enTransaccion(() -> {
            Pedido pedido = repo.buscarPorId(cmd.pedidoId())
                    .orElseThrow(() -> new PedidoNoEncontradoException(cmd.pedidoId()));
            pedido.cancelar();
            repo.guardar(pedido);
            return pedido;
        });
        notificador.notificarCancelacion(p.clienteId(), p.id());   // fuera de tx
        tx.enTransaccion(() -> auditoria.registrar("CANCELADO", cmd.pedidoId()));
    } catch (PedidoException e) {
        tx.enTransaccion(() -> auditoria.registrar("CANCELACION_FALLIDA", cmd.pedidoId()));
        throw e;
    }
}
```

Mejor aún: el email no debería enviarse síncronamente; registra
`PedidoCancelado` en el outbox dentro de la primera transacción y que un
consumidor envíe el email (bloque 4). Así, si el SMTP está caído, el email
se reintenta y el usuario no espera 2 segundos.

Si el `EjecutorTransaccional` se llama desde dentro de otra transacción,
la auditoría debería usar `REQUIRES_NEW`: añade un método
`enTransaccionNueva(...)` al puerto, implementado con un
`TransactionTemplate` con `setPropagationBehavior(PROPAGATION_REQUIRES_NEW)`.

</details>

---

## Referencias

- Spring Framework, *Transaction Management* (declarativa, programática, propagación, proxies): https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- Martin Fowler, *Unit of Work* en el catálogo PoEAA: https://martinfowler.com/eaaCatalog/
- Vaughn Vernon, *Implementing Domain-Driven Design* (2013), cap. 10 "Aggregates" (*Effective Aggregate Design*).
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023); repo https://github.com/thombergs/buckpal
- Spring Framework testing (transacciones en tests): https://docs.spring.io/spring-framework/reference/testing.html
- Vlad Mihalcea, sobre aislamiento, propagación y `readOnly`: https://vladmihalcea.com/
- Gamma et al., *Design Patterns* (1994): patrón Decorator.

---

## Mantra

> *"La transacción abraza al caso de uso, no al repositorio. Corta, de un
> solo agregado, y sin nadie esperando a la red dentro."*
