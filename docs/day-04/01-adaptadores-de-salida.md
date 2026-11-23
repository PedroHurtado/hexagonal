# Día 4 — Bloque 1: Adaptadores de salida

> *"The application communicates over these ports with the outside world…
> For each external device there is an adapter that converts the API
> definition to the signals needed by that device and vice versa."*
> — Alistair Cockburn, *Hexagonal Architecture* (2005)

---

## 1. El problema en una frase

Nuestros casos de uso **necesitan** cosas del mundo exterior (guardar un
pedido, cobrarlo, avisar al cliente), pero si los escribimos **llamando
directamente** a JPA, a un SDK de pagos o a `JavaMailSender`, el corazón de la
aplicación queda **clavado** a esas tecnologías.

---

## 2. Definición

Un **adaptador de salida** (*driven adapter*, adaptador secundario) es una
clase de infraestructura que:

1. **Implementa** un puerto de salida definido **dentro** del hexágono
   (`application.port.out`).
2. **Traduce** entre el modelo del dominio y el modelo de la tecnología
   (filas, JSON, mensajes, correos).
3. **Encapsula** todos los detalles técnicos: conexiones, timeouts,
   serialización, códigos de error, reintentos.

En cristiano: el caso de uso dice *"guárdame este pedido"* en su idioma; el
adaptador se encarga de convertirlo en `INSERT INTO pedidos …` y de que el
caso de uso no se entere.

| Pieza                 | Dónde vive                                       | Quién la define        |
|-----------------------|--------------------------------------------------|------------------------|
| Puerto de salida      | `application.port.out`                           | El hexágono (negocio)  |
| Adaptador de salida   | `infrastructure.adapter.out.<tecnología>`        | La infraestructura     |
| Modelo técnico        | Dentro del paquete del adaptador                 | La infraestructura     |
| Cableado              | `infrastructure.config`                          | La *composition root*  |

---

## 3. Bad: el caso de uso habla con la infraestructura

```java
package com.curso.pedidos.application.service;

import jakarta.persistence.EntityManager;            // ❌ JPA en aplicación
import org.springframework.mail.SimpleMailMessage;  // ❌ Spring Mail
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;   // ❌ HTTP

public class ConfirmarPedidoService {

    private final EntityManager em;
    private final RestClient stripe;
    private final JavaMailSender mail;

    // constructor omitido

    public void ejecutar(ConfirmarPedidoCommand cmd) {
        PedidoJpaEntity entity = em.find(PedidoJpaEntity.class, cmd.pedidoId());
        if (entity == null) throw new PedidoNoEncontradoException(...);
        Pedido pedido = /* mapear a mano aquí mismo */;
        pedido.confirmar();

        var resp = stripe.post().uri("/v1/charges")
                .body(Map.of("amount", pedido.total().importe()))
                .retrieve().toEntity(Map.class);       // ❌ JSON de Stripe aquí
        if (resp.getStatusCode().value() == 402) { /* ... */ }

        em.merge(/* volver a mapear */);
        var msg = new SimpleMailMessage();
        msg.setTo(cmd.email());
        msg.setText("Tu pedido está confirmado");
        mail.send(msg);
    }
}
```

### ¿Qué duele?

- **Test unitario imposible**: para probar la regla "no se confirma un pedido
  vacío" necesito `EntityManager`, un servidor HTTP y un SMTP.
- **Cambiar de proveedor de pagos** (Stripe → Redsys) obliga a tocar el caso
  de uso, que es *negocio*.
- **Los códigos HTTP (402, 503) se filtran** a la lógica de aplicación.
- **El modelo de Stripe** (sus nombres de campo, sus céntimos) contamina
  nuestro lenguaje ubicuo.
- La clase tiene **cuatro razones para cambiar** (SRP roto).

---

## 4. Good: puerto definido dentro, adaptador fuera

El caso de uso solo conoce **puertos** con lenguaje de negocio:

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.out.*;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.Pedido;

public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final NotificadorPedidos notificador;
    private final PublicadorDeEventos publicador;

    public ConfirmarPedidoService(PedidoRepository pedidoRepository,
                                  NotificadorPedidos notificador,
                                  PublicadorDeEventos publicador) {
        this.pedidoRepository = pedidoRepository;
        this.notificador = notificador;
        this.publicador = publicador;
    }

    @Override
    public void ejecutar(ConfirmarPedidoCommand cmd) {
        Pedido pedido = pedidoRepository.buscarPorId(cmd.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(cmd.pedidoId()));
        var evento = pedido.confirmar();          // devuelve PedidoConfirmado
        pedidoRepository.guardar(pedido);
        publicador.publicar(evento);
        notificador.notificarConfirmacion(pedido, cmd.emailCliente());
    }
}
```

Y cada tecnología tiene su adaptador, que **implementa** el puerto:

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

public class PedidoPersistenceAdapter implements PedidoRepository { /* JPA */ }

package com.curso.pedidos.infrastructure.adapter.out.notification;

public class EmailNotificadorAdapter implements NotificadorPedidos { /* SMTP */ }

package com.curso.pedidos.infrastructure.adapter.out.messaging;

public class KafkaPublicadorDeEventos implements PublicadorDeEventos { /* Kafka */ }
```

Ningún `import` de `jakarta.persistence`, `org.springframework.*` ni
`com.fasterxml.jackson` aparece en `application` ni en `domain`.

---

## 5. Diagrama: la inversión de dependencias

```
                        ┌─────────────────────────────────────────────┐
                        │                 HEXÁGONO                    │
   ┌────────────┐       │  ┌──────────────────┐   ┌────────────────┐  │
   │ Controller │──────►│  │ ConfirmarPedido  │──►│ «port out»     │  │
   │ (in.web)   │       │  │ Service          │   │ PedidoRepo     │◄─┼──┐
   └────────────┘       │  └──────────────────┘   │ PasarelaDePago │◄─┼──┼──┐
                        │           │             │ Notificador... │◄─┼──┼──┼──┐
                        │           ▼             └────────────────┘  │  │  │  │
                        │      ┌─────────┐                            │  │  │  │
                        │      │ Pedido  │  (dominio puro)            │  │  │  │
                        │      └─────────┘                            │  │  │  │
                        └─────────────────────────────────────────────┘  │  │  │
                                                                         │  │  │
                     implements (la flecha apunta HACIA DENTRO)          │  │  │
   ┌──────────────────────────┐                                          │  │  │
   │ PedidoPersistenceAdapter │──────────────────────────────────────────┘  │  │
   │   └─► PedidoJpaRepository│──► PostgreSQL                               │  │
   └──────────────────────────┘                                             │  │
   ┌──────────────────────────┐                                             │  │
   │ StripeLikePasarelaAdapter│─────────────────────────────────────────────┘  │
   │   └─► RestClient         │──► API de pagos                                │
   └──────────────────────────┘                                                │
   ┌──────────────────────────┐                                                │
   │ EmailNotificadorAdapter  │────────────────────────────────────────────────┘
   │   └─► JavaMailSender     │──► SMTP
   └──────────────────────────┘
```

Lo importante: **el flujo de control** va del servicio hacia la base de datos,
pero **la dependencia de código** va del adaptador hacia el puerto. Eso es el
**Principio de Inversión de Dependencias** (día 1) aplicado a la arquitectura.

---

## 6. Anatomía de un adaptador de salida

Casi todos los adaptadores de salida tienen las mismas cuatro capas internas:

```
   ┌────────────────────────────────────────────────────────┐
   │ XxxAdapter implements PuertoDeSalida                   │
   │                                                        │
   │  1. Recibe objetos de dominio (Pedido, Dinero...)      │
   │  2. Mapper: dominio ──► modelo técnico                 │
   │  3. Cliente técnico (JpaRepository, RestClient, ...)   │
   │  4. Mapper: modelo técnico ──► dominio                 │
   │     + traducción de errores técnicos ──► excepciones   │
   │       del puerto / del dominio                         │
   └────────────────────────────────────────────────────────┘
```

| Responsabilidad       | Ejemplo en persistencia                    | Ejemplo en HTTP                           |
|-----------------------|--------------------------------------------|-------------------------------------------|
| Modelo técnico        | `PedidoJpaEntity`                          | `CobroRequest` / `CobroResponse` (records)|
| Mapper                | `PedidoPersistenceMapper`                  | `PagoHttpMapper`                          |
| Cliente técnico       | `PedidoJpaRepository` (Spring Data)        | `RestClient`                              |
| Traducción de errores | `DataIntegrityViolationException` → ...    | HTTP 402 → `ResultadoPago.Rechazado`      |

---

## 7. Anticorruption Layer (ACL)

Eric Evans, en *Domain-Driven Design* (2003), describe la **Anticorruption
Layer**: una capa que aísla nuestro modelo del modelo de un sistema externo,
traduciendo en ambos sentidos para que los conceptos ajenos no "corrompan"
nuestro lenguaje ubicuo.

> En cristiano: **un adaptador de salida hacia un sistema externo es una ACL**.

Ejemplo: la pasarela de pago trabaja en **céntimos** (`amount: 1999`), con
códigos ISO en minúscula (`"eur"`) y estados como `"succeeded"`,
`"requires_action"`, `"card_declined"`. Nuestro dominio habla de
`Dinero(19.99, EUR)` y de `ResultadoPago.Aprobado` / `Rechazado`.

```
 Nuestro dominio                 ACL (adaptador)                  Sistema externo
 ┌───────────────┐   ┌──────────────────────────────────┐   ┌──────────────────┐
 │ Dinero(19.99, │──►│ céntimos = importe × 100         │──►│ amount: 1999     │
 │        EUR)   │   │ moneda.getCurrencyCode().lower() │   │ currency: "eur"  │
 │               │   │                                  │   │                  │
 │ Aprobado(ref) │◄──│ "succeeded" ──► Aprobado         │◄──│ status: ...      │
 │ Rechazado(m)  │◄──│ "card_declined" ──► Rechazado    │   │ code: ...        │
 └───────────────┘   └──────────────────────────────────┘   └──────────────────┘
```

Trampa: **reutilizar los DTOs del proveedor** (los del SDK de Stripe, por
ejemplo) como parámetros del puerto. En cuanto lo haces, el puerto ya no es
tuyo: es del proveedor.

---

## 8. Trampas y errores comunes

- **Trampa: puertos con nombre de tecnología.** `PedidoJpaPort`,
  `KafkaPort`, `StripeGateway`. El puerto se nombra por **lo que el negocio
  necesita**: `PedidoRepository`, `PublicadorDeEventos`, `PasarelaDePago`.
- **Trampa: el puerto expone tipos técnicos.** `Page<PedidoJpaEntity>`,
  `ResponseEntity<?>`, `Optional<Map<String,Object>>`. Si en la firma aparece
  algo de Spring, el puerto está mal.
- **Trampa: un puerto por tabla.** Los puertos se diseñan por **agregado** y
  por **necesidad del caso de uso**, no por tabla. `LineaPedido` no tiene
  repositorio propio: se persiste con su `Pedido`.
- **Trampa: lógica de negocio en el adaptador.** "Si el total supera 1000 €,
  cobra en dos plazos" no va en `StripeLikePasarelaAdapter`. El adaptador
  traduce; no decide.
- **Trampa: adaptadores que se llaman entre sí.** El adaptador de pagos no
  debería usar el repositorio JPA. Si necesita datos, que se los pase el caso
  de uso. Lo verificaremos con ArchUnit en el
  [bloque 6](06-verificar-la-arquitectura-con-archunit.md).
- **Trampa: excepciones técnicas que escapan.** Un
  `HttpServerErrorException` que llega al controlador es una fuga de
  abstracción. El adaptador traduce a una excepción del puerto.

---

## 9. Testing: cada pieza con su test

| Qué pruebo                 | Cómo                                                     | Infra real |
|----------------------------|----------------------------------------------------------|------------|
| Caso de uso                | JUnit + fake/mock del puerto de salida                   | No         |
| Adaptador de persistencia  | `@DataJpaTest` con H2 o Testcontainers                   | Sí (BBDD)  |
| Adaptador HTTP             | WireMock simulando el API externo                        | Simulada   |
| Adaptador de correo        | Mock de `JavaMailSender` o GreenMail                     | Simulada   |
| Cableado completo          | `@SpringBootTest` (pocos)                                | Opcional   |

El caso de uso, gracias al puerto, se prueba **sin Spring** con un fake:

```java
package com.curso.pedidos.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfirmarPedidoServiceTest {

    private final InMemoryPedidoRepository repo = new InMemoryPedidoRepository();
    private final NotificadorPedidos notificador = mock(NotificadorPedidos.class);
    private final PublicadorDeEventos publicador = mock(PublicadorDeEventos.class);
    private final ConfirmarPedidoService service =
            new ConfirmarPedidoService(repo, notificador, publicador);

    @Test
    void confirma_el_pedido_y_publica_el_evento() {
        Pedido pedido = PedidoMother.borradorConUnaLinea();
        repo.guardar(pedido);
        var email = new Email("ana@example.com");

        service.ejecutar(new ConfirmarPedidoCommand(pedido.id(), email));

        assertThat(repo.buscarPorId(pedido.id()))
                .get().extracting(Pedido::estado)
                .isEqualTo(EstadoPedido.CONFIRMADO);
        verify(publicador).publicar(any(PedidoConfirmado.class));
        verify(notificador).notificarConfirmacion(pedido, email);
    }
}
```

Y el **adaptador** se prueba **solo**, contra su tecnología. Eso lo hacemos en
los bloques 2, 3 y 4.

---

## 10. Resumen

| Concepto                    | Idea clave                                                   |
|-----------------------------|--------------------------------------------------------------|
| Puerto de salida            | Lo define el hexágono, en lenguaje de negocio                |
| Adaptador de salida         | Implementa el puerto, encapsula la tecnología                |
| Inversión de dependencias   | Código: adaptador → puerto. Control: servicio → adaptador    |
| Anticorruption Layer        | El adaptador traduce y protege el lenguaje ubicuo            |
| Testing                     | Caso de uso con fakes; adaptador contra su tecnología        |

---

## Ejercicio

Revisa esta firma de puerto y reescríbela para que sea un puerto de salida
"limpio":

```java
public interface StripePort {
    ResponseEntity<Map<String, Object>> charge(long amountInCents, String currency);
}
```

Pista: nombre de negocio, tipos de dominio, sin Spring, y un resultado que el
caso de uso pueda interpretar sin conocer HTTP.

<details><summary>Solución</summary>

```java
package com.curso.pedidos.application.port.out;

public interface PasarelaDePago {
    ResultadoPago cobrar(PedidoId pedidoId, Dinero importe);
}

public sealed interface ResultadoPago {
    record Aprobado(String referencia) implements ResultadoPago {}
    record Rechazado(String motivo) implements ResultadoPago {}
}
```

- Nombre de negocio (`PasarelaDePago`), no del proveedor.
- `Dinero` en vez de `long` + `String`: los céntimos son cosa del adaptador.
- `ResultadoPago` sellado: el caso de uso hace `switch` exhaustivo sin saber
  nada de códigos HTTP.
- Los fallos **técnicos** (timeout, 503) no son un "resultado": se modelan
  como excepción (`PasarelaDePagoNoDisponibleException`), lo vemos en el
  [bloque 3](03-adaptador-cliente-http.md).

</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture* (2005):
  https://alistair.cockburn.us/hexagonal-architecture/
- Cockburn & Garrido de Paz, *Hexagonal Architecture Explained* (2024).
- Eric Evans, *Domain-Driven Design* (2003) — capítulo 14, *Anticorruption Layer*.
- Robert C. Martin, *The Clean Architecture* (2012):
  https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023);
  repo https://github.com/thombergs/buckpal
- Netflix Tech Blog, *Ready for changes with Hexagonal Architecture* (2020):
  https://netflixtechblog.com/ready-for-changes-with-hexagonal-architecture-b315ec967749

---

## Mantra

> *"El puerto habla el idioma del negocio; el adaptador es el traductor
> jurado."*
