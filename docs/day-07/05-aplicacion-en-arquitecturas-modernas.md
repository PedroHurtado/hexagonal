# Día 7 — Bloque 5: Aplicación en arquitecturas modernas

> *"…to be developed and tested in isolation from its eventual run-time devices
> and databases."* — Alistair Cockburn, *Hexagonal Architecture* (2005).
> En 2005 no existían Kubernetes, Kafka ni Lambda… y no le hizo falta.

---

## 1. El problema en una frase

La hexagonal nació en 2005 pensando en "una aplicación". Hoy hablamos de
microservicios, monolitos modulares, eventos y funciones serverless. La
pregunta es: **¿la hexagonal escala a esas arquitecturas o se queda en el
nivel de una clase `Service`?**

Respuesta corta: escala muy bien, porque **opera a otro nivel**. Microservicios,
eventos o serverless son decisiones de **arquitectura de sistema** (cómo se
reparten y comunican los procesos). Hexagonal es una decisión de
**arquitectura de aplicación** (cómo se organiza el código dentro de cada
proceso). Se complementan.

```
  NIVEL SISTEMA      ¿Cuántos procesos? ¿Cómo se comunican?   microservicios,
  (macro)            ¿Dónde están los datos?                  eventos, serverless
        │
        ▼
  NIVEL APLICACIÓN   ¿Cómo organizo el código de UN proceso?  hexagonal, Clean,
  (micro)            ¿Hacia dónde apuntan las dependencias?   VSA, capas
```

---

## 2. Microservicios: un hexágono por servicio

Cada microservicio es un hexágono completo con su dominio, sus puertos y sus
adaptadores. La comunicación entre servicios **siempre pasa por adaptadores**:
para el servicio de Pedidos, el servicio de Pagos es un sistema externo más,
igual que una base de datos.

```
                         ┌─────────────────┐
              HTTP ─────►│  API Gateway    │
                         └───┬─────────┬───┘
                             │         │
             ┌───────────────▼──┐   ┌──▼────────────────┐
             │   PEDIDOS        │   │   CATÁLOGO        │
             │  ┌────────────┐  │   │  ┌────────────┐   │
   REST ────►│  │  dominio   │  │   │  │  dominio   │   │◄──── REST
             │  │  Pedido    │  │   │  │  Producto  │   │
             │  └────────────┘  │   │  └────────────┘   │
             │ [web][kafka-in]  │   │ [web][kafka-in]   │
             │ [jpa][kafka-out] │   │ [jpa][kafka-out]  │
             │ [catalogo-http]──┼──►│                   │
             └───┬──────────┬───┘   └─────────┬─────────┘
                 │          │                 │
             ┌───▼──┐   ┌───▼─────────────────▼───────────────┐
             │ PG   │   │            KAFKA                     │
             │pedid.│   │  pedidos.eventos   catalogo.eventos  │
             └──────┘   └───┬───────────────────────┬──────────┘
                            │                       │
             ┌──────────────▼───┐        ┌──────────▼────────┐
             │   PAGOS          │        │   ENVÍOS          │
             │  ┌────────────┐  │        │  ┌────────────┐   │
             │  │  dominio   │  │        │  │  dominio   │   │
             │  │  Pago      │  │        │  │  Envio     │   │
             │  └────────────┘  │        │  └────────────┘   │
             │ [kafka-in]       │        │ [kafka-in]        │
             │ [stripe-http]────┼──► Pasarela externa         │
             │ [jpa][kafka-out] │        │ [jpa][kafka-out]  │
             └──────────────────┘        └───────────────────┘

   Cada caja grande = un hexágono. [xxx] = adaptador.
   Entre hexágonos solo viajan mensajes/HTTP, nunca objetos de dominio.
```

Reglas prácticas:

- **El dominio no se comparte entre servicios.** Nada de un `pedidos-domain.jar`
  usado por Pagos y Envíos: eso es un monolito distribuido. Cada servicio tiene
  su propio modelo; `Pedido` en Pagos puede ser solo `(pedidoId, importe)`.
- **El cliente de otro servicio es un puerto de salida** con lenguaje del
  dominio que lo consume: `CatalogoProductos.precioActual(ProductoId)`, no
  `CatalogoRestClient.getProductBySku(...)`. Su adaptador hace HTTP (RestClient)
  y traduce el modelo remoto: es la **capa anticorrupción** de DDD.
- **Contratos versionados** en los adaptadores (DTOs de API, esquemas de
  eventos). El dominio puede evolucionar sin romper a los consumidores.
- **Tamaño**: un microservicio con dos endpoints y lógica trivial no necesita
  un hexágono con todos los puertos. Ver la matriz del
  [bloque 4](04-comparativa-hexagonal-clean-vertical-slice.md).

---

## 3. Monolito modular con Spring Modulith

Muchos equipos descubren que **no necesitan microservicios**, sino **límites
claros**. Un monolito modular es un único desplegable con módulos que respetan
fronteras como si fueran servicios. Spring Modulith ayuda a construirlo y a
verificarlo.

### 3.1 Convención de Spring Modulith

- Cada **subpaquete directo** del paquete de la aplicación es un **módulo de
  aplicación**: `com.curso.pedidos.pedidos`, `com.curso.pedidos.catalogo`…
- Lo que está en el paquete raíz del módulo es su **API pública**; los
  subpaquetes son **internos** (otros módulos no pueden usarlos). Se pueden
  exponer subpaquetes concretos con `@NamedInterface`.
- La integración preferente entre módulos es por **eventos de aplicación**.

### 3.2 Cada módulo, un hexágono

```
com.curso.pedidos
├── PedidosApplication.java
├── pedidos                           ← módulo = bounded context = hexágono
│   ├── PedidoConfirmado.java         (API pública del módulo: el evento)
│   ├── domain/...                    (interno)
│   ├── application/port/in|out/...   (interno)
│   ├── application/service/...       (interno)
│   └── infrastructure/...            (interno)
├── pagos                             ← otro hexágono
│   ├── application/...
│   └── infrastructure/
│       └── adapter/in/events/PedidoConfirmadoListener.java
└── envios
    └── ...
```

### 3.3 Integración por eventos entre hexágonos

En Pedidos, el adaptador de salida `PublicadorDeEventos` delega en el
`ApplicationEventPublisher` de Spring:

```java
package com.curso.pedidos.pedidos.infrastructure.adapter.out.events;

import com.curso.pedidos.pedidos.application.port.out.PublicadorDeEventos;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
class SpringPublicadorDeEventosAdapter implements PublicadorDeEventos {

    private final ApplicationEventPublisher publisher;

    SpringPublicadorDeEventosAdapter(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publicar(Object evento) {
        publisher.publishEvent(evento);
    }
}
```

En Pagos, el listener es un **adaptador de entrada** que invoca su propio caso
de uso:

```java
package com.curso.pedidos.pagos.infrastructure.adapter.in.events;

import com.curso.pedidos.pagos.application.port.in.IniciarCobroCommand;
import com.curso.pedidos.pagos.application.port.in.IniciarCobroUseCase;
import com.curso.pedidos.pedidos.PedidoConfirmado;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class PedidoConfirmadoListener {

    private final IniciarCobroUseCase iniciarCobro;

    PedidoConfirmadoListener(IniciarCobroUseCase iniciarCobro) {
        this.iniciarCobro = iniciarCobro;
    }

    // Asíncrono, tras el commit de la transacción que publicó el evento,
    // y en su propia transacción.
    @ApplicationModuleListener
    void on(PedidoConfirmado evento) {
        iniciarCobro.ejecutar(new IniciarCobroCommand(evento.pedidoId()));
    }
}
```

> Detalle importante: `PedidoConfirmado` está en el paquete raíz del módulo
> `pedidos`, es decir, es **API pública**. Por eso aquí se define como
> `record PedidoConfirmado(UUID pedidoId, Instant ocurridoEn)` con tipos
> estándar, y **no** con el `PedidoId` interno del dominio: si lo hiciera,
> Pagos dependería de un tipo interno de Pedidos y `modulos.verify()` fallaría.
> El evento público es un **contrato**, no un objeto de dominio.

### 3.4 Verificar los límites con un test

```java
class ModularidadTest {

    private final ApplicationModules modulos = ApplicationModules.of(PedidosApplication.class);

    @Test
    void los_modulos_respetan_sus_limites() {
        modulos.verify();      // falla si un módulo usa internos de otro o hay ciclos
    }

    @Test
    void genera_documentacion() {
        new Documenter(modulos).writeDocumentation();   // diagramas C4/PlantUML
    }
}
```

Y cada módulo se puede testear aislado con `@ApplicationModuleTest`.

> Por qué encaja tan bien con hexagonal: **ArchUnit protegía el interior del
> hexágono (dominio no depende de infraestructura); Spring Modulith protege las
> fronteras entre hexágonos.** Dos niveles de límites, dos herramientas.

Con el *Event Publication Registry* de Spring Modulith, los eventos publicados
se registran en BD junto con la transacción y se reintentan si el listener
falla: una forma de **Transactional Outbox** sin escribirla a mano. Y si un
módulo se extrae algún día a microservicio, su hexágono se va entero: solo
cambian los adaptadores de eventos (de eventos de aplicación a Kafka).

---

## 4. Event-driven: adaptadores Kafka

Kafka es **infraestructura**. En hexagonal aparece en dos sitios, ambos
adaptadores:

```
   topic pagos.eventos                                  topic pedidos.eventos
         │                                                      ▲
         ▼                                                      │
  ┌──────────────────┐   ┌───────────────────────────┐   ┌──────┴──────────────┐
  │ PagoRecibido     │──►│ MarcarComoPagadoUseCase   │──►│ KafkaPublicadorDe   │
  │ KafkaListener    │   │   (puerto de entrada)     │   │ EventosAdapter      │
  │ (adapter IN)     │   │ Pedido.marcarComoPagado() │   │ (adapter OUT)       │
  └──────────────────┘   └───────────────────────────┘   └─────────────────────┘
```

### 4.1 Adaptador de entrada

```java
package com.curso.pedidos.infrastructure.adapter.in.messaging;

import com.curso.pedidos.application.port.in.MarcarComoPagadoCommand;
import com.curso.pedidos.application.port.in.MarcarComoPagadoUseCase;
import com.curso.pedidos.domain.model.PedidoId;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class PagoRecibidoKafkaListener {

    /** Contrato del mensaje externo: NO es un objeto de dominio. */
    record PagoRecibidoMensaje(String pedidoId, String referenciaPago) {
    }

    private final MarcarComoPagadoUseCase marcarComoPagado;

    PagoRecibidoKafkaListener(MarcarComoPagadoUseCase marcarComoPagado) {
        this.marcarComoPagado = marcarComoPagado;
    }

    @KafkaListener(topics = "pagos.eventos", groupId = "pedidos")
    void on(PagoRecibidoMensaje mensaje) {
        marcarComoPagado.ejecutar(new MarcarComoPagadoCommand(
                new PedidoId(UUID.fromString(mensaje.pedidoId()))));
    }
}
```

Es **exactamente** el mismo papel que el `PedidoController` del día 3: traducir
un protocolo (aquí, un mensaje JSON en un topic) a un comando del caso de uso.
El caso de uso no sabe si le llama HTTP o Kafka. Eso es lo que Cockburn pedía:
*"driven by users, programs, automated test or batch scripts"*.

> Trampa: Kafka entrega **al menos una vez**. El caso de uso (o el adaptador)
> debe ser **idempotente**: marcar como pagado un pedido ya pagado no debe
> fallar ni duplicar efectos. Es una decisión de dominio, no de Kafka.

### 4.2 Adaptador de salida

```java
package com.curso.pedidos.infrastructure.adapter.out.messaging;

import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.PedidoCancelado;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.event.PedidoPagado;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
class KafkaPublicadorDeEventosAdapter implements PublicadorDeEventos {

    record EventoPedidoMensaje(String tipo, String pedidoId, String ocurridoEn) {
    }

    private final KafkaTemplate<String, EventoPedidoMensaje> kafka;

    KafkaPublicadorDeEventosAdapter(KafkaTemplate<String, EventoPedidoMensaje> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publicar(Object evento) {
        EventoPedidoMensaje mensaje = switch (evento) {
            case PedidoConfirmado e -> msg("PedidoConfirmado", e.pedidoId().valor(), e.ocurridoEn());
            case PedidoPagado e     -> msg("PedidoPagado", e.pedidoId().valor(), e.ocurridoEn());
            case PedidoCancelado e  -> msg("PedidoCancelado", e.pedidoId().valor(), e.ocurridoEn());
            default -> throw new IllegalArgumentException("Evento no soportado: " + evento);
        };
        kafka.send("pedidos.eventos", mensaje.pedidoId(), mensaje);   // clave = pedidoId → orden por pedido
    }

    private static EventoPedidoMensaje msg(String tipo, java.util.UUID id, java.time.Instant t) {
        return new EventoPedidoMensaje(tipo, id.toString(), t.toString());
    }
}
```

> Nota Java 17: el `switch` con patrones de tipo es estándar desde **Java 21**.
> En Java 17 usa `if (evento instanceof PedidoConfirmado e) { ... }` encadenados
> (o haz `sealed` la jerarquía de eventos y migra a 21, que es lo recomendable
> en proyectos nuevos).

> Trampa: publicar en Kafka **dentro** de la transacción de BD y asumir que es
> atómico. No lo es: si el commit falla, el mensaje ya salió. La solución es el
> **Transactional Outbox** (guardar el evento en una tabla en la misma
> transacción y publicarlo después). Lo vimos el [día 6](../day-06/00-README.md);
> con hexagonal es simplemente **otro adaptador** de `PublicadorDeEventos`.

---

## 5. Serverless: Spring Cloud Function como adaptador de entrada

En serverless (AWS Lambda, Azure Functions, Google Cloud Functions) el punto
de entrada es una **función**. Spring Cloud Function permite escribir beans
`java.util.function.Function/Consumer/Supplier` y desplegarlos en distintos
proveedores mediante adaptadores específicos (por ejemplo
`spring-cloud-function-adapter-aws`).

En hexagonal, esa función es **otro adaptador de entrada**:

```java
package com.curso.pedidos.infrastructure.adapter.in.function;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.domain.model.PedidoId;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FuncionesPedidos {

    record ConfirmarPedidoPeticion(String pedidoId) {
    }

    record ConfirmarPedidoRespuesta(String pedidoId, String estado) {
    }

    @Bean
    Function<ConfirmarPedidoPeticion, ConfirmarPedidoRespuesta> confirmarPedido(
            ConfirmarPedidoUseCase useCase) {
        return peticion -> {
            var pedido = useCase.ejecutar(new ConfirmarPedidoCommand(
                    new PedidoId(UUID.fromString(peticion.pedidoId()))));
            return new ConfirmarPedidoRespuesta(peticion.pedidoId(), pedido.estado().name());
        };
    }
}
```

```
   API Gateway / SQS / EventBridge
               │
               ▼
   ┌─────────────────────────┐     ┌─────────────────────────┐
   │ Lambda (adaptador AWS)  │────►│ Function bean           │  ← adaptador IN
   └─────────────────────────┘     └───────────┬─────────────┘
                                               ▼
                                   ┌─────────────────────────┐
                                   │ ConfirmarPedidoUseCase  │  ← hexágono intacto
                                   └───────────┬─────────────┘
                                               ▼
                                   ┌─────────────────────────┐
                                   │ DynamoDB / RDS adapter  │  ← adaptador OUT
                                   └─────────────────────────┘
```

Lo interesante: **el mismo hexágono** se puede desplegar como aplicación web
en un contenedor y como función. Solo cambian los adaptadores de entrada y el
empaquetado. Ojo con el **arranque en frío**: un contexto Spring grande penaliza
en Lambda; opciones como imágenes nativas con GraalVM o AWS SnapStart mitigan,
pero son decisiones de infraestructura que no tocan el dominio.

---

## 6. DDD estratégico y bounded contexts

DDD estratégico responde "¿dónde están las fronteras del modelo?". Hexagonal
responde "¿cómo protejo cada modelo de la tecnología?". Juntos:

| Concepto DDD estratégico     | Cómo se materializa en hexagonal                              |
|------------------------------|---------------------------------------------------------------|
| **Bounded context**          | Un hexágono (módulo Modulith o microservicio)                  |
| **Ubiquitous language**      | Nombres de dominio y de **puertos** (`PasarelaDePago`, no `StripeClient`) |
| **Context map**              | Qué adaptadores conectan qué hexágonos y con qué contrato      |
| **Anti-corruption layer**    | Adaptador de salida que traduce el modelo remoto al propio     |
| **Open host service / published language** | Adaptador de entrada con contrato público versionado (API REST, esquema de eventos) |
| **Shared kernel**            | Código compartido mínimo y explícito (p. ej. `Dinero`); con mucha cautela |
| **Domain events**            | Eventos de dominio publicados por un puerto de salida, consumidos por adaptadores de entrada de otros contextos |

> Regla práctica: **dibuja primero el mapa de contextos y luego los
> hexágonos**. Un hexágono que mezcla dos contextos (Pedidos y Facturación con
> el mismo `Pedido`) está mal cortado, por muy limpios que sean sus puertos.

---

## 7. Caso real: Netflix (2020)

En 2020 el equipo de Studio Engineering de Netflix publicó en su blog técnico
*Ready for changes with Hexagonal Architecture*. Lo más relevante para el curso:

- Construían una aplicación nueva cuyas **fuentes de datos iban a cambiar**
  (empezaban leyendo de un sistema existente y planeaban migrar a otros,
  incluido GraphQL).
- Organizaron el código con **entidades, repositorios (interfaces), interactores
  (casos de uso), data sources (adaptadores de salida) y una capa de
  transporte (adaptadores de entrada)**. Fíjate en el vocabulario: **mezcla
  hexagonal y Clean** ("interactors" es de Clean) sin ningún complejo.
- Cuando llegó el cambio de fuente de datos, lo resolvieron escribiendo un
  **nuevo adaptador** sin tocar la lógica de negocio, en muy poco tiempo según
  cuentan en el artículo.
- Destacan también la **testabilidad**: la lógica se prueba sin depender de las
  fuentes de datos reales.

Lección: el beneficio de hexagonal **no se ve el primer día**. Se ve el día que
cambia lo que estaba fuera. Si en tu sistema "lo de fuera" no va a cambiar
nunca, el beneficio es menor (y la matriz del bloque 4 te lo dirá).

---

## 8. Tabla resumen

| Arquitectura moderna       | Papel de la hexagonal                                   | Adaptadores típicos                         |
|----------------------------|---------------------------------------------------------|---------------------------------------------|
| Microservicios             | Un hexágono por servicio; otros servicios = sistemas externos | RestClient, Kafka, JPA                   |
| Monolito modular           | Un hexágono por módulo; Spring Modulith vigila fronteras | `ApplicationEventPublisher`, `@ApplicationModuleListener` |
| Event-driven               | Kafka/Rabbit como adaptadores de entrada y salida        | `@KafkaListener`, `KafkaTemplate`, Outbox   |
| Serverless                 | La función es un adaptador de entrada                    | Spring Cloud Function + adaptador del proveedor |
| DDD estratégico            | Bounded context = hexágono; ACL = adaptador de salida    | Traductores de modelo remoto                |

---

## Ejercicio

Añade un **segundo adaptador de entrada** para `ConfirmarPedidoUseCase`: un
listener Kafka que escuche el topic `checkout.completado` con mensajes
`{"pedidoId": "..."}`. Responde: ¿cuántos ficheros del dominio y de la
aplicación has tocado?

<details><summary>Solución</summary>

```java
@Component
class CheckoutCompletadoKafkaListener {

    record CheckoutCompletadoMensaje(String pedidoId) {
    }

    private final ConfirmarPedidoUseCase confirmarPedido;

    CheckoutCompletadoKafkaListener(ConfirmarPedidoUseCase confirmarPedido) {
        this.confirmarPedido = confirmarPedido;
    }

    @KafkaListener(topics = "checkout.completado", groupId = "pedidos")
    void on(CheckoutCompletadoMensaje mensaje) {
        confirmarPedido.ejecutar(new ConfirmarPedidoCommand(
                new PedidoId(UUID.fromString(mensaje.pedidoId()))));
    }
}
```

**Cero** ficheros de dominio y aplicación tocados. Un fichero nuevo en
`infrastructure.adapter.in.messaging`. Queda pendiente la idempotencia: si el
mensaje llega dos veces, la segunda confirmación lanza
`TransicionDeEstadoInvalidaException`. Opciones: que el listener ignore ese
caso concreto si el pedido ya está `CONFIRMADO`, o registrar los mensajes
procesados.

Compáralo con VSA extremo: tendrías que añadir un listener **dentro** de
`ConfirmarPedido.java` (llamando al mismo `Handler`), lo cual también es
sencillo. La diferencia aparece cuando son 5 casos de uso × 3 canales.
</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture* (2005):
  https://alistair.cockburn.us/hexagonal-architecture/
- Netflix Technology Blog, *Ready for changes with Hexagonal Architecture* (2020):
  https://netflixtechblog.com/ready-for-changes-with-hexagonal-architecture-b315ec967749
- Spring Modulith, documentación de referencia:
  https://docs.spring.io/spring-modulith/reference/
- Chris Richardson, *Transactional Outbox*:
  https://microservices.io/patterns/data/transactional-outbox.html ; *Saga*:
  https://microservices.io/patterns/data/saga.html
- Eric Evans, *Domain-Driven Design* (2003) — parte IV, diseño estratégico;
  Vaughn Vernon, *Implementing Domain-Driven Design* (2013).
- Martin Fowler, *StranglerFigApplication*:
  https://martinfowler.com/bliki/StranglerFigApplication.html
- jMolecules (anotaciones de arquitectura y DDD que Spring Modulith entiende):
  https://github.com/xmolecules/jmolecules
- Spring Boot: https://docs.spring.io/spring-boot/index.html

---

## Mantra

> *"Microservicios, eventos o serverless deciden cuántas cajas hay. Hexagonal
> decide qué hay dentro de cada caja."*
