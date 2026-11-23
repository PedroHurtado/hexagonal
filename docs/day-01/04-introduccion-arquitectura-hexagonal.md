# Día 1 — Bloque 4: Introducción a la arquitectura hexagonal

> *"Allow an application to equally be driven by users, programs, automated
> test or batch scripts, and to be developed and tested in isolation from its
> eventual run-time devices and databases."*
> — Alistair Cockburn, *Hexagonal Architecture* (2005)

---

## 1. El problema en una frase

Necesitamos que la lógica de negocio **no sepa** quién la llama (REST, Kafka,
un test, un batch) ni a quién llama (PostgreSQL, Stripe, SMTP), para poder
cambiar cualquiera de esas piezas **sin tocar el núcleo**.

En cristiano: el negocio en el centro, la tecnología enchufada por fuera, y
**enchufes con forma definida por el negocio**.

---

## 2. La intención de Cockburn: "Ports and Adapters"

Alistair Cockburn llegó a esta idea observando un problema muy concreto de los
90: la lógica de negocio se **filtraba** a la interfaz de usuario por un lado y
a la base de datos por el otro. Las aplicaciones eran imposibles de probar de
forma automática porque solo se podían conducir **a través de la UI**.

Su propuesta, traducida:

> *"Permitir que una aplicación sea conducida igualmente por usuarios,
> programas, tests automatizados o scripts batch, y que se desarrolle y pruebe
> aislada de sus eventuales dispositivos y bases de datos en tiempo de
> ejecución."*

Y la mecánica:

> *"As events arrive from the outside world at a port, a technology-specific
> adapter converts it into a usable procedure call or message and passes it to
> the application. The application is blissfully ignorant of the nature of the
> input device."*

Es decir: cuando un evento llega desde fuera a un **puerto**, un **adaptador**
específico de una tecnología lo convierte en una llamada que la aplicación
entiende. La aplicación **ignora felizmente** qué dispositivo hay al otro lado.

El nombre oficial del patrón es **Ports and Adapters**. "Hexagonal" es el
apodo que ganó por el dibujo.

---

## 3. ¿Por qué un hexágono?

Spoiler: **el seis no significa nada**. Cockburn lo explica así:

- Quería romper con el dibujo de **capas apiladas** (arriba la UI, abajo la BD),
  que sugiere una asimetría falsa: "arriba" y "abajo".
- Necesitaba una figura con **suficientes lados** para dibujar varios puertos
  (UI, tests, BD, notificaciones, otras apps…) sin que pareciera un
  rectángulo de capas.
- Un hexágono es fácil de dibujar y deja espacio para los adaptadores
  alrededor.

Lo que importa no es el número de lados sino la **topología**: hay un
**dentro** y un **fuera**, no un arriba y un abajo.

---

## 4. Conceptos básicos

| Concepto              | Qué es                                                                   | Ejemplo en Pedidos                                   |
|-----------------------|--------------------------------------------------------------------------|------------------------------------------------------|
| **Aplicación (dentro)**| Dominio + casos de uso. Reglas de negocio puras.                        | `Pedido`, `ConfirmarPedidoService`                   |
| **Puerto**            | Interfaz que define **una conversación** con el exterior, en lenguaje de negocio | `ConfirmarPedidoUseCase`, `PedidoRepository`   |
| **Adaptador**         | Traductor entre una tecnología concreta y un puerto                      | `PedidoController`, `PedidoPersistenceAdapter`       |
| **Fuera**             | Actores y sistemas: usuarios, tests, BD, APIs, colas                     | Navegador, PostgreSQL, pasarela de pago              |

### 4.1 Dentro y fuera

- **Dentro**: lo que el negocio reconocería como suyo. Cambia cuando cambia el
  negocio.
- **Fuera**: todo lo que es tecnología o sistemas ajenos. Cambia cuando cambia
  la tecnología, el proveedor o el protocolo.

### 4.2 La regla de dependencia

> **Las dependencias de código fuente solo apuntan hacia dentro.**

- `domain` no importa nada de `application` ni de `infrastructure`.
- `application` importa `domain`, nunca `infrastructure`.
- `infrastructure` importa lo que necesite de `application` y `domain`.
- Ni `domain` ni `application` importan **Spring, JPA ni Jackson**.

### 4.3 Lado izquierdo (driving) y lado derecho (driven)

Cockburn distingue dos lados **asimétricos en intención**:

| Lado                         | Quién inicia la conversación | Puerto               | Adaptador                                  |
|------------------------------|------------------------------|----------------------|--------------------------------------------|
| **Izquierdo — driving**      | El actor externo llama a la app | **De entrada**: lo **implementa** la aplicación | Llama al puerto (`PedidoController`) |
| **Derecho — driven**         | La app llama al sistema externo | **De salida**: lo **define** la aplicación y lo implementa el adaptador | Implementa el puerto (`PedidoPersistenceAdapter`) |

Truco para no liarse: **en los dos lados, la flecha de dependencia del
adaptador apunta al puerto**. En la entrada el adaptador *usa* el puerto; en la
salida el adaptador *implementa* el puerto. Mañana (día 2) veremos los nombres
alternativos: primario/secundario.

---

## 5. El diagrama

```
     LADO IZQUIERDO (driving)                              LADO DERECHO (driven)
     "me usan"                                             "uso"

  ┌──────────────┐                                                ┌──────────────┐
  │   Navegador  │                                                │  PostgreSQL  │
  │  / app móvil │                                                └──────▲───────┘
  └──────┬───────┘                                                       │
         │ HTTP                                                          │ SQL
  ┌──────▼─────────────┐          ________________          ┌────────────┴───────────┐
  │ PedidoController   │         /                \         │ PedidoPersistence      │
  │ (adaptador web)    │──┐     /   APLICACIÓN     \     ┌──│ Adapter (JPA)          │
  └────────────────────┘  │    /                    \    │  └────────────────────────┘
                          │   /  puertos de entrada  \   │
  ┌────────────────────┐  └──►○ CrearPedidoUseCase    \  │  ┌────────────────────────┐
  │ PedidoKafkaListener│────►○ ConfirmarPedidoUseCase  │  ├──│ StripeLikePasarela     │
  │ (adaptador msg)    │     │                         │  │  │ Adapter (RestClient)   │──► API pagos
  └────────────────────┘     │   ┌─────────────────┐   │  │  └────────────────────────┘
                             │   │     DOMINIO     │   │  │
  ┌────────────────────┐     │   │ Pedido, Dinero, │   │  │  ┌────────────────────────┐
  │ Test JUnit         │────►○   │ EstadoPedido... │   ○◄─┼──│ EmailNotificador       │──► SMTP
  │ (adaptador test)   │      \  └─────────────────┘  ○◄─┘  │ Adapter                │
  └────────────────────┘       \  puertos de salida  ○◄──┐  └────────────────────────┘
                                \ PedidoRepository   /   │
                                 \ PasarelaDePago   /    │  ┌────────────────────────┐
                                  \ Notificador... /     └──│ InMemoryPedido         │
                                   \______________/         │ RepositoryAdapter(test)│
                                                            └────────────────────────┘

   ○  = puerto (interfaz)      ──►  = dependencia de código fuente (SIEMPRE hacia dentro)
```

Lectura:

- A la izquierda, **varios adaptadores** pueden conducir el **mismo** caso de
  uso: REST, Kafka y un test JUnit llaman a `ConfirmarPedidoUseCase` sin que el
  caso de uso lo sepa.
- A la derecha, **varios adaptadores** pueden implementar el **mismo** puerto:
  JPA en producción, en memoria en tests.
- El dominio está **en el centro del centro**: ni siquiera conoce los puertos.

---

## 6. Primer refactor: del `PedidoService` del bloque 3 a hexagonal mínimo

Partimos del `confirmarYCobrar` de
[03-el-problema-de-la-arquitectura-en-capas.md](03-el-problema-de-la-arquitectura-en-capas.md).
No vamos a hacerlo perfecto (eso es el resto del curso); vamos a **invertir
las flechas**.

### Paso 1: las reglas, al dominio

```java
package com.curso.pedidos.domain.model;

public class Pedido {

    // ... campos y factoría crear(...) (ver bloque 5)

    public void confirmar() {
        if (estado != EstadoPedido.BORRADOR) {
            throw new TransicionDeEstadoInvalidaException(id, estado, EstadoPedido.CONFIRMADO);
        }
        if (lineas.isEmpty()) {
            throw new TransicionDeEstadoInvalidaException(id, estado, EstadoPedido.CONFIRMADO);
        }
        this.estado = EstadoPedido.CONFIRMADO;
    }

    public void marcarComoPagado() {
        if (estado != EstadoPedido.CONFIRMADO) {
            throw new TransicionDeEstadoInvalidaException(id, estado, EstadoPedido.PAGADO);
        }
        this.estado = EstadoPedido.PAGADO;
    }

    public Dinero total() {
        return lineas.stream()
                .map(LineaPedido::subtotal)
                .reduce(Dinero.cero(), Dinero::sumar);
    }
}
```

Sin `import jakarta.*`, sin `import org.springframework.*`. Java puro.

### Paso 2: el puerto de entrada (lo que la app ofrece)

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;

public interface ConfirmarPedidoUseCase {

    void ejecutar(ConfirmarPedidoCommand command);

    record ConfirmarPedidoCommand(PedidoId pedidoId, String tokenDePago) { }
}
```

### Paso 3: los puertos de salida (lo que la app necesita), en lenguaje de negocio

```java
package com.curso.pedidos.application.port.out;

public interface PedidoRepository {
    void guardar(Pedido pedido);
    Optional<Pedido> buscarPorId(PedidoId id);
}
```

```java
package com.curso.pedidos.application.port.out;

public interface PasarelaDePago {
    ResultadoPago cobrar(PedidoId pedidoId, Dinero importe, String tokenDePago);

    sealed interface ResultadoPago permits Aprobado, Rechazado { }
    record Aprobado(String referencia) implements ResultadoPago { }
    record Rechazado(String motivo) implements ResultadoPago { }
}
```

Fíjate: `PasarelaDePago`, no `StripeClient`; `cobrar`, no `postCharge`;
`Dinero`, no `int amountInCents`.

### Paso 4: el caso de uso orquesta

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.out.PasarelaDePago;
import com.curso.pedidos.application.port.out.PasarelaDePago.Rechazado;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.exception.PagoRechazadoException;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.Pedido;

public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {

    private final PedidoRepository pedidos;
    private final PasarelaDePago pasarela;

    public ConfirmarPedidoService(PedidoRepository pedidos, PasarelaDePago pasarela) {
        this.pedidos = pedidos;
        this.pasarela = pasarela;
    }

    @Override
    public void ejecutar(ConfirmarPedidoCommand command) {
        Pedido pedido = pedidos.buscarPorId(command.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(command.pedidoId()));

        pedido.confirmar();                                   // regla de dominio

        PasarelaDePago.ResultadoPago resultado =
                pasarela.cobrar(pedido.id(), pedido.total(), command.tokenDePago());
        if (resultado instanceof Rechazado rechazado) {      // pattern matching (Java 16+)
            throw new PagoRechazadoException(pedido.id(), rechazado.motivo());
        }
        pedido.marcarComoPagado();

        pedidos.guardar(pedido);
    }
}
```

> Nota Java 21: con *pattern matching for switch* (estándar desde Java 21) y la
> interfaz `sealed`, el compilador comprueba que tratas **todos** los casos:
>
> ```java
> switch (resultado) {
>     case Aprobado a  -> pedido.marcarComoPagado();
>     case Rechazado r -> throw new PagoRechazadoException(pedido.id(), r.motivo());
> }
> ```
>
> En Java 17 es *preview*; es una buena razón para empezar proyectos nuevos en
> Java 21.

`PagoRechazadoException` sería una subclase más de `PedidoException`; la
añadimos al paquete `domain.exception` junto a las del curso.

### Paso 5: los adaptadores, fuera

```java
// infrastructure.adapter.in.web — llama al puerto de entrada
@RestController
@RequestMapping("/pedidos")
class PedidoController {
    private final ConfirmarPedidoUseCase confirmarPedido;
    PedidoController(ConfirmarPedidoUseCase confirmarPedido) { this.confirmarPedido = confirmarPedido; }

    @PostMapping("/{id}/confirmacion")
    ResponseEntity<Void> confirmar(@PathVariable UUID id, @RequestBody ConfirmacionRequest body) {
        confirmarPedido.ejecutar(new ConfirmarPedidoCommand(new PedidoId(id), body.token()));
        return ResponseEntity.noContent().build();
    }

    record ConfirmacionRequest(String token) { }
}

// infrastructure.adapter.out.payment — implementa el puerto de salida
class StripeLikePasarelaAdapter implements PasarelaDePago {
    private final RestClient restClient;
    StripeLikePasarelaAdapter(RestClient restClient) { this.restClient = restClient; }

    @Override
    public ResultadoPago cobrar(PedidoId pedidoId, Dinero importe, String token) {
        // aquí, y SOLO aquí, viven las URLs, los céntimos y el JSON del proveedor (día 4)
        throw new UnsupportedOperationException("Día 4");
    }
}
```

### Antes y después

```
ANTES (capas)                               DESPUÉS (hexagonal mínimo)

PedidoController                            PedidoController ──► «ConfirmarPedidoUseCase»
      │                                                                  ▲
      ▼                                                                  │ implements
PedidoService ──► JPA, Jackson,             ConfirmarPedidoService ──────┘
                  RestTemplate, Kafka                │
      │                                              ├──► «PedidoRepository» ◄── PedidoPersistenceAdapter
      ▼                                              ├──► «PasarelaDePago»   ◄── StripeLikePasarelaAdapter
PedidoEntity (anémica)                               ▼
                                            Pedido (rico, Java puro)
```

Ya hemos ganado:

- La regla "no se confirma un vacío" vive en `Pedido` y se prueba en
  milisegundos.
- Cambiar de pasarela = **nuevo adaptador**. El caso de uso no se entera.
- La llamada HTTP ya no está escondida en una transacción JPA (dónde poner
  `@Transactional` lo discutimos en el día 6).

---

## 7. Testing: el mismo caso de uso, sin Spring y sin mocks

```java
class ConfirmarPedidoServiceTest {

    // Adaptador de salida "de test": implementa el puerto igual que el real
    static class PasarelaFalsa implements PasarelaDePago {
        ResultadoPago respuesta = new Aprobado("REF-1");
        int cobros = 0;

        @Override
        public ResultadoPago cobrar(PedidoId id, Dinero importe, String token) {
            cobros++;
            return respuesta;
        }
    }

    private final InMemoryPedidoRepositoryAdapter repo = new InMemoryPedidoRepositoryAdapter();
    private final PasarelaFalsa pasarela = new PasarelaFalsa();
    private final ConfirmarPedidoService service = new ConfirmarPedidoService(repo, pasarela);

    @Test
    void confirma_y_marca_como_pagado_si_el_cobro_es_aceptado() {
        Pedido pedido = pedidoConUnaLinea();
        repo.guardar(pedido);

        service.ejecutar(new ConfirmarPedidoCommand(pedido.id(), "tok"));

        assertThat(repo.buscarPorId(pedido.id())).get()
                .extracting(Pedido::estado).isEqualTo(EstadoPedido.PAGADO);
    }

    @Test
    void no_cobra_un_pedido_vacio() {
        Pedido vacio = Pedido.crear(ClienteId.nuevo());
        repo.guardar(vacio);

        assertThatThrownBy(() -> service.ejecutar(new ConfirmarPedidoCommand(vacio.id(), "tok")))
                .isInstanceOf(TransicionDeEstadoInvalidaException.class);
        assertThat(pasarela.cobros).isZero();
    }

    @Test
    void lanza_excepcion_de_dominio_si_el_pago_es_rechazado() {
        Pedido pedido = pedidoConUnaLinea();
        repo.guardar(pedido);
        pasarela.respuesta = new Rechazado("fondos insuficientes");

        assertThatThrownBy(() -> service.ejecutar(new ConfirmarPedidoCommand(pedido.id(), "tok")))
                .isInstanceOf(PagoRechazadoException.class);
    }

    private static Pedido pedidoConUnaLinea() {
        Pedido p = Pedido.crear(ClienteId.nuevo());
        p.anadirLinea(new ProductoId("SKU-1"), new Cantidad(1), Dinero.euros("25.00"));
        return p;
    }
}
```

Compáralo con el bloque 3: **cero mocks de framework, cero contexto, excepciones
con nombre de negocio**. `InMemoryPedidoRepositoryAdapter` lo escribimos en el
bloque 5.

---

## 8. Beneficios y costes

| Beneficio                                         | Coste                                                       |
|---------------------------------------------------|-------------------------------------------------------------|
| Lógica de negocio testeable en milisegundos       | Más tipos: puertos, adaptadores, DTOs, mappers              |
| Cambiar BD/proveedor/protocolo sin tocar el núcleo| **Mapeo** dominio ↔ JPA ↔ DTO (código repetitivo)           |
| Varios adaptadores para el mismo caso de uso      | Curva de aprendizaje para el equipo                         |
| Perímetros de aislamiento frente a fallos externos| Riesgo de sobreingeniería en CRUDs triviales                |
| Estructura homogénea entre servicios del equipo   | Navegación: más saltos entre ficheros                        |
| Decisiones técnicas aplazables (empezar en memoria)| Se pierden "atajos" del framework (entidades JPA directas) |

---

## 9. Cuándo NO usarla

- **CRUD puro** sin reglas: un back-office que solo lee y escribe tablas. Spring
  Data REST o un controller + repositorio es más honesto.
- **Prototipos y pruebas de concepto** que se van a tirar.
- **Scripts, lambdas o jobs** de un solo uso con 200 líneas.
- **Equipos que no van a mantener la disciplina**: un hexágono a medias (con
  `@Entity` en el dominio "porque es más rápido") suma costes sin beneficios.
- Cuando el valor está en la **integración** y no en el dominio (un *proxy* o un
  *gateway* que solo transforma mensajes).

Regla práctica: **cuantas más reglas de negocio y más sistemas externos, más
compensa**. Un servicio de Pedidos con pagos, stock y notificaciones está en el
lado claro de la balanza.

Trampa: "**hexágono por carpetas**". Crear `port/in`, `port/out`, `adapter`…
y que `domain` importe `jakarta.persistence`. La estructura de paquetes no es la
arquitectura; **la dirección de los `import` sí**. En el día 5 lo blindaremos
con ArchUnit.

---

## 10. Resumen

| Idea                     | En una frase                                                        |
|--------------------------|---------------------------------------------------------------------|
| Intención                | Conducir la app desde cualquier actor y probarla aislada            |
| Dentro / fuera           | Negocio dentro; tecnología fuera                                    |
| Puerto                   | Interfaz en lenguaje de negocio que define una conversación         |
| Adaptador                | Traductor tecnología ↔ puerto                                       |
| Regla de dependencia     | Los `import` apuntan siempre hacia dentro                           |
| Driving (izquierda)      | Me llaman; el adaptador **usa** el puerto de entrada                |
| Driven (derecha)         | Llamo; el adaptador **implementa** el puerto de salida              |
| Por qué hexágono         | Para romper la metáfora de capas; el número de lados es irrelevante |

---

## Ejercicio

Clasifica cada pieza como **dominio**, **puerto de entrada**, **puerto de
salida**, **adaptador de entrada** o **adaptador de salida**:

1. `PedidoKafkaListener`
2. `NotificadorPedidos`
3. `EstadoPedido`
4. `CancelarPedidoUseCase`
5. `EmailNotificadorAdapter`
6. `PedidoJpaRepository` (interfaz de Spring Data)
7. Un job `@Scheduled` que cancela borradores antiguos
8. `Dinero`

<details><summary>Solución</summary>

1. Adaptador de entrada (mensajería).
2. Puerto de salida.
3. Dominio.
4. Puerto de entrada.
5. Adaptador de salida (implementa `NotificadorPedidos`).
6. **Detalle interno** del adaptador de salida de persistencia. No es un
   puerto: lo usa `PedidoPersistenceAdapter` por dentro. Error clásico:
   llamarlo puerto porque "es una interfaz".
7. Adaptador de entrada (scheduler): conduce a `CancelarPedidoUseCase`.
8. Dominio (value object).

</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture* (2005): https://alistair.cockburn.us/hexagonal-architecture/
- Cockburn & Garrido de Paz, *Hexagonal Architecture Explained* (2024).
- Netflix Tech Blog, *Ready for changes with Hexagonal Architecture* (2020): https://netflixtechblog.com/ready-for-changes-with-hexagonal-architecture-b315ec967749
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023) y https://github.com/thombergs/buckpal
- Robert C. Martin, *The Clean Architecture* (2012): https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- ArchUnit: https://www.archunit.org/userguide/html/000_Index.html

---

## Mantra

> *"La aplicación no sabe quién la llama ni a quién llama: solo conoce sus puertos."*
