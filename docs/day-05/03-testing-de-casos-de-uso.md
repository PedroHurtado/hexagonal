# Día 5 — Bloque 3: Testing de casos de uso

> *"Mock roles, not objects."* — Steve Freeman, Nat Pryce, Tim Mackinnon y Joe Walnes (OOPSLA, 2004)

---

## 1. El problema en una frase

Los servicios de aplicación **orquestan**: cargan un agregado por un puerto, le piden algo al dominio, guardan, publican, notifican. Probarlos exige **sustituir los puertos de salida**, y la forma en que lo hagas decide si tu suite será un activo o un lastre que se rompe con cada refactor.

Hoy vemos las dos escuelas (fakes en memoria y Mockito), cuándo usar cada una, y el truco que convierte los fakes en algo fiable: **los tests de contrato de puerto**.

---

## 2. El caso de uso bajo prueba

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.out.NotificadorPedidos;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.Pedido;

import java.time.Clock;

public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final PublicadorDeEventos publicadorDeEventos;
    private final NotificadorPedidos notificador;
    private final Clock clock;

    public ConfirmarPedidoService(PedidoRepository pedidoRepository,
                                  PublicadorDeEventos publicadorDeEventos,
                                  NotificadorPedidos notificador,
                                  Clock clock) {
        this.pedidoRepository = pedidoRepository;
        this.publicadorDeEventos = publicadorDeEventos;
        this.notificador = notificador;
        this.clock = clock;
    }

    @Override
    public void ejecutar(ConfirmarPedidoCommand command) {
        Pedido pedido = pedidoRepository.buscarPorId(command.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(command.pedidoId()));

        pedido.confirmar();
        pedidoRepository.guardar(pedido);

        publicadorDeEventos.publicar(new PedidoConfirmado(pedido.id(), clock.instant()));
        notificador.notificarConfirmacion(pedido);
    }
}
```

Puertos de salida implicados (del día 2):

```java
public interface PedidoRepository {
    void guardar(Pedido pedido);
    Optional<Pedido> buscarPorId(PedidoId id);
    List<Pedido> buscarPorCliente(ClienteId clienteId);
}

public interface PublicadorDeEventos {
    void publicar(EventoDeDominio evento);   // EventoDeDominio: interfaz marcadora en domain.event
}

public interface NotificadorPedidos {
    void notificarConfirmacion(Pedido pedido);
    void notificarCancelacion(Pedido pedido);
}
```

> Fíjate: **no hay ni una anotación de Spring**. El servicio se instancia con `new` en el test. Ese es el premio del día 4 (cableado en `BeanConfiguration`).

---

## 3. Bad: Mockito a granel, mockeando hasta el dominio

```java
@ExtendWith(MockitoExtension.class)
class ConfirmarPedidoServiceBadTest {

    @Mock PedidoRepository repo;
    @Mock PublicadorDeEventos publicador;
    @Mock NotificadorPedidos notificador;
    @Mock Pedido pedido;                       // ✘ mock del agregado
    @Mock Clock clock;                         // ✘ mock de una clase del JDK

    @Test
    void confirma() {
        PedidoId id = new PedidoId(UUID.randomUUID());
        when(repo.buscarPorId(id)).thenReturn(Optional.of(pedido));
        when(pedido.id()).thenReturn(id);
        when(clock.instant()).thenReturn(Instant.EPOCH);

        new ConfirmarPedidoService(repo, publicador, notificador, clock)
                .ejecutar(new ConfirmarPedidoCommand(id));

        verify(pedido).confirmar();            // ✘ prueba "que se llamó", no que funcione
        verify(repo).guardar(pedido);
        verify(publicador).publicar(any());    // ✘ cualquier evento vale
        verify(notificador).notificarConfirmacion(pedido);
    }
}
```

### ¿Qué duele?

- **El test es un espejo de la implementación.** Repite línea a línea el código de producción con `verify`. Si reordenas o refactorizas (sin cambiar comportamiento), el test se rompe.
- **Mockear `Pedido`** hace que el test pase aunque `confirmar()` esté rota o un pedido vacío se confirme. Hemos borrado al protagonista.
- **`publicar(any())`** pasaría con un `PedidoCancelado`.
- **`@Mock Clock`**: `Clock` es una clase abstracta del JDK con implementaciones de test (`Clock.fixed`). Mockearla es complicarse la vida.
- **Ningún test del camino triste**: ¿qué pasa si el pedido no existe?

---

## 4. Good (opción A): fakes en memoria

### 4.1 El fake: `InMemoryPedidoRepository`

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryPedidoRepository implements PedidoRepository {

    private final Map<PedidoId, Pedido> almacen = new ConcurrentHashMap<>();

    @Override
    public void guardar(Pedido pedido) {
        almacen.put(pedido.id(), copia(pedido));
    }

    @Override
    public Optional<Pedido> buscarPorId(PedidoId id) {
        return Optional.ofNullable(almacen.get(id)).map(InMemoryPedidoRepository::copia);
    }

    @Override
    public List<Pedido> buscarPorCliente(ClienteId clienteId) {
        return almacen.values().stream()
                .filter(p -> p.clienteId().equals(clienteId))
                .map(InMemoryPedidoRepository::copia)
                .toList();
    }

    // --- utilidades SOLO de test (no forman parte del puerto) ---
    public int numeroDePedidos() { return almacen.size(); }
    public void limpiar() { almacen.clear(); }

    /** Simula la (de)serialización de una BD: quien lee no comparte referencia con quien guardó. */
    private static Pedido copia(Pedido p) {
        return Pedido.reconstituir(p.id(), p.clienteId(), p.lineas(), p.estado(), p.creadoEn());
    }
}
```

> **Trampa clave: el fake que comparte referencias.** Si el fake guarda y devuelve **el mismo objeto**, un servicio que olvida llamar a `guardar()` pasará los tests (el objeto en el mapa ya está mutado) pero fallará en producción con JPA desacoplado o con cualquier BD. La `copia(...)` hace que el fake se comporte como un almacenamiento real.

### 4.2 Un spy propio para el publicador

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.event.EventoDeDominio;

import java.util.ArrayList;
import java.util.List;

public class PublicadorDeEventosEspia implements PublicadorDeEventos {

    private final List<EventoDeDominio> publicados = new ArrayList<>();

    @Override
    public void publicar(EventoDeDominio evento) { publicados.add(evento); }

    public List<EventoDeDominio> publicados() { return List.copyOf(publicados); }
}
```

### 4.3 El test con fakes

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.out.InMemoryPedidoRepository;
import com.curso.pedidos.application.port.out.NotificadorPedidos;
import com.curso.pedidos.application.port.out.PublicadorDeEventosEspia;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.PedidoMother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfirmarPedidoServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-23T12:00:00Z");

    private InMemoryPedidoRepository repositorio;
    private PublicadorDeEventosEspia publicador;
    private List<Pedido> notificados;
    private ConfirmarPedidoService servicio;

    @BeforeEach
    void setUp() {
        repositorio = new InMemoryPedidoRepository();
        publicador = new PublicadorDeEventosEspia();
        notificados = new ArrayList<>();
        NotificadorPedidos notificador = new NotificadorPedidos() {     // spy con clase anónima
            @Override public void notificarConfirmacion(Pedido pedido) { notificados.add(pedido); }
            @Override public void notificarCancelacion(Pedido pedido) { }
        };
        servicio = new ConfirmarPedidoService(
                repositorio, publicador, notificador, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @Test
    void confirma_y_persiste_el_pedido() {
        Pedido pedido = PedidoMother.borradorConLineas();
        repositorio.guardar(pedido);

        servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id()));

        assertThat(repositorio.buscarPorId(pedido.id()))
                .get()
                .extracting(Pedido::estado)
                .isEqualTo(EstadoPedido.CONFIRMADO);
    }

    @Test
    void publica_el_evento_PedidoConfirmado_con_la_hora_del_reloj() {
        Pedido pedido = PedidoMother.borradorConLineas();
        repositorio.guardar(pedido);

        servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id()));

        assertThat(publicador.publicados())
                .containsExactly(new PedidoConfirmado(pedido.id(), AHORA));
    }

    @Test
    void notifica_al_cliente() {
        Pedido pedido = PedidoMother.borradorConLineas();
        repositorio.guardar(pedido);

        servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id()));

        assertThat(notificados).extracting(Pedido::id).containsExactly(pedido.id());
    }

    @Test
    void si_el_pedido_no_existe_lanza_PedidoNoEncontrado_y_no_publica_nada() {
        PedidoId inexistente = new PedidoId(UUID.randomUUID());

        assertThatThrownBy(() -> servicio.ejecutar(new ConfirmarPedidoCommand(inexistente)))
                .isInstanceOf(PedidoNoEncontradoException.class);

        assertThat(publicador.publicados()).isEmpty();
        assertThat(notificados).isEmpty();
    }

    @Test
    void si_el_dominio_rechaza_la_confirmacion_no_se_guarda_ni_se_publica() {
        Pedido vacio = PedidoMother.borradorVacio();
        repositorio.guardar(vacio);

        assertThatThrownBy(() -> servicio.ejecutar(new ConfirmarPedidoCommand(vacio.id())))
                .isInstanceOf(PedidoException.class);

        assertThat(repositorio.buscarPorId(vacio.id())).get()
                .extracting(Pedido::estado).isEqualTo(EstadoPedido.BORRADOR);
        assertThat(publicador.publicados()).isEmpty();
    }
}
```

Fíjate en lo que **no** hay: ni `verify`, ni `when`, ni `any()`. El test habla de **estado observable** ("el pedido guardado está confirmado", "se publicó este evento exacto"). Puedo refactorizar el servicio entero y, mientras el comportamiento sea el mismo, los tests siguen verdes.

> `PedidoConfirmado` es un `record`, así que `containsExactly(new PedidoConfirmado(id, AHORA))` compara por valor. Gracias al `Clock.fixed`, el instante es predecible. **Esto no se puede hacer con `Instant.now()` dentro del servicio.**

---

## 5. Good (opción B): Mockito bien usado

Mockito brilla cuando lo que importa es **la interacción con un efecto externo**: cobrar, enviar, publicar. Y en equipos que ya lo dominan, para no mantener fakes de puertos triviales.

### 5.1 Estilo clásico: `when` / `verify` / `ArgumentCaptor`

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.out.NotificadorPedidos;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.EventoDeDominio;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoMother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmarPedidoServiceMockitoTest {

    private static final Instant AHORA = Instant.parse("2026-11-23T12:00:00Z");

    @Mock PedidoRepository pedidoRepository;
    @Mock PublicadorDeEventos publicador;
    @Mock NotificadorPedidos notificador;
    @Captor ArgumentCaptor<Pedido> pedidoCaptor;
    @Captor ArgumentCaptor<EventoDeDominio> eventoCaptor;

    ConfirmarPedidoService servicio;

    @BeforeEach
    void setUp() {
        // No usamos @InjectMocks: el Clock no es un mock y queremos ver el constructor explícito
        servicio = new ConfirmarPedidoService(pedidoRepository, publicador, notificador,
                Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @Test
    void guarda_el_pedido_confirmado() {
        Pedido pedido = PedidoMother.borradorConLineas();                // dominio REAL
        when(pedidoRepository.buscarPorId(pedido.id())).thenReturn(Optional.of(pedido));

        servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id()));

        verify(pedidoRepository).guardar(pedidoCaptor.capture());
        assertThat(pedidoCaptor.getValue().estado()).isEqualTo(EstadoPedido.CONFIRMADO);
    }

    @Test
    void publica_PedidoConfirmado() {
        Pedido pedido = PedidoMother.borradorConLineas();
        when(pedidoRepository.buscarPorId(pedido.id())).thenReturn(Optional.of(pedido));

        servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id()));

        verify(publicador).publicar(eventoCaptor.capture());
        assertThat(eventoCaptor.getValue())
                .isInstanceOf(PedidoConfirmado.class)
                .isEqualTo(new PedidoConfirmado(pedido.id(), AHORA));
    }

    @Test
    void guarda_antes_de_publicar() {
        Pedido pedido = PedidoMother.borradorConLineas();
        when(pedidoRepository.buscarPorId(pedido.id())).thenReturn(Optional.of(pedido));

        servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id()));

        InOrder orden = inOrder(pedidoRepository, publicador);
        orden.verify(pedidoRepository).guardar(any());
        orden.verify(publicador).publicar(any());
    }

    @Test
    void pedido_inexistente_no_toca_nada_mas() {
        Pedido pedido = PedidoMother.borradorConLineas();
        when(pedidoRepository.buscarPorId(pedido.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id())))
                .isInstanceOf(PedidoNoEncontradoException.class);

        verify(pedidoRepository, never()).guardar(any());
        verifyNoInteractions(publicador, notificador);
    }
}
```

> **Strict stubs**: con `MockitoExtension`, si haces un `when(...)` que el test no llega a usar, Mockito falla con `UnnecessaryStubbingException`. Es una ayuda, no un estorbo: te avisa de *setup* muerto. No lo desactives con `lenient()` por sistema.

### 5.2 Estilo BDD: `BDDMockito`

Mismo test, vocabulario *given/then* que encaja con la estructura del test:

```java
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.ArgumentMatchers.any;

@Test
void publica_PedidoConfirmado_estilo_bdd() {
    // given
    Pedido pedido = PedidoMother.borradorConLineas();
    given(pedidoRepository.buscarPorId(pedido.id())).willReturn(Optional.of(pedido));

    // when
    servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id()));

    // then
    then(publicador).should().publicar(new PedidoConfirmado(pedido.id(), AHORA));
    then(notificador).should().notificarConfirmacion(pedido);
    then(notificador).shouldHaveNoMoreInteractions();
}

@Test
void si_falla_la_persistencia_no_se_publica() {
    Pedido pedido = PedidoMother.borradorConLineas();
    given(pedidoRepository.buscarPorId(pedido.id())).willReturn(Optional.of(pedido));
    willThrow(new IllegalStateException("BD caída")).given(pedidoRepository).guardar(any());

    assertThatThrownBy(() -> servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id())))
            .hasMessage("BD caída");

    then(publicador).shouldHaveNoInteractions();
}
```

### 5.3 Fakes vs Mockito: cuándo cada uno

| Criterio                                 | Fake en memoria                          | Mockito                                       |
|------------------------------------------|------------------------------------------|-----------------------------------------------|
| Puerto con estado (repositorio)          | ✔ Ideal                                  | Posible, pero el test acaba en `verify(save)` |
| Puerto de efecto (cobrar, notificar)     | Spy propio sencillo                      | ✔ `verify` + `ArgumentCaptor`                 |
| Simular fallos (timeout, excepción)      | Hay que programarlo en el fake           | ✔ `willThrow(...)` en una línea               |
| Robustez ante refactors                  | ✔ Alta (prueba estado)                   | Media/baja (prueba interacciones)             |
| Coste de mantenimiento                   | Una clase por puerto, reutilizable       | Cero clases, pero *setup* repetido            |
| ¿Se comporta como el real?               | Solo si tiene **test de contrato**       | Solo lo que tú programes en cada test         |
| Legibilidad para negocio                 | ✔ Alta                                   | Media                                         |

En cristiano: **fakes para repositorios, Mockito para efectos y fallos**. Y nunca para el dominio.

---

## 6. Tests de contrato de puerto

### 6.1 El problema

Un fake es tan útil como fiel. ¿Quién garantiza que `InMemoryPedidoRepository` y `PedidoPersistenceAdapter` se comportan igual? ¿Que los dos actualizan al guardar dos veces? ¿Que los dos devuelven `Optional.empty()` y no `null` ni excepción?

**Respuesta: una única batería de tests, escrita contra el puerto, ejecutada contra cada implementación.**

```
                     ┌─────────────────────────────────────┐
                     │  PedidoRepositoryContractTest       │
                     │  (abstract)                         │
                     │  + guarda_y_recupera()              │
                     │  + inexistente_devuelve_vacio()     │
                     │  + guardar_dos_veces_actualiza()    │
                     │  + busca_por_cliente()              │
                     │  # abstract repositorio()           │
                     └───────────────┬─────────────────────┘
                                     │ extends
               ┌─────────────────────┴──────────────────────┐
               ▼                                            ▼
 ┌───────────────────────────────┐          ┌────────────────────────────────────┐
 │ InMemoryPedidoRepositoryTest  │          │ PedidoPersistenceAdapterIT         │
 │ repositorio() = new InMemory  │          │ @DataJpaTest + Testcontainers      │
 │ (surefire, ms)                │          │ repositorio() = adapter JPA real   │
 └───────────────────────────────┘          │ (failsafe, segundos, Docker)       │
                                            └────────────────────────────────────┘
```

### 6.2 La clase abstracta

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.curso.pedidos.domain.model.PedidoBuilder.unPedido;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class PedidoRepositoryContractTest {

    /** Cada subclase entrega su implementación del puerto. */
    protected abstract PedidoRepository repositorio();

    /** Gancho: en JPA hace flush + clear para obligar a ir a la BD. En memoria, nada. */
    protected void sincronizar() { }

    @Test
    void guarda_y_recupera_un_pedido_con_sus_lineas() {
        Pedido pedido = unPedido().conIdAleatorio()
                .conLinea("SKU-TECLADO", 2, "49.90")
                .conLinea("SKU-RATON", 1, "19.90")
                .build();

        repositorio().guardar(pedido);
        sincronizar();

        Pedido recuperado = repositorio().buscarPorId(pedido.id()).orElseThrow();
        assertThat(recuperado.id()).isEqualTo(pedido.id());
        assertThat(recuperado.clienteId()).isEqualTo(pedido.clienteId());
        assertThat(recuperado.estado()).isEqualTo(EstadoPedido.BORRADOR);
        assertThat(recuperado.creadoEn()).isEqualTo(pedido.creadoEn());
        assertThat(recuperado.lineas())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(pedido.lineas());
        assertThat(recuperado.total()).isEqualTo(Dinero.euros("119.70"));
    }

    @Test
    void un_id_inexistente_devuelve_optional_vacio() {
        assertThat(repositorio().buscarPorId(new PedidoId(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void guardar_dos_veces_el_mismo_pedido_actualiza_y_no_duplica() {
        Pedido pedido = unPedido().conIdAleatorio().conUnaLineaCualquiera().build();
        repositorio().guardar(pedido);
        sincronizar();

        Pedido cargado = repositorio().buscarPorId(pedido.id()).orElseThrow();
        cargado.confirmar();
        repositorio().guardar(cargado);
        sincronizar();

        assertThat(repositorio().buscarPorId(pedido.id()))
                .get().extracting(Pedido::estado).isEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(repositorio().buscarPorCliente(pedido.clienteId()))
                .extracting(Pedido::id).containsOnlyOnce(pedido.id());
    }

    @Test
    void busca_solo_los_pedidos_del_cliente_indicado() {
        ClienteId ana = new ClienteId(UUID.randomUUID());
        ClienteId luis = new ClienteId(UUID.randomUUID());
        Pedido p1 = unPedido().conIdAleatorio().deCliente(ana).build();
        Pedido p2 = unPedido().conIdAleatorio().deCliente(ana).build();
        Pedido p3 = unPedido().conIdAleatorio().deCliente(luis).build();
        repositorio().guardar(p1);
        repositorio().guardar(p2);
        repositorio().guardar(p3);
        sincronizar();

        assertThat(repositorio().buscarPorCliente(ana))
                .extracting(Pedido::id)
                .containsExactlyInAnyOrder(p1.id(), p2.id());
    }

    @Test
    void un_cliente_sin_pedidos_devuelve_lista_vacia_no_null() {
        assertThat(repositorio().buscarPorCliente(new ClienteId(UUID.randomUUID())))
                .isNotNull()
                .isEmpty();
    }
}
```

### 6.3 Implementación 1: el fake (rápido, surefire)

```java
package com.curso.pedidos.application.port.out;

import org.junit.jupiter.api.BeforeEach;

class InMemoryPedidoRepositoryTest extends PedidoRepositoryContractTest {

    private InMemoryPedidoRepository repositorio;

    @BeforeEach
    void setUp() {
        repositorio = new InMemoryPedidoRepository();
    }

    @Override
    protected PedidoRepository repositorio() {
        return repositorio;
    }
}
```

### 6.4 Implementación 2: el adaptador JPA real (failsafe, Docker)

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PedidoRepositoryContractTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PedidoPersistenceAdapter.class, PedidoPersistenceMapper.class})
class PedidoPersistenceAdapterIT extends PedidoRepositoryContractTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PedidoPersistenceAdapter adapter;
    @Autowired TestEntityManager entityManager;

    @Override
    protected PedidoRepository repositorio() {
        return adapter;
    }

    @Override
    protected void sincronizar() {
        entityManager.flush();   // fuerza los INSERT/UPDATE
        entityManager.clear();   // vacía la caché de 1er nivel: la siguiente lectura va a PostgreSQL
    }
}
```

Detalles importantes:

- **JUnit 5 hereda los `@Test`** de la clase abstracta. Surefire/Failsafe ignoran clases abstractas, así que `PedidoRepositoryContractTest` no se ejecuta sola.
- **`sincronizar()`** es la diferencia entre un test de persistencia real y uno que solo prueba la caché de Hibernate. Sin `clear()`, `buscarPorId` devolvería la misma instancia en memoria y no detectarías un mapeo roto.
- `@DataJpaTest` es `@Transactional`: cada test hace *rollback* al final. Los datos no se filtran entre tests.
- El detalle de Testcontainers y `@ServiceConnection` se explica en el [bloque 4](04-testing-de-infraestructura.md).

> **El test de contrato encuentra bugs reales.** Casos típicos que salen la primera vez que se ejecuta contra PostgreSQL: `BigDecimal` que vuelve como `119.7` en lugar de `119.70` (por eso normalizamos la escala en `Dinero`), `Instant` que pierde los nanosegundos (PostgreSQL guarda microsegundos), líneas que se duplican al guardar dos veces por un `cascade` mal configurado, o `buscarPorCliente` que devuelve `null`.

> Trampa: si en `creadoEn` usas `Instant.now()` en el builder, el contrato puede fallar contra PostgreSQL por la precisión (nanos vs micros). Con el `AHORA` fijo del `PedidoBuilder` sin fracciones no pasa. Si necesitas instantes "reales", trúncalos: `Instant.now().truncatedTo(ChronoUnit.MICROS)`.

### 6.5 Otros puertos con contrato

La misma técnica vale para cualquier puerto con dos implementaciones:

| Puerto               | Implementación de test         | Implementación real                 |
|----------------------|--------------------------------|-------------------------------------|
| `PedidoRepository`   | `InMemoryPedidoRepository`     | `PedidoPersistenceAdapter` (JPA)    |
| `CatalogoProductos`  | `InMemoryCatalogoProductos`    | `CatalogoRestAdapter` (WireMock)    |
| `PublicadorDeEventos`| `PublicadorDeEventosEspia`     | `KafkaPublicadorDeEventos`          |

---

## 7. Trampas y errores comunes

- **Trampa: `@SpringBootTest` para probar un servicio de aplicación.** Si el servicio no tiene anotaciones de Spring (y no debería), no hay nada de Spring que probar. `new` y listo.
- **Trampa: `verify` de todo.** Verifica solo los efectos que forman parte del **contrato** del caso de uso (se publicó el evento, se cobró). No verifiques llamadas de consulta (`buscarPorId`): si el resultado es correcto, la consulta se hizo.
- **Trampa: `any()` por pereza.** `publicar(any())` no prueba nada. Usa el valor exacto (records) o `ArgumentCaptor`.
- **Trampa: fakes sin contrato.** Un fake no validado diverge del real con el tiempo y genera falsos verdes.
- **Trampa: compartir el fake entre tests como `static`.** Estado que se filtra entre tests = tests que dependen del orden. Nuevo fake en cada `@BeforeEach`.

---

## 8. Tabla resumen

| Situación                                     | Herramienta recomendada                         |
|-----------------------------------------------|-------------------------------------------------|
| Probar flujo feliz del caso de uso            | Fakes + aserciones de estado                    |
| Verificar que se publicó un evento concreto   | Spy propio o `ArgumentCaptor` / `then().should()` |
| Simular fallo de infraestructura              | Mockito `willThrow`                             |
| Verificar orden de efectos                    | Mockito `InOrder`                               |
| Garantizar fidelidad de un fake               | Test de contrato abstracto                      |
| Hora del sistema                              | `Clock.fixed(...)` inyectado                    |

---

## Ejercicio

Implementa `CrearPedidoService` (crea un pedido en BORRADOR para un cliente, con id generado por un puerto `GeneradorDeIds` y fecha del `Clock`, y lo guarda) y pruébalo:

1. Con `InMemoryPedidoRepository` y un `GeneradorDeIds` stub (lambda).
2. Añade al contrato un test `guardar_no_modifica_el_objeto_original_tras_la_lectura`... ¿tiene sentido? Razónalo.

**Pista:** `GeneradorDeIds` puede ser una interfaz funcional `PedidoId siguiente()`; en el test, `() -> ID_FIJO`.

<details><summary>Solución</summary>

```java
public interface GeneradorDeIds {
    PedidoId siguiente();
}

public record CrearPedidoCommand(ClienteId clienteId) { }

public class CrearPedidoService implements CrearPedidoUseCase {
    private final PedidoRepository repositorio;
    private final GeneradorDeIds generadorDeIds;
    private final Clock clock;

    public CrearPedidoService(PedidoRepository repositorio, GeneradorDeIds generadorDeIds, Clock clock) {
        this.repositorio = repositorio;
        this.generadorDeIds = generadorDeIds;
        this.clock = clock;
    }

    @Override
    public PedidoId ejecutar(CrearPedidoCommand command) {
        Pedido pedido = Pedido.crear(generadorDeIds.siguiente(), command.clienteId(), clock.instant());
        repositorio.guardar(pedido);
        return pedido.id();
    }
}
```

```java
class CrearPedidoServiceTest {

    private static final PedidoId ID_FIJO =
            new PedidoId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final Instant AHORA = Instant.parse("2026-11-23T12:00:00Z");

    private final InMemoryPedidoRepository repositorio = new InMemoryPedidoRepository();
    private final CrearPedidoService servicio = new CrearPedidoService(
            repositorio, () -> ID_FIJO, Clock.fixed(AHORA, ZoneOffset.UTC));

    @Test
    void crea_un_pedido_en_borrador_con_id_y_fecha_deterministas() {
        ClienteId cliente = new ClienteId(UUID.randomUUID());

        PedidoId id = servicio.ejecutar(new CrearPedidoCommand(cliente));

        assertThat(id).isEqualTo(ID_FIJO);
        Pedido guardado = repositorio.buscarPorId(ID_FIJO).orElseThrow();
        assertThat(guardado.estado()).isEqualTo(EstadoPedido.BORRADOR);
        assertThat(guardado.clienteId()).isEqualTo(cliente);
        assertThat(guardado.creadoEn()).isEqualTo(AHORA);
    }
}
```

Sobre el punto 2: **sí tiene sentido** como test de contrato: "modificar un objeto leído sin llamar a `guardar` no cambia lo almacenado". Es exactamente la propiedad que hace fiel al fake (la `copia`). Ojo: contra JPA con `sincronizar()` (flush+clear) se cumple porque la entidad queda *detached*; **sin** `clear()` y dentro de una transacción, Hibernate haría *dirty checking* sobre la entidad JPA... aunque como el adaptador mapea a un `Pedido` de dominio nuevo, la modificación del objeto de dominio nunca llega a la entidad. Es justo el tipo de matiz que un contrato hace explícito.

</details>

---

## Referencias

- Mockito: https://site.mockito.org/ (Javadoc de `Mockito`, `BDDMockito`, `ArgumentCaptor`).
- AssertJ: https://assertj.github.io/doc/
- Martin Fowler, *Mocks Aren't Stubs* (2007): https://martinfowler.com/articles/mocksArentStubs.html
- Martin Fowler, *Contract Test*: https://martinfowler.com/bliki/ContractTest.html
- Gerard Meszaros, Test Double: http://xunitpatterns.com/Test%20Double.html
- Freeman, Pryce, Mackinnon, Walnes, *Mock Roles, not Objects* (OOPSLA 2004).
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023); repo https://github.com/thombergs/buckpal
- Alistair Cockburn, *Hexagonal Architecture* (2005) — los puertos como punto de sustitución para tests: https://alistair.cockburn.us/hexagonal-architecture/

---

## Mantra

> *"Un fake sin test de contrato es una mentira piadosa. Con contrato, es la mejor herramienta de tu suite."*
