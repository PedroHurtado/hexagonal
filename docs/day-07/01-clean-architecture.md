# Día 7 — Bloque 1: Clean Architecture

> *"The overriding rule that makes this architecture work is The Dependency
> Rule. This rule says that source code dependencies can only point inwards."*
> — Robert C. Martin, *The Clean Architecture* (2012)

---

## 1. El problema en una frase

Entre 2005 y 2012 aparecen varias arquitecturas (Hexagonal de Cockburn, Onion
de Palermo, DCI, BCE de Jacobson…) que dicen **casi lo mismo con palabras
distintas**. En 2012 Robert C. Martin (Uncle Bob) publica un post que intenta
**unificarlas en un solo diagrama** con una sola regla. En 2017 lo amplía en el
libro *Clean Architecture*.

En cristiano: Clean Architecture no inventa la rueda; **la dibuja en círculos y
le pone nombre a cada radio**.

---

## 2. Qué dice Uncle Bob (fuente original)

En el post de 2012 Martin enumera las arquitecturas que quiere unificar
(Hexagonal, Onion, Screaming Architecture, DCI, BCE) y dice que todas producen
sistemas que son:

1. **Independientes de frameworks.** El framework es una herramienta, no una
   jaula.
2. **Testeables.** Las reglas de negocio se prueban sin UI, sin BD, sin
   servidor web.
3. **Independientes de la UI.** Cambiar web por consola no toca el negocio.
4. **Independientes de la base de datos.** Oracle, PostgreSQL, Mongo… da igual.
5. **Independientes de cualquier agente externo.** El negocio no sabe nada del
   mundo exterior.

Y todo descansa en **una regla**:

> *"Nothing in an inner circle can know anything at all about something in an
> outer circle."*

Ni clases, ni funciones, ni variables, ni **nombres**. Si en el círculo de
casos de uso aparece la palabra `ResponseEntity`, `@Entity` o `HttpServletRequest`,
has roto la regla.

---

## 3. Los cuatro círculos

```
 ┌───────────────────────────────────────────────────────────────────┐
 │  FRAMEWORKS & DRIVERS   (Spring MVC, JPA/Hibernate, PostgreSQL,  │
 │                          Kafka, UI web, dispositivos)             │
 │   ┌───────────────────────────────────────────────────────────┐   │
 │   │  INTERFACE ADAPTERS  (Controllers, Presenters, ViewModels,│   │
 │   │                       Gateways/Repositories de BD)        │   │
 │   │   ┌───────────────────────────────────────────────────┐   │   │
 │   │   │  USE CASES  (Interactors, Input/Output Boundaries,│   │   │
 │   │   │              Request/Response Models)             │   │   │
 │   │   │   ┌───────────────────────────────────────────┐   │   │   │
 │   │   │   │  ENTITIES  (Pedido, LineaPedido, Dinero,  │   │   │   │
 │   │   │   │             reglas de negocio de empresa) │   │   │   │
 │   │   │   └───────────────────────────────────────────┘   │   │   │
 │   │   └───────────────────────────────────────────────────┘   │   │
 │   └───────────────────────────────────────────────────────────┘   │
 └───────────────────────────────────────────────────────────────────┘

        Dependencias de código fuente:  ───►  SIEMPRE hacia dentro
```

| Círculo               | Qué contiene                                                    | En nuestro dominio |
|-----------------------|-----------------------------------------------------------------|--------------------|
| **Entities**          | Reglas de negocio de **empresa** (críticas, las más estables)   | `Pedido`, `LineaPedido`, `Dinero`, `EstadoPedido` |
| **Use Cases**         | Reglas de negocio de **aplicación**: orquestan entidades         | `ConfirmarPedidoInteractor` y sus boundaries |
| **Interface Adapters**| Convierten datos entre el formato de los casos de uso y el exterior | `ConfirmarPedidoController`, `ConfirmarPedidoPresenter`, `PedidoGatewayJpa` |
| **Frameworks & Drivers** | Detalles: framework web, BD, colas. "Glue code" mínimo        | Spring Boot, Hibernate, PostgreSQL, configuración |

### ¿Solo cuatro círculos?

Esto se suele olvidar y es importante para el bloque 2. El propio post tiene
una sección titulada *"Only Four Circles?"* donde Martin dice que **no**: los
círculos son esquemáticos y puedes necesitar más. Lo que no es negociable es la
regla de dependencia.

> Trampa: "Clean Architecture obliga a tener exactamente cuatro paquetes". No.
> Obliga a que las dependencias apunten hacia dentro. Los cuatro círculos son
> el caso típico, no una ley.

---

## 4. El vocabulario propio de Clean: el flujo de control

Lo que de verdad distingue a Clean de Hexagonal es **lo explícito** que es el
lado de entrada/salida de un caso de uso. Uncle Bob lo dibuja en la esquina
inferior derecha de su diagrama:

```
                      Interface Adapters │ Use Cases
                                         │
  HTTP ──► ┌────────────┐  Request Model │  ┌──────────────────┐
           │ Controller │───────────────────►│ «interface»      │
           └────────────┘                │  │ Input Boundary   │
                                         │  └────────▲─────────┘
                                         │           │ implements
                                         │  ┌────────┴─────────┐     ┌──────────┐
                                         │  │   Interactor     │────►│ Entities │
                                         │  └────────┬─────────┘     └──────────┘
                                         │           │ uses
                                         │  ┌────────▼─────────┐
           ┌────────────┐ Response Model │  │ «interface»      │
  HTTP ◄── │ Presenter  │◄───────────────────│ Output Boundary  │
           └─────┬──────┘   implements   │  └──────────────────┘
                 │                       │
                 ▼                       │
           ┌────────────┐                │
           │ View Model │                │
           └────────────┘                │
```

| Concepto            | Qué es                                                                     |
|---------------------|----------------------------------------------------------------------------|
| **Request Model**   | Estructura de datos simple con la entrada del caso de uso. Sin anotaciones de framework. |
| **Input Boundary**  | Interfaz que expone el caso de uso. La llama el controller.                 |
| **Interactor**      | Implementación del caso de uso. Orquesta entidades y gateways.             |
| **Output Boundary** | Interfaz que el **caso de uso define** y el **presenter implementa**. Por aquí sale el resultado. |
| **Response Model**  | Estructura de datos simple con la salida del caso de uso.                   |
| **Presenter**       | Convierte el Response Model en un **View Model** listo para pintar.          |
| **View Model**      | Datos ya formateados para la vista (strings, códigos HTTP). La vista es "tonta" (*Humble Object*). |
| **Gateway**         | Interfaz de acceso a datos definida en el círculo de casos de uso; la implementa un adaptador. |

La clave: **el flujo de control va de fuera a dentro y vuelve a salir**
(Controller → Interactor → Presenter), pero **las dependencias de código siempre
apuntan hacia dentro**. ¿Cómo sale el resultado sin que el Interactor conozca al
Presenter? Con **inversión de dependencias**: el Interactor llama a una
interfaz (Output Boundary) que él mismo define.

> ¿Te suena? Es exactamente lo que hacíamos con los puertos de salida el día 2.
> El Output Boundary es un **puerto de salida que apunta hacia la UI**.

---

## 5. Bad: el caso de uso que sabe de HTTP

```java
// usecases/ConfirmarPedidoService.java  — ¡en el círculo de casos de uso!
@Service
public class ConfirmarPedidoService {

    private final PedidoJpaRepository repo;           // detalle de BD en el centro

    public ResponseEntity<?> confirmar(UUID id) {       // detalle web en el centro
        PedidoJpaEntity e = repo.findById(id).orElse(null);
        if (e == null) {
            return ResponseEntity.notFound().build();
        }
        if (e.getLineas().isEmpty()) {
            return ResponseEntity.unprocessableEntity().body("Pedido vacío");
        }
        e.setEstado("CONFIRMADO");
        repo.save(e);
        return ResponseEntity.ok(Map.of("estado", "CONFIRMADO"));
    }
}
```

### ¿Qué duele?

- El caso de uso **nombra** `ResponseEntity` y `PedidoJpaEntity`: dos nombres de
  círculos externos. Regla de dependencia rota dos veces.
- Las reglas de negocio (no confirmar vacío) viven en el servicio, no en la
  entidad: modelo anémico.
- Para testearlo necesitas Spring MVC en el classpath y un repositorio JPA (o un
  mock de una interfaz de Spring Data con 30 métodos).
- Si mañana el mismo caso de uso se dispara desde un listener de Kafka, ¿qué
  hace con un `ResponseEntity`?

---

## 6. Good: `ConfirmarPedido` en Clean Architecture con Spring Boot

### 6.1 Árbol de ficheros

```
com.curso.pedidos
├── entities                                  ← círculo 1
│   ├── Pedido.java  LineaPedido.java  EstadoPedido.java
│   ├── PedidoId.java  ClienteId.java  ProductoId.java  Cantidad.java  Dinero.java
│   ├── PedidoConfirmado.java
│   └── PedidoException.java  TransicionDeEstadoInvalidaException.java
├── usecases                                  ← círculo 2
│   ├── confirmarpedido
│   │   ├── ConfirmarPedidoInputBoundary.java     (nuevo)
│   │   ├── ConfirmarPedidoRequest.java           (nuevo)
│   │   ├── ConfirmarPedidoResponse.java          (nuevo)
│   │   ├── ConfirmarPedidoOutputBoundary.java    (nuevo)
│   │   └── ConfirmarPedidoInteractor.java        (nuevo)
│   └── gateways
│       ├── PedidoGateway.java                    (compartido)
│       └── EventosGateway.java                   (compartido)
├── adapters                                  ← círculo 3
│   ├── web/confirmarpedido
│   │   ├── ConfirmarPedidoController.java        (nuevo)
│   │   ├── ConfirmarPedidoPresenter.java         (nuevo)
│   │   └── ConfirmarPedidoViewModel.java         (nuevo)
│   └── persistence
│       ├── PedidoGatewayJpa.java                 (compartido)
│       └── PedidoJpaEntity.java ...              (compartido)
└── frameworks                                ← círculo 4
    └── config
        └── CasosDeUsoConfig.java                 (modificado: +1 @Bean)
```

**Recuento para esta feature: 8 ficheros nuevos + 1 modificado.** Lo usaremos en
la comparativa del bloque 4.

### 6.2 Entities (el mismo dominio de todo el curso)

Solo el método que nos interesa; el resto del agregado es el de días anteriores.

```java
package com.curso.pedidos.entities;

public class Pedido {
    // ... campos: id, clienteId, lineas, estado, creadoEn

    public void confirmar() {
        if (estado != EstadoPedido.BORRADOR) {
            throw new TransicionDeEstadoInvalidaException(estado, EstadoPedido.CONFIRMADO);
        }
        if (lineas.isEmpty()) {
            throw new PedidoException("No se puede confirmar un pedido vacío");
        }
        this.estado = EstadoPedido.CONFIRMADO;
    }
}
```

### 6.3 Use Cases: boundaries, models e interactor

```java
package com.curso.pedidos.usecases.confirmarpedido;

import java.util.Objects;
import java.util.UUID;

/** Request Model: datos planos, sin anotaciones de framework. */
public record ConfirmarPedidoRequest(UUID pedidoId) {
    public ConfirmarPedidoRequest {
        Objects.requireNonNull(pedidoId, "pedidoId es obligatorio");
    }
}
```

```java
package com.curso.pedidos.usecases.confirmarpedido;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Response Model: lo que el caso de uso cuenta al mundo. Tipos simples. */
public record ConfirmarPedidoResponse(
        UUID pedidoId,
        String estado,
        BigDecimal total,
        String moneda,
        Instant confirmadoEn) {
}
```

```java
package com.curso.pedidos.usecases.confirmarpedido;

/** Input Boundary: lo que el controller ve del caso de uso. */
public interface ConfirmarPedidoInputBoundary {

    void confirmar(ConfirmarPedidoRequest request, ConfirmarPedidoOutputBoundary presenter);
}
```

```java
package com.curso.pedidos.usecases.confirmarpedido;

import java.util.UUID;

/**
 * Output Boundary: la define el círculo de casos de uso y la implementa el presenter.
 * Un método por cada desenlace de negocio: nada de excepciones cruzando la frontera.
 */
public interface ConfirmarPedidoOutputBoundary {

    void presentarConfirmado(ConfirmarPedidoResponse response);

    void presentarNoEncontrado(UUID pedidoId);

    void presentarRechazado(UUID pedidoId, String motivo);
}
```

```java
package com.curso.pedidos.usecases.gateways;

import com.curso.pedidos.entities.Pedido;
import com.curso.pedidos.entities.PedidoId;
import java.util.Optional;

public interface PedidoGateway {

    Optional<Pedido> buscarPorId(PedidoId id);

    void guardar(Pedido pedido);
}
```

```java
package com.curso.pedidos.usecases.gateways;

public interface EventosGateway {

    void publicar(Object eventoDeDominio);
}
```

```java
package com.curso.pedidos.usecases.confirmarpedido;

import com.curso.pedidos.entities.Dinero;
import com.curso.pedidos.entities.Pedido;
import com.curso.pedidos.entities.PedidoConfirmado;
import com.curso.pedidos.entities.PedidoException;
import com.curso.pedidos.entities.PedidoId;
import com.curso.pedidos.usecases.gateways.EventosGateway;
import com.curso.pedidos.usecases.gateways.PedidoGateway;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/** Interactor: Java puro. Ni una importación de Spring. */
public final class ConfirmarPedidoInteractor implements ConfirmarPedidoInputBoundary {

    private final PedidoGateway pedidos;
    private final EventosGateway eventos;
    private final Clock clock;

    public ConfirmarPedidoInteractor(PedidoGateway pedidos, EventosGateway eventos, Clock clock) {
        this.pedidos = pedidos;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Override
    public void confirmar(ConfirmarPedidoRequest request, ConfirmarPedidoOutputBoundary presenter) {
        Optional<Pedido> encontrado = pedidos.buscarPorId(new PedidoId(request.pedidoId()));
        if (encontrado.isEmpty()) {
            presenter.presentarNoEncontrado(request.pedidoId());
            return;
        }

        Pedido pedido = encontrado.get();
        try {
            pedido.confirmar();                       // la regla vive en la entidad
        } catch (PedidoException e) {                 // incluye TransicionDeEstadoInvalida
            presenter.presentarRechazado(request.pedidoId(), e.getMessage());
            return;
        }

        pedidos.guardar(pedido);
        Instant ahora = clock.instant();
        eventos.publicar(new PedidoConfirmado(pedido.id(), ahora));

        Dinero total = pedido.total();
        presenter.presentarConfirmado(new ConfirmarPedidoResponse(
                request.pedidoId(),
                pedido.estado().name(),
                total.importe(),
                total.moneda().getCurrencyCode(),
                ahora));
    }
}
```

### 6.4 Interface Adapters: Controller, Presenter, View Model

```java
package com.curso.pedidos.adapters.web.confirmarpedido;

/**
 * View Model: lo que la "vista" (aquí, la serialización HTTP) necesita, ya decidido.
 * La vista no toma decisiones: es un Humble Object.
 */
public record ConfirmarPedidoViewModel(int httpStatus, Object cuerpo) {

    public record PedidoConfirmadoJson(String pedidoId, String estado,
                                       String total, String confirmadoEn) {
    }
}
```

```java
package com.curso.pedidos.adapters.web.confirmarpedido;

import com.curso.pedidos.usecases.confirmarpedido.ConfirmarPedidoOutputBoundary;
import com.curso.pedidos.usecases.confirmarpedido.ConfirmarPedidoResponse;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Presenter: traduce desenlaces de negocio a decisiones de presentación (HTTP). */
public final class ConfirmarPedidoPresenter implements ConfirmarPedidoOutputBoundary {

    private ConfirmarPedidoViewModel viewModel;

    @Override
    public void presentarConfirmado(ConfirmarPedidoResponse r) {
        var json = new ConfirmarPedidoViewModel.PedidoConfirmadoJson(
                r.pedidoId().toString(),
                r.estado(),
                r.total().setScale(2, RoundingMode.HALF_EVEN) + " " + r.moneda(),
                r.confirmadoEn().toString());
        viewModel = new ConfirmarPedidoViewModel(HttpStatus.OK.value(), json);
    }

    @Override
    public void presentarNoEncontrado(UUID pedidoId) {
        var problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "No existe el pedido " + pedidoId);
        problema.setTitle("Pedido no encontrado");
        viewModel = new ConfirmarPedidoViewModel(HttpStatus.NOT_FOUND.value(), problema);
    }

    @Override
    public void presentarRechazado(UUID pedidoId, String motivo) {
        var problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, motivo);
        problema.setTitle("No se puede confirmar el pedido " + pedidoId);
        viewModel = new ConfirmarPedidoViewModel(HttpStatus.CONFLICT.value(), problema);
    }

    public ConfirmarPedidoViewModel viewModel() {
        if (viewModel == null) {
            throw new IllegalStateException("El caso de uso no presentó ningún resultado");
        }
        return viewModel;
    }
}
```

```java
package com.curso.pedidos.adapters.web.confirmarpedido;

import com.curso.pedidos.usecases.confirmarpedido.ConfirmarPedidoInputBoundary;
import com.curso.pedidos.usecases.confirmarpedido.ConfirmarPedidoRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfirmarPedidoController {

    private final ConfirmarPedidoInputBoundary confirmarPedido;

    public ConfirmarPedidoController(ConfirmarPedidoInputBoundary confirmarPedido) {
        this.confirmarPedido = confirmarPedido;
    }

    @PostMapping("/pedidos/{id}/confirmacion")
    public ResponseEntity<Object> confirmar(@PathVariable UUID id) {
        var presenter = new ConfirmarPedidoPresenter();          // uno por petición
        confirmarPedido.confirmar(new ConfirmarPedidoRequest(id), presenter);
        var vm = presenter.viewModel();
        return ResponseEntity.status(vm.httpStatus()).body(vm.cuerpo());
    }
}
```

### 6.5 Frameworks & Drivers: cableado y transacción

```java
package com.curso.pedidos.frameworks.config;

import com.curso.pedidos.usecases.confirmarpedido.ConfirmarPedidoInputBoundary;
import com.curso.pedidos.usecases.confirmarpedido.ConfirmarPedidoInteractor;
import com.curso.pedidos.usecases.gateways.EventosGateway;
import com.curso.pedidos.usecases.gateways.PedidoGateway;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class CasosDeUsoConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ConfirmarPedidoInputBoundary confirmarPedido(PedidoGateway pedidos,
                                                 EventosGateway eventos,
                                                 Clock clock,
                                                 TransactionTemplate tx) {
        var interactor = new ConfirmarPedidoInteractor(pedidos, eventos, clock);
        // Decorador transaccional: la transacción es un detalle del círculo exterior.
        return (request, presenter) ->
                tx.executeWithoutResult(status -> interactor.confirmar(request, presenter));
    }
}
```

`PedidoGatewayJpa` es exactamente el `PedidoPersistenceAdapter` del día 4 con
otro nombre (implementa `PedidoGateway`, traduce `Pedido` ↔ `PedidoJpaEntity`).
`EventosGateway` lo implementa un adaptador que delega en
`ApplicationEventPublisher` o en Kafka.

---

## 7. El problema del Presenter en Spring (honestidad)

El Presenter clásico de Uncle Bob encaja de maravilla en aplicaciones con UI
"empujada" (MVC de escritorio, una vista que se actualiza cuando el presenter
escribe en el View Model). En un **controlador REST** el modelo es
petición/respuesta: el controller tiene que **devolver** algo. Hay tres
soluciones y conviene conocerlas:

| Opción | Cómo | Pros | Contras |
|--------|------|------|---------|
| **A. Presenter como parámetro** (la de este ejemplo) | `confirmar(request, presenter)`; el controller crea un presenter por petición | Sin estado compartido, thread-safe, fácil de testear | El interactor no recibe el boundary en el constructor (variación respecto al libro) |
| **B. Presenter `@RequestScope`** | Inyectar el presenter en el interactor y en el controller como bean de ámbito petición (proxy) | Fiel al diagrama original | Magia de proxies, el interactor depende de un bean con ciclo de vida web; difícil fuera de HTTP |
| **C. Devolver el Response Model** | `ConfirmarPedidoResponse confirmar(request)` y el controller mapea | Simple, idiomático en Spring | Ya no hay Output Boundary: los errores salen como excepciones. Es, en la práctica, **hexagonal** |

> Trampa: implementar el presenter como **singleton con estado** (`@Component`
> con un campo `viewModel`). Dos peticiones concurrentes se pisan el resultado.
> Es un bug de concurrencia que no sale en tests unitarios.

La mayoría de proyectos Java que dicen ser "Clean" usan en realidad la opción
C. Tom Hombergs lo reconoce abiertamente en *Get Your Hands Dirty on Clean
Architecture*: su implementación es hexagonal (puertos y adaptadores) y se
apoya en las ideas de Clean. No es un pecado: es una decisión pragmática. Pero
conviene **saber que la estás tomando**.

---

## 8. Testing

### 8.1 Test unitario del Interactor (sin Spring, sin BD)

```java
package com.curso.pedidos.usecases.confirmarpedido;

import static org.assertj.core.api.Assertions.assertThat;

import com.curso.pedidos.entities.EstadoPedido;
import com.curso.pedidos.entities.Pedido;
import com.curso.pedidos.entities.PedidoConfirmado;
import com.curso.pedidos.entities.PedidoId;
import com.curso.pedidos.usecases.gateways.EventosGateway;
import com.curso.pedidos.usecases.gateways.PedidoGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConfirmarPedidoInteractorTest {

    private static final Instant AHORA = Instant.parse("2026-11-23T14:00:00Z");

    private final PedidoGatewayEnMemoria pedidos = new PedidoGatewayEnMemoria();
    private final List<Object> eventos = new ArrayList<>();
    private final PresenterEspia presenter = new PresenterEspia();
    private final ConfirmarPedidoInteractor interactor = new ConfirmarPedidoInteractor(
            pedidos, eventos::add, Clock.fixed(AHORA, ZoneOffset.UTC));

    @Test
    void confirma_un_pedido_en_borrador_con_lineas() {
        Pedido pedido = PedidoMother.borradorConUnaLinea();
        pedidos.guardar(pedido);

        interactor.confirmar(new ConfirmarPedidoRequest(pedido.id().valor()), presenter);

        assertThat(presenter.confirmado).isNotNull();
        assertThat(presenter.confirmado.estado()).isEqualTo("CONFIRMADO");
        assertThat(presenter.confirmado.confirmadoEn()).isEqualTo(AHORA);
        assertThat(pedidos.buscarPorId(pedido.id()).orElseThrow().estado())
                .isEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(eventos).containsExactly(new PedidoConfirmado(pedido.id(), AHORA));
    }

    @Test
    void presenta_no_encontrado_si_el_pedido_no_existe() {
        UUID inexistente = UUID.randomUUID();

        interactor.confirmar(new ConfirmarPedidoRequest(inexistente), presenter);

        assertThat(presenter.noEncontrado).isEqualTo(inexistente);
        assertThat(eventos).isEmpty();
    }

    @Test
    void presenta_rechazo_si_el_pedido_esta_vacio() {
        Pedido vacio = PedidoMother.borradorVacio();
        pedidos.guardar(vacio);

        interactor.confirmar(new ConfirmarPedidoRequest(vacio.id().valor()), presenter);

        assertThat(presenter.motivoRechazo).contains("vacío");
        assertThat(eventos).isEmpty();
    }

    // --- dobles de test escritos a mano: más legibles que mocks para boundaries ---

    static final class PresenterEspia implements ConfirmarPedidoOutputBoundary {
        ConfirmarPedidoResponse confirmado;
        UUID noEncontrado;
        String motivoRechazo;

        public void presentarConfirmado(ConfirmarPedidoResponse r) { confirmado = r; }
        public void presentarNoEncontrado(UUID id) { noEncontrado = id; }
        public void presentarRechazado(UUID id, String motivo) { motivoRechazo = motivo; }
    }

    static final class PedidoGatewayEnMemoria implements PedidoGateway {
        private final Map<PedidoId, Pedido> datos = new HashMap<>();

        public Optional<Pedido> buscarPorId(PedidoId id) { return Optional.ofNullable(datos.get(id)); }
        public void guardar(Pedido pedido) { datos.put(pedido.id(), pedido); }
    }
}
```

`EventosGateway` es una interfaz de un solo método, así que `eventos::add` sirve
como doble. `PedidoMother` es el *Object Mother* del [día 5](../day-05/00-README.md).

### 8.2 Test unitario del Presenter

```java
class ConfirmarPedidoPresenterTest {

    @Test
    void formatea_el_total_y_devuelve_200() {
        var presenter = new ConfirmarPedidoPresenter();
        UUID id = UUID.randomUUID();

        presenter.presentarConfirmado(new ConfirmarPedidoResponse(
                id, "CONFIRMADO", new BigDecimal("149.9"), "EUR",
                Instant.parse("2026-11-23T14:00:00Z")));

        var vm = presenter.viewModel();
        assertThat(vm.httpStatus()).isEqualTo(200);
        assertThat(vm.cuerpo()).isInstanceOfSatisfying(
                ConfirmarPedidoViewModel.PedidoConfirmadoJson.class,
                json -> assertThat(json.total()).isEqualTo("149.90 EUR"));
    }

    @Test
    void rechazo_se_traduce_a_409() {
        var presenter = new ConfirmarPedidoPresenter();
        presenter.presentarRechazado(UUID.randomUUID(), "No se puede confirmar un pedido vacío");
        assertThat(presenter.viewModel().httpStatus()).isEqualTo(409);
    }
}
```

### 8.3 Y además

- **Controller**: `@WebMvcTest(ConfirmarPedidoController.class)` con
  `@MockBean ConfirmarPedidoInputBoundary` (en Boot 3.4+ `@MockitoBean`). Se
  usa `doAnswer` para que el mock invoque al presenter recibido.
- **Gateway JPA**: el mismo test de integración con Testcontainers del día 6.
- **Regla de dependencia**: un test de ArchUnit garantiza que `entities` y
  `usecases` no importan `org.springframework..` ni `jakarta.persistence..`
  (lo vimos el día 5).

---

## 9. Relación con Onion Architecture (Palermo, 2008)

Jeffrey Palermo publicó *The Onion Architecture* en 2008, cuatro años antes del
post de Uncle Bob. Su regla fundamental es la misma: el código puede depender
de capas más centrales, nunca de capas más externas, y el acoplamiento va
**hacia el centro**.

```
     ┌─────────────────────────────────────────────┐
     │  UI · Infrastructure · Tests                │
     │   ┌─────────────────────────────────────┐   │
     │   │  Application Services               │   │
     │   │   ┌─────────────────────────────┐   │   │
     │   │   │  Domain Services            │   │   │
     │   │   │   ┌─────────────────────┐   │   │   │
     │   │   │   │   Domain Model      │   │   │   │
     │   │   │   └─────────────────────┘   │   │   │
     │   │   └─────────────────────────────┘   │   │
     │   └─────────────────────────────────────┘   │
     └─────────────────────────────────────────────┘
```

| Onion (2008)          | Clean (2012)              |
|-----------------------|---------------------------|
| Domain Model          | Entities                  |
| Domain Services       | Entities / Use Cases (según el caso) |
| Application Services  | Use Cases (Interactors)   |
| UI, Infrastructure, Tests | Interface Adapters + Frameworks & Drivers |
| Interfaces de repositorio en el núcleo | Gateways en el círculo de Use Cases |

Lo que Onion aporta de forma explícita: **separar servicios de dominio y
servicios de aplicación** en capas distintas, y poner la infraestructura y los
**tests** en el anillo exterior. Lo que Clean añade: el vocabulario de
Boundaries/Presenter y la ambición de unificar a todas.

---

## 10. Tabla resumen

| Pregunta                                | Respuesta en Clean Architecture |
|-----------------------------------------|---------------------------------|
| ¿Regla central?                         | Dependencias de código solo hacia dentro |
| ¿Cuántas capas?                         | 4 círculos típicos; el post admite más |
| ¿Cómo entra una petición?               | Controller → Request Model → Input Boundary → Interactor |
| ¿Cómo sale el resultado?                | Interactor → Response Model → Output Boundary → Presenter → View Model |
| ¿Dónde vive la lógica de negocio?       | Entities (empresa) y Interactors (aplicación) |
| ¿Dónde vive Spring?                     | Solo en los dos círculos exteriores |
| ¿Coste?                                 | Muchos ficheros por caso de uso (8 en `ConfirmarPedido`) |
| ¿Beneficio?                             | Casos de uso 100 % testeables sin framework; presentación desacoplada |

---

## Ejercicio

Reescribe `ConfirmarPedido` con la **opción C** (el Input Boundary devuelve
`ConfirmarPedidoResponse` y lanza excepciones de dominio). Cuenta los ficheros
que desaparecen y responde: ¿qué has perdido?

<details><summary>Solución</summary>

Desaparecen `ConfirmarPedidoOutputBoundary`, `ConfirmarPedidoPresenter` y
`ConfirmarPedidoViewModel` (quedan 5 ficheros nuevos). El controller pasa a ser:

```java
@PostMapping("/pedidos/{id}/confirmacion")
public PedidoConfirmadoJson confirmar(@PathVariable UUID id) {
    var r = confirmarPedido.confirmar(new ConfirmarPedidoRequest(id));
    return PedidoConfirmadoJson.desde(r);
}
```

y un `@RestControllerAdvice` traduce `PedidoNoEncontradoException` → 404 y
`PedidoException` → 409.

Lo que pierdes: (1) los desenlaces de negocio ya no son **explícitos en la
firma** del caso de uso (hay que leer el código para saber qué excepciones
salen); (2) la traducción a HTTP queda repartida entre controller y advice.
Lo que ganas: menos ficheros, código idiomático en Spring. Y, de hecho, ahora
tienes exactamente la **arquitectura hexagonal** del curso: lo vemos en el
bloque 2.
</details>

---

## Referencias

- Robert C. Martin, *The Clean Architecture* (2012):
  https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- Robert C. Martin, *Clean Architecture: A Craftsman's Guide to Software
  Structure and Design* (Prentice Hall, 2017). Capítulos sobre la regla de
  dependencia, *Presenters and Humble Objects* y *The Missing Chapter*.
- Jeffrey Palermo, *The Onion Architecture: part 1* (2008):
  https://jeffreypalermo.com/2008/07/the-onion-architecture-part-1/
- Alistair Cockburn, *Hexagonal Architecture* (2005):
  https://alistair.cockburn.us/hexagonal-architecture/
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023) y
  https://github.com/thombergs/buckpal
- Spring Framework, gestión programática de transacciones (`TransactionTemplate`):
  https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- ArchUnit: https://www.archunit.org/userguide/html/000_Index.html

---

## Mantra

> *"El flujo de control entra y sale; las dependencias solo entran."*
