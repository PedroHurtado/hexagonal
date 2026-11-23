# Día 8 — Bloque 3: Después de migrar — el hexágono en arquitecturas modernas

> *"An evolutionary architecture supports guided, incremental change across
> multiple dimensions."*
> — Neal Ford, Rebecca Parsons, Patrick Kua, *Building Evolutionary Architectures*

---

## 1. El problema en una frase

Ya tenemos el hexágono dentro del monolito. ¿Y ahora qué? El cliente quiere
**sacar notificaciones a Kafka**, **quizá extraer pagos a un microservicio** y
**ver qué pasa en producción**. La pregunta es: ¿cuánto nos cuesta cada cosa
**ahora** que el código está ordenado?

> Este bloque **no repite** la parte conceptual del día 7
> ([Aplicación en arquitecturas modernas](../day-07/05-aplicacion-en-arquitecturas-modernas.md)):
> allí vimos microservicios, Modulith, CQRS y mensajería en abstracto. Aquí
> vemos **qué cambia en nuestro proyecto migrado**, con código.

---

## 2. La prueba del algodón: cambios que ahora son baratos

| Cambio pedido por el negocio                 | En el monolito legacy                        | En el hexágono migrado                                  |
|----------------------------------------------|----------------------------------------------|---------------------------------------------------------|
| Publicar eventos en Kafka                    | Tocar `PedidoService` dentro de la transacción | Un adaptador nuevo de `PublicadorDeEventos`           |
| Cambiar proveedor de pagos                   | Tres semanas                                  | Un adaptador nuevo de `PasarelaDePago` + test WireMock |
| Confirmar pedidos desde un fichero nocturno  | Duplicar lógica                               | Adaptador de entrada `scheduler` que llama al caso de uso |
| Extraer pagos a un microservicio             | "No sabemos por dónde cortar"                 | El corte ya existe: es el puerto `PasarelaDePago`     |
| Añadir métricas de negocio                   | `log.info` por todo el servicio               | Decorador/adaptador transversal (§6)                   |

En cristiano: **el hexágono no es el fin, es lo que hace baratos los
siguientes pasos.**

---

## 3. Monolito modular con Spring Modulith

### 3.1 Por qué antes de microservicios

La mayoría de equipos que "necesitan microservicios" necesitan, en realidad,
**módulos con fronteras que se respeten**. Spring Modulith verifica esas
fronteras dentro del mismo despliegue.

Tras la migración tenemos un hexágono de **pedidos**. El monolito tiene más
cosas (catálogo, pagos, reembolsos, informes). Los convertimos en módulos de
primer nivel:

```
com.curso.tienda                          ← clase @SpringBootApplication aquí
├── pedidos                               ← módulo (hexágono completo dentro)
│   ├── domain / application / infrastructure
│   └── PedidosApi.java                   ← API pública del módulo (opcional)
├── catalogo                              ← módulo
├── pagos                                 ← módulo (candidato a extraer)
└── informes                              ← módulo legacy, aún en capas
```

> Para el proyecto del curso mantenemos `com.curso.pedidos` como raíz; si
> adoptáis Modulith, el hexágono de pedidos pasa a ser **un módulo**, y la
> estructura interna canónica no cambia.

### 3.2 Verificar la modularidad en un test

```xml
<dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-core</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<!-- dependencyManagement: org.springframework.modulith:spring-modulith-bom:1.2.x (compatible con Boot 3.3) -->
```

```java
class ModularidadTest {

    private final ApplicationModules modulos = ApplicationModules.of(TiendaApplication.class);

    @Test
    void los_modulos_respetan_sus_fronteras() {
        modulos.verify();      // falla si un módulo usa clases internas de otro o hay ciclos
    }

    @Test
    void genera_documentacion() {
        new Documenter(modulos).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
```

> Trampa: Modulith considera "interno" todo lo que está en **subpaquetes** de
> un módulo. Si `pagos` necesita algo de `pedidos.domain.model`, Modulith se
> queja. Bien: obliga a decidir qué expone el módulo (eventos, un `PedidosApi`)
> o a marcar paquetes con `@NamedInterface`.

### 3.3 Eventos entre módulos con el registro de publicaciones

Nuestro `PublicadorDeEventos` puede tener un adaptador que use los eventos de
Spring. Con Modulith, el *Event Publication Registry* persiste los eventos y
reintenta los listeners fallidos: es un **outbox gestionado**.

```java
package com.curso.pedidos.infrastructure.adapter.out.messaging;

@Component
class SpringPublicadorDeEventosAdapter implements PublicadorDeEventos {

    private final ApplicationEventPublisher publisher;

    SpringPublicadorDeEventosAdapter(ApplicationEventPublisher publisher) { this.publisher = publisher; }

    @Override
    public void publicar(List<EventoDeDominio> eventos) {
        eventos.forEach(publisher::publishEvent);   // se llama dentro de la UnidadDeTrabajo
    }
}
```

```java
// En el módulo de notificaciones (o en pedidos.infrastructure.adapter.in.messaging)
@Component
class NotificacionesListener {

    private final NotificadorPedidos notificador;
    private final ConsultarPedidoQuery consultar;

    NotificacionesListener(NotificadorPedidos notificador, ConsultarPedidoQuery consultar) {
        this.notificador = notificador;
        this.consultar = consultar;
    }

    @ApplicationModuleListener     // = @Async + @Transactional(REQUIRES_NEW) + @TransactionalEventListener(AFTER_COMMIT)
    void on(PedidoPagado evento) {
        notificador.notificarConfirmacion(consultar.porId(evento.pedidoId()));
    }
}
```

Con esto, la llamada directa a `notificador` del final de
`ConfirmarPedidoService` puede **desaparecer**: el email sale cuando el evento
se ha confirmado en BD, con reintentos. El dominio y el caso de uso **no se
han enterado**.

```xml
<dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-jpa</artifactId>   <!-- persiste el registro de publicaciones -->
</dependency>
```

---

## 4. Adaptadores para Kafka

### 4.1 Salida: publicar eventos de integración

Regla: **no publiques tus eventos de dominio tal cual** en Kafka. Son internos
y cambian con el modelo. Publica **eventos de integración** versionados.

```java
package com.curso.pedidos.infrastructure.adapter.out.messaging;

@Component
@ConditionalOnProperty(name = "pedidos.eventos.transporte", havingValue = "kafka")
class KafkaPublicadorDeEventos implements PublicadorDeEventos {

    private static final String TOPIC = "tienda.pedidos.v1";
    private final KafkaTemplate<String, PedidoEventoV1> kafka;

    KafkaPublicadorDeEventos(KafkaTemplate<String, PedidoEventoV1> kafka) { this.kafka = kafka; }

    @Override
    public void publicar(List<EventoDeDominio> eventos) {
        eventos.forEach(e -> kafka.send(TOPIC, e.pedidoId().valor().toString(), PedidoEventoV1.desde(e)));
    }
}

/** Contrato público, versionado y estable. Vive en infraestructura, no en el dominio. */
record PedidoEventoV1(String tipo, UUID pedidoId, Instant ocurridoEn) {
    static PedidoEventoV1 desde(EventoDeDominio e) {
        String tipo = switch (e) {          // switch exhaustivo gracias a la interfaz sealed
            case PedidoConfirmado c -> "PEDIDO_CONFIRMADO";
            case PedidoPagado p     -> "PEDIDO_PAGADO";
            case PedidoCancelado c  -> "PEDIDO_CANCELADO";
        };
        return new PedidoEventoV1(tipo, e.pedidoId().valor(), e.ocurridoEn());
    }
}
```

> Ojo: el *pattern matching* en `switch` es estándar desde **Java 21**. En
> Java 17 era *preview*; usa `if (e instanceof PedidoConfirmado)` encadenado o
> un método abstracto en la interfaz.

> Trampa: `kafka.send` dentro de la transacción de BD **no** es atómico con el
> commit. Si la BD hace rollback, el mensaje ya salió (o al revés). Soluciones:
> outbox (tabla + relay / Debezium) o el registro de Modulith con
> *externalización* de eventos (`@Externalized`). Visto en el día 6.

### 4.2 Entrada: un listener es un adaptador de entrada más

```java
package com.curso.pedidos.infrastructure.adapter.in.messaging;

@Component
class PagosExternosKafkaListener {

    private final ConfirmarPedidoUseCase confirmarPedido;

    PagosExternosKafkaListener(ConfirmarPedidoUseCase confirmarPedido) { this.confirmarPedido = confirmarPedido; }

    @KafkaListener(topics = "tienda.checkout.v1", groupId = "pedidos")
    void on(CheckoutCompletadoV1 mensaje) {
        confirmarPedido.ejecutar(new ConfirmarPedidoCommand(
                new PedidoId(mensaje.pedidoId()), mensaje.tokenTarjeta()));
    }
}
```

Mismo caso de uso que el REST. Cero duplicación. **Idempotencia**: Kafka
entrega *at least once*; si el mensaje llega dos veces, `pedido.confirmar()`
lanza `TransicionDeEstadoInvalidaException` en el segundo intento. Decide si
eso es un error o un "ya estaba hecho" (lo normal: capturar, registrar y
confirmar el *offset*).

### 4.3 Testing

- **Adaptador de salida**: `@SpringBootTest` + Testcontainers Kafka
  (`org.testcontainers:kafka`) y un consumidor de prueba; o `@EmbeddedKafka`.
- **Adaptador de entrada**: test unitario llamando a `on(...)` con el caso de
  uso mockeado (el adaptador es delgado; el contrato de Kafka se prueba aparte).

```java
@Test
void un_checkout_completado_confirma_el_pedido() {
    ConfirmarPedidoUseCase useCase = mock(ConfirmarPedidoUseCase.class);
    UUID id = UUID.randomUUID();

    new PagosExternosKafkaListener(useCase).on(new CheckoutCompletadoV1(id, "tok"));

    verify(useCase).ejecutar(new ConfirmarPedidoCommand(new PedidoId(id), "tok"));
}
```

---

## 5. Extraer un microservicio: pagos

### 5.1 El corte ya está hecho

En el legacy, "pagos" eran 40 líneas de `RestTemplate` incrustadas en
`PedidoService`. Ahora es un **puerto** (`PasarelaDePago`) con un adaptador.
Extraer el microservicio de pagos (que hablará con el proveedor real, guardará
cobros y gestionará reembolsos) es:

```
 ANTES de extraer                         DESPUÉS de extraer

 ┌───────────── monolito ─────────────┐   ┌────────── monolito ─────────┐      ┌──── ms-pagos ─────┐
 │ ConfirmarPedidoService              │   │ ConfirmarPedidoService      │      │ (su propio        │
 │        │                            │   │        │                    │      │  hexágono)        │
 │        ▼                            │   │        ▼                    │ HTTP │ CobrosController  │
 │ PasarelaDePago (puerto)             │   │ PasarelaDePago (puerto)     │─────►│ CobrarUseCase     │
 │        │                            │   │        │                    │      │ ProveedorPago ──► │ Stripe-like
 │ StripeLikePasarelaAdapter ─────────────►│ PagosServiceClientAdapter   │      └───────────────────┘
 └─────────────────────────────────────┘   └─────────────────────────────┘
        proveedor externo                 el dominio de pedidos NO cambia
```

Cambia **un adaptador**. El dominio de pedidos, sus casos de uso y sus tests no
se tocan. Eso es exactamente lo que prometía Cockburn.

### 5.2 Qué SÍ cambia (y no hay que esconder)

- **Latencia y fallos de red** entre servicios: timeouts, reintentos,
  *circuit breaker* (Resilience4j) en el adaptador.
- **Consistencia**: la compensación `anularCobro` pasa a ser una **saga** entre
  servicios. Si se hace por eventos, la orquestación vive en la aplicación,
  nunca en el dominio.
- **Contratos**: se versionan y se prueban (contract testing: Spring Cloud
  Contract o Pact) además de WireMock.
- **Datos**: ms-pagos tiene su base de datos. Nada de compartir tablas.

> Trampa: extraer microservicios **antes** de tener el hexágono. Cortas por
> donde puedes, no por donde debes, y acabas con un *monolito distribuido*.

---

## 6. Observabilidad como adaptador transversal

### 6.1 El problema

Queremos: logs con contexto, métricas de negocio (pedidos confirmados, pagos
rechazados, importe cobrado), trazas distribuidas. Y **no** queremos que el
dominio importe Micrometer ni SLF4J.

### 6.2 Dónde va cada cosa

| Qué                               | Dónde                                        | Cómo                                                  |
|-----------------------------------|----------------------------------------------|-------------------------------------------------------|
| Métricas técnicas HTTP/JVM/BD     | Gratis                                       | Actuator + Micrometer (`http.server.requests`, Hikari) |
| Trazas distribuidas               | Infraestructura                              | Micrometer Tracing + OpenTelemetry; `RestClient.Builder` autoconfigurado propaga contexto |
| Logs técnicos de adaptadores      | Adaptadores                                  | SLF4J en el adaptador (pago, email, Kafka)            |
| Métricas de negocio de casos de uso | Decorador del puerto de entrada (infra)    | `ConfirmarPedidoUseCase` envuelto en `config`          |
| Métricas a partir de eventos      | Listener de eventos (infra)                  | Contador por tipo de `EventoDeDominio`                |
| Logs en el dominio                | **En ningún sitio**                          | El dominio comunica con excepciones y eventos         |

### 6.3 Decorador de caso de uso con Micrometer

```java
package com.curso.pedidos.infrastructure.observability;

/** Adaptador transversal: mide el caso de uso sin que el caso de uso lo sepa. */
class ConfirmarPedidoMedido implements ConfirmarPedidoUseCase {

    private final ConfirmarPedidoUseCase delegado;
    private final MeterRegistry registry;
    private final Timer duracion;

    ConfirmarPedidoMedido(ConfirmarPedidoUseCase delegado, MeterRegistry registry) {
        this.delegado = delegado;
        this.registry = registry;
        this.duracion = Timer.builder("pedidos.confirmacion.duracion")
                .description("Tiempo de confirmar un pedido (incluye cobro)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @Override
    public void ejecutar(ConfirmarPedidoCommand comando) {
        try {
            duracion.record(() -> delegado.ejecutar(comando));
            registry.counter("pedidos.confirmacion", "resultado", "ok").increment();
        } catch (PagoRechazadoException e) {
            registry.counter("pedidos.confirmacion", "resultado", "pago_rechazado").increment();
            throw e;
        } catch (RuntimeException e) {
            registry.counter("pedidos.confirmacion", "resultado", e.getClass().getSimpleName()).increment();
            throw e;
        }
    }
}
```

```java
// BeanConfiguration: se envuelve al cablear
@Bean
ConfirmarPedidoUseCase confirmarPedidoUseCase(/* ...dependencias... */, MeterRegistry registry) {
    ConfirmarPedidoUseCase servicio = new ConfirmarPedidoService(/* ... */);
    return new ConfirmarPedidoMedido(servicio, registry);
}
```

> Trampa: etiquetas (*tags*) con alta cardinalidad. Nunca `pedidoId` como tag:
> cada pedido crearía una serie temporal nueva y tumbaría Prometheus. Tags con
> valores acotados (resultado, tipo de error, moneda).

Alternativa con la API de **Observation** (Micrometer 1.10+, integrada en
Boot 3): una sola instrumentación produce métrica **y** span de traza.

```java
@Override
public void ejecutar(ConfirmarPedidoCommand comando) {
    Observation.createNotStarted("pedidos.confirmacion", observationRegistry)
            .lowCardinalityKeyValue("canal", "rest")
            .observe(() -> delegado.ejecutar(comando));
}
```

### 6.4 Actuator: salud de los adaptadores

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  endpoint:
    health:
      show-details: when-authorized
      probes:
        enabled: true          # /actuator/health/liveness y /readiness para Kubernetes
```

```java
@Component("pasarelaDePago")
class PasarelaDePagoHealthIndicator implements HealthIndicator {
    private final RestClient http;
    PasarelaDePagoHealthIndicator(RestClient.Builder b, PagosProperties p) { this.http = b.baseUrl(p.url()).build(); }

    @Override
    public Health health() {
        try {
            http.get().uri("/health").retrieve().toBodilessEntity();
            return Health.up().build();
        } catch (RestClientException e) {
            return Health.down(e).build();
        }
    }
}
```

> Trampa: meter el health de un proveedor externo en el grupo de *liveness*.
> Si el proveedor cae, Kubernetes reiniciará tus pods en bucle sin arreglar
> nada. Externos, como mucho, en *readiness* (o en ninguno).

### 6.5 Testing de la observabilidad

```java
@Test
void cuenta_los_pagos_rechazados() {
    var registry = new SimpleMeterRegistry();
    ConfirmarPedidoUseCase falla = c -> { throw new PagoRechazadoException(c.pedidoId(), "fondos"); };
    var medido = new ConfirmarPedidoMedido(falla, registry);

    assertThatThrownBy(() -> medido.ejecutar(new ConfirmarPedidoCommand(PedidoId.nuevo(), "tok")))
            .isInstanceOf(PagoRechazadoException.class);

    assertThat(registry.counter("pedidos.confirmacion", "resultado", "pago_rechazado").count())
            .isEqualTo(1.0);
}
```

---

## 7. El hexágono migrado con todas sus caras

```
                            ┌──────────────── adaptadores de entrada ────────────────┐
                            │ REST (PedidoController)  Kafka listener  Scheduler/CLI │
                            └───────────────┬─────────────────────────────────────────┘
                                            ▼
           ┌─ observabilidad ─┐   ┌────────────────────────────────────┐
           │ ConfirmarPedido- │──►│  application (puertos + servicios) │
           │ Medido (decor.)  │   │   ┌──────────────────────────┐     │
           └──────────────────┘   │   │  domain: Pedido, eventos │     │
                                  │   └──────────────────────────┘     │
                                  └───────────────┬────────────────────┘
                                                  ▼
            ┌──────────────────────────── adaptadores de salida ─────────────────────────┐
            │ JPA (PostgreSQL)  RestClient → ms-pagos  Email  Kafka / Modulith registry  │
            └────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Resumen

| Evolución                  | Qué se añade                                | Qué NO se toca                       |
|----------------------------|---------------------------------------------|--------------------------------------|
| Monolito modular           | Fronteras verificadas con Modulith          | Estructura interna del hexágono      |
| Eventos (interno)          | `SpringPublicadorDeEventosAdapter` + listeners | Casos de uso                      |
| Kafka                      | Adaptador de salida + eventos de integración v1 | Dominio y eventos de dominio     |
| Microservicio de pagos     | Adaptador cliente + resiliencia + contratos | `PasarelaDePago`, dominio, tests     |
| Observabilidad             | Decoradores, health indicators, Actuator    | Dominio (0 imports de Micrometer)    |

---

## Ejercicio

Añade una métrica de negocio **"importe total cobrado"** por moneda, sin tocar
ni `domain` ni `application`.

Pista: ¿qué evento sabe que se ha cobrado? ¿Lleva el importe? Si no lo lleva,
¿a quién se lo preguntas?

<details><summary>Solución</summary>

`PedidoPagado` solo lleva `pedidoId` y `ocurridoEn`. Opciones:

1. **Listener de eventos** en infraestructura que consulta el pedido y
   acumula en un `DistributionSummary`:

```java
@Component
class MetricasDeCobroListener {
    private final ConsultarPedidoQuery consultar;
    private final MeterRegistry registry;

    MetricasDeCobroListener(ConsultarPedidoQuery consultar, MeterRegistry registry) {
        this.consultar = consultar;
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(PedidoPagado evento) {
        Dinero total = consultar.porId(evento.pedidoId()).total();
        DistributionSummary.builder("pedidos.importe.cobrado")
                .baseUnit(total.moneda().getCurrencyCode())
                .tag("moneda", total.moneda().getCurrencyCode())
                .register(registry)
                .record(total.importe().doubleValue());
    }
}
```

2. **Enriquecer el evento** con el importe (`PedidoPagado(pedidoId, importe,
   ocurridoEn)`): toca el dominio, pero es legítimo si el negocio considera el
   importe parte del hecho "se ha pagado". Decisión de diseño → ADR.

</details>

---

## Referencias

- Spring Modulith: https://docs.spring.io/spring-modulith/reference/
- Spring Boot docs (Actuator, Observability): https://docs.spring.io/spring-boot/index.html
- Chris Richardson, *Transactional Outbox*: https://microservices.io/patterns/data/transactional-outbox.html ; *Saga*: https://microservices.io/patterns/data/saga.html
- Martin Fowler, *Strangler Fig Application*: https://martinfowler.com/bliki/StranglerFigApplication.html
- Netflix Tech Blog, *Ready for changes with Hexagonal Architecture* (2020): https://netflixtechblog.com/ready-for-changes-with-hexagonal-architecture-b315ec967749
- Neal Ford, Rebecca Parsons, Patrick Kua, *Building Evolutionary Architectures* (O'Reilly, 2017; 2ª ed. 2022).
- Día 7 del curso: [Aplicación en arquitecturas modernas](../day-07/05-aplicacion-en-arquitecturas-modernas.md)

## Mantra

> *"Con el hexágono hecho, cada arquitectura moderna es solo un adaptador más:
> el dominio no se entera de que ahora vive en Kafka, en un módulo o en la nube."*
