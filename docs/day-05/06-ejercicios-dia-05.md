# Día 5 — Bloque 6: Taller — La suite completa de `CancelarPedido`

> *"Code without tests is broken by design."* — Jacob Kaplan-Moss

---

## 1. El objetivo en una frase

Escribir **todos los tests** del caso de uso *Cancelar pedido*, capa a capa, aplicando lo visto hoy: dominio puro, caso de uso con fakes y con Mockito, adaptador web con `@WebMvcTest`, contrato de persistencia, un E2E y una regla ArchUnit.

Al final tendrás un "corte vertical" de la pirámide para un caso de uso real:

```
                       ┌──────────────┐
  Paso 6               │   ArchUnit   │  la regla que impide hacer trampas
                       └──────────────┘
                     ┌──────────────────┐
  Paso 5             │  E2E PedidoApiIT │  1 test: cancelar por HTTP contra PostgreSQL
                     └──────────────────┘
               ┌──────────────────────────────┐
  Paso 4       │ PedidoControllerTest (cancel)│  204 / 404 / 409
               └──────────────────────────────┘
          ┌────────────────────────────────────────┐
  Paso 3  │ Contrato PedidoRepository (ya existe)  │  reutilizado: 0 líneas nuevas
          └────────────────────────────────────────┘
      ┌────────────────────────────────────────────────┐
  P.2 │ CancelarPedidoServiceTest (fakes + Mockito)    │  orquestación y errores
      └────────────────────────────────────────────────┘
  ┌────────────────────────────────────────────────────────┐
  P.1 │ PedidoTest.AlCancelar (dominio)                    │  reglas de negocio
  └────────────────────────────────────────────────────────┘
```

**Duración:** ~15 min en clase (pasos 1 y 2) + el resto en casa (~1h).

---

## 2. Especificación del caso de uso

> **Como** cliente, **quiero** cancelar un pedido **para** no recibirlo ni pagarlo.

Reglas de negocio:

1. Se puede cancelar un pedido en `BORRADOR`, `CONFIRMADO` o `PAGADO`.
2. **No** se puede cancelar un pedido `ENVIADO` → `TransicionDeEstadoInvalidaException`.
3. Cancelar un pedido ya `CANCELADO` también es una transición inválida.
4. Al cancelar: se guarda el pedido, se publica `PedidoCancelado(pedidoId, ocurridoEn)` con la hora del `Clock` y se notifica al cliente (`notificarCancelacion`).
5. Si el pedido no existe → `PedidoNoEncontradoException`, sin efectos.
6. Si el dominio rechaza la cancelación → no se guarda, no se publica, no se notifica.

API HTTP: `POST /api/pedidos/{id}/cancelacion` → `204 No Content` | `404` | `409`.

### Código de producción de partida

```java
// application.port.in
public record CancelarPedidoCommand(PedidoId pedidoId) {
    public CancelarPedidoCommand {
        Objects.requireNonNull(pedidoId, "pedidoId");
    }
}

public interface CancelarPedidoUseCase {
    void ejecutar(CancelarPedidoCommand command);
}
```

```java
// application.service
public class CancelarPedidoService implements CancelarPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final PublicadorDeEventos publicadorDeEventos;
    private final NotificadorPedidos notificador;
    private final Clock clock;

    public CancelarPedidoService(PedidoRepository pedidoRepository,
                                 PublicadorDeEventos publicadorDeEventos,
                                 NotificadorPedidos notificador,
                                 Clock clock) {
        this.pedidoRepository = pedidoRepository;
        this.publicadorDeEventos = publicadorDeEventos;
        this.notificador = notificador;
        this.clock = clock;
    }

    @Override
    public void ejecutar(CancelarPedidoCommand command) {
        Pedido pedido = pedidoRepository.buscarPorId(command.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(command.pedidoId()));

        pedido.cancelar();
        pedidoRepository.guardar(pedido);

        publicadorDeEventos.publicar(new PedidoCancelado(pedido.id(), clock.instant()));
        notificador.notificarCancelacion(pedido);
    }
}
```

```java
// infrastructure.config.BeanConfiguration (extracto)
@Bean
CancelarPedidoUseCase cancelarPedidoUseCase(PedidoRepository pedidoRepository,
                                            PublicadorDeEventos publicadorDeEventos,
                                            NotificadorPedidos notificador,
                                            Clock clock) {
    return new CancelarPedidoService(pedidoRepository, publicadorDeEventos, notificador, clock);
}
```

```java
// infrastructure.adapter.in.web.PedidoController (nuevo endpoint)
@PostMapping("/{id}/cancelacion")
public ResponseEntity<Void> cancelar(@PathVariable UUID id) {
    cancelarPedido.ejecutar(new CancelarPedidoCommand(new PedidoId(id)));
    return ResponseEntity.noContent().build();
}
```

---

## Paso 1 — Dominio: `Pedido.cancelar()`

**Enunciado.** En `PedidoTest`, crea (o completa) el `@Nested` `AlCancelar` con:

- Un `@ParameterizedTest` que recorra los estados cancelables.
- Un `@ParameterizedTest` que recorra los estados **no** cancelables y compruebe tipo de excepción y que el estado **no cambia**.
- Un test que compruebe que cancelar no altera las líneas ni el total.

**Pista:** `@EnumSource(names = {...})` y `PedidoMother.enEstado(estado)`.

<details><summary>Solución</summary>

```java
package com.curso.pedidos.domain.model;

import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PedidoCancelacionTest {

    @Nested
    @DisplayName("al cancelar")
    class AlCancelar {

        @ParameterizedTest(name = "desde {0} pasa a CANCELADO")
        @EnumSource(value = EstadoPedido.class, names = {"BORRADOR", "CONFIRMADO", "PAGADO"})
        void los_estados_previos_al_envio_se_cancelan(EstadoPedido estado) {
            Pedido pedido = PedidoMother.enEstado(estado);

            pedido.cancelar();

            assertThat(pedido.estado()).isEqualTo(EstadoPedido.CANCELADO);
        }

        @ParameterizedTest(name = "desde {0} lanza TransicionDeEstadoInvalida")
        @EnumSource(value = EstadoPedido.class, names = {"ENVIADO", "CANCELADO"})
        void enviado_o_cancelado_no_se_cancela(EstadoPedido estado) {
            Pedido pedido = PedidoMother.enEstado(estado);

            assertThatThrownBy(pedido::cancelar)
                    .isInstanceOf(TransicionDeEstadoInvalidaException.class)
                    .hasMessageContaining(estado.name());

            assertThat(pedido.estado()).as("el estado no debe cambiar").isEqualTo(estado);
        }

        @Test
        void cancelar_no_altera_lineas_ni_total() {
            Pedido pedido = PedidoMother.borradorConLineas();
            Dinero totalAntes = pedido.total();

            pedido.cancelar();

            assertThat(pedido.lineas()).hasSize(2);
            assertThat(pedido.total()).isEqualTo(totalAntes);
        }
    }
}
```

Reflexión: ¿por qué `@EnumSource` con `names` explícitos y no `mode = EXCLUDE`? Porque si mañana se añade un estado `DEVUELTO`, **queremos que alguien decida** conscientemente si es cancelable. Con `EXCLUDE` el nuevo estado entraría automáticamente en el grupo "cancelables" y el test fallaría (bien) o pasaría (mal) según la implementación. Las dos opciones son defendibles; lo importante es elegir a propósito.

</details>

---

## Paso 2 — Caso de uso: `CancelarPedidoService`

**Enunciado.**

a) Escribe `CancelarPedidoServiceTest` con **fakes** (`InMemoryPedidoRepository`, `PublicadorDeEventosEspia`) y un `Clock.fixed`, cubriendo las reglas 4, 5 y 6.

b) Escribe **un** test con Mockito + `ArgumentCaptor` que compruebe que, si `guardar` lanza una excepción, **no** se publica ni se notifica.

**Pista:** para el notificador, un spy con clase anónima que guarde los pedidos en una lista (como en el bloque 3).

<details><summary>Solución (a): con fakes</summary>

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.CancelarPedidoCommand;
import com.curso.pedidos.application.port.out.InMemoryPedidoRepository;
import com.curso.pedidos.application.port.out.NotificadorPedidos;
import com.curso.pedidos.application.port.out.PublicadorDeEventosEspia;
import com.curso.pedidos.domain.event.PedidoCancelado;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.PedidoMother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Cancelar pedido")
class CancelarPedidoServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-23T16:30:00Z");

    private InMemoryPedidoRepository repositorio;
    private PublicadorDeEventosEspia publicador;
    private List<Pedido> notificados;
    private CancelarPedidoService servicio;

    @BeforeEach
    void setUp() {
        repositorio = new InMemoryPedidoRepository();
        publicador = new PublicadorDeEventosEspia();
        notificados = new ArrayList<>();
        NotificadorPedidos notificador = new NotificadorPedidos() {
            @Override public void notificarConfirmacion(Pedido pedido) { }
            @Override public void notificarCancelacion(Pedido pedido) { notificados.add(pedido); }
        };
        servicio = new CancelarPedidoService(repositorio, publicador, notificador,
                Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("cuando el pedido es cancelable")
    class CuandoEsCancelable {

        private Pedido pedido;

        @BeforeEach
        void dadoUnPedidoConfirmado() {
            pedido = PedidoMother.confirmado();
            repositorio.guardar(pedido);
        }

        @Test
        void queda_cancelado_en_el_repositorio() {
            servicio.ejecutar(new CancelarPedidoCommand(pedido.id()));

            assertThat(repositorio.buscarPorId(pedido.id()))
                    .get().extracting(Pedido::estado).isEqualTo(EstadoPedido.CANCELADO);
        }

        @Test
        void publica_PedidoCancelado_con_la_hora_del_reloj() {
            servicio.ejecutar(new CancelarPedidoCommand(pedido.id()));

            assertThat(publicador.publicados())
                    .containsExactly(new PedidoCancelado(pedido.id(), AHORA));
        }

        @Test
        void notifica_la_cancelacion_al_cliente() {
            servicio.ejecutar(new CancelarPedidoCommand(pedido.id()));

            assertThat(notificados).extracting(Pedido::id).containsExactly(pedido.id());
            assertThat(notificados.get(0).estado()).isEqualTo(EstadoPedido.CANCELADO);
        }
    }

    @Nested
    @DisplayName("cuando el pedido no existe")
    class CuandoNoExiste {

        @Test
        void lanza_PedidoNoEncontrado_sin_efectos() {
            PedidoId inexistente = new PedidoId(UUID.randomUUID());

            assertThatThrownBy(() -> servicio.ejecutar(new CancelarPedidoCommand(inexistente)))
                    .isInstanceOf(PedidoNoEncontradoException.class);

            assertThat(repositorio.numeroDePedidos()).isZero();
            assertThat(publicador.publicados()).isEmpty();
            assertThat(notificados).isEmpty();
        }
    }

    @Nested
    @DisplayName("cuando el dominio rechaza la cancelación")
    class CuandoElDominioLaRechaza {

        @Test
        void un_pedido_enviado_no_se_cancela_ni_se_publica_nada() {
            Pedido enviado = PedidoMother.enviado();
            repositorio.guardar(enviado);

            assertThatThrownBy(() -> servicio.ejecutar(new CancelarPedidoCommand(enviado.id())))
                    .isInstanceOf(TransicionDeEstadoInvalidaException.class);

            assertThat(repositorio.buscarPorId(enviado.id()))
                    .get().extracting(Pedido::estado).isEqualTo(EstadoPedido.ENVIADO);
            assertThat(publicador.publicados()).isEmpty();
            assertThat(notificados).isEmpty();
        }
    }
}
```

</details>

<details><summary>Solución (b): fallo de persistencia con Mockito</summary>

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.CancelarPedidoCommand;
import com.curso.pedidos.application.port.out.NotificadorPedidos;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoMother;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class CancelarPedidoServiceMockitoTest {

    @Mock PedidoRepository pedidoRepository;
    @Mock PublicadorDeEventos publicador;
    @Mock NotificadorPedidos notificador;
    @Captor ArgumentCaptor<Pedido> pedidoCaptor;

    @Test
    void si_falla_la_persistencia_no_se_publica_ni_se_notifica() {
        CancelarPedidoService servicio = new CancelarPedidoService(pedidoRepository, publicador, notificador,
                Clock.fixed(Instant.parse("2026-11-23T16:30:00Z"), ZoneOffset.UTC));
        Pedido pedido = PedidoMother.pagado();
        given(pedidoRepository.buscarPorId(pedido.id())).willReturn(Optional.of(pedido));
        willThrow(new IllegalStateException("BD caída")).given(pedidoRepository).guardar(any());

        assertThatThrownBy(() -> servicio.ejecutar(new CancelarPedidoCommand(pedido.id())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BD caída");

        then(pedidoRepository).should().guardar(pedidoCaptor.capture());
        assertThat(pedidoCaptor.getValue().estado())
                .as("se intentó guardar ya cancelado")
                .isEqualTo(EstadoPedido.CANCELADO);
        then(publicador).shouldHaveNoInteractions();
        then(notificador).shouldHaveNoInteractions();
    }
}
```

Pregunta para debatir: el pedido en memoria ha quedado `CANCELADO` aunque la BD haya fallado. ¿Es un problema? En este servicio no, porque la instancia se descarta al terminar la petición. Pero si publicáramos el evento **antes** de guardar, habríamos anunciado una cancelación que no existe. Por eso el orden "guardar → publicar" importa (y en el día 6 veremos el patrón *Transactional Outbox* para hacerlo atómico).

</details>

---

## Paso 3 — Persistencia: el contrato ya lo cubre

**Enunciado.** Comprueba que **no necesitas ningún test nuevo** en la capa de persistencia para este caso de uso. ¿Qué test del `PedidoRepositoryContractTest` garantiza que un pedido cancelado se guarda y se recupera como `CANCELADO` en PostgreSQL?

Opcional: añade al contrato un `@ParameterizedTest` que guarde y recupere un pedido **en cada estado** de `EstadoPedido`.

**Pista:** el `@ParameterizedTest` funciona igual en una clase abstracta heredada.

<details><summary>Solución</summary>

`guardar_dos_veces_el_mismo_pedido_actualiza_y_no_duplica` prueba que una transición de estado se persiste (con `CONFIRMADO`). Para cubrir **todos** los estados, incluido `CANCELADO`:

```java
// En PedidoRepositoryContractTest
@ParameterizedTest(name = "persiste y recupera el estado {0}")
@EnumSource(EstadoPedido.class)
void persiste_cualquier_estado(EstadoPedido estado) {
    Pedido pedido = unPedido().conIdAleatorio().conUnaLineaCualquiera().enEstado(estado).build();

    repositorio().guardar(pedido);
    sincronizar();

    assertThat(repositorio().buscarPorId(pedido.id()))
            .get().extracting(Pedido::estado).isEqualTo(estado);
}
```

Imports adicionales: `org.junit.jupiter.params.ParameterizedTest` y `org.junit.jupiter.params.provider.EnumSource`.

Este test, ejecutado contra PostgreSQL, caza el clásico `@Enumerated(ORDINAL)` y una columna `estado VARCHAR(9)` demasiado corta para `CONFIRMADO` (10 caracteres). Ejecutado contra el fake, pasa trivialmente. **Ese es el valor del contrato.**

</details>

---

## Paso 4 — Adaptador web: `@WebMvcTest`

**Enunciado.** Añade a `PedidoControllerTest` un `@Nested` `CancelarPedido` con tres tests: 204 (y el comando correcto llega al puerto), 404 y 409.

**Pista:** `then(cancelarPedido).should().ejecutar(new CancelarPedidoCommand(new PedidoId(ID)))` funciona porque el comando es un `record`.

<details><summary>Solución</summary>

```java
// Dentro de PedidoControllerTest (@WebMvcTest(PedidoController.class)),
// que ya declara @MockBean CancelarPedidoUseCase cancelarPedido;

@Nested
class CancelarPedido {

    @Test
    void devuelve_204_y_envia_el_comando_al_caso_de_uso() throws Exception {
        mockMvc.perform(post("/api/pedidos/{id}/cancelacion", ID))
                .andExpect(status().isNoContent());

        then(cancelarPedido).should().ejecutar(new CancelarPedidoCommand(new PedidoId(ID)));
    }

    @Test
    void pedido_inexistente_devuelve_404() throws Exception {
        willThrow(new PedidoNoEncontradoException(new PedidoId(ID)))
                .given(cancelarPedido).ejecutar(any());

        mockMvc.perform(post("/api/pedidos/{id}/cancelacion", ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void pedido_enviado_devuelve_409_con_el_detalle() throws Exception {
        willThrow(new TransicionDeEstadoInvalidaException("No se puede cancelar un pedido ENVIADO"))
                .given(cancelarPedido).ejecutar(any());

        mockMvc.perform(post("/api/pedidos/{id}/cancelacion", ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("No se puede cancelar un pedido ENVIADO"));
    }
}
```

Imports adicionales respecto al bloque 4: `com.curso.pedidos.application.port.in.CancelarPedidoCommand`.

Fíjate en que **no** probamos aquí "un pedido PAGADO se puede cancelar": eso es dominio. El controller solo traduce.

</details>

---

## Paso 5 — End-to-end: un único test

**Enunciado.** En `PedidoApiIT` (que extiende `AbstractIntegrationTest`), añade **un** test que:

1. Guarde por el puerto un pedido `PAGADO`.
2. Lo cancele por HTTP.
3. Compruebe el 204, que en BD está `CANCELADO` y que se publicó un `PedidoCancelado`.

**Pista:** `rest.postForEntity("/api/pedidos/{id}/cancelacion", null, Void.class, id)`.

<details><summary>Solución</summary>

```java
@Test
void cancelar_un_pedido_pagado_por_http() {
    Pedido pagado = unPedido().conIdAleatorio()
            .conLinea("SKU-MONITOR", 1, "199.00")
            .enEstado(EstadoPedido.PAGADO)
            .build();
    pedidoRepository.guardar(pagado);

    ResponseEntity<Void> respuesta = rest.postForEntity(
            "/api/pedidos/{id}/cancelacion", null, Void.class, pagado.id().valor());

    assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(pedidoRepository.buscarPorId(pagado.id()))
            .get().extracting(Pedido::estado).isEqualTo(EstadoPedido.CANCELADO);
    then(publicadorDeEventos).should().publicar(isA(PedidoCancelado.class));
    then(notificador).should().notificarCancelacion(any());
}
```

Imports adicionales: `com.curso.pedidos.domain.event.PedidoCancelado`.

¿Por qué solo uno? El E2E demuestra que `BeanConfiguration` cablea `CancelarPedidoService` con el adaptador JPA real y que la ruta está expuesta. Los casos 404/409 ya están cubiertos por el `@WebMvcTest` y por el dominio. Un segundo E2E aquí cuesta segundos y no protege nada nuevo.

> Trampa: los `@MockBean` de `AbstractIntegrationTest` se **resetean** después de cada test (`MockReset.AFTER` por defecto), así que las verificaciones de un test no se contaminan con las del anterior aunque el contexto sea compartido.

</details>

---

## Paso 6 — ArchUnit: que nadie se salte el caso de uso

**Enunciado.** Un compañero propone "para ir rápido" que `PedidoController` llame directamente a `PedidoRepository` y haga `pedido.cancelar()` él mismo. Escribe una regla ArchUnit que lo impida: **los adaptadores de entrada no pueden depender de puertos de salida**.

**Pista:** `noClasses().that().resideInAPackage("..adapter.in..").should().dependOnClassesThat().resideInAPackage("..application.port.out..")`.

<details><summary>Solución</summary>

```java
@ArchTest
static final ArchRule los_adaptadores_de_entrada_solo_hablan_con_puertos_de_entrada =
        noClasses().that().resideInAPackage("..infrastructure.adapter.in..")
                .should().dependOnClassesThat().resideInAPackage("..application.port.out..")
                .orShould().dependOnClassesThat().resideInAPackage("..application.service..")
                .because("un adaptador de entrada invoca casos de uso (puertos de entrada), "
                        + "no repositorios ni implementaciones concretas");
```

Con esta regla, el atajo del compañero rompe el build con un mensaje que explica **por qué**. La arquitectura deja de ser una opinión y pasa a ser un test.

</details>

---

## 3. Checklist de la suite

| Capa             | Test                                      | Tipo de doble                 | Sufijo | ¿Hecho? |
|------------------|-------------------------------------------|-------------------------------|--------|---------|
| Dominio          | `PedidoTest.AlCancelar`                   | Ninguno                       | `Test` | ☐       |
| Aplicación       | `CancelarPedidoServiceTest`               | Fake + spy                    | `Test` | ☐       |
| Aplicación       | `CancelarPedidoServiceMockitoTest`        | Mock + `ArgumentCaptor`       | `Test` | ☐       |
| Puerto (contrato)| `persiste_cualquier_estado`               | Fake / PostgreSQL real        | `Test` / `IT` | ☐ |
| Adaptador web    | `PedidoControllerTest.CancelarPedido`     | `@MockBean` del caso de uso   | `Test` | ☐       |
| Sistema          | `PedidoApiIT.cancelar_un_pedido_pagado_por_http` | Solo terceros          | `IT`   | ☐       |
| Arquitectura     | Regla `adapter.in` ↛ `port.out`           | —                             | `Test` | ☐       |

---

## 4. Preguntas de repaso

1. ¿Por qué en hexagonal se dice que "el doble de test vive en el puerto"?
2. Diferencia entre **stub** y **mock** según Meszaros. ¿Qué es un `mock(...)` de Mockito usado solo con `when/thenReturn`?
3. ¿Qué problema resuelve que `InMemoryPedidoRepository` devuelva **copias** de los pedidos?
4. ¿Qué garantiza un test de contrato de puerto que no garantizan los tests del fake por separado?
5. ¿Por qué hace falta `entityManager.flush()` **y** `clear()` en el `sincronizar()` del IT de persistencia?
6. ¿Qué aporta `@ServiceConnection` frente a `@DynamicPropertySource`? ¿Desde qué versión de Spring Boot existe?
7. En Spring Boot 3.3 usamos `@MockBean`. ¿Qué cambia en 3.4?
8. ¿Por qué no sirve `@Transactional` para limpiar datos en un test `@SpringBootTest(webEnvironment = RANDOM_PORT)`?
9. Nombra tres cosas que invalidan la caché de contextos de Spring.
10. ¿Por qué la cobertura de líneas no mide la calidad de los tests? ¿Qué herramienta lo hace mejor?
11. ¿Qué diferencia hay entre Surefire y Failsafe, y qué convención de nombres usamos?
12. ¿Cuándo preferirías un honeycomb a una pirámide?

<details><summary>Respuestas</summary>

1. Porque el puerto es la frontera entre lo que controlamos (dominio + aplicación, rápido y determinista) y el I/O. Doblar ahí permite probar el núcleo sin infraestructura, y el adaptador real se prueba aparte contra su tecnología.
2. Un **stub** devuelve respuestas preprogramadas (verificación de **estado**); un **mock** lleva expectativas sobre cómo se le llama (verificación de **comportamiento**). Un `mock()` de Mockito usado solo con `when/thenReturn` es, en rigor, un stub.
3. Evita que el servicio "funcione" sin llamar a `guardar()` por compartir referencia con el almacén. Con copias, el fake se comporta como una BD real: lo no guardado no existe.
4. Que el fake y el adaptador real **se comportan igual** ante las mismas operaciones. Sin contrato, el fake puede divergir y dar falsos verdes en todos los tests de aplicación.
5. `flush()` fuerza a Hibernate a ejecutar los SQL (y que salten constraints). `clear()` vacía la caché de primer nivel para que la siguiente lectura vaya a PostgreSQL y pase por el mapeo real; sin él, recibirías la misma instancia en memoria.
6. Spring Boot obtiene automáticamente la conexión (URL, usuario, contraseña) del contenedor y crea los `ConnectionDetails`, sin escribir propiedades a mano. Existe desde **Spring Boot 3.1**.
7. `@MockBean`/`@SpyBean` quedan *deprecated* en favor de `@MockitoBean`/`@MockitoSpyBean` (de Spring Framework 6.2, paquete `org.springframework.test.context.bean.override.mockito`).
8. Porque la petición HTTP se procesa en **otro hilo** del servidor embebido, con su propia transacción. El rollback del test no la afecta.
9. Distinto conjunto de `@MockBean`, distintos perfiles/propiedades, distintas clases de configuración o `@Import`, y `@DirtiesContext` (que la destruye explícitamente).
10. Porque mide qué se **ejecutó**, no qué se **verificó**. PIT (mutation testing) mide si los tests detectan cambios en el código.
11. Surefire ejecuta en la fase `test` los `*Test` (rápidos, sin Docker); Failsafe ejecuta en `integration-test`/`verify` los `*IT` y no rompe el build hasta `verify`, permitiendo limpieza posterior.
12. Cuando el servicio tiene poca lógica propia y el riesgo está en la integración (microservicio fino, "pegamento" entre sistemas, CRUD).

</details>

---

## Referencias

- JUnit 5 User Guide: https://junit.org/junit5/docs/current/user-guide/
- AssertJ: https://assertj.github.io/doc/
- Mockito: https://site.mockito.org/
- Testcontainers for Java: https://java.testcontainers.org/
- ArchUnit User Guide: https://www.archunit.org/userguide/html/000_Index.html
- Spring Framework, Testing: https://docs.spring.io/spring-framework/reference/testing.html
- Gerard Meszaros, Test Double: http://xunitpatterns.com/Test%20Double.html
- Ham Vocke, *The Practical Test Pyramid*: https://martinfowler.com/articles/practical-test-pyramid.html
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023); repo https://github.com/thombergs/buckpal

---

## Mantra

> *"Cada capa prueba lo suyo y confía en las demás. Una regla, un test, en el sitio más barato posible."*
