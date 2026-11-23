# Día 7 — Bloque 2: Hexagonal vs Clean (y Onion): familia, no rivales

> *"Allow an application to equally be driven by users, programs, automated
> test or batch scripts, and to be developed and tested in isolation from its
> eventual run-time devices and databases."*
> — Alistair Cockburn, *Hexagonal Architecture* (2005)

---

## 1. El problema en una frase

En las entrevistas y en los equipos se oye mucho "¿usamos hexagonal o Clean?"
como si fuera elegir entre Java y Python. **Es una falsa dicotomía**: son dos
formulaciones de la misma idea, con énfasis distintos. La pregunta útil es
**qué piezas del vocabulario de cada una te aportan valor** en tu proyecto.

---

## 2. Lo que comparten (y es lo importante)

| Idea común                                   | Hexagonal (2005)                         | Clean (2012)                         | Onion (2008)                         |
|----------------------------------------------|------------------------------------------|--------------------------------------|--------------------------------------|
| El negocio no depende de la tecnología       | La aplicación no conoce los adaptadores  | Regla de dependencia                 | Acoplamiento hacia el centro         |
| Inversión de dependencias en la salida       | Puerto de salida implementado por un adaptador | Gateway implementado en Interface Adapters | Interfaz de repositorio en el núcleo |
| Testeable sin infraestructura                | Objetivo explícito (test harness como actor) | Objetivo explícito                  | Tests en el anillo exterior          |
| Framework y BD son detalles                  | Son adaptadores                          | Son el círculo exterior              | Son el anillo exterior               |

En cristiano: **las tres son aplicaciones del Principio de Inversión de
Dependencias (DIP) a escala de arquitectura**. Si entiendes una, entiendes las
otras tres cuartas partes de las demás.

```
   HEXAGONAL                   CLEAN                     ONION
   (dentro/fuera)              (círculos)                (capas de cebolla)

  ┌──┐   ┌─────────┐   ┌──┐   ┌───────────────┐        ┌───────────────┐
  │A │──►│         │◄──│A │   │ ┌───────────┐ │        │ ┌───────────┐ │
  │d │   │ APLICA- │   │d │   │ │ ┌───────┐ │ │        │ │ ┌───────┐ │ │
  │a │──►│  CIÓN   │◄──│a │   │ │ │ Ent.  │ │ │        │ │ │Domain │ │ │
  │p.│   │         │   │p.│   │ │ └───────┘ │ │        │ │ └───────┘ │ │
  └──┘   └─────────┘   └──┘   │ └─UseCases──┘ │        │ └─AppSvc────┘ │
  driving   puertos   driven  └──Adapters/FW──┘        └──UI/Infra/Test┘

             Todas las flechas de dependencia: ───► hacia el centro
```

---

## 3. En qué difieren DE VERDAD

Aquí está el valor del bloque. Cuatro diferencias reales, ninguna de ellas
filosófica:

### 3.1 Simetría izquierda/derecha vs anillos

- **Hexagonal** solo distingue **dentro** y **fuera**. Fuera hay dos lados: el
  que **dirige** la aplicación (driving: UI, tests, CLI, colas entrantes) y el
  que **es dirigido** por ella (driven: BD, pasarela de pago, email). Cockburn
  insiste en que conceptualmente son simétricos: ambos son *puertos* con
  *adaptadores*, y un test automatizado es un actor driving tan legítimo como
  una UI.
- **Clean** dibuja **anillos concéntricos**. UI y BD también están juntas en el
  anillo exterior (en eso coincide con hexagonal), pero el diagrama no
  distingue "lado que dirige" de "lado dirigido": lo que distingue es **el nivel
  de abstracción** (cuanto más dentro, más estable y más política).

> Matiz honesto: la simetría de hexagonal es **conceptual**, no de
> implementación. En el lado driving la aplicación **implementa** el puerto
> (`ConfirmarPedidoService implements ConfirmarPedidoUseCase`); en el lado
> driven la aplicación **define** el puerto y el adaptador lo implementa. Ya lo
> vimos el [día 2](../day-02/03-definicion-de-puertos.md).

### 3.2 Número de capas internas: no prescrito vs prescrito

- **Hexagonal no dice nada del interior del hexágono.** Cockburn eligió el
  hexágono para tener sitio donde dibujar puertos, no porque el número seis
  signifique algo, y el interior es simplemente "la aplicación". Que tú lo
  dividas en `domain` + `application` (como hacemos en el curso) es una
  decisión **añadida**, normalmente inspirada en DDD, Onion o Clean.
- **Clean sí propone capas internas**: Entities (reglas de empresa) y Use Cases
  (reglas de aplicación). Aunque el post admite más círculos, el modelo mental
  que deja es "cuatro capas".

### 3.3 El lado de salida hacia la UI: Presenter

- **Clean** modela explícitamente la salida de un caso de uso hacia la UI con
  **Output Boundary + Presenter + View Model**. El caso de uso no "devuelve";
  "presenta".
- **Hexagonal** no tiene ese concepto. En la práctica, el puerto de entrada
  devuelve un resultado (o lanza una excepción) y el adaptador de entrada lo
  traduce. Si quieres un presenter en hexagonal, lo modelas como un **puerto de
  salida más** (es perfectamente legal).

### 3.4 Vocabulario y granularidad

- Hexagonal habla de **puertos** (agrupaciones con propósito: "for confirming
  orders", "for storing orders") y **adaptadores**.
- Clean habla de **Boundaries**, **Interactors**, **Gateways**, **Request/Response
  Models**, **Presenters**, **Controllers**. Más piezas, más nombres, más
  prescripción. Eso ayuda a equipos grandes a hablar igual; también genera más
  ficheros.

---

## 4. Mapeo de conceptos

| Concepto                          | Hexagonal (curso)                         | Clean Architecture                   | Onion                     |
|-----------------------------------|-------------------------------------------|--------------------------------------|---------------------------|
| Núcleo de negocio                 | `domain` (model, service, event)          | Entities                             | Domain Model + Domain Services |
| Caso de uso (contrato)            | Puerto de entrada `ConfirmarPedidoUseCase`| Input Boundary                       | Interfaz de Application Service |
| Caso de uso (implementación)      | `ConfirmarPedidoService`                  | Interactor                           | Application Service       |
| Datos de entrada                  | `ConfirmarPedidoCommand` (record)         | Request Model                        | DTO / comando             |
| Datos de salida                   | Valor de retorno / record de resultado    | Response Model                       | DTO                       |
| Salida hacia la UI                | (no existe; retorno + excepciones)        | Output Boundary + Presenter + View Model | (no existe)            |
| Acceso a datos (contrato)         | Puerto de salida `PedidoRepository`       | Gateway (`PedidoGateway`)            | Interfaz de repositorio en el núcleo |
| Acceso a datos (implementación)   | `PedidoPersistenceAdapter`                | Gateway impl. en Interface Adapters  | Infrastructure            |
| Entrada HTTP                      | Adaptador de entrada `PedidoController`   | Controller                           | UI                        |
| Framework, BD, colas              | Adaptadores + `infrastructure.config`     | Frameworks & Drivers                 | Infrastructure            |
| Tests                             | Actor driving más (test harness)          | Fuera de los círculos                | Anillo exterior           |
| Regla                             | Dentro no conoce fuera                    | Dependencias solo hacia dentro       | Acoplamiento hacia el centro |

---

## 5. El mismo caso de uso en hexagonal (para comparar)

Mismo dominio (`Pedido.confirmar()` idéntico al del bloque 1), misma API, mismo
evento. Estructura canónica del curso.

### 5.1 Árbol de ficheros de la feature

```
com.curso.pedidos
├── domain
│   ├── model/Pedido.java ...                        (existente)
│   ├── event/PedidoConfirmado.java                  (existente)
│   └── exception/...                                (existente)
├── application
│   ├── port
│   │   ├── in
│   │   │   ├── ConfirmarPedidoUseCase.java          (nuevo)
│   │   │   └── ConfirmarPedidoCommand.java          (nuevo)
│   │   └── out
│   │       ├── PedidoRepository.java                (existente, reutilizado)
│   │       └── PublicadorDeEventos.java             (existente, reutilizado)
│   └── service
│       └── ConfirmarPedidoService.java              (nuevo)
└── infrastructure
    ├── adapter/in/web
    │   ├── PedidoController.java                    (modificado: +1 método)
    │   ├── PedidoConfirmadoResponse.java            (nuevo)
    │   └── GlobalExceptionHandler.java              (existente)
    └── config
        └── BeanConfiguration.java                   (modificado: +1 @Bean)
```

**Recuento: 4 ficheros nuevos + 2 modificados.** Clean necesitó 8 + 1. La
diferencia está exactamente en el Presenter/Output Boundary/View Model y en
tener un controller por caso de uso.

### 5.2 Puerto de entrada y comando

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;
import java.util.Objects;

public record ConfirmarPedidoCommand(PedidoId pedidoId) {
    public ConfirmarPedidoCommand {
        Objects.requireNonNull(pedidoId, "pedidoId es obligatorio");
    }
}
```

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.Pedido;

public interface ConfirmarPedidoUseCase {

    /**
     * @throws com.curso.pedidos.domain.exception.PedidoNoEncontradoException si no existe
     * @throws com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException si no está en BORRADOR
     * @throws com.curso.pedidos.domain.exception.PedidoException si está vacío
     */
    Pedido ejecutar(ConfirmarPedidoCommand command);
}
```

> Nota de diseño: devolver el agregado `Pedido` es una decisión pragmática
> (el adaptador solo lo lee). La alternativa más estricta es devolver un record
> de resultado (`PedidoConfirmadoResult`) para que el adaptador web no pueda
> invocar métodos de negocio. Lo discutimos el [día 3](../day-03/00-README.md).

### 5.3 Servicio de aplicación

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.Pedido;
import java.time.Clock;

public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {

    private final PedidoRepository pedidos;
    private final PublicadorDeEventos eventos;
    private final Clock clock;

    public ConfirmarPedidoService(PedidoRepository pedidos, PublicadorDeEventos eventos, Clock clock) {
        this.pedidos = pedidos;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Override
    public Pedido ejecutar(ConfirmarPedidoCommand command) {
        Pedido pedido = pedidos.buscarPorId(command.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(command.pedidoId()));

        pedido.confirmar();                                   // invariantes en el dominio
        pedidos.guardar(pedido);
        eventos.publicar(new PedidoConfirmado(pedido.id(), clock.instant()));
        return pedido;
    }
}
```

Compáralo con el Interactor del bloque 1: **la orquestación es idéntica**. Lo
único distinto es cómo sale el resultado (retorno + excepciones vs presenter).

### 5.4 Adaptador de entrada

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.domain.model.Pedido;
import java.math.BigDecimal;
import java.util.UUID;

public record PedidoConfirmadoResponse(UUID pedidoId, String estado,
                                       BigDecimal total, String moneda) {

    static PedidoConfirmadoResponse desde(Pedido pedido) {
        return new PedidoConfirmadoResponse(
                pedido.id().valor(),
                pedido.estado().name(),
                pedido.total().importe(),
                pedido.total().moneda().getCurrencyCode());
    }
}
```

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.domain.model.PedidoId;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pedidos")
public class PedidoController {

    private final ConfirmarPedidoUseCase confirmarPedido;
    // ... otros casos de uso (crear, añadir línea, consultar) inyectados igual

    public PedidoController(ConfirmarPedidoUseCase confirmarPedido) {
        this.confirmarPedido = confirmarPedido;
    }

    @PostMapping("/{id}/confirmacion")
    public PedidoConfirmadoResponse confirmar(@PathVariable UUID id) {
        var pedido = confirmarPedido.ejecutar(new ConfirmarPedidoCommand(new PedidoId(id)));
        return PedidoConfirmadoResponse.desde(pedido);
    }
}
```

El `GlobalExceptionHandler` (día 3) ya traducía `PedidoNoEncontradoException` →
404 y `PedidoException` (y sus hijas) → 409 con `ProblemDetail`. Ese es, en
hexagonal, **el papel del Presenter**: repartido entre el controller (caso feliz)
y el advice (casos de error).

### 5.5 Cableado

```java
// infrastructure/config/BeanConfiguration.java  (fragmento)
@Bean
ConfirmarPedidoUseCase confirmarPedidoUseCase(PedidoRepository pedidos,
                                              PublicadorDeEventos eventos,
                                              Clock clock,
                                              TransactionTemplate tx) {
    var service = new ConfirmarPedidoService(pedidos, eventos, clock);
    return command -> tx.execute(status -> service.ejecutar(command));
}
```

Idéntico truco que en Clean: el decorador transaccional vive fuera.

### 5.6 Tests

```java
package com.curso.pedidos.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConfirmarPedidoServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-23T14:00:00Z");

    private final PedidoRepository pedidos = mock(PedidoRepository.class);
    private final PublicadorDeEventos eventos = mock(PublicadorDeEventos.class);
    private final ConfirmarPedidoService service =
            new ConfirmarPedidoService(pedidos, eventos, Clock.fixed(AHORA, ZoneOffset.UTC));

    @Test
    void confirma_guarda_y_publica() {
        Pedido pedido = PedidoMother.borradorConUnaLinea();
        when(pedidos.buscarPorId(pedido.id())).thenReturn(Optional.of(pedido));

        Pedido resultado = service.ejecutar(new ConfirmarPedidoCommand(pedido.id()));

        assertThat(resultado.estado()).isEqualTo(EstadoPedido.CONFIRMADO);
        verify(pedidos).guardar(pedido);
        verify(eventos).publicar(new PedidoConfirmado(pedido.id(), AHORA));
    }

    @Test
    void falla_si_no_existe() {
        PedidoId id = new PedidoId(UUID.randomUUID());
        when(pedidos.buscarPorId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ejecutar(new ConfirmarPedidoCommand(id)))
                .isInstanceOf(PedidoNoEncontradoException.class);
        verifyNoInteractions(eventos);
    }

    @Test
    void no_guarda_ni_publica_si_el_pedido_esta_vacio() {
        Pedido vacio = PedidoMother.borradorVacio();
        when(pedidos.buscarPorId(vacio.id())).thenReturn(Optional.of(vacio));

        assertThatThrownBy(() -> service.ejecutar(new ConfirmarPedidoCommand(vacio.id())))
                .isInstanceOf(PedidoException.class);
        verify(pedidos, never()).guardar(any());
        verifyNoInteractions(eventos);
    }
}
```

Y el test del adaptador web, con el puerto mockeado:

```java
@WebMvcTest(PedidoController.class)
@Import(GlobalExceptionHandler.class)
class PedidoControllerConfirmarTest {

    @Autowired MockMvc mvc;
    @MockBean ConfirmarPedidoUseCase confirmarPedido;   // Boot 3.4+: @MockitoBean

    @Test
    void devuelve_409_si_el_dominio_rechaza() throws Exception {
        when(confirmarPedido.ejecutar(any()))
                .thenThrow(new PedidoException("No se puede confirmar un pedido vacío"));

        mvc.perform(post("/pedidos/{id}/confirmacion", UUID.randomUUID()))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.detail").value("No se puede confirmar un pedido vacío"));
    }
}
```

---

## 6. Lado a lado: el mismo caso de uso

| Aspecto                         | Clean (bloque 1)                                 | Hexagonal (este bloque)                     |
|---------------------------------|--------------------------------------------------|---------------------------------------------|
| Ficheros nuevos                 | 8                                                | 4                                           |
| Ficheros modificados            | 1 (config)                                       | 2 (controller + config)                     |
| Contrato del caso de uso        | `InputBoundary.confirmar(request, presenter)`     | `UseCase.ejecutar(command)` → `Pedido`       |
| Desenlaces de error             | Métodos del Output Boundary (explícitos)         | Excepciones de dominio (implícitas en firma) |
| Traducción a HTTP               | Presenter (un sitio)                             | Controller + `GlobalExceptionHandler`       |
| Orquestación                    | Idéntica                                         | Idéntica                                    |
| Dominio                         | Idéntico                                         | Idéntico                                    |
| Test unitario del caso de uso   | Fakes a mano (gateway + presenter espía)         | Mockito sobre puertos                       |
| Spring en el centro             | No                                               | No                                          |

> Lo que salta a la vista: **el 90 % del código es el mismo**. La discusión
> "hexagonal o Clean" en un proyecto Spring REST se reduce casi siempre a
> "¿quiero Presenter o no?".

---

## 7. Trampas

- **Trampa: "Clean = cuatro módulos Maven obligatorios".** Ni el post ni el
  libro lo exigen. Los límites se pueden hacer con paquetes + ArchUnit.
- **Trampa: "Hexagonal = solo dos capas".** Hexagonal no prescribe el interior;
  dividirlo en dominio + aplicación es compatible y recomendable si hay lógica
  de dominio rica.
- **Trampa: mezclar vocabularios a medias.** `PedidoGatewayAdapterPortImpl` es
  la señal de un equipo que no ha decidido qué vocabulario usar. Elegid uno y
  documentadlo (un ADR basta).
- **Trampa: copiar el Presenter "porque lo dice el libro"** en una API REST donde
  el mapeo es trivial. Pagas tres ficheros por caso de uso sin beneficio.
- **Trampa inversa: renunciar al Presenter** cuando de verdad tienes varias
  salidas para el mismo caso de uso (REST + HTML + informe PDF). Ahí el
  Presenter brilla.

---

## 8. Conclusión: son familia

```
                         DIP (Robert C. Martin, años 90)
                                     │
            ┌────────────────────────┼────────────────────────┐
            ▼                        ▼                        ▼
   Hexagonal (Cockburn 2005)   Onion (Palermo 2008)   Clean (Martin 2012/2017)
   "dentro/fuera, puertos"     "capas hacia el núcleo" "círculos + boundaries"
            │                        │                        │
            └────────────────────────┼────────────────────────┘
                                     ▼
                Lo que hacemos en el curso: hexagonal con interior
                dividido en domain + application (influencia Onion/DDD)
                y sin Presenter (simplificación habitual en Spring)
```

Si alguien te pregunta "¿esto es hexagonal o Clean?", la respuesta honesta
suele ser: **"es hexagonal en la frontera, con capas internas al estilo Onion
y sin el Presenter de Clean"**. Y eso está bien.

---

## Ejercicio

Añade a la versión hexagonal un **presenter como puerto de salida**:
`ConfirmacionPresenter` con `presentarConfirmado(...)`, `presentarNoEncontrado(...)`
y `presentarRechazado(...)`. ¿En qué paquete va la interfaz? ¿Y la
implementación? ¿Sigue siendo hexagonal?

<details><summary>Solución</summary>

- La interfaz va en `application.port.out` (la define la aplicación, igual que
  `PedidoRepository`).
- La implementación va en `infrastructure.adapter.in.web` (es parte del
  adaptador web, aunque técnicamente implemente un puerto "de salida": Cockburn
  admite que un mismo adaptador participe en varios puertos).
- Sí, sigue siendo hexagonal. Acabas de demostrar que la diferencia con Clean
  no es de principio, es de **qué puertos decides modelar**. Y de nuevo te
  encuentras el problema del presenter por petición del bloque 1, sección 7.
</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture* (2005):
  https://alistair.cockburn.us/hexagonal-architecture/
- Alistair Cockburn y Juan Manuel Garrido de Paz, *Hexagonal Architecture
  Explained* (2024).
- Robert C. Martin, *The Clean Architecture* (2012):
  https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html ;
  *Clean Architecture* (2017).
- Jeffrey Palermo, *The Onion Architecture: part 1* (2008):
  https://jeffreypalermo.com/2008/07/the-onion-architecture-part-1/
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023);
  https://github.com/thombergs/buckpal
- Spring Framework testing (MockMvc, `@WebMvcTest`):
  https://docs.spring.io/spring-framework/reference/testing.html
- Mockito: https://site.mockito.org/ · AssertJ: https://assertj.github.io/doc/

---

## Mantra

> *"Hexagonal y Clean no son rivales: son primas que comparten apellido —
> Inversión de Dependencias."*
