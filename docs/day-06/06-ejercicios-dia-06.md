# Día 6 — Bloque 6: Ejercicios y repaso

Cuatro ejercicios graduados, de "calentar" a "esto es un sistema de
verdad". En clase da tiempo a uno o dos (25 min); el resto, para casa.
Todos trabajan sobre el proyecto `code/` y el dominio Pedidos.

| #  | Ejercicio                                       | Nivel        | Bloques que practica | Tiempo orientativo |
|----|-------------------------------------------------|--------------|----------------------|--------------------|
| 1  | Versión optimista de punta a punta (ETag/409)   | Básico       | 1, 3                 | 20 min             |
| 2  | Listado de pedidos con CQRS ligero y paginación | Intermedio   | 2                    | 25 min             |
| 3  | Outbox para `PedidoCancelado` + consumidor idempotente | Intermedio-alto | 3, 4         | 40 min             |
| 4  | Saga orquestada Pedido → Pago → Envío           | Avanzado     | 4, 5                 | 60+ min            |

---

## Ejercicio 1 — Versión optimista de punta a punta

### Enunciado

Hoy dos usuarios de back-office pueden pisarse al modificar un pedido.
Implementa el control de concurrencia completo para el caso de uso
`ConfirmarPedidoUseCase`:

1. `GET /pedidos/{id}` devuelve la cabecera `ETag` con la versión.
2. `POST /pedidos/{id}/confirmar` exige `If-Match`. El comando
   `ConfirmarPedidoCommand` lleva la versión esperada.
3. Si la versión no coincide, la API responde **409 Conflict**.
4. Test de integración (Testcontainers) que reproduzca el conflicto.

**Pista:** el agregado transporta `Long version` como dato opaco (bloque 1,
sección 8). El servicio debe comparar la versión del comando con la del
agregado cargado **antes** de modificar nada.

<details><summary>Solución</summary>

Comando:

```java
public record ConfirmarPedidoCommand(PedidoId pedidoId, long versionEsperada) { }
```

Servicio (con `EjecutorTransaccional`):

```java
public void ejecutar(ConfirmarPedidoCommand cmd) {
    tx.enTransaccion(() -> {
        Pedido pedido = repository.buscarPorId(cmd.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(cmd.pedidoId()));
        if (pedido.version() == null || pedido.version() != cmd.versionEsperada()) {
            throw new ConflictoDeConcurrenciaException(cmd.pedidoId(),
                    cmd.versionEsperada(), pedido.version());
        }
        pedido.confirmar(clock.instant());
        repository.guardar(pedido);
        publicador.publicar(pedido.extraerEventos());
    });
}
```

Controlador:

```java
@GetMapping("/pedidos/{id}")
ResponseEntity<PedidoResponse> obtener(@PathVariable UUID id) {
    var vista = consultar.detalle(id).orElseThrow(() -> new PedidoNoEncontradoException(new PedidoId(id)));
    return ResponseEntity.ok().eTag("\"" + vista.version() + "\"").body(PedidoResponse.de(vista));
}

@PostMapping("/pedidos/{id}/confirmar")
ResponseEntity<Void> confirmar(@PathVariable UUID id, @RequestHeader("If-Match") String ifMatch) {
    long version = Long.parseLong(ifMatch.replace("\"", ""));
    confirmarPedido.ejecutar(new ConfirmarPedidoCommand(new PedidoId(id), version));
    return ResponseEntity.noContent().build();
}
```

`ControllerAdvice`:

```java
@ExceptionHandler(ConflictoDeConcurrenciaException.class)
ProblemDetail conflicto(ConflictoDeConcurrenciaException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "El pedido ha sido modificado por otro usuario. Recárgalo.");
}
```

Test:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ConcurrenciaPedidoIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;

    @Test
    void segunda_modificacion_con_version_obsoleta_da_409() throws Exception {
        UUID id = crearPedidoConUnaLinea();   // helper vía API o repositorio
        String etag = mvc.perform(get("/pedidos/{id}", id))
                .andReturn().getResponse().getHeader("ETag");

        mvc.perform(post("/pedidos/{id}/lineas", id).header("If-Match", etag)
                .contentType(APPLICATION_JSON).content(lineaJson()))   // sube la versión
           .andExpect(status().is2xxSuccessful());

        mvc.perform(post("/pedidos/{id}/confirmar", id).header("If-Match", etag))
           .andExpect(status().isConflict());
    }
}
```

</details>

---

## Ejercicio 2 — Listado con CQRS ligero y paginación

### Enunciado

El back-office necesita `GET /backoffice/pedidos?estado=CONFIRMADO&pagina=0&tamano=20`
con columnas: id, cliente, fecha, estado, total, nº de líneas. Ordenado por
fecha descendente.

1. Amplía `ConsultarPedidoQuery` con un método de búsqueda filtrada.
2. Implementa el adaptador con `JdbcClient`, sin pasar por el agregado.
3. Devuelve `PaginaDe<PedidoResumenView>`, **no** `Page` de Spring Data.
4. Tope de tamaño de página: 100 (regla de aplicación, testeada en unitario).
5. Test de integración del adaptador con `@JdbcTest` + Testcontainers.

**Pista:** el filtro `estado` es opcional. Cuidado con construir SQL
concatenando strings con valores del usuario.

<details><summary>Solución</summary>

Puerto (filtro como `record` para no multiplicar parámetros):

```java
public interface ConsultarPedidoQuery {
    // ...
    PaginaDe<PedidoResumenView> buscar(FiltroPedidos filtro, int pagina, int tamano);

    record FiltroPedidos(String estado) {          // null = todos
        public FiltroPedidos {
            if (estado != null) EstadoPedido.valueOf(estado);   // valida contra el enum
        }
    }
}
```

Servicio:

```java
@Override
public PaginaDe<PedidoResumenView> buscar(FiltroPedidos filtro, int pagina, int tamano) {
    return lecturas.buscar(filtro, pagina, Math.min(tamano, 100));
}
```

Adaptador (el SQL es fijo; solo cambian **parámetros**, nunca texto del usuario):

```java
@Override
public PaginaDe<PedidoResumenView> buscar(FiltroPedidos filtro, int pagina, int tamano) {
    var filas = jdbc.sql(SELECT_RESUMEN + """
             WHERE (CAST(:estado AS VARCHAR) IS NULL OR p.estado = :estado)
             GROUP BY p.id
             ORDER BY p.creado_en DESC
             LIMIT :lim OFFSET :off
            """)
            .param("estado", filtro.estado())
            .param("lim", tamano)
            .param("off", pagina * tamano)
            .query(PedidoResumenView.class).list();

    long total = jdbc.sql("""
            SELECT COUNT(*) FROM pedidos p
             WHERE (CAST(:estado AS VARCHAR) IS NULL OR p.estado = :estado)
            """)
            .param("estado", filtro.estado())
            .query(Long.class).single();

    return new PaginaDe<>(filas, pagina, tamano, total);
}
```

(El `CAST` evita que PostgreSQL se queje de no poder inferir el tipo de un
parámetro `null`.)

Test unitario del tope:

```java
@Test
void el_tamano_de_pagina_se_limita_a_100() {
    PedidoLecturas lecturas = mock(PedidoLecturas.class);
    when(lecturas.buscar(any(), anyInt(), anyInt())).thenReturn(new PaginaDe<>(List.of(), 0, 100, 0));
    new ConsultarPedidoService(lecturas).buscar(new FiltroPedidos(null), 0, 5000);
    verify(lecturas).buscar(any(), eq(0), eq(100));
}
```

Test de integración: inserta con `@Sql` tres pedidos (dos `CONFIRMADO`, uno
`BORRADOR`) y comprueba que el filtro devuelve 2, que sin filtro devuelve 3
y que el orden es por fecha descendente.

</details>

---

## Ejercicio 3 — Outbox para `PedidoCancelado` y consumidor idempotente

### Enunciado

1. `Pedido.cancelar(Instant)` registra `PedidoCancelado`.
2. `CancelarPedidoService` lo publica vía `PublicadorDeEventos` (adaptador
   outbox) en la misma transacción que el cambio de estado.
3. Implementa en el mismo proyecto un consumidor (simulando el servicio de
   Catálogo) que, al recibir `PedidoCancelado`, **libera stock**. Debe ser
   idempotente usando la tabla `mensajes_procesados`.
4. Tests:
   - unitario del dominio (evento registrado; no se cancela un `ENVIADO`),
   - integración: cancelar deja exactamente una fila en `outbox`,
   - integración: si el caso de uso falla después de registrar el evento,
     **no** queda fila en `outbox` (rollback),
   - consumidor: el mismo `mensaje-id` dos veces libera stock una vez.

**Pista:** para forzar el fallo tras registrar el evento en el test de
rollback, puedes usar un `PedidoRepository` decorado que lance excepción en
`guardar`… pero ojo con el orden: en el servicio, ¿qué va antes, `guardar` o
`publicar`? Para el test, invierte la situación con un publicador que
inserta y luego lanza.

<details><summary>Solución</summary>

Dominio:

```java
public void cancelar(Instant ahora) {
    if (estado == EstadoPedido.ENVIADO) {
        throw new TransicionDeEstadoInvalidaException("No se cancela un pedido enviado");
    }
    if (estado == EstadoPedido.CANCELADO) {
        return;                                  // idempotencia natural
    }
    estado = EstadoPedido.CANCELADO;
    eventos.add(new PedidoCancelado(id, ahora));
}
```

Test de rollback del outbox:

```java
@Test
void si_falla_despues_de_publicar_no_queda_nada_en_outbox() {
    Pedido pedido = pedidoConfirmadoGuardado();

    assertThatThrownBy(() -> tx.enTransaccion(() -> {
        Pedido p = repository.buscarPorId(pedido.id()).orElseThrow();
        p.cancelar(Instant.now());
        repository.guardar(p);
        publicador.publicar(p.extraerEventos());      // INSERT en outbox
        throw new IllegalStateException("fallo tras publicar");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE agregado_id = :id")
            .param("id", pedido.id().valor()).query(Long.class).single()).isZero();
    assertThat(repository.buscarPorId(pedido.id()).orElseThrow().estado())
            .isEqualTo(EstadoPedido.CONFIRMADO);
}
```

Consumidor idempotente:

```java
@KafkaListener(topics = "pedidos.eventos", groupId = "catalogo")
void alRecibir(@Payload String payload,
               @Header("tipo-evento") String tipo,
               @Header("mensaje-id") String mensajeId) {
    if (!"PedidoCancelado".equals(tipo)) return;
    tx.enTransaccion(() -> {
        int nuevas = jdbc.sql("""
                INSERT INTO mensajes_procesados (mensaje_id, consumidor)
                VALUES (:id, 'catalogo.pedido-cancelado') ON CONFLICT DO NOTHING
                """).param("id", UUID.fromString(mensajeId)).update();
        if (nuevas == 0) return;
        liberarStock.ejecutar(new LiberarStockCommand(PedidoId.de(leerPedidoId(payload))));
    });
}
```

Test: invocar `alRecibir` dos veces con el mismo `mensajeId` y
`verify(liberarStock, times(1)).ejecutar(any())`.

</details>

---

## Ejercicio 4 — Saga orquestada Pedido → Pago → Envío

### Enunciado

Implementa un orquestador `PagoYEnvioSaga` (servicio de aplicación +
estado persistido) que, tras `PedidoConfirmado`:

1. Ordena el cobro (reutiliza `PagarPedidoUseCase`).
2. Si el cobro va bien, ordena programar el envío por un puerto de salida
   `ServicioDeEnvios.programar(PedidoId)`.
3. Si el envío se rechaza, **compensa**: reembolsa el pago y cancela el
   pedido.
4. Persiste el estado de la saga (`INICIADA`, `PAGADA`, `ENVIO_PROGRAMADO`,
   `COMPENSANDO`, `COMPENSADA`, `COMPLETADA`) para poder reanudarla si el
   proceso muere.
5. Dibuja el diagrama de estados en ASCII.
6. Tests unitarios de los tres caminos: feliz, pago rechazado, envío
   rechazado.

**Pista:** modela la saga como un **agregado** (`SagaPagoYEnvio`) con su
propio repositorio. Cada paso es: cargar saga → decidir → actuar fuera de tx
→ guardar nuevo estado. Igual que `PagarPedido`, pero con más fases.

<details><summary>Solución</summary>

Diagrama de estados:

```
                 ┌──────────┐ pago OK  ┌─────────┐ envío OK ┌──────────────────┐   ┌────────────┐
 PedidoConfirmado│ INICIADA │─────────►│ PAGADA  │─────────►│ ENVIO_PROGRAMADO │──►│ COMPLETADA │
 ───────────────►└────┬─────┘          └────┬────┘          └──────────────────┘   └────────────┘
                      │ pago rechazado      │ envío rechazado
                      ▼                     ▼
               ┌────────────┐        ┌─────────────┐ reembolso + cancelación ┌────────────┐
               │  FALLIDA   │        │ COMPENSANDO │────────────────────────►│ COMPENSADA │
               └────────────┘        └─────────────┘                         └────────────┘
```

Agregado de la saga (dominio de aplicación, sin Spring):

```java
public final class SagaPagoYEnvio {
    public enum Estado { INICIADA, PAGADA, ENVIO_PROGRAMADO, COMPLETADA, FALLIDA, COMPENSANDO, COMPENSADA }

    private final PedidoId pedidoId;
    private Estado estado;
    private String referenciaPago;

    public static SagaPagoYEnvio iniciar(PedidoId id) { return new SagaPagoYEnvio(id, Estado.INICIADA, null); }

    public void pagoRealizado(String ref) { exigir(Estado.INICIADA); estado = Estado.PAGADA; referenciaPago = ref; }
    public void pagoRechazado()           { exigir(Estado.INICIADA); estado = Estado.FALLIDA; }
    public void envioProgramado()         { exigir(Estado.PAGADA);   estado = Estado.COMPLETADA; }
    public void envioRechazado()          { exigir(Estado.PAGADA);   estado = Estado.COMPENSANDO; }
    public void compensada()              { exigir(Estado.COMPENSANDO); estado = Estado.COMPENSADA; }

    private void exigir(Estado esperado) {
        if (estado != esperado) throw new IllegalStateException("Saga en " + estado + ", se esperaba " + esperado);
    }
    // constructor privado, getters
}
```

Orquestador (fragmento):

```java
public void alConfirmarsePedido(PedidoId pedidoId, String tokenPago) {
    tx.enTransaccion(() -> sagas.guardar(SagaPagoYEnvio.iniciar(pedidoId)));

    var pago = pagarPedido.ejecutar(new PagarPedidoCommand(pedidoId, "saga-" + pedidoId.valor(), tokenPago));
    if (!(pago instanceof ResultadoPagoPedido.Pagado pagado)) {
        tx.enTransaccion(() -> { var s = sagas.cargar(pedidoId); s.pagoRechazado(); sagas.guardar(s); });
        return;
    }
    tx.enTransaccion(() -> { var s = sagas.cargar(pedidoId); s.pagoRealizado(pagado.referenciaPago()); sagas.guardar(s); });

    boolean envioOk = envios.programar(pedidoId);                       // fuera de tx
    if (envioOk) {
        tx.enTransaccion(() -> { var s = sagas.cargar(pedidoId); s.envioProgramado(); sagas.guardar(s); });
    } else {
        tx.enTransaccion(() -> { var s = sagas.cargar(pedidoId); s.envioRechazado(); sagas.guardar(s); });
        compensar(pedidoId);    // reembolsar (clave determinista) + cancelar pedido → compensada()
    }
}
```

Fíjate en la clave `"saga-" + pedidoId`: determinista, para que si la saga se
reanuda tras una caída, el cobro no se repita.

Tests: con fakes de `PagarPedidoUseCase`, `ServicioDeEnvios` y un
repositorio de sagas en memoria, comprobar el estado final en los tres
caminos (`COMPLETADA`, `FALLIDA`, `COMPENSADA`) y, en el tercero, que se
llamó una vez al reembolso y que el pedido acabó `CANCELADO`.

Discusión: con dos o tres pasos, ¿compensa la orquestación frente a la
coreografía? Aquí sí, porque hay compensación y queremos el flujo visible
en un solo sitio. Para más pasos, mira Temporal o Camunda antes de
construir tu propio motor.

</details>

---

## Preguntas de repaso

Responde en una o dos frases. Las respuestas están debajo.

1. ¿Qué diferencia hay entre `Pedido.crear(...)` y `Pedido.reconstituir(...)`
   y por qué importa?
2. Nombra dos costes de anotar el dominio con `@Entity`.
3. ¿Por qué generar el `PedidoId` en el dominio facilita publicar eventos?
4. ¿Por qué `spring.jpa.open-in-view` debería estar a `false` en un proyecto
   hexagonal?
5. ¿Qué pasa si editas un script de Flyway ya aplicado en producción?
6. `PedidoJpaRepository` extiende `JpaRepository`. ¿Es un Repository en el
   sentido de Evans?
7. ¿Cuándo **no** usarías el lado de lectura (CQRS ligero) para una consulta?
8. ¿Por qué no devolver `Page<T>` de Spring Data desde un puerto?
9. Llamas desde `importar()` a `this.importarUno()`, anotado con
   `@Transactional(REQUIRES_NEW)`. ¿Qué ocurre?
10. ¿Por qué `@DataJpaTest` no sirve para probar un
    `@TransactionalEventListener(AFTER_COMMIT)`?
11. ¿Qué problema resuelve el Transactional Outbox y qué garantía de entrega
    ofrece?
12. ¿Por qué un consumidor de eventos tiene que ser idempotente si ya usamos
    outbox?
13. Diferencia entre coreografía y orquestación en una saga.
14. ¿Por qué la llamada a la pasarela de pago va fuera de la transacción?
15. ¿Por qué un timeout de la pasarela no puede tratarse como "rechazado"?

<details><summary>Respuestas</summary>

1. `crear` es un hecho de negocio (aplica reglas, puede registrar eventos);
   `reconstituir` rehidrata desde persistencia sin reglas ni eventos. Si el
   mapper usa `crear`, cada lectura dispararía validaciones o eventos
   fantasma.
2. Constructor vacío y campos no `final` (adiós invariantes e
   inmutabilidad); proxies y *lazy loading* en el dominio; dependencia de
   `jakarta.persistence`; esquema acoplado al modelo.
3. Porque el agregado tiene identidad desde el primer momento: el evento
   (y la fila del outbox) puede referenciar el id en la misma transacción,
   antes del `INSERT`.
4. Porque mantiene la sesión de Hibernate abierta hasta la vista: consultas
   ocultas fuera del adaptador, N+1, conexiones retenidas; y esconde que el
   adaptador no carga el agregado completo.
5. Flyway detecta el cambio de *checksum* y la aplicación no arranca en los
   entornos donde ya se aplicó. Se corrige con una migración nueva.
6. No: trabaja con `PedidoJpaEntity`, no con el agregado. Se comporta como
   un DAO dentro del adaptador. El Repository de Evans es el puerto
   `PedidoRepository`.
7. Cuando la lectura sirve para **decidir una escritura** (comprobar stock,
   validar estado): eso va por el agregado dentro de la transacción.
8. Acopla la aplicación a Spring Data; si mañana la lectura sale de otra
   fuente, el contrato cambia. `PaginaDe<T>` propio no tiene dependencias.
9. La llamada no pasa por el proxy: no se abre la nueva transacción. Se
   ejecuta dentro de la del llamador (o sin ninguna).
10. Porque el test es transaccional y hace rollback al final: nunca hay
    commit, así que los listeners `AFTER_COMMIT` no se disparan.
11. La escritura dual BD + broker. Garantiza que el evento **nunca se
    pierde** si el cambio se confirma; entrega *at-least-once*.
12. Porque *at-least-once* implica duplicados posibles (el relay publica y
    muere antes de marcar). Sin idempotencia, el efecto se aplicaría dos
    veces.
13. Coreografía: cada servicio reacciona a eventos de los demás, sin
    coordinador. Orquestación: un orquestador con estado envía comandos y
    decide compensaciones. Menos acoplamiento vs más visibilidad.
14. El rollback no deshace el cobro, la conexión queda retenida (limita el
    throughput y agota el pool), alarga bloqueos y encadena timeouts.
15. Porque no sabemos si se cobró. Si lo damos por rechazado, el cliente
    reintentará y se cobrará dos veces. Es `Indeterminado`: se resuelve con
    reintento con la misma clave o conciliación.

</details>

---

## Referencias

- Martin Fowler, catálogo PoEAA: https://martinfowler.com/eaaCatalog/
- Martin Fowler, *CQRS*: https://martinfowler.com/bliki/CQRS.html
- Chris Richardson, *Saga*: https://microservices.io/patterns/data/saga.html
- Chris Richardson, *Transactional Outbox*: https://microservices.io/patterns/data/transactional-outbox.html
- Spring Framework, *Transaction Management*: https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- Spring Data JPA: https://docs.spring.io/spring-data/jpa/reference/
- Flyway: https://documentation.red-gate.com/flyway
- Testcontainers: https://java.testcontainers.org/ ; WireMock: https://wiremock.org/docs/
- Vlad Mihalcea: https://vladmihalcea.com/

---

## Mantra

> *"Si sabes dónde empieza y acaba cada transacción, qué evento sale de
> cada cambio y qué pasa cuando llega dos veces, ya no estás haciendo una
> demo: estás haciendo un sistema."*
