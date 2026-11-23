# Día 4 — Bloque 4: Adaptadores de notificación y mensajería

> *"Make the change easy, then make the easy change."* — Kent Beck

---

## 1. El problema en una frase

Cuando se confirma un pedido hay que **avisar al cliente** (email) y **contar
al resto del sistema** lo que ha pasado (evento en Kafka). En producción
queremos SMTP y Kafka reales; en el portátil del desarrollador y en los tests,
**ni SMTP ni Kafka**… y sin tocar una línea del caso de uso.

---

## 2. Los dos puertos

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.Email;
import com.curso.pedidos.domain.model.Pedido;

public interface NotificadorPedidos {
    void notificarConfirmacion(Pedido pedido, Email destinatario);
}
```

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.event.EventoDeDominio;

public interface PublicadorDeEventos {
    void publicar(EventoDeDominio evento);
}
```

Y los eventos del dominio (día 2), con una interfaz común:

```java
package com.curso.pedidos.domain.event;

public sealed interface EventoDeDominio
        permits PedidoConfirmado, PedidoPagado, PedidoCancelado {
    PedidoId pedidoId();
    Instant ocurridoEn();
}

public record PedidoConfirmado(PedidoId pedidoId, Instant ocurridoEn) implements EventoDeDominio {}
public record PedidoPagado(PedidoId pedidoId, Instant ocurridoEn) implements EventoDeDominio {}
public record PedidoCancelado(PedidoId pedidoId, Instant ocurridoEn) implements EventoDeDominio {}
```

> Fíjate en los nombres: `NotificadorPedidos`, no `EmailSender`;
> `PublicadorDeEventos`, no `KafkaProducer`. Mañana el aviso puede ser un SMS
> o un push, y el bus puede ser RabbitMQ. El puerto no cambia.

---

## 3. Bad: `if (entorno == "dev")` en el código

```java
public class EmailNotificadorAdapter implements NotificadorPedidos {

    @Value("${app.entorno}") String entorno;   // ❌
    private final JavaMailSender mail;

    @Override
    public void notificarConfirmacion(Pedido pedido, Email destinatario) {
        if ("dev".equals(entorno) || "test".equals(entorno)) {   // ❌ flags de entorno
            System.out.println("Email simulado a " + destinatario);
            return;
        }
        // enviar de verdad...
    }
}
```

### ¿Qué duele?

- **Lógica de despliegue mezclada** con lógica de envío. Cada adaptador
  acaba con su propio `if`.
- **Código de producción que no se ejecuta en tests**: justo el camino que
  queremos probar está detrás de un `if` que en test nunca se cumple.
- **Viola OCP**: añadir un entorno `staging` obliga a editar todos los
  adaptadores.

La solución hexagonal: **dos adaptadores distintos** para el mismo puerto y
que la *composition root* elija cuál cablear.

---

## 4. Good (I): `EmailNotificadorAdapter` con `JavaMailSender`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

```yaml
spring:
  mail:
    host: smtp.example.com
    port: 587
    username: ${SMTP_USER}
    password: ${SMTP_PASSWORD}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
      mail.smtp.connectiontimeout: 3000
      mail.smtp.timeout: 5000
notificaciones:
  remitente: pedidos@tienda.example
```

```java
package com.curso.pedidos.infrastructure.adapter.out.notification;

import com.curso.pedidos.application.port.out.NotificadorPedidos;
import com.curso.pedidos.domain.model.Email;
import com.curso.pedidos.domain.model.Pedido;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

public class EmailNotificadorAdapter implements NotificadorPedidos {

    private final JavaMailSender mailSender;
    private final String remitente;

    public EmailNotificadorAdapter(JavaMailSender mailSender, String remitente) {
        this.mailSender = mailSender;
        this.remitente = remitente;
    }

    @Override
    public void notificarConfirmacion(Pedido pedido, Email destinatario) {
        var mensaje = new SimpleMailMessage();
        mensaje.setFrom(remitente);
        mensaje.setTo(destinatario.valor());
        mensaje.setSubject("Tu pedido " + pedido.id().valor() + " está confirmado");
        mensaje.setText("""
                ¡Gracias por tu compra!

                Pedido: %s
                Total:  %s %s
                """.formatted(pedido.id().valor(),
                              pedido.total().importe(),
                              pedido.total().moneda().getCurrencyCode()));
        try {
            mailSender.send(mensaje);
        } catch (MailException e) {
            throw new NotificacionFallidaException(
                    "No se pudo notificar el pedido " + pedido.id().valor(), e);
        }
    }
}
```

`NotificacionFallidaException` es una excepción **del puerto**
(`application.port.out`), no `MailException` de Spring. ¿Debe un fallo de
email tumbar la confirmación del pedido? Normalmente **no**: es una decisión
del caso de uso (o mejor, mándalo de forma asíncrona tras el evento). Lo
retomamos con *Transactional Outbox* en el día 6.

> Variante: plantillas HTML con Thymeleaf + `MimeMessageHelper`. La plantilla
> es un detalle del adaptador: vive en `resources/templates/email/`, no en el
> dominio.

---

## 5. Good (II): `PublicadorDeEventos`

### 5.1 Con Kafka

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
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
```

**No publiques el evento de dominio tal cual.** El mensaje en Kafka es un
**contrato público** con otros equipos; el record de dominio es interno y debe
poder cambiar. El adaptador traduce a un mensaje de integración:

```java
package com.curso.pedidos.infrastructure.adapter.out.messaging;

import java.time.Instant;

/** Contrato público (versionado) del topic "pedidos.eventos". */
record PedidoEventoMensaje(String tipo, int version, String pedidoId, Instant ocurridoEn) {}
```

```java
package com.curso.pedidos.infrastructure.adapter.out.messaging;

import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.*;
import org.springframework.kafka.core.KafkaTemplate;

public class KafkaPublicadorDeEventos implements PublicadorDeEventos {

    static final String TOPIC = "pedidos.eventos";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaPublicadorDeEventos(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publicar(EventoDeDominio evento) {
        var mensaje = new PedidoEventoMensaje(
                tipoDe(evento), 1,
                evento.pedidoId().valor().toString(),
                evento.ocurridoEn());
        // clave = pedidoId → todos los eventos de un pedido van a la misma partición (orden)
        kafkaTemplate.send(TOPIC, mensaje.pedidoId(), mensaje);
    }

    private static String tipoDe(EventoDeDominio evento) {
        if (evento instanceof PedidoConfirmado) return "PEDIDO_CONFIRMADO";
        if (evento instanceof PedidoPagado)     return "PEDIDO_PAGADO";
        if (evento instanceof PedidoCancelado)  return "PEDIDO_CANCELADO";
        throw new IllegalArgumentException("Evento desconocido: " + evento);
    }
}
```

> Con Java 21 el método `tipoDe` es un `switch` con *pattern matching*
> exhaustivo sobre el `sealed interface`: si mañana añades `PedidoEnviado`,
> no compila hasta que lo trates.

> Trampa: `kafkaTemplate.send(...)` es **asíncrono** (devuelve
> `CompletableFuture<SendResult>`). Si necesitas garantía de entrega
> sincronizada con la BBDD, el patrón correcto es **Transactional Outbox**
> (día 6), no un `.get()` dentro de la transacción.

### 5.2 Con `ApplicationEventPublisher` (in-process)

Para un monolito modular, publicar en el bus de eventos de Spring es más que
suficiente (y Spring Modulith lo convierte en una opción seria con
persistencia de eventos):

```java
package com.curso.pedidos.infrastructure.adapter.out.messaging;

import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.EventoDeDominio;
import org.springframework.context.ApplicationEventPublisher;

public class SpringPublicadorDeEventos implements PublicadorDeEventos {

    private final ApplicationEventPublisher publisher;

    public SpringPublicadorDeEventos(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publicar(EventoDeDominio evento) {
        publisher.publishEvent(evento);   // desde Spring 4.2 acepta cualquier objeto
    }
}
```

Un consumidor (adaptador **de entrada** del mismo u otro módulo):

```java
@Component
class EnviarEmailAlConfirmarListener {

    @TransactionalEventListener   // se ejecuta tras el commit
    void on(PedidoConfirmado evento) { /* ... */ }
}
```

---

## 6. Adaptadores en memoria / fakes

Para desarrollo local y tests, implementamos **los mismos puertos** con
adaptadores triviales. No son mocks: son **implementaciones de verdad**,
simples y deterministas.

```java
package com.curso.pedidos.infrastructure.adapter.out.notification;

import com.curso.pedidos.application.port.out.NotificadorPedidos;
import com.curso.pedidos.domain.model.Email;
import com.curso.pedidos.domain.model.Pedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogNotificadorAdapter implements NotificadorPedidos {

    private static final Logger log = LoggerFactory.getLogger(LogNotificadorAdapter.class);

    @Override
    public void notificarConfirmacion(Pedido pedido, Email destinatario) {
        log.info("[FAKE-EMAIL] A {}: pedido {} confirmado (total {})",
                destinatario.valor(), pedido.id().valor(), pedido.total());
    }
}
```

```java
package com.curso.pedidos.infrastructure.adapter.out.messaging;

import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.EventoDeDominio;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryPublicadorDeEventos implements PublicadorDeEventos {

    private final List<EventoDeDominio> publicados = new CopyOnWriteArrayList<>();

    @Override
    public void publicar(EventoDeDominio evento) {
        publicados.add(evento);
    }

    public List<EventoDeDominio> publicados() {
        return List.copyOf(publicados);
    }

    public void limpiar() {
        publicados.clear();
    }
}
```

| Tipo de doble | Qué es                                          | Cuándo                                 |
|---------------|-------------------------------------------------|----------------------------------------|
| **Mock**      | Objeto generado (Mockito) que verifica llamadas | Test unitario: "¿se llamó a publicar?" |
| **Fake**      | Implementación real, simplificada               | Dev local, tests de aceptación, demos  |
| **Stub**      | Devuelve respuestas fijas                       | Simular un `CatalogoProductos`         |

> Los fakes pueden vivir en `src/main` (si se usan en el perfil `local`) o en
> `src/test` (si solo los usan los tests). Lo importante: también hay que
> **probar el fake** (un fake roto da tests verdes mentirosos). En el día 5
> veremos **tests de contrato del puerto**: el mismo test abstracto ejecutado
> contra el fake y contra el adaptador real.

---

## 7. Elegir adaptador con perfiles de Spring

```java
package com.curso.pedidos.infrastructure.config;

import com.curso.pedidos.application.port.out.NotificadorPedidos;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.infrastructure.adapter.out.messaging.InMemoryPublicadorDeEventos;
import com.curso.pedidos.infrastructure.adapter.out.messaging.KafkaPublicadorDeEventos;
import com.curso.pedidos.infrastructure.adapter.out.notification.EmailNotificadorAdapter;
import com.curso.pedidos.infrastructure.adapter.out.notification.LogNotificadorAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
class SalidaConfiguration {

    @Bean
    @Profile("prod")
    NotificadorPedidos emailNotificador(JavaMailSender mailSender,
                                        @Value("${notificaciones.remitente}") String remitente) {
        return new EmailNotificadorAdapter(mailSender, remitente);
    }

    @Bean
    @Profile("!prod")
    NotificadorPedidos logNotificador() {
        return new LogNotificadorAdapter();
    }

    @Bean
    @Profile("prod")
    PublicadorDeEventos kafkaPublicador(KafkaTemplate<String, Object> kafkaTemplate) {
        return new KafkaPublicadorDeEventos(kafkaTemplate);
    }

    @Bean
    @Profile("!prod")
    PublicadorDeEventos inMemoryPublicador() {
        return new InMemoryPublicadorDeEventos();
    }
}
```

```bash
# Local: fakes, sin SMTP ni Kafka
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Producción
java -jar pedidos.jar --spring.profiles.active=prod
```

> Trampa: con `spring-kafka` y `spring-boot-starter-mail` en el classpath,
> Spring Boot autoconfigura `KafkaTemplate` y `JavaMailSender` aunque no los
> uses. No pasa nada (no conectan hasta el primer uso), pero si molestan en
> local puedes excluir las autoconfiguraciones por perfil con
> `spring.autoconfigure.exclude`.

Alternativa a `@Profile`: `@ConditionalOnProperty(name = "pedidos.notificador",
havingValue = "email")`. Es más granular (puedo tener Kafka real y email fake
en *staging*). Lo comparamos en el
[bloque 5](05-gestion-de-dependencias.md).

```
                      ┌─────────────────────────┐
                      │ «port» NotificadorPedidos│
                      └────────────▲────────────┘
                 implements        │        implements
           ┌───────────────────────┴──────────────────────┐
 ┌─────────┴────────────────┐                 ┌───────────┴───────────┐
 │ EmailNotificadorAdapter  │                 │ LogNotificadorAdapter │
 │ @Profile("prod")         │                 │ @Profile("!prod")     │
 │  └─► JavaMailSender      │                 │  └─► SLF4J            │
 └──────────────────────────┘                 └───────────────────────┘
          El caso de uso no sabe cuál le ha tocado. Ni le importa.
```

---

## 8. Testing

### 8.1 Email: mock de `JavaMailSender` + `ArgumentCaptor`

```java
package com.curso.pedidos.infrastructure.adapter.out.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailNotificadorAdapterTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final EmailNotificadorAdapter adapter =
            new EmailNotificadorAdapter(mailSender, "pedidos@tienda.example");

    @Test
    void envia_un_email_con_el_id_del_pedido() {
        Pedido pedido = PedidoMother.confirmadoConDosLineas();

        adapter.notificarConfirmacion(pedido, new Email("ana@example.com"));

        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage enviado = captor.getValue();
        assertThat(enviado.getTo()).containsExactly("ana@example.com");
        assertThat(enviado.getFrom()).isEqualTo("pedidos@tienda.example");
        assertThat(enviado.getSubject()).contains(pedido.id().valor().toString());
    }

    @Test
    void traduce_mail_exception_a_excepcion_del_puerto() {
        doThrow(new MailSendException("SMTP caído"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> adapter.notificarConfirmacion(
                PedidoMother.confirmadoConDosLineas(), new Email("ana@example.com")))
                .isInstanceOf(NotificacionFallidaException.class)
                .hasCauseInstanceOf(MailSendException.class);
    }
}
```

> Para un test más realista: **GreenMail** levanta un SMTP en memoria y
> permite leer los correos recibidos.

### 8.2 Kafka: verificar el mensaje de integración

```java
class KafkaPublicadorDeEventosTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final KafkaPublicadorDeEventos publicador = new KafkaPublicadorDeEventos(kafka);

    @Test
    void publica_mensaje_versionado_con_clave_pedido_id() {
        var pedidoId = new PedidoId(UUID.randomUUID());
        var ahora = Instant.parse("2026-11-23T10:00:00Z");

        publicador.publicar(new PedidoConfirmado(pedidoId, ahora));

        verify(kafka).send(
                KafkaPublicadorDeEventos.TOPIC,
                pedidoId.valor().toString(),
                new PedidoEventoMensaje("PEDIDO_CONFIRMADO", 1,
                        pedidoId.valor().toString(), ahora));
    }
}
```

Para un test de integración real: `@EmbeddedKafka` (de `spring-kafka-test`) o
mejor **Testcontainers** con `KafkaContainer`, consumiendo del topic y
comprobando el JSON. Lo hacemos en el día 5.

### 8.3 El fake, usado en un test de aplicación

```java
@Test
void confirmar_publica_pedido_confirmado() {
    var publicador = new InMemoryPublicadorDeEventos();
    var service = new ConfirmarPedidoService(repo, new LogNotificadorAdapter(), publicador);

    service.ejecutar(new ConfirmarPedidoCommand(pedido.id(), new Email("a@b.es")));

    assertThat(publicador.publicados())
            .singleElement()
            .isInstanceOf(PedidoConfirmado.class);
}
```

Sin Mockito, sin Spring, legible para cualquiera.

---

## 9. Resumen

| Puerto                | Adaptador prod               | Adaptador dev/test             | Selección         |
|-----------------------|------------------------------|--------------------------------|-------------------|
| `NotificadorPedidos`  | `EmailNotificadorAdapter`    | `LogNotificadorAdapter`        | `@Profile`        |
| `PublicadorDeEventos` | `KafkaPublicadorDeEventos`   | `InMemoryPublicadorDeEventos`  | `@Profile`        |
| `PublicadorDeEventos` | `SpringPublicadorDeEventos`  | (el mismo)                     | Monolito modular  |

---

## Ejercicio

Crea un `CompositeNotificadorPedidos` que reciba una `List<NotificadorPedidos>`
y notifique a todos (email + SMS futuro). Si uno falla, los demás deben
seguir ejecutándose y al final se lanza una única excepción si hubo fallos.

Pista: patrón Composite (GoF). Cuidado con que Spring no inyecte el composite
dentro de sí mismo.

<details><summary>Solución</summary>

```java
public class CompositeNotificadorPedidos implements NotificadorPedidos {

    private final List<NotificadorPedidos> notificadores;

    public CompositeNotificadorPedidos(List<NotificadorPedidos> notificadores) {
        this.notificadores = List.copyOf(notificadores);
    }

    @Override
    public void notificarConfirmacion(Pedido pedido, Email destinatario) {
        List<RuntimeException> fallos = new ArrayList<>();
        for (NotificadorPedidos n : notificadores) {
            try {
                n.notificarConfirmacion(pedido, destinatario);
            } catch (RuntimeException e) {
                fallos.add(e);
            }
        }
        if (!fallos.isEmpty()) {
            var ex = new NotificacionFallidaException(
                    fallos.size() + " notificaciones fallidas", fallos.get(0));
            fallos.stream().skip(1).forEach(ex::addSuppressed);
            throw ex;
        }
    }
}
```

Cableado: construye los adaptadores concretos **sin** registrarlos como beans
de tipo `NotificadorPedidos` (o con un tipo concreto) y expón solo el
composite con `@Bean @Primary`, para evitar ciclos y ambigüedades.

</details>

---

## Referencias

- Spring Boot docs — Mail, Kafka, Profiles:
  https://docs.spring.io/spring-boot/index.html
- Spring Modulith — eventos de aplicación:
  https://docs.spring.io/spring-modulith/reference/
- Chris Richardson, *Transactional Outbox*:
  https://microservices.io/patterns/data/transactional-outbox.html
- Mockito: https://site.mockito.org/ ; AssertJ: https://assertj.github.io/doc/
- Testcontainers: https://java.testcontainers.org/
- Gamma et al., *Design Patterns* (1994) — Composite, Adapter.

---

## Mantra

> *"Un puerto, muchos adaptadores. El entorno elige el adaptador; el código
> nunca pregunta en qué entorno está."*
