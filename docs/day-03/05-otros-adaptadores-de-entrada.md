# Día 3 — Bloque 5: Otros adaptadores de entrada

> *"Ports and adapters: the application is blissfully ignorant of the nature of the input device."*
> — Alistair Cockburn, *Hexagonal Architecture* (2005)

---

## 1. El problema en una frase

Mañana los pedidos no solo llegarán por la web: llegarán por **Kafka** desde el marketplace, habrá que **cancelar automáticamente** borradores abandonados cada noche y soporte quiere **una herramienta de consola** para dar de alta pedidos a mano. ¿Cuánto hay que tocar el hexágono? **Nada.**

---

## 2. La prueba de fuego del hexágono

Este bloque es la demostración práctica de la promesa de Cockburn: la aplicación puede ser **conducida igual** por usuarios, programas, tests o scripts batch.

```
   ┌──────────────────────┐
   │ PedidoController     │ HTTP ─────────┐
   └──────────────────────┘               │
   ┌──────────────────────┐               │      ┌──────────────────────────────┐
   │ PedidoSolicitado     │ Kafka ────────┼─────►│  CrearPedidoUseCase          │
   │ KafkaListener        │               │      │  AnadirLineaPedidoUseCase    │
   └──────────────────────┘               │      │  CancelarPedidoUseCase       │
   ┌──────────────────────┐               │      │  ListarBorradoresAbandonados │
   │ CancelarBorradores   │ Reloj ────────┤      │        Query                 │
   │ AbandonadosJob       │               │      │                              │
   └──────────────────────┘               │      │   (los MISMOS puertos y      │
   ┌──────────────────────┐               │      │    servicios del día 2)      │
   │ ImportarPedido       │ argv ─────────┤      └──────────────────────────────┘
   │ CliRunner            │               │
   └──────────────────────┘               │
   ┌──────────────────────┐               │
   │ Tests JUnit          │ Java ─────────┘
   └──────────────────────┘
      adapter.in.*                                 application.port.in
```

| Adaptador                          | Paquete                                     | Disparador          | Puertos que usa                                      |
|------------------------------------|---------------------------------------------|---------------------|------------------------------------------------------|
| `PedidoController`                 | `infrastructure.adapter.in.web`             | Petición HTTP       | Crear, AñadirLínea, Confirmar, Consultar             |
| `PedidoSolicitadoKafkaListener`    | `infrastructure.adapter.in.messaging`       | Mensaje Kafka       | Crear, AñadirLínea                                   |
| `CancelarBorradoresAbandonadosJob` | `infrastructure.adapter.in.scheduler`       | Cron                | ListarBorradoresAbandonados, Cancelar                |
| `ImportarPedidoCliRunner`          | `infrastructure.adapter.in.cli`             | Arranque con args   | Crear, AñadirLínea                                   |

Fíjate en la columna derecha: **se repiten**. Ese es el punto.

---

## 3. Bad: el caso de uso "copiado" en cada entrada

```java
@KafkaListener(topics = "pedidos.solicitados")
public void onMensaje(String json) {
    Map<String, Object> m = objectMapper.readValue(json, Map.class);
    PedidoJpaEntity e = new PedidoJpaEntity();
    e.setClienteId(UUID.fromString((String) m.get("clienteId")));
    e.setEstado("BORRADOR");
    for (Map<String, Object> l : (List<Map<String, Object>>) m.get("lineas")) {
        BigDecimal precio = catalogoJpa.findPrecio((String) l.get("sku"));   // regla duplicada
        if (stockJpa.disponible((String) l.get("sku")) < (Integer) l.get("cantidad")) {
            return;                                                         // ¡se traga el error!
        }
        e.getLineas().add(new LineaJpa(...));
    }
    pedidoJpaRepository.save(e);
}
```

### ¿Qué duele?

- La lógica de "crear pedido" existe ahora **dos veces** (controlador y listener) y divergirá.
- El listener habla JPA directamente: ni puertos ni dominio.
- El fallo de stock se **traga** en silencio: el mensaje se marca como consumido y el pedido desaparece.
- Imposible de testear sin Kafka y sin base de datos.

---

## 4. Good (1): adaptador de mensajería con Spring Kafka

### 4.1 Dependencia y configuración

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: pedidos-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.curso.pedidos.infrastructure.adapter.in.messaging"
        spring.json.value.default.type: "com.curso.pedidos.infrastructure.adapter.in.messaging.PedidoSolicitadoMessage"
        spring.json.use.type.headers: false
```

> `use.type.headers: false` + `value.default.type`: el productor (otro equipo) no tiene por qué conocer nuestras clases Java. El contrato es el JSON, no el FQCN.

### 4.2 El mensaje: el "DTO" de la mensajería

```java
package com.curso.pedidos.infrastructure.adapter.in.messaging;

import java.util.List;
import java.util.UUID;

public record PedidoSolicitadoMessage(
        UUID clienteId,
        List<Linea> lineas
) {
    public record Linea(String sku, int cantidad) { }
}
```

Es exactamente el mismo papel que `CrearPedidoRequest` en REST: **contrato del protocolo**, no dominio. Vive en `adapter.in.messaging`.

### 4.3 El listener

```java
package com.curso.pedidos.infrastructure.adapter.in.messaging;

import com.curso.pedidos.application.port.in.*;
import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.ProductoId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class PedidoSolicitadoKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(PedidoSolicitadoKafkaListener.class);

    private final CrearPedidoUseCase crearPedido;
    private final AnadirLineaPedidoUseCase anadirLinea;

    PedidoSolicitadoKafkaListener(CrearPedidoUseCase crearPedido,
                                  AnadirLineaPedidoUseCase anadirLinea) {
        this.crearPedido = crearPedido;
        this.anadirLinea = anadirLinea;
    }

    @KafkaListener(topics = "${pedidos.kafka.topic-solicitados:pedidos.solicitados}")
    void onPedidoSolicitado(PedidoSolicitadoMessage mensaje) {
        PedidoId id = crearPedido.ejecutar(new CrearPedidoCommand(new ClienteId(mensaje.clienteId())));
        for (PedidoSolicitadoMessage.Linea linea : mensaje.lineas()) {
            anadirLinea.ejecutar(new AnadirLineaPedidoCommand(
                    id, new ProductoId(linea.sku()), new Cantidad(linea.cantidad())));
        }
        log.info("Pedido {} creado desde Kafka para el cliente {}", id.valor(), mensaje.clienteId());
    }
}
```

**Compara con `PedidoController.crear` + `anadirLinea`**: los mismos puertos, los mismos comandos. Cero cambios en `domain` y `application`.

> **Deuda consciente** (lo avisamos en el bloque 1): encadenar `crear` + `anadirLinea` en el adaptador no es atómico. Si la segunda línea falla por stock, queda un pedido a medias. La solución correcta es un caso de uso `RealizarPedidoUseCase(clienteId, lineas)` con una sola transacción. Lo dejamos así para mostrar la reutilización, y lo resolvemos en el ejercicio del fichero 06.

### 4.4 Errores en mensajería: no hay "códigos HTTP"

El equivalente del `@RestControllerAdvice` en Kafka es el **`DefaultErrorHandler`** del contenedor: reintentos con *backoff* y, al agotarlos, envío a un **Dead Letter Topic**.

```java
package com.curso.pedidos.infrastructure.adapter.in.messaging;

import com.curso.pedidos.domain.exception.PedidoException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaErrorHandlingConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template);   // publica en <topic>-dlt
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3));
        // Las reglas de negocio no se arreglan reintentando: directo a la DLT
        handler.addNotRetryableExceptions(PedidoException.class, IllegalArgumentException.class);
        return handler;
    }
}
```

Spring Boot conecta automáticamente un bean `CommonErrorHandler` al contenedor de listeners. La traducción equivalente a la tabla del bloque 4:

| Excepción                        | REST             | Kafka                                   |
|----------------------------------|------------------|-----------------------------------------|
| Validación / dato inválido       | `400`            | Sin reintento → DLT                     |
| `PedidoException` (negocio)      | `404/409/422`    | Sin reintento → DLT (o evento de rechazo)|
| Infra transitoria (BD caída)     | `500`/`503`      | Reintentar con backoff → DLT al final   |

Misma excepción de dominio, **semántica distinta según el protocolo**. Por eso la traducción vive en cada adaptador y nunca en el dominio.

### 4.5 ¿Y RabbitMQ?

Idéntico en espíritu, cambia la anotación:

```java
@Component
class PedidoSolicitadoRabbitListener {
    private final CrearPedidoUseCase crearPedido;
    private final AnadirLineaPedidoUseCase anadirLinea;
    // constructor...

    @RabbitListener(queues = "pedidos.solicitados")
    void onPedidoSolicitado(PedidoSolicitadoMessage mensaje) {
        // exactamente el mismo cuerpo que el listener de Kafka
    }
}
```

Con `spring-boot-starter-amqp` y un `Jackson2JsonMessageConverter` como bean, Spring deserializa el JSON al record. **Cambiar de Kafka a Rabbit es cambiar un adaptador**, no la aplicación.

### 4.6 Testing del listener sin broker

El listener es una clase Java con un método. **Se prueba llamándolo directamente**: sin Kafka, sin Spring.

```java
package com.curso.pedidos.infrastructure.adapter.in.messaging;

import com.curso.pedidos.application.port.in.*;
import com.curso.pedidos.domain.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PedidoSolicitadoKafkaListenerTest {

    private final CrearPedidoUseCase crearPedido = mock(CrearPedidoUseCase.class);
    private final AnadirLineaPedidoUseCase anadirLinea = mock(AnadirLineaPedidoUseCase.class);
    private final PedidoSolicitadoKafkaListener listener =
            new PedidoSolicitadoKafkaListener(crearPedido, anadirLinea);

    @Test
    void creaElPedidoYAnadeCadaLineaEnOrden() {
        UUID clienteId = UUID.randomUUID();
        PedidoId pedidoId = new PedidoId(UUID.randomUUID());
        when(crearPedido.ejecutar(any())).thenReturn(pedidoId);

        listener.onPedidoSolicitado(new PedidoSolicitadoMessage(clienteId, List.of(
                new PedidoSolicitadoMessage.Linea("LIB-001", 2),
                new PedidoSolicitadoMessage.Linea("LIB-002", 1))));

        InOrder orden = inOrder(crearPedido, anadirLinea);
        orden.verify(crearPedido).ejecutar(new CrearPedidoCommand(new ClienteId(clienteId)));
        orden.verify(anadirLinea).ejecutar(new AnadirLineaPedidoCommand(
                pedidoId, new ProductoId("LIB-001"), new Cantidad(2)));
        orden.verify(anadirLinea).ejecutar(new AnadirLineaPedidoCommand(
                pedidoId, new ProductoId("LIB-002"), new Cantidad(1)));
        verifyNoMoreInteractions(crearPedido, anadirLinea);
    }

    @Test
    void mensajeConCantidadInvalida_propagaLaExcepcionParaQueActueElErrorHandler() {
        when(crearPedido.ejecutar(any())).thenReturn(new PedidoId(UUID.randomUUID()));
        var mensaje = new PedidoSolicitadoMessage(UUID.randomUUID(),
                List.of(new PedidoSolicitadoMessage.Linea("LIB-001", 0)));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> listener.onPedidoSolicitado(mensaje))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(anadirLinea);
    }
}
```

El segundo test documenta una decisión: **el listener no captura excepciones**; las propaga para que el `DefaultErrorHandler` decida (reintento/DLT). Si el listener las tragara, perderíamos mensajes.

¿Y la deserialización JSON, el topic, el group-id? Eso se prueba **con un broker real** en tests de integración: **Testcontainers** con un contenedor de Kafka (`org.testcontainers:kafka`) o `@EmbeddedKafka` de `spring-kafka-test`. Lo veremos en el **día 5**.

---

## 5. Good (2): adaptador planificado con `@Scheduled`

Requisito: cada noche, cancelar los pedidos en `BORRADOR` con más de 7 días.

### 5.1 Puertos (en `application.port.in`, sin Spring)

```java
public interface ListarBorradoresAbandonadosQuery {
    List<PedidoId> listar(Instant creadosAntesDe);
}
```

`CancelarPedidoUseCase` **ya existía** (día 2). Solo añadimos una consulta; el caso de uso de cancelar se reutiliza tal cual.

### 5.2 El job

```java
package com.curso.pedidos.infrastructure.adapter.in.scheduler;

import com.curso.pedidos.application.port.in.CancelarPedidoCommand;
import com.curso.pedidos.application.port.in.CancelarPedidoUseCase;
import com.curso.pedidos.application.port.in.ListarBorradoresAbandonadosQuery;
import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.model.PedidoId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
class CancelarBorradoresAbandonadosJob {

    private static final Logger log = LoggerFactory.getLogger(CancelarBorradoresAbandonadosJob.class);

    private final ListarBorradoresAbandonadosQuery listarAbandonados;
    private final CancelarPedidoUseCase cancelarPedido;
    private final Clock clock;
    private final Duration antiguedadMaxima;

    CancelarBorradoresAbandonadosJob(ListarBorradoresAbandonadosQuery listarAbandonados,
                                     CancelarPedidoUseCase cancelarPedido,
                                     Clock clock,
                                     @Value("${pedidos.borradores.antiguedad-maxima:P7D}") Duration antiguedadMaxima) {
        this.listarAbandonados = listarAbandonados;
        this.cancelarPedido = cancelarPedido;
        this.clock = clock;
        this.antiguedadMaxima = antiguedadMaxima;
    }

    @Scheduled(cron = "${pedidos.borradores.cron:0 0 3 * * *}", zone = "Europe/Madrid")
    void ejecutar() {
        Instant limite = Instant.now(clock).minus(antiguedadMaxima);
        int cancelados = 0;
        for (PedidoId id : listarAbandonados.listar(limite)) {
            try {
                cancelarPedido.ejecutar(new CancelarPedidoCommand(id));
                cancelados++;
            } catch (PedidoException e) {
                // Un pedido problemático no debe abortar el lote completo
                log.warn("No se pudo cancelar el pedido {}: {}", id.valor(), e.getMessage());
            }
        }
        log.info("Job borradores abandonados: {} pedidos cancelados (límite {})", cancelados, limite);
    }
}
```

Necesita habilitar la planificación y exponer un `Clock`:

```java
package com.curso.pedidos.infrastructure.config;

@Configuration
@EnableScheduling
class SchedulingConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

Notas:

- **`Clock` inyectado**: sin él, el test dependería de la hora real. Inyectar el reloj es la forma estándar de hacer testeable el tiempo.
- **`try/catch` aquí sí**: en un lote, la "traducción de errores" del protocolo batch es *registrar y seguir*. Es la misma idea que el `@RestControllerAdvice`, aplicada a otro protocolo.
- **Varias instancias**: si despliegas 3 réplicas, el job corre 3 veces. Soluciones: ShedLock, Quartz en clúster o un scheduler externo (Kubernetes CronJob que llama al CLI del apartado siguiente). Es un problema **del adaptador**, no del dominio.
- La regla "7 días" ¿es negocio o configuración? Si cambia por campaña comercial, puede ser negocio y merecer vivir en el dominio. Aquí la tratamos como parámetro operativo.

### 5.3 Testing del job

```java
package com.curso.pedidos.infrastructure.adapter.in.scheduler;

import com.curso.pedidos.application.port.in.*;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import com.curso.pedidos.domain.model.PedidoId;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

class CancelarBorradoresAbandonadosJobTest {

    private static final Instant AHORA = Instant.parse("2026-11-23T03:00:00Z");

    private final ListarBorradoresAbandonadosQuery listar = mock(ListarBorradoresAbandonadosQuery.class);
    private final CancelarPedidoUseCase cancelar = mock(CancelarPedidoUseCase.class);
    private final CancelarBorradoresAbandonadosJob job = new CancelarBorradoresAbandonadosJob(
            listar, cancelar, Clock.fixed(AHORA, ZoneOffset.UTC), Duration.ofDays(7));

    @Test
    void consultaConElLimiteCorrectoYCancelaCadaPedido() {
        PedidoId p1 = new PedidoId(UUID.randomUUID());
        PedidoId p2 = new PedidoId(UUID.randomUUID());
        when(listar.listar(Instant.parse("2026-11-16T03:00:00Z"))).thenReturn(List.of(p1, p2));

        job.ejecutar();

        verify(cancelar).ejecutar(new CancelarPedidoCommand(p1));
        verify(cancelar).ejecutar(new CancelarPedidoCommand(p2));
    }

    @Test
    void unFalloDeNegocioNoDetieneElLote() {
        PedidoId p1 = new PedidoId(UUID.randomUUID());
        PedidoId p2 = new PedidoId(UUID.randomUUID());
        when(listar.listar(any())).thenReturn(List.of(p1, p2));
        willThrow(new TransicionDeEstadoInvalidaException("ya enviado"))
                .given(cancelar).ejecutar(new CancelarPedidoCommand(p1));

        job.ejecutar();

        verify(cancelar).ejecutar(new CancelarPedidoCommand(p2));   // el segundo se procesa igual
    }
}
```

No probamos que el cron "dispare a las 3:00": eso es Spring y está probado por Spring. Probamos **nuestra traducción**: tiempo → consulta → comandos, y la política de errores.

---

## 6. Good (3): adaptador de línea de comandos con `CommandLineRunner`

Requisito: soporte quiere ejecutar

```bash
java -jar pedidos.jar --spring.profiles.active=cli crear-pedido 1111...5555 LIB-001:2 LIB-002:1
```

y que la aplicación cree el pedido y termine.

### 6.1 El runner

```java
package com.curso.pedidos.infrastructure.adapter.in.cli;

import com.curso.pedidos.application.port.in.*;
import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.ProductoId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.UUID;

@Component
@Profile("cli")
class ImportarPedidoCliRunner implements CommandLineRunner {

    private final CrearPedidoUseCase crearPedido;
    private final AnadirLineaPedidoUseCase anadirLinea;
    private final PrintStream out;

    @Autowired   // con dos constructores, Spring necesita saber cuál usar
    ImportarPedidoCliRunner(CrearPedidoUseCase crearPedido, AnadirLineaPedidoUseCase anadirLinea) {
        this(crearPedido, anadirLinea, System.out);
    }

    ImportarPedidoCliRunner(CrearPedidoUseCase crearPedido, AnadirLineaPedidoUseCase anadirLinea,
                            PrintStream out) {
        this.crearPedido = crearPedido;
        this.anadirLinea = anadirLinea;
        this.out = out;
    }

    @Override
    public void run(String... args) {
        String[] posicionales = Arrays.stream(args).filter(a -> !a.startsWith("--")).toArray(String[]::new);
        if (posicionales.length < 3 || !"crear-pedido".equals(posicionales[0])) {
            throw new IllegalArgumentException(
                    "Uso: crear-pedido <clienteId> <sku:cantidad> [<sku:cantidad> ...]");
        }
        PedidoId id = crearPedido.ejecutar(
                new CrearPedidoCommand(new ClienteId(UUID.fromString(posicionales[1]))));

        for (int i = 2; i < posicionales.length; i++) {
            String[] partes = posicionales[i].split(":");
            if (partes.length != 2) {
                throw new IllegalArgumentException("Línea mal formada (esperado sku:cantidad): " + posicionales[i]);
            }
            anadirLinea.ejecutar(new AnadirLineaPedidoCommand(
                    id, new ProductoId(partes[0]), new Cantidad(Integer.parseInt(partes[1]))));
        }
        out.println("Pedido creado: " + id.valor());
    }
}
```

Notas:

- **Dos constructores**: uno para Spring (usa `System.out`, marcado con `@Autowired` porque con varios constructores Spring no sabe cuál elegir) y otro para inyectar un `PrintStream` en tests.
- **`@Profile("cli")`**: el runner solo existe cuando se arranca en modo CLI; en el servicio web no se ejecuta.
- **Errores → código de salida**: si `run` lanza una excepción, Spring Boot aborta el arranque y la JVM termina con código ≠ 0. Para códigos de salida específicos, implementa `ExitCodeGenerator` o mapea excepciones con `ExitCodeExceptionMapper` (Spring Boot). Es la "tabla excepción → HTTP" del protocolo CLI.
- Para CLIs más ricas: **Spring Shell** o **picocli**. El principio no cambia.
- Si en modo CLI no quieres levantar Tomcat: `--spring.main.web-application-type=none`.

### 6.2 Testing del runner

```java
package com.curso.pedidos.infrastructure.adapter.in.cli;

import com.curso.pedidos.application.port.in.*;
import com.curso.pedidos.domain.model.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ImportarPedidoCliRunnerTest {

    private final CrearPedidoUseCase crearPedido = mock(CrearPedidoUseCase.class);
    private final AnadirLineaPedidoUseCase anadirLinea = mock(AnadirLineaPedidoUseCase.class);
    private final ByteArrayOutputStream salida = new ByteArrayOutputStream();
    private final ImportarPedidoCliRunner runner =
            new ImportarPedidoCliRunner(crearPedido, anadirLinea, new PrintStream(salida, true));

    @Test
    void creaPedidoConSusLineasEImprimeElId() {
        UUID clienteId = UUID.randomUUID();
        PedidoId pedidoId = new PedidoId(UUID.randomUUID());
        when(crearPedido.ejecutar(any())).thenReturn(pedidoId);

        runner.run("--spring.profiles.active=cli", "crear-pedido", clienteId.toString(), "LIB-001:2", "LIB-002:1");

        verify(crearPedido).ejecutar(new CrearPedidoCommand(new ClienteId(clienteId)));
        verify(anadirLinea).ejecutar(new AnadirLineaPedidoCommand(pedidoId, new ProductoId("LIB-001"), new Cantidad(2)));
        verify(anadirLinea).ejecutar(new AnadirLineaPedidoCommand(pedidoId, new ProductoId("LIB-002"), new Cantidad(1)));
        assertThat(salida.toString()).contains("Pedido creado: " + pedidoId.valor());
    }

    @Test
    void sinArgumentosSuficientes_fallaConMensajeDeUso() {
        assertThatThrownBy(() -> runner.run("crear-pedido"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Uso:");
        verifyNoInteractions(crearPedido, anadirLinea);
    }

    @Test
    void lineaMalFormada_falla() {
        when(crearPedido.ejecutar(any())).thenReturn(new PedidoId(UUID.randomUUID()));

        assertThatThrownBy(() -> runner.run("crear-pedido", UUID.randomUUID().toString(), "LIB-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sku:cantidad");
    }
}
```

---

## 7. Lo que NO ha cambiado

Hagamos el inventario tras añadir tres adaptadores nuevos:

| Capa / paquete                         | Ficheros modificados                                         |
|----------------------------------------|--------------------------------------------------------------|
| `domain.*`                             | **0**                                                        |
| `application.service`                  | **0** (salvo el servicio que implementa la nueva *query*)    |
| `application.port.in`                  | **+1** interfaz (`ListarBorradoresAbandonadosQuery`)         |
| `infrastructure.adapter.in.web`        | **0**                                                        |
| `infrastructure.adapter.in.messaging`  | +3 (listener, mensaje, config de errores)                    |
| `infrastructure.adapter.in.scheduler`  | +1 (job)                                                     |
| `infrastructure.adapter.in.cli`        | +1 (runner)                                                  |
| `infrastructure.config`                | +1 (`SchedulingConfig`) y registrar la nueva query en `BeanConfiguration` |

Eso es **Open/Closed a nivel de arquitectura**: abierta a nuevos canales de entrada, cerrada a modificación del núcleo.

---

## 8. Trampas comunes

- **Lógica de negocio "porque viene de Kafka"**: "si el mensaje viene del marketplace, aplica un 5 % de descuento". Eso es una regla de negocio ⇒ un campo `canal` en el comando y la regla en el dominio.
- **Listener que traga excepciones** con `catch (Exception e) { log.error(...) }`: el offset avanza y el mensaje se pierde. Propaga y configura el error handler.
- **Idempotencia**: Kafka garantiza *at-least-once*; el mismo mensaje puede llegar dos veces. La idempotencia (clave de mensaje, tabla de procesados) es responsabilidad del adaptador o de un caso de uso específico. Lo veremos con Outbox en el día 6.
- **Jobs que cargan todo en memoria**: `listar()` devolviendo 2 millones de IDs. Pagina en el puerto (`listar(limite, tamañoPagina)`).
- **Un "ServicioDeKafka" en `application`** con `@KafkaListener`: la anotación es infraestructura; va en el adaptador.

---

## 9. Resumen

| Adaptador | Disparador        | Traducción de entrada                  | Traducción de errores                  | Test                                         |
|-----------|-------------------|----------------------------------------|----------------------------------------|----------------------------------------------|
| REST      | HTTP              | JSON → DTO → Command                   | `@RestControllerAdvice` → ProblemDetail| `@WebMvcTest` + MockMvc                      |
| Kafka     | Mensaje           | JSON → Message record → Command        | `DefaultErrorHandler` → retry / DLT    | Llamada directa + mocks (Testcontainers d5)  |
| Rabbit    | Mensaje           | JSON → Message record → Command        | Retry / DLQ                            | Llamada directa + mocks                      |
| Scheduler | Reloj             | `Clock` → consulta → Commands          | Log y continuar con el lote            | Llamada directa con `Clock.fixed`            |
| CLI       | `argv`            | Strings → Command                      | Excepción → exit code                  | `run(...)` directo + `PrintStream` capturado |

---

## Ejercicio

Añade un adaptador **RabbitMQ** que escuche la cola `pedidos.confirmar` con mensajes `{"pedidoId": "..."}` y confirme el pedido. Escribe su test unitario.

**Pista**: el puerto ya existe. ¿Cuántas líneas de lógica tiene el listener?

<details>
<summary>Solución</summary>

```java
package com.curso.pedidos.infrastructure.adapter.in.messaging;

public record ConfirmarPedidoMessage(UUID pedidoId) { }

@Component
class ConfirmarPedidoRabbitListener {

    private final ConfirmarPedidoUseCase confirmarPedido;

    ConfirmarPedidoRabbitListener(ConfirmarPedidoUseCase confirmarPedido) {
        this.confirmarPedido = confirmarPedido;
    }

    @RabbitListener(queues = "pedidos.confirmar")
    void onConfirmar(ConfirmarPedidoMessage mensaje) {
        confirmarPedido.ejecutar(new ConfirmarPedidoCommand(new PedidoId(mensaje.pedidoId())));
    }
}
```

```java
class ConfirmarPedidoRabbitListenerTest {

    private final ConfirmarPedidoUseCase confirmar = mock(ConfirmarPedidoUseCase.class);
    private final ConfirmarPedidoRabbitListener listener = new ConfirmarPedidoRabbitListener(confirmar);

    @Test
    void traduceElMensajeAlComando() {
        UUID id = UUID.randomUUID();
        listener.onConfirmar(new ConfirmarPedidoMessage(id));
        verify(confirmar).ejecutar(new ConfirmarPedidoCommand(new PedidoId(id)));
    }
}
```

Una línea de lógica. Y el `ConfirmarPedidoService` es exactamente el mismo que usa `POST /api/pedidos/{id}/confirmacion`.
</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture* (2005): https://alistair.cockburn.us/hexagonal-architecture/
- Spring for Apache Kafka, Reference: https://docs.spring.io/spring-kafka/reference/
- Spring AMQP, Reference: https://docs.spring.io/spring-amqp/reference/
- Spring Framework, *Task Execution and Scheduling*: https://docs.spring.io/spring-framework/reference/integration/scheduling.html
- Spring Boot reference (CommandLineRunner, exit codes): https://docs.spring.io/spring-boot/index.html
- Testcontainers for Java: https://java.testcontainers.org/
- Mockito: https://site.mockito.org/
- Netflix Tech Blog, *Ready for changes with Hexagonal Architecture*: https://netflixtechblog.com/ready-for-changes-with-hexagonal-architecture-b315ec967749

---

## Mantra

> *"Un caso de uso, muchas puertas. Si para abrir una puerta nueva tienes que mover la casa, no tenías puertas: tenías paredes con agujeros."*
