# Día 7 — Bloque 6: Ejercicios — CancelarPedido dos veces, y debate

> *"In theory, there is no difference between theory and practice. In
> practice, there is."* — atribuida a varios autores (Yogi Berra, Jan van de
> Snepscheut…)

---

## 1. Objetivo

Hasta ahora has **leído** la comparación. Ahora la vas a **medir con tus
manos**: implementarás el mismo caso de uso, `CancelarPedido`, en **VSA
extremo** y en **hexagonal**, contarás ficheros, líneas y tests, y luego
harás un cambio de requisitos sobre las dos versiones para ver dónde duele.

Reglas de negocio (las del dominio del curso):

- Se puede cancelar un pedido en `BORRADOR`, `CONFIRMADO` o `PAGADO`.
- **No** se puede cancelar un pedido `ENVIADO`.
- Cancelar un pedido ya `CANCELADO` es una transición inválida.
- Al cancelar se publica `PedidoCancelado(pedidoId, ocurridoEn)`.

API: `POST /pedidos/{id}/cancelacion` → `200` con `{pedidoId, estado}`;
`404` si no existe; `409` si la transición no es válida.

Organización sugerida (35 min): por parejas, una persona hace VSA y la otra
hexagonal (15 min); se intercambian y revisan (5 min); medición y cambio de
requisitos (5 min); debate (10 min).

---

## Ejercicio 1 — CancelarPedido en VSA extremo

Crea `features/cancelarpedido/CancelarPedido.java` con `Command`, `Response`,
evento, `Endpoint`, `Handler` y errores, siguiendo el modelo de
[`ConfirmarPedido.java`](03-vertical-slice-architecture.md). Escribe su test de
integración con MockMvc + Testcontainers con al menos: caso feliz, 404, 409 por
`ENVIADO`.

Pista: no necesitas tocar el esquema; solo cambia la columna `estado`.

<details><summary>Solución</summary>

```java
package com.curso.pedidos.features.cancelarpedido;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Feature: cancelar un pedido. No se cancela un pedido ENVIADO ni uno ya CANCELADO. */
public final class CancelarPedido {

    private CancelarPedido() {
    }

    public record Command(UUID pedidoId) {
    }

    public record Response(UUID pedidoId, String estado) {
    }

    public record PedidoCancelado(UUID pedidoId, Instant ocurridoEn) {
    }

    private static final Set<String> CANCELABLES = Set.of("BORRADOR", "CONFIRMADO", "PAGADO");

    @RestController
    public static class Endpoint {

        private final Handler handler;

        public Endpoint(Handler handler) {
            this.handler = handler;
        }

        @PostMapping("/pedidos/{id}/cancelacion")
        public Response cancelar(@PathVariable UUID id) {
            return handler.handle(new Command(id));
        }

        @ExceptionHandler(NoEncontrado.class)
        ProblemDetail noEncontrado(NoEncontrado e) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        }

        @ExceptionHandler(Rechazado.class)
        ProblemDetail rechazado(Rechazado e) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @Component
    public static class Handler {

        private final JdbcClient jdbc;
        private final ApplicationEventPublisher eventos;
        private final Clock clock;

        public Handler(JdbcClient jdbc, ApplicationEventPublisher eventos, Clock clock) {
            this.jdbc = jdbc;
            this.eventos = eventos;
            this.clock = clock;
        }

        @Transactional
        public Response handle(Command command) {
            UUID id = command.pedidoId();

            String estado = jdbc.sql("SELECT estado FROM pedidos WHERE id = :id FOR UPDATE")
                    .param("id", id)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new NoEncontrado(id));

            if (!CANCELABLES.contains(estado)) {
                throw new Rechazado("No se puede cancelar un pedido en estado " + estado);
            }

            jdbc.sql("UPDATE pedidos SET estado = 'CANCELADO', version = version + 1 WHERE id = :id")
                .param("id", id)
                .update();

            eventos.publishEvent(new PedidoCancelado(id, clock.instant()));
            return new Response(id, "CANCELADO");
        }
    }

    static final class NoEncontrado extends RuntimeException {
        NoEncontrado(UUID id) {
            super("No existe el pedido " + id);
        }
    }

    static final class Rechazado extends RuntimeException {
        Rechazado(String motivo) {
            super(motivo);
        }
    }
}
```

Test (asumiendo una anotación compuesta `@PostgresTest` en `shared` que agrupa
`@SpringBootTest`, `@AutoConfigureMockMvc`, `@RecordApplicationEvents` y la
configuración del contenedor con `@ServiceConnection`):

```java
package com.curso.pedidos.features.cancelarpedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.curso.pedidos.shared.PostgresTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

@PostgresTest
class CancelarPedidoTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ApplicationEvents eventos;

    @ParameterizedTest
    @ValueSource(strings = {"BORRADOR", "CONFIRMADO", "PAGADO"})
    void cancela_pedidos_en_estados_cancelables(String estadoInicial) throws Exception {
        UUID id = pedido(estadoInicial);

        mvc.perform(post("/pedidos/{id}/cancelacion", id))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.estado").value("CANCELADO"));

        assertThat(estado(id)).isEqualTo("CANCELADO");
        assertThat(eventos.stream(CancelarPedido.PedidoCancelado.class)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENVIADO", "CANCELADO"})
    void rechaza_con_409(String estadoInicial) throws Exception {
        UUID id = pedido(estadoInicial);

        mvc.perform(post("/pedidos/{id}/cancelacion", id))
           .andExpect(status().isConflict());

        assertThat(estado(id)).isEqualTo(estadoInicial);
        assertThat(eventos.stream(CancelarPedido.PedidoCancelado.class)).isEmpty();
    }

    @Test
    void devuelve_404_si_no_existe() throws Exception {
        mvc.perform(post("/pedidos/{id}/cancelacion", UUID.randomUUID()))
           .andExpect(status().isNotFound());
    }

    private UUID pedido(String estado) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO pedidos (id, cliente_id, estado, creado_en) VALUES (:id, :c, :e, :t)")
            .param("id", id).param("c", UUID.randomUUID()).param("e", estado)
            .param("t", Timestamp.from(Instant.now()))
            .update();
        return id;
    }

    private String estado(UUID id) {
        return jdbc.sql("SELECT estado FROM pedidos WHERE id = :id")
                   .param("id", id).query(String.class).single();
    }
}
```

`ApplicationEvents` se reinicia en cada método de test, así que los conteos de
eventos son por test.
</details>

---

## Ejercicio 2 — CancelarPedido en hexagonal

Implementa el mismo caso de uso con la estructura canónica del curso:
`CancelarPedidoUseCase`, `CancelarPedidoCommand`, `CancelarPedidoService`,
método en `PedidoController`, DTO de respuesta y `@Bean` en
`BeanConfiguration`. La regla vive en `Pedido.cancelar()`. Escribe test de
dominio y test unitario del servicio.

<details><summary>Solución</summary>

Dominio (si aún no lo tenías del día 2):

```java
// domain/model/Pedido.java (fragmento)
public void cancelar() {
    if (estado == EstadoPedido.ENVIADO || estado == EstadoPedido.CANCELADO) {
        throw new TransicionDeEstadoInvalidaException(estado, EstadoPedido.CANCELADO);
    }
    this.estado = EstadoPedido.CANCELADO;
}
```

Puerto de entrada y comando:

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;
import java.util.Objects;

public record CancelarPedidoCommand(PedidoId pedidoId) {
    public CancelarPedidoCommand {
        Objects.requireNonNull(pedidoId, "pedidoId es obligatorio");
    }
}
```

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.Pedido;

public interface CancelarPedidoUseCase {
    Pedido ejecutar(CancelarPedidoCommand command);
}
```

Servicio:

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.CancelarPedidoCommand;
import com.curso.pedidos.application.port.in.CancelarPedidoUseCase;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.PedidoCancelado;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.Pedido;
import java.time.Clock;

public class CancelarPedidoService implements CancelarPedidoUseCase {

    private final PedidoRepository pedidos;
    private final PublicadorDeEventos eventos;
    private final Clock clock;

    public CancelarPedidoService(PedidoRepository pedidos, PublicadorDeEventos eventos, Clock clock) {
        this.pedidos = pedidos;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Override
    public Pedido ejecutar(CancelarPedidoCommand command) {
        Pedido pedido = pedidos.buscarPorId(command.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(command.pedidoId()));
        pedido.cancelar();
        pedidos.guardar(pedido);
        eventos.publicar(new PedidoCancelado(pedido.id(), clock.instant()));
        return pedido;
    }
}
```

Adaptador web:

```java
// infrastructure/adapter/in/web/PedidoCanceladoResponse.java
public record PedidoCanceladoResponse(UUID pedidoId, String estado) {
    static PedidoCanceladoResponse desde(Pedido p) {
        return new PedidoCanceladoResponse(p.id().valor(), p.estado().name());
    }
}

// infrastructure/adapter/in/web/PedidoController.java (método nuevo)
@PostMapping("/{id}/cancelacion")
public PedidoCanceladoResponse cancelar(@PathVariable UUID id) {
    var pedido = cancelarPedido.ejecutar(new CancelarPedidoCommand(new PedidoId(id)));
    return PedidoCanceladoResponse.desde(pedido);
}
```

Cableado:

```java
// infrastructure/config/BeanConfiguration.java (fragmento)
@Bean
CancelarPedidoUseCase cancelarPedidoUseCase(PedidoRepository pedidos, PublicadorDeEventos eventos,
                                            Clock clock, TransactionTemplate tx) {
    var service = new CancelarPedidoService(pedidos, eventos, clock);
    return command -> tx.execute(status -> service.ejecutar(command));
}
```

Tests:

```java
class PedidoCancelarTest {

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"ENVIADO", "CANCELADO"})
    void no_se_puede_cancelar_desde(EstadoPedido estado) {
        Pedido pedido = PedidoMother.enEstado(estado);
        assertThatThrownBy(pedido::cancelar)
                .isInstanceOf(TransicionDeEstadoInvalidaException.class);
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"BORRADOR", "CONFIRMADO", "PAGADO"})
    void se_puede_cancelar_desde(EstadoPedido estado) {
        Pedido pedido = PedidoMother.enEstado(estado);
        pedido.cancelar();
        assertThat(pedido.estado()).isEqualTo(EstadoPedido.CANCELADO);
    }
}

class CancelarPedidoServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-23T17:00:00Z");
    private final PedidoRepository pedidos = mock(PedidoRepository.class);
    private final PublicadorDeEventos eventos = mock(PublicadorDeEventos.class);
    private final CancelarPedidoService service =
            new CancelarPedidoService(pedidos, eventos, Clock.fixed(AHORA, ZoneOffset.UTC));

    @Test
    void cancela_guarda_y_publica() {
        Pedido pedido = PedidoMother.enEstado(EstadoPedido.CONFIRMADO);
        when(pedidos.buscarPorId(pedido.id())).thenReturn(Optional.of(pedido));

        service.ejecutar(new CancelarPedidoCommand(pedido.id()));

        verify(pedidos).guardar(pedido);
        verify(eventos).publicar(new PedidoCancelado(pedido.id(), AHORA));
    }

    @Test
    void no_publica_si_el_dominio_rechaza() {
        Pedido enviado = PedidoMother.enEstado(EstadoPedido.ENVIADO);
        when(pedidos.buscarPorId(enviado.id())).thenReturn(Optional.of(enviado));

        assertThatThrownBy(() -> service.ejecutar(new CancelarPedidoCommand(enviado.id())))
                .isInstanceOf(TransicionDeEstadoInvalidaException.class);
        verify(pedidos, never()).guardar(any());
        verifyNoInteractions(eventos);
    }
}
```

`PedidoMother.enEstado(...)` reconstruye un pedido en el estado pedido (vía el
método de reconstitución que usa el adaptador de persistencia, día 6).
</details>

---

## Ejercicio 3 — Mide

Rellena la tabla con **tus** números (las líneas, con `wc -l` o el IDE; sin
contar imports si quieres afinar):

| Medida                                   | VSA extremo | Hexagonal |
|------------------------------------------|-------------|-----------|
| Ficheros de producción nuevos            |             |           |
| Ficheros de producción modificados       |             |           |
| Líneas de producción escritas            |             |           |
| Ficheros de test nuevos                  |             |           |
| Nº de tests (métodos/escenarios)         |             |           |
| Tiempo de ejecución de esos tests        |             |           |
| ¿Tuviste que leer código de otra feature?|             |           |

<details><summary>Valores de referencia (con las soluciones de arriba)</summary>

| Medida                                   | VSA extremo                  | Hexagonal                                      |
|------------------------------------------|------------------------------|------------------------------------------------|
| Ficheros de producción nuevos            | 1                            | 4 (UseCase, Command, Service, Response)        |
| Ficheros de producción modificados       | 0                            | 2 (Controller, BeanConfiguration) + `Pedido` si no tenía `cancelar()` |
| Líneas de producción escritas            | ~110                         | ~85                                            |
| Ficheros de test nuevos                  | 1                            | 2 (dominio + servicio), + 1 opcional `@WebMvcTest` |
| Nº de escenarios                         | 6 (3 + 2 parametrizados + 404) | 7 (5 de dominio parametrizados + 2 de servicio) |
| Tiempo de ejecución                      | segundos (contexto + contenedor; ~ms si ya estaban cacheados) | milisegundos |
| ¿Leíste otra feature?                    | Sí, `ConfirmarPedido` como plantilla (copia) | Sí, `ConfirmarPedidoService` como plantilla, y reutilizaste `PedidoRepository` y el adaptador JPA |

Observa la columna "líneas": **son parecidas**. La diferencia real no está en
el volumen, está en **dónde** quedan y en **qué reutilizas**.
</details>

---

## Ejercicio 4 — Cambio de requisitos

Negocio pide: *"Al cancelar hay que guardar un **motivo** obligatorio (máx. 200
caracteres) y la fecha de cancelación. Y desde ahora, en `ConsultarPedido`, hay
que devolver el motivo si el pedido está cancelado."*

Sin implementarlo del todo, **lista los ficheros que tocarías** en cada
versión.

<details><summary>Solución</summary>

**VSA extremo**

1. `db/migration/V2__motivo_cancelacion.sql` (nuevo): `ALTER TABLE pedidos ADD
   COLUMN motivo_cancelacion VARCHAR(200), ADD COLUMN cancelado_en TIMESTAMPTZ;`
2. `CancelarPedido.java`: `Command` con `motivo`, body en el `Endpoint`,
   `Validator`, `UPDATE` con las nuevas columnas, evento con motivo.
3. `ConsultarPedido.java`: `SELECT` y `Response` con el motivo.
4. Tests de las dos features.

→ **2 ficheros Java + 1 migración.** Cada cambio está donde esperas.

**Hexagonal**

1. `V2__motivo_cancelacion.sql` (nuevo).
2. `Pedido` (`cancelar(MotivoCancelacion motivo, Instant cuando)`) y quizá un
   value object nuevo `MotivoCancelacion` (validación de 200 caracteres en el
   dominio).
3. `PedidoCancelado` (evento con motivo).
4. `CancelarPedidoCommand` (+ motivo) y `CancelarPedidoService`.
5. `PedidoController` + request DTO nuevo `CancelarPedidoRequest`.
6. `PedidoJpaEntity` + mapper de persistencia (nuevas columnas).
7. DTO de respuesta de consulta + su mapper.
8. Tests de dominio, servicio y adaptador de persistencia.

→ **8-10 ficheros.** Más trabajo… pero la regla "motivo obligatorio, máx. 200"
queda **en el dominio**, y la reutiliza cualquier otro canal (Kafka, batch) que
cancele pedidos. En VSA, si mañana aparece `CancelarPedidoPorKafka`, tendrás que
acordarte de copiar la validación… o refactorizar hacia el dominio.

Esta es la conclusión del bloque 4 en pequeño: **el coste se mueve**.
</details>

---

## Ejercicio 5 (opcional) — Refactor hacia el dominio en VSA

En la versión VSA, `ConfirmarPedido`, `CancelarPedido` y `AnadirLinea`
comprueban estados con `String`. Extrae el `enum EstadoPedido` con
`puedePasarA(...)` a `shared/domain` (como en el
[bloque 3, sección 7](03-vertical-slice-architecture.md)) y añade un test de
ArchUnit que impida que una slice dependa de otra.

<details><summary>Solución</summary>

El `enum` es el de la sección 7.2 del bloque 3. En `CancelarPedido.Handler` el
`Set<String> CANCELABLES` desaparece:

```java
EstadoPedido actual = EstadoPedido.valueOf(estado);
if (!actual.puedePasarA(EstadoPedido.CANCELADO)) {
    throw new Rechazado("No se puede cancelar un pedido en estado " + actual);
}
```

Test de unidad del enum (rápido, sin Spring):

```java
class EstadoPedidoTest {

    @Test
    void enviado_no_se_puede_cancelar() {
        assertThat(EstadoPedido.ENVIADO.puedePasarA(EstadoPedido.CANCELADO)).isFalse();
    }

    @Test
    void pagado_si_se_puede_cancelar() {
        assertThat(EstadoPedido.PAGADO.puedePasarA(EstadoPedido.CANCELADO)).isTrue();
    }
}
```

ArchUnit:

```java
@AnalyzeClasses(packages = "com.curso.pedidos")
class SlicesTest {

    @ArchTest
    static final ArchRule slices_no_se_conocen =
            SlicesRuleDefinition.slices()
                    .matching("com.curso.pedidos.features.(*)..")
                    .should().notDependOnEachOther();
}
```

Fíjate en lo que acaba de pasar: has vuelto a tener **tests unitarios rápidos
de reglas de negocio** porque esas reglas tienen ahora un hogar en el dominio.
VSA y hexagonal se han acercado un paso.
</details>

---

## Ejercicio 6 — Debate guiado (10 min)

Divide la clase en dos grupos: uno defiende VSA extremo y otro hexagonal para
el proyecto real de vuestra empresa. Cada grupo tiene 2 minutos por pregunta.
El docente modera y al final se vota… **la matriz, no la camiseta**.

1. ¿Cuántas veces habéis **cambiado de base de datos o de framework** en un
   proyecto en producción en los últimos 5 años? ¿Y cuántas veces habéis
   **añadido una feature**? ¿Qué dice eso sobre dónde conviene optimizar?
2. Un test de integración con Testcontainers tarda 3 s; un unitario, 5 ms. Con
   400 tests, ¿importa? ¿Qué tests os han salvado más veces de un bug en
   producción?
3. ¿Quién de vuestro equipo **sabría reconocer** cuándo una slice necesita
   refactor hacia el dominio? Si la respuesta es "nadie", ¿qué arquitectura es
   más segura?
4. "Hexagonal protege el dominio". ¿Vuestro dominio es rico o es
   mayoritariamente CRUD con validaciones? Poned tres reglas de negocio reales
   que no sean "campo obligatorio".
5. ¿Cuántos **canales de entrada** tiene (o tendrá) el sistema: REST, colas,
   batch, CLI? ¿Cambia eso la respuesta?
6. Un compañero nuevo llega el lunes. ¿En cuál de las dos arquitecturas hará su
   primera feature antes? ¿Y en cuál la hará **sin romper una regla de negocio
   que no conocía**?
7. ¿Qué híbrido os convence más para vuestro caso y qué regla de ArchUnit
   pondríais el primer día para que no se degrade?

<details><summary>Guía para el docente (respuestas orientativas)</summary>

1. Casi siempre: muchas más features que cambios de BD. Argumento a favor de
   VSA… **salvo** que el sistema tenga varios adaptadores reales (pagos,
   mensajería, proveedores externos) que sí cambian, o que el dominio sea rico.
   El beneficio de hexagonal no es solo "cambiar de BD": es **aislar y testear
   reglas**.
2. Importa a partir de cierto tamaño, pero menos de lo que parece si el contexto
   de Spring se cachea. Los tests de integración suelen cazar bugs de mapeo y SQL
   que los unitarios no ven; los unitarios cazan bugs de reglas con mucha más
   precisión. Una base sana tiene ambos (día 5).
3. VSA exige **más criterio**, no menos. Un equipo junior suele estar más
   protegido por una estructura con límites explícitos y ArchUnit.
4. Si no salen tres reglas reales, probablemente es un sistema CRUD: VSA o
   capas simples. Si salen diez, hexagonal con dominio rico.
5. Varios canales para los mismos casos de uso es el escenario de libro de
   hexagonal (es literalmente la frase de intención de Cockburn).
6. Primera feature antes: VSA. Sin romper reglas que no conocía: hexagonal (las
   reglas están en el dominio y sus tests fallan). Es la tensión central del día.
7. Lo más habitual: hexágono por bounded context, slices dentro, lecturas
   directas. Reglas: dominio sin Spring/JPA; slices sin dependencias entre sí;
   (con Modulith) `ApplicationModules.verify()`.
</details>

---

## Referencias

- Jimmy Bogard, *Vertical Slice Architecture* (2018):
  https://www.jimmybogard.com/vertical-slice-architecture/
- Alistair Cockburn, *Hexagonal Architecture* (2005):
  https://alistair.cockburn.us/hexagonal-architecture/
- Robert C. Martin, *The Clean Architecture* (2012):
  https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- Martin Fowler, *The Practical Test Pyramid*:
  https://martinfowler.com/articles/practical-test-pyramid.html
- JUnit 5 (tests parametrizados): https://junit.org/junit5/docs/current/user-guide/
- Testcontainers: https://java.testcontainers.org/
- ArchUnit: https://www.archunit.org/userguide/html/000_Index.html

---

## Mantra

> *"No discutas qué arquitectura es mejor: implementa la misma feature en las
> dos, mide, cambia un requisito… y deja que hablen los números."*
