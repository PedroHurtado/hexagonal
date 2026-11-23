# Día 5 — Bloque 2: Testing del dominio

> *"Tests are the first users of your code."* — Kent Beck (paráfrasis habitual de *Test-Driven Development: By Example*, 2002)

---

## 1. El problema en una frase

El dominio es **lo más valioso y lo más barato de probar** del sistema, y aun así es frecuente encontrarlo cubierto con tests ilegibles: setups de 40 líneas, nombres como `test1()`, `assertTrue(pedido.getEstado() == ...)` y cero casos límite.

Un buen test de dominio es, a la vez, **especificación ejecutable** del negocio. Si un analista lee los `@DisplayName`, debería reconocer las reglas.

---

## 2. Recordatorio: el agregado `Pedido` (código de producción)

Versión resumida del día 2, con lo necesario para entender los tests:

```java
package com.curso.pedidos.domain.model;

import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Pedido {

    private final PedidoId id;
    private final ClienteId clienteId;
    private final List<LineaPedido> lineas;
    private EstadoPedido estado;
    private final Instant creadoEn;

    private Pedido(PedidoId id, ClienteId clienteId, List<LineaPedido> lineas,
                   EstadoPedido estado, Instant creadoEn) {
        this.id = Objects.requireNonNull(id);
        this.clienteId = Objects.requireNonNull(clienteId);
        this.lineas = new ArrayList<>(lineas);
        this.estado = Objects.requireNonNull(estado);
        this.creadoEn = Objects.requireNonNull(creadoEn);
    }

    public static Pedido crear(PedidoId id, ClienteId clienteId, Instant creadoEn) {
        return new Pedido(id, clienteId, List.of(), EstadoPedido.BORRADOR, creadoEn);
    }

    /** Para adaptadores de persistencia y tests: rehidrata sin pasar por las transiciones. */
    public static Pedido reconstituir(PedidoId id, ClienteId clienteId, List<LineaPedido> lineas,
                                      EstadoPedido estado, Instant creadoEn) {
        return new Pedido(id, clienteId, lineas, estado, creadoEn);
    }

    public void anadirLinea(ProductoId productoId, Cantidad cantidad, Dinero precioUnitario) {
        if (estado != EstadoPedido.BORRADOR) {
            throw new TransicionDeEstadoInvalidaException(
                "No se pueden añadir líneas a un pedido en estado " + estado);
        }
        lineas.add(new LineaPedido(productoId, cantidad, precioUnitario));
    }

    public void confirmar() {
        if (estado != EstadoPedido.BORRADOR) {
            throw new TransicionDeEstadoInvalidaException("Solo se confirma un pedido en BORRADOR");
        }
        if (lineas.isEmpty()) {
            throw new PedidoException("No se puede confirmar un pedido vacío");
        }
        estado = EstadoPedido.CONFIRMADO;
    }

    public void marcarComoPagado() {
        if (estado != EstadoPedido.CONFIRMADO) {
            throw new TransicionDeEstadoInvalidaException("Solo se paga un pedido CONFIRMADO");
        }
        estado = EstadoPedido.PAGADO;
    }

    public void cancelar() {
        if (estado == EstadoPedido.ENVIADO || estado == EstadoPedido.CANCELADO) {
            throw new TransicionDeEstadoInvalidaException("No se puede cancelar un pedido " + estado);
        }
        estado = EstadoPedido.CANCELADO;
    }

    public Dinero total() {
        return lineas.stream()
                .map(LineaPedido::subtotal)
                .reduce(Dinero.cero(), Dinero::sumar);
    }

    public PedidoId id() { return id; }
    public ClienteId clienteId() { return clienteId; }
    public EstadoPedido estado() { return estado; }
    public Instant creadoEn() { return creadoEn; }
    public List<LineaPedido> lineas() { return Collections.unmodifiableList(lineas); }

    @Override public boolean equals(Object o) { return o instanceof Pedido p && p.id.equals(id); }
    @Override public int hashCode() { return id.hashCode(); }
}
```

Y el value object `Dinero` (simplificado a una moneda por defecto, EUR):

```java
public record Dinero(BigDecimal importe, Currency moneda) {
    private static final Currency EUR = Currency.getInstance("EUR");

    public Dinero {
        Objects.requireNonNull(importe);
        Objects.requireNonNull(moneda);
        if (importe.signum() < 0) throw new IllegalArgumentException("Importe negativo");
        importe = importe.setScale(2, RoundingMode.HALF_EVEN);   // ¡normaliza la escala!
    }
    public static Dinero euros(String importe) { return new Dinero(new BigDecimal(importe), EUR); }
    public static Dinero cero() { return new Dinero(BigDecimal.ZERO, EUR); }
    public Dinero sumar(Dinero otro) { comprobarMoneda(otro); return new Dinero(importe.add(otro.importe), moneda); }
    public Dinero multiplicar(int factor) { return new Dinero(importe.multiply(BigDecimal.valueOf(factor)), moneda); }
    private void comprobarMoneda(Dinero otro) {
        if (!moneda.equals(otro.moneda)) throw new IllegalArgumentException("Monedas distintas");
    }
}
```

> ¿Por qué el `setScale(2)` en el constructor compacto? Porque `new BigDecimal("10.0").equals(new BigDecimal("10.00"))` es **`false`**. Sin normalizar, `Dinero.euros("10.0")` y `Dinero.euros("10.00")` no serían iguales y los tests (y el negocio) fallarían de forma muy confusa. Este bug lo descubre... un test.

---

## 3. Bad: el test de dominio que todos hemos visto

```java
class PedidoTest {

    @Test
    void test1() {
        Pedido p = Pedido.crear(new PedidoId(UUID.randomUUID()), new ClienteId(UUID.randomUUID()), Instant.now());
        p.anadirLinea(new ProductoId("SKU-1"), new Cantidad(2), new Dinero(new BigDecimal("10"), Currency.getInstance("EUR")));
        p.anadirLinea(new ProductoId("SKU-2"), new Cantidad(1), new Dinero(new BigDecimal("5"), Currency.getInstance("EUR")));
        p.confirmar();
        assertTrue(p.estado() == EstadoPedido.CONFIRMADO);
        assertEquals(new BigDecimal("25.00"), p.total().importe());
        try {
            p.anadirLinea(new ProductoId("SKU-3"), new Cantidad(1), new Dinero(new BigDecimal("1"), Currency.getInstance("EUR")));
            fail();
        } catch (Exception e) {
            // ok
        }
    }
}
```

### ¿Qué duele?

- **`test1`** no dice qué regla protege. Cuando falle en CI, nadie sabrá qué se ha roto.
- **Tres comportamientos en un test**: confirmar, calcular total, impedir líneas tras confirmar. El primer fallo oculta los demás.
- **Ruido**: la construcción de `Dinero` y de ids ocupa más que la intención.
- **`assertTrue(a == b)`**: si falla, el mensaje es `expected: <true> but was: <false>`. Inútil.
- **`catch (Exception e)`**: pasaría también con un `NullPointerException`. El test miente.
- **`Instant.now()` y `UUID.randomUUID()`**: datos no deterministas sin necesidad.

---

## 4. Good: JUnit 5 + AssertJ a fondo

### 4.1 Given / When / Then

Cada test tiene **tres bloques** visibles (también llamado *Arrange / Act / Assert*). Una línea en blanco entre ellos basta; los comentarios `// given` ayudan al principio.

```java
@Test
void calcula_el_total_como_suma_de_subtotales() {
    // given
    Pedido pedido = PedidoMother.borradorVacio();
    pedido.anadirLinea(new ProductoId("SKU-TECLADO"), new Cantidad(2), Dinero.euros("10.00"));
    pedido.anadirLinea(new ProductoId("SKU-RATON"), new Cantidad(1), Dinero.euros("5.00"));

    // when
    Dinero total = pedido.total();

    // then
    assertThat(total).isEqualTo(Dinero.euros("25.00"));
}
```

### 4.2 Test data: Object Mother y Test Data Builder

Dos patrones complementarios para eliminar el ruido del *given*:

- **Object Mother** (Schuh & Punke, ThoughtWorks, 2001; recogido por Fowler): una clase con **métodos factoría con nombre de negocio** que devuelven objetos típicos. `PedidoMother.confirmado()`.
- **Test Data Builder** (Nat Pryce, 2007; *GOOS*): builder fluido con **valores por defecto razonables** donde solo especificas lo que importa al test.

Combinados: la Mother usa el Builder por dentro.

```java
package com.curso.pedidos.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PedidoBuilder {

    public static final Instant AHORA = Instant.parse("2026-11-23T10:00:00Z");

    private PedidoId id = new PedidoId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private ClienteId clienteId = new ClienteId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"));
    private EstadoPedido estado = EstadoPedido.BORRADOR;
    private Instant creadoEn = AHORA;
    private final List<LineaPedido> lineas = new ArrayList<>();

    private PedidoBuilder() { }

    public static PedidoBuilder unPedido() { return new PedidoBuilder(); }

    public PedidoBuilder conId(PedidoId id) { this.id = id; return this; }
    public PedidoBuilder conIdAleatorio() { this.id = new PedidoId(UUID.randomUUID()); return this; }
    public PedidoBuilder deCliente(ClienteId clienteId) { this.clienteId = clienteId; return this; }
    public PedidoBuilder enEstado(EstadoPedido estado) { this.estado = estado; return this; }
    public PedidoBuilder creadoEn(Instant instante) { this.creadoEn = instante; return this; }

    public PedidoBuilder conLinea(String sku, int cantidad, String precio) {
        lineas.add(new LineaPedido(new ProductoId(sku), new Cantidad(cantidad), Dinero.euros(precio)));
        return this;
    }

    public PedidoBuilder conUnaLineaCualquiera() { return conLinea("SKU-GENERICO", 1, "10.00"); }

    public Pedido build() {
        return Pedido.reconstituir(id, clienteId, lineas, estado, creadoEn);
    }
}
```

```java
package com.curso.pedidos.domain.model;

import static com.curso.pedidos.domain.model.PedidoBuilder.unPedido;

public final class PedidoMother {

    private PedidoMother() { }

    public static Pedido borradorVacio() {
        return unPedido().build();
    }

    public static Pedido borradorConLineas() {
        return unPedido()
                .conLinea("SKU-TECLADO", 2, "49.90")
                .conLinea("SKU-RATON", 1, "19.90")
                .build();
    }

    public static Pedido confirmado() {
        return unPedido().conUnaLineaCualquiera().enEstado(EstadoPedido.CONFIRMADO).build();
    }

    public static Pedido pagado() {
        return unPedido().conUnaLineaCualquiera().enEstado(EstadoPedido.PAGADO).build();
    }

    public static Pedido enviado() {
        return unPedido().conUnaLineaCualquiera().enEstado(EstadoPedido.ENVIADO).build();
    }

    public static Pedido enEstado(EstadoPedido estado) {
        return unPedido().conUnaLineaCualquiera().enEstado(estado).build();
    }
}
```

> **¿Por qué `reconstituir` y no `crear()` + transiciones?** Porque para tener un pedido `ENVIADO` no queremos depender de que `confirmar()` y `marcarComoPagado()` funcionen: si uno se rompe, fallarían en cascada decenas de tests que no tienen nada que ver. Además, el dominio quizá ni tenga un método `enviar()` público todavía.

> Trampa: la **Mother obesa**. Si `PedidoMother` tiene 60 métodos (`confirmadoConDosLineasYClienteVipEnDolares`...), es el momento de tirar del builder directamente en el test.

### 4.3 El test completo: `@Nested`, `@DisplayName`, `@ParameterizedTest`

```java
package com.curso.pedidos.domain.model;

import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static com.curso.pedidos.domain.model.PedidoBuilder.unPedido;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

@DisplayName("Pedido")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PedidoTest {

    @Nested
    @DisplayName("al crearse")
    class AlCrearse {

        @Test
        void empieza_en_borrador_y_sin_lineas() {
            Pedido pedido = Pedido.crear(
                    new PedidoId(java.util.UUID.randomUUID()),
                    new ClienteId(java.util.UUID.randomUUID()),
                    PedidoBuilder.AHORA);

            assertThat(pedido.estado()).isEqualTo(EstadoPedido.BORRADOR);
            assertThat(pedido.lineas()).isEmpty();
            assertThat(pedido.total()).isEqualTo(Dinero.cero());
            assertThat(pedido.creadoEn()).isEqualTo(PedidoBuilder.AHORA);
        }
    }

    @Nested
    @DisplayName("al añadir líneas")
    class AlAnadirLineas {

        @Test
        void en_borrador_se_anade_la_linea() {
            Pedido pedido = PedidoMother.borradorVacio();

            pedido.anadirLinea(new ProductoId("SKU-1"), new Cantidad(3), Dinero.euros("2.50"));

            assertThat(pedido.lineas())
                    .hasSize(1)
                    .extracting(LineaPedido::productoId, LineaPedido::cantidad)
                    .containsExactly(tuple(new ProductoId("SKU-1"), new Cantidad(3)));
        }

        @ParameterizedTest(name = "en estado {0} lanza TransicionDeEstadoInvalida")
        @EnumSource(value = EstadoPedido.class, names = "BORRADOR", mode = EnumSource.Mode.EXCLUDE)
        void fuera_de_borrador_no_se_pueden_anadir_lineas(EstadoPedido estado) {
            Pedido pedido = PedidoMother.enEstado(estado);

            assertThatThrownBy(() ->
                    pedido.anadirLinea(new ProductoId("SKU-X"), new Cantidad(1), Dinero.euros("1.00")))
                    .isInstanceOf(TransicionDeEstadoInvalidaException.class)
                    .hasMessageContaining(estado.name());
        }

        @Test
        void la_lista_de_lineas_expuesta_es_inmutable() {
            Pedido pedido = PedidoMother.borradorConLineas();

            assertThatThrownBy(() -> pedido.lineas().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("al calcular el total")
    class AlCalcularElTotal {

        @ParameterizedTest(name = "{0} x {1} + {2} x {3} = {4}")
        @CsvSource({
                "1, 10.00, 1, 5.00,  15.00",
                "2, 10.00, 1, 5.00,  25.00",
                "3, 0.10,  3, 0.20,   0.90",   // el clásico 0.1 + 0.2 de los double
                "10, 9.99, 0, 0.00,  99.90"
        })
        void suma_los_subtotales(int cant1, String precio1, int cant2, String precio2, String esperado) {
            Pedido pedido = PedidoMother.borradorVacio();
            pedido.anadirLinea(new ProductoId("A"), new Cantidad(cant1), Dinero.euros(precio1));
            if (cant2 > 0) {
                pedido.anadirLinea(new ProductoId("B"), new Cantidad(cant2), Dinero.euros(precio2));
            }

            assertThat(pedido.total()).isEqualTo(Dinero.euros(esperado));
        }
    }

    @Nested
    @DisplayName("al confirmar")
    class AlConfirmar {

        @Test
        void un_borrador_con_lineas_pasa_a_confirmado() {
            Pedido pedido = PedidoMother.borradorConLineas();

            pedido.confirmar();

            assertThat(pedido.estado()).isEqualTo(EstadoPedido.CONFIRMADO);
        }

        @Test
        void un_pedido_vacio_no_se_puede_confirmar() {
            Pedido pedido = PedidoMother.borradorVacio();

            assertThatThrownBy(pedido::confirmar)
                    .isExactlyInstanceOf(PedidoException.class)
                    .hasMessage("No se puede confirmar un pedido vacío");

            assertThat(pedido.estado())
                    .as("el estado no debe cambiar si la operación falla")
                    .isEqualTo(EstadoPedido.BORRADOR);
        }
    }

    @Nested
    @DisplayName("al cancelar")
    class AlCancelar {

        @ParameterizedTest(name = "desde {0} se puede cancelar")
        @EnumSource(value = EstadoPedido.class, names = {"BORRADOR", "CONFIRMADO", "PAGADO"})
        void desde_estados_previos_al_envio_se_cancela(EstadoPedido estadoInicial) {
            Pedido pedido = PedidoMother.enEstado(estadoInicial);

            pedido.cancelar();

            assertThat(pedido.estado()).isEqualTo(EstadoPedido.CANCELADO);
        }

        @ParameterizedTest(name = "desde {0} NO se puede cancelar")
        @EnumSource(value = EstadoPedido.class, names = {"ENVIADO", "CANCELADO"})
        void un_pedido_enviado_o_ya_cancelado_no_se_cancela(EstadoPedido estadoInicial) {
            Pedido pedido = PedidoMother.enEstado(estadoInicial);

            assertThatThrownBy(pedido::cancelar)
                    .isInstanceOf(TransicionDeEstadoInvalidaException.class);
            assertThat(pedido.estado()).isEqualTo(estadoInicial);
        }
    }

    @Test
    void dos_pedidos_con_el_mismo_id_son_iguales_aunque_difiera_el_estado() {
        Pedido a = unPedido().enEstado(EstadoPedido.BORRADOR).build();
        Pedido b = unPedido().enEstado(EstadoPedido.PAGADO).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
```

Lo que ganamos:

| Técnica                        | Para qué sirve                                                                 |
|--------------------------------|--------------------------------------------------------------------------------|
| `@DisplayName` en clase/nested | El informe se lee como una especificación: "Pedido › al cancelar › desde ENVIADO NO se puede cancelar" |
| `@DisplayNameGeneration(ReplaceUnderscores)` | Los métodos `un_pedido_vacio_no_se_puede_confirmar` se muestran con espacios |
| `@Nested`                      | Agrupa por comportamiento; cada grupo puede tener su propio `@BeforeEach`      |
| `@ParameterizedTest` + `@EnumSource` | Recorre **todos** los estados: si mañana se añade `DEVUELTO`, el test lo cubre solo |
| `@CsvSource`                   | Tabla de ejemplos de negocio legible                                           |
| `assertThatThrownBy`           | Comprueba **tipo exacto** y mensaje, no "cualquier excepción"                  |
| `.as("...")`                   | Mensaje de fallo con intención                                                 |

Salida en el IDE / informe de Surefire:

```
Pedido
├── al crearse
│   └── empieza en borrador y sin lineas                         ✔
├── al añadir líneas
│   ├── en borrador se anade la linea                            ✔
│   ├── fuera de borrador no se pueden anadir lineas
│   │   ├── en estado CONFIRMADO lanza TransicionDeEstadoInvalida ✔
│   │   ├── en estado PAGADO lanza TransicionDeEstadoInvalida     ✔
│   │   └── ...
├── al cancelar
│   ├── desde BORRADOR se puede cancelar                         ✔
│   └── desde ENVIADO NO se puede cancelar                       ✔
```

### 4.4 Más formas de afirmar excepciones

```java
// Estilo "given-when-then" explícito con catchThrowable
Throwable error = catchThrowable(pedido::confirmar);
assertThat(error)
        .isInstanceOf(PedidoException.class)
        .hasMessageContaining("vacío");

// Estilo tipado (devuelve la excepción para afirmar sobre sus campos)
TransicionDeEstadoInvalidaException ex =
        catchThrowableOfType(pedido::cancelar, TransicionDeEstadoInvalidaException.class);
assertThat(ex.getMessage()).contains("ENVIADO");

// Afirmar que NO lanza
assertThatCode(pedido::confirmar).doesNotThrowAnyException();

// JUnit 5 nativo (equivalente, menos expresivo)
PedidoException e = assertThrows(PedidoException.class, pedido::confirmar);
```

> Nota: Spring Boot 3.3 trae AssertJ 3.25.x, donde la firma es `catchThrowableOfType(ThrowingCallable, Class)`. A partir de AssertJ 3.26 se añade el orden `(Class, ThrowingCallable)` y el antiguo queda *deprecated*. Si al subir de versión el IDE te lo tacha, solo hay que invertir los argumentos.

### 4.5 Value objects: tests pequeños y exhaustivos

```java
class DineroTest {

    @Test
    void normaliza_la_escala_a_dos_decimales() {
        assertThat(Dinero.euros("10")).isEqualTo(Dinero.euros("10.00"));
    }

    @Test
    void no_admite_importes_negativos() {
        assertThatThrownBy(() -> Dinero.euros("-0.01"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void no_suma_monedas_distintas() {
        Dinero dolares = new Dinero(new BigDecimal("1.00"), Currency.getInstance("USD"));

        assertThatThrownBy(() -> Dinero.euros("1.00").sumar(dolares))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Monedas distintas");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    void la_cantidad_debe_ser_positiva(int valor) {
        assertThatThrownBy(() -> new Cantidad(valor)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

---

## 5. Aserciones custom de AssertJ

Cuando el mismo grupo de aserciones se repite, se encapsula en una **aserción de dominio**. El test pasa a hablar el lenguaje del negocio.

```java
package com.curso.pedidos.domain.model;

import org.assertj.core.api.AbstractAssert;

public class PedidoAssert extends AbstractAssert<PedidoAssert, Pedido> {

    private PedidoAssert(Pedido actual) {
        super(actual, PedidoAssert.class);
    }

    public static PedidoAssert assertThat(Pedido actual) {
        return new PedidoAssert(actual);
    }

    public PedidoAssert estaEn(EstadoPedido esperado) {
        isNotNull();
        if (actual.estado() != esperado) {
            failWithMessage("Se esperaba el pedido <%s> en estado <%s> pero estaba en <%s>",
                    actual.id().valor(), esperado, actual.estado());
        }
        return this;
    }

    public PedidoAssert tieneLineas(int numero) {
        isNotNull();
        if (actual.lineas().size() != numero) {
            failWithMessage("Se esperaban <%d> líneas pero había <%d>", numero, actual.lineas().size());
        }
        return this;
    }

    public PedidoAssert totaliza(String importeEnEuros) {
        isNotNull();
        Dinero esperado = Dinero.euros(importeEnEuros);
        if (!actual.total().equals(esperado)) {
            failWithMessage("Se esperaba un total de <%s> pero era <%s>", esperado, actual.total());
        }
        return this;
    }

    public PedidoAssert estaCancelado() { return estaEn(EstadoPedido.CANCELADO); }
}
```

Uso:

```java
import static com.curso.pedidos.domain.model.PedidoAssert.assertThat;

@Test
void un_pedido_confirmado_con_dos_lineas() {
    Pedido pedido = PedidoMother.borradorConLineas();

    pedido.confirmar();

    assertThat(pedido)
            .estaEn(EstadoPedido.CONFIRMADO)
            .tieneLineas(2)
            .totaliza("119.70");
}
```

Mensaje cuando falla:

```
java.lang.AssertionError: Se esperaba el pedido <00000000-0000-0000-0000-000000000001>
en estado <CONFIRMADO> pero estaba en <BORRADOR>
```

> Si hay muchos objetos de dominio, AssertJ tiene un generador (`assertj-assertions-generator-maven-plugin`) que crea las clases `XxxAssert` automáticamente. Para un curso, escribirlas a mano enseña más.

> Trampa: el import estático de tu `assertThat` choca con el de `Assertions.assertThat`. Solución habitual: una clase `MisAserciones extends Assertions` con el `assertThat(Pedido)` añadido, e importar solo esa.

---

## 6. ¿Son buenos mis tests? Mutation testing con PIT

La cobertura de líneas dice qué código **se ejecutó**; no dice si algún test **habría fallado** si ese código estuviera mal. El *mutation testing* lo comprueba: introduce pequeños cambios (mutantes) en el código de producción y mira si la suite los "mata".

```
   Código original                Mutante                       ¿Algún test falla?
   ──────────────────────         ──────────────────────        ──────────────────
   if (lineas.isEmpty())    ──►   if (!lineas.isEmpty())   ──►  Sí → mutante MUERTO ✔
   estado == ENVIADO        ──►   estado != ENVIADO        ──►  Sí → MUERTO ✔
   return total;            ──►   return null;             ──►  No → SUPERVIVIENTE ✘ (falta un test)
```

Configuración con PIT (pitest-maven) para JUnit 5:

```xml
<plugin>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-maven</artifactId>
    <version>1.17.0</version>
    <dependencies>
        <dependency>
            <groupId>org.pitest</groupId>
            <artifactId>pitest-junit5-plugin</artifactId>
            <version>1.2.1</version>
        </dependency>
    </dependencies>
    <configuration>
        <targetClasses>
            <param>com.curso.pedidos.domain.*</param>
        </targetClasses>
        <targetTests>
            <param>com.curso.pedidos.domain.*</param>
        </targetTests>
        <mutationThreshold>80</mutationThreshold>
    </configuration>
</plugin>
```

```bash
./mvnw test-compile org.pitest:pitest-maven:mutationCoverage
# Informe HTML en target/pit-reports/
```

> PIT es caro: aplícalo **al dominio** (que es rápido de testear) y no en cada build; un job nocturno o una ejecución manual antes de una release basta. El hexágono ayuda: como el dominio no tiene Spring, PIT vuela.

---

## 7. Trampas y errores comunes

- **Trampa: testear getters y setters.** No aportan. Los getters se prueban indirectamente al afirmar comportamiento.
- **Trampa: un `assert` por test como dogma.** La regla real es **un comportamiento por test**. Afirmar estado + número de líneas tras confirmar es un único comportamiento.
- **Trampa: lógica en el test** (`if`, bucles, cálculos del esperado con la misma fórmula que producción). El esperado se escribe **literal**: `"25.00"`, no `precio * cantidad`.
- **Trampa: datos aleatorios por defecto.** Ids fijos en el builder; aleatorios solo cuando el test necesita varios distintos (`conIdAleatorio()`).
- **Trampa: probar métodos privados.** Si sientes que necesitas hacerlo, probablemente hay un value object o servicio de dominio esperando a nacer.

---

## 8. Tabla resumen

| Herramienta / patrón           | Cuándo                                        | Ejemplo                                  |
|--------------------------------|-----------------------------------------------|------------------------------------------|
| `@Nested` + `@DisplayName`     | Siempre en agregados con varios comportamientos | `PedidoTest.AlCancelar`                |
| `@ParameterizedTest`           | Misma regla, varios datos                      | Cancelación por estado                   |
| Object Mother                  | Objetos típicos con nombre de negocio          | `PedidoMother.confirmado()`              |
| Test Data Builder              | Variaciones puntuales                          | `unPedido().enEstado(PAGADO).build()`    |
| `assertThatThrownBy`           | Invariantes que lanzan excepción               | Confirmar pedido vacío                   |
| Aserción custom                | Aserciones repetidas en muchos tests           | `assertThat(pedido).estaCancelado()`     |
| PIT                            | Medir la **calidad** de los tests del dominio  | `mutationThreshold` 80 %                 |

---

## Ejercicio

1. Añade al dominio la regla: **"un pedido no puede tener más de 20 líneas"**. Escribe primero el test (rojo), luego el código (verde).
2. Añade a `PedidoAssert` el método `contieneProducto(String sku)`.
3. Ejecuta PIT sobre `domain` y localiza un mutante superviviente. Escribe el test que lo mata.

**Pista:** para el punto 1, usa el builder en un bucle *dentro del given* (es el único sitio donde un bucle en un test es aceptable) o un `@ParameterizedTest` con `19, 20` (ok) y `21` (falla).

<details><summary>Solución</summary>

```java
// Producción (Pedido)
private static final int MAX_LINEAS = 20;

public void anadirLinea(ProductoId productoId, Cantidad cantidad, Dinero precioUnitario) {
    if (estado != EstadoPedido.BORRADOR) {
        throw new TransicionDeEstadoInvalidaException(
            "No se pueden añadir líneas a un pedido en estado " + estado);
    }
    if (lineas.size() >= MAX_LINEAS) {
        throw new PedidoException("Un pedido no puede tener más de " + MAX_LINEAS + " líneas");
    }
    lineas.add(new LineaPedido(productoId, cantidad, precioUnitario));
}
```

```java
@Nested
class LimiteDeLineas {

    private Pedido pedidoCon(int numeroLineas) {
        PedidoBuilder builder = PedidoBuilder.unPedido();
        for (int i = 0; i < numeroLineas; i++) {
            builder.conLinea("SKU-" + i, 1, "1.00");
        }
        return builder.build();
    }

    @Test
    void admite_hasta_veinte_lineas() {
        Pedido pedido = pedidoCon(19);

        assertThatCode(() -> pedido.anadirLinea(new ProductoId("SKU-20"), new Cantidad(1), Dinero.euros("1.00")))
                .doesNotThrowAnyException();
        assertThat(pedido.lineas()).hasSize(20);
    }

    @Test
    void rechaza_la_linea_veintiuno() {
        Pedido pedido = pedidoCon(20);

        assertThatThrownBy(() -> pedido.anadirLinea(new ProductoId("SKU-21"), new Cantidad(1), Dinero.euros("1.00")))
                .isInstanceOf(PedidoException.class)
                .hasMessageContaining("20");
    }
}
```

```java
// PedidoAssert
public PedidoAssert contieneProducto(String sku) {
    isNotNull();
    boolean contiene = actual.lineas().stream()
            .anyMatch(l -> l.productoId().equals(new ProductoId(sku)));
    if (!contiene) {
        failWithMessage("Se esperaba que el pedido contuviera el producto <%s>", sku);
    }
    return this;
}
```

Mutante típico superviviente: cambiar `>=` por `>` en `lineas.size() >= MAX_LINEAS`. Lo mata precisamente el test de **frontera** `rechaza_la_linea_veintiuno` (con 20 líneas previas). Si solo hubieras probado con 25 líneas, el mutante sobreviviría.

</details>

---

## Referencias

- JUnit 5 User Guide: https://junit.org/junit5/docs/current/user-guide/
- AssertJ documentation (incluye *custom assertions*): https://assertj.github.io/doc/
- Martin Fowler, *Object Mother*: https://martinfowler.com/bliki/ObjectMother.html
- Martin Fowler, *Given When Then*: https://martinfowler.com/bliki/GivenWhenThen.html
- Nat Pryce, *Test Data Builders* (2007) y Freeman & Pryce, *Growing Object-Oriented Software, Guided by Tests* (2009).
- PIT Mutation Testing: https://pitest.org/
- Kent Beck, *Test-Driven Development: By Example* (2002).
- Eric Evans, *Domain-Driven Design* (2003) — los invariantes del agregado son lo primero que se prueba.

---

## Mantra

> *"Un test de dominio que no entiende el negocio no protege al negocio. Nómbralo como lo diría el cliente."*
