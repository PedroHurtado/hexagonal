# Día 2 — Bloque 2: Testing del modelo de dominio

> *"Write tests. Not too many. Mostly integration."* — Guillermo Rauch
>
> ...salvo en el dominio de un hexágono, donde la respuesta es: **muchos, unitarios y rapidísimos.**

---

## 1. El problema en una frase

Si para comprobar que "no se confirma un pedido vacío" hay que arrancar Spring, levantar una base de
datos y lanzar una petición HTTP, **esa regla no se va a probar** (o se probará una vez y se olvidará).

En cristiano: el dominio puro es el código **más barato de probar** que existe. Aprovechémoslo.

---

## 2. Dónde encaja en la pirámide

```
                 ▲  lento, caro, frágil
                ╱ ╲
               ╱E2E╲            pocos      (día 5)
              ╱─────╲
             ╱ Integr.╲         algunos    adaptadores con Testcontainers/WireMock (días 4–5)
            ╱──────────╲
           ╱ Aplicación ╲       bastantes  servicios con fakes/mocks (bloque 5 de hoy)
          ╱──────────────╲
         ╱    DOMINIO     ╲     MUCHOS     hoy: new + assertThat, milisegundos
        ╱──────────────────╲
                 ▼  rápido, barato, estable
```

La pirámide de tests (Fowler, *The Practical Test Pyramid*) encaja como un guante con el hexágono:
**cuanto más al centro, más tests y más baratos**.

---

## 3. Dependencias de test

Con Spring Boot basta con el starter de test (trae JUnit 5, AssertJ y Mockito). **No** anotamos
nada con `@SpringBootTest`: el starter solo aporta las librerías.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

Si el módulo de dominio fuera un artefacto Maven independiente (sin Spring), bastaría con:

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <scope>test</scope>
</dependency>
```

(con las versiones gestionadas por el BOM de JUnit / el parent de Spring Boot 3.3).

---

## 4. Bad — probar el dominio a través de Spring

```java
// ❌ 8 segundos para comprobar un if
@SpringBootTest
@AutoConfigureMockMvc
class PedidoServiceTest {

    @Autowired MockMvc mvc;
    @Autowired PedidoJpaRepository repo;

    @Test
    void confirmarVacioDaError() throws Exception {
        var p = repo.save(new PedidoEntity());          // sin líneas
        mvc.perform(post("/pedidos/" + p.getId() + "/confirmacion"))
           .andExpect(status().is4xxClientError());      // ¿400? ¿409? ¿por qué falló?
    }
}
```

### ¿Qué duele?

- **Lento**: arranca el contexto completo y una BD. Multiplicado por 200 reglas, la suite tarda
  minutos y la gente deja de ejecutarla.
- **Impreciso**: un 4xx puede venir de un JSON mal formado, de seguridad o de la regla. El test pasa
  por motivos equivocados.
- **Frágil**: cambiar la URL o el mapeo JPA rompe un test que debería hablar de negocio.
- **Acoplado**: no se puede escribir antes de tener controlador y persistencia.

---

## 5. Good — tests unitarios puros del agregado

Organizamos con `@Nested` por comportamiento y nombres en español que se leen como especificación.

```java
package com.curso.pedidos.domain.model;

import com.curso.pedidos.domain.event.PedidoCancelado;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.event.PedidoPagado;
import com.curso.pedidos.domain.exception.PedidoNoModificableException;
import com.curso.pedidos.domain.exception.PedidoVacioException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PedidoTest {

    private static final ProductoId TECLADO = new ProductoId("TEC-001");
    private static final ProductoId RATON = new ProductoId("RAT-002");

    private Pedido pedidoConUnaLinea() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(TECLADO, Cantidad.de(2), Dinero.euros("25.00"));
        return pedido;
    }

    private Pedido pedidoEnEstado(EstadoPedido estado) {
        return Pedido.reconstituir(
                PedidoId.nuevo(), ClienteId.nuevo(),
                List.of(new LineaPedido(TECLADO, Cantidad.de(1), Dinero.euros("10.00"))),
                estado, Instant.now());
    }

    @Nested
    @DisplayName("Al crear un pedido")
    class Crear {

        @Test
        void empieza_en_borrador_sin_lineas_y_con_total_cero() {
            Pedido pedido = Pedido.crear(ClienteId.nuevo());

            assertThat(pedido.estado()).isEqualTo(EstadoPedido.BORRADOR);
            assertThat(pedido.lineas()).isEmpty();
            assertThat(pedido.total()).isEqualTo(Dinero.euros("0"));
            assertThat(pedido.id()).isNotNull();
            assertThat(pedido.creadoEn()).isBeforeOrEqualTo(Instant.now());
        }

        @Test
        void no_genera_eventos() {
            assertThat(Pedido.crear(ClienteId.nuevo()).extraerEventos()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Al añadir líneas")
    class AnadirLinea {

        @Test
        void calcula_el_total_como_suma_de_subtotales() {
            Pedido pedido = pedidoConUnaLinea();                        // 2 x 25,00
            pedido.anadirLinea(RATON, Cantidad.de(1), Dinero.euros("9.99"));

            assertThat(pedido.lineas()).hasSize(2);
            assertThat(pedido.total()).isEqualTo(Dinero.euros("59.99"));
        }

        @Test
        void el_mismo_producto_acumula_cantidad_en_la_misma_linea() {
            Pedido pedido = pedidoConUnaLinea();
            pedido.anadirLinea(TECLADO, Cantidad.de(3), Dinero.euros("25.00"));

            assertThat(pedido.lineas()).singleElement()
                    .extracting(LineaPedido::cantidad)
                    .isEqualTo(Cantidad.de(5));
        }

        @Test
        void la_lista_de_lineas_no_se_puede_modificar_desde_fuera() {
            Pedido pedido = pedidoConUnaLinea();

            assertThatThrownBy(() -> pedido.lineas().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void rechaza_mezclar_monedas() {
            Pedido pedido = pedidoConUnaLinea();
            Dinero dolares = new Dinero(new java.math.BigDecimal("5"), java.util.Currency.getInstance("USD"));

            assertThatThrownBy(() -> pedido.anadirLinea(RATON, Cantidad.de(1), dolares))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "no se añaden líneas en estado {0}")
        @EnumSource(value = EstadoPedido.class, names = "BORRADOR", mode = EnumSource.Mode.EXCLUDE)
        void solo_se_pueden_anadir_lineas_en_borrador(EstadoPedido estado) {
            Pedido pedido = pedidoEnEstado(estado);

            assertThatThrownBy(() -> pedido.anadirLinea(RATON, Cantidad.de(1), Dinero.euros("5")))
                    .isInstanceOf(PedidoNoModificableException.class)
                    .hasMessageContaining(estado.name());
        }
    }

    @Nested
    @DisplayName("Al confirmar")
    class Confirmar {

        @Test
        void un_pedido_con_lineas_pasa_a_confirmado_y_emite_evento() {
            Pedido pedido = pedidoConUnaLinea();

            pedido.confirmar();

            assertThat(pedido.estado()).isEqualTo(EstadoPedido.CONFIRMADO);
            assertThat(pedido.extraerEventos())
                    .singleElement()
                    .isInstanceOfSatisfying(PedidoConfirmado.class, evento -> {
                        assertThat(evento.pedidoId()).isEqualTo(pedido.id());
                        assertThat(evento.ocurridoEn()).isNotNull();
                    });
        }

        @Test
        void un_pedido_vacio_no_se_confirma_y_sigue_en_borrador() {
            Pedido pedido = Pedido.crear(ClienteId.nuevo());

            assertThatThrownBy(pedido::confirmar).isInstanceOf(PedidoVacioException.class);
            assertThat(pedido.estado()).isEqualTo(EstadoPedido.BORRADOR);
            assertThat(pedido.extraerEventos()).isEmpty();
        }

        @Test
        void confirmar_dos_veces_es_una_transicion_invalida() {
            Pedido pedido = pedidoConUnaLinea();
            pedido.confirmar();

            assertThatThrownBy(pedido::confirmar)
                    .isInstanceOfSatisfying(TransicionDeEstadoInvalidaException.class, ex -> {
                        assertThat(ex.origen()).isEqualTo(EstadoPedido.CONFIRMADO);
                        assertThat(ex.destino()).isEqualTo(EstadoPedido.CONFIRMADO);
                    });
        }

        @Test
        void extraer_eventos_los_vacia() {
            Pedido pedido = pedidoConUnaLinea();
            pedido.confirmar();

            assertThat(pedido.extraerEventos()).hasSize(1);
            assertThat(pedido.extraerEventos()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Ciclo de vida completo")
    class CicloDeVida {

        @Test
        void borrador_confirmado_pagado_enviado() {
            Pedido pedido = pedidoConUnaLinea();

            pedido.confirmar();
            pedido.marcarComoPagado();
            pedido.marcarComoEnviado();

            assertThat(pedido.estado()).isEqualTo(EstadoPedido.ENVIADO);
            assertThat(pedido.extraerEventos())
                    .hasExactlyElementsOfTypes(PedidoConfirmado.class, PedidoPagado.class);
        }

        @Test
        void no_se_paga_un_pedido_en_borrador() {
            assertThatThrownBy(pedidoConUnaLinea()::marcarComoPagado)
                    .isInstanceOf(TransicionDeEstadoInvalidaException.class);
        }
    }

    @Nested
    @DisplayName("Al cancelar")
    class Cancelar {

        @ParameterizedTest(name = "se puede cancelar desde {0}")
        @EnumSource(value = EstadoPedido.class, names = {"BORRADOR", "CONFIRMADO", "PAGADO"})
        void se_cancela_desde_estados_previos_al_envio(EstadoPedido estado) {
            Pedido pedido = pedidoEnEstado(estado);

            pedido.cancelar();

            assertThat(pedido.estado()).isEqualTo(EstadoPedido.CANCELADO);
            assertThat(pedido.extraerEventos()).singleElement().isInstanceOf(PedidoCancelado.class);
        }

        @ParameterizedTest(name = "no se puede cancelar desde {0}")
        @EnumSource(value = EstadoPedido.class, names = {"ENVIADO", "CANCELADO"})
        void no_se_cancela_un_pedido_enviado_ni_uno_ya_cancelado(EstadoPedido estado) {
            Pedido pedido = pedidoEnEstado(estado);

            assertThatThrownBy(pedido::cancelar)
                    .isInstanceOf(TransicionDeEstadoInvalidaException.class);
            assertThat(pedido.estado()).isEqualTo(estado);
        }
    }

    @Test
    void dos_pedidos_son_iguales_si_tienen_el_mismo_id() {
        PedidoId id = PedidoId.nuevo();
        Pedido a = Pedido.reconstituir(id, ClienteId.nuevo(), List.of(), EstadoPedido.BORRADOR, Instant.now());
        Pedido b = Pedido.reconstituir(id, ClienteId.nuevo(), List.of(), EstadoPedido.CANCELADO, Instant.now());

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
```

Fijaos:

- `pedidoEnEstado(...)` usa `reconstituir` para colocar el agregado en cualquier estado sin recorrer
  el ciclo entero. Es legítimo en tests (simula "venir de la BD").
- Cada test comprueba **también lo que no debe cambiar** (el estado sigue en `BORRADOR`, no hay
  eventos). Una regla rota no debe dejar efectos a medias.
- Los `@ParameterizedTest` con `@EnumSource` recorren **toda la matriz de transiciones** con muy
  poco código. Si mañana añadís un estado `DEVUELTO`, el test de "solo en borrador" lo cubre solo.

---

## 6. Tests de value objects

En un VO probamos tres cosas: **validación**, **igualdad por valor** y **operaciones**.

```java
package com.curso.pedidos.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DineroTest {

    @Test
    void dos_dineros_con_el_mismo_importe_y_moneda_son_iguales_aunque_cambie_la_escala() {
        assertThat(Dinero.euros("10")).isEqualTo(Dinero.euros("10.00"));
        assertThat(Dinero.euros("10").hashCode()).isEqualTo(Dinero.euros("10.00").hashCode());
    }

    @Test
    void misma_cantidad_en_monedas_distintas_no_son_iguales() {
        Dinero usd = new Dinero(new BigDecimal("10"), Currency.getInstance("USD"));
        assertThat(Dinero.euros("10")).isNotEqualTo(usd);
    }

    @ParameterizedTest(name = "{0} + {1} = {2}")
    @CsvSource({
            "10.00, 5.50, 15.50",
            "0,     0,    0.00",
            "0.10,  0.20, 0.30"     // con double esto daría 0.30000000000000004
    })
    void suma_importes_de_la_misma_moneda(String a, String b, String esperado) {
        assertThat(Dinero.euros(a).sumar(Dinero.euros(b))).isEqualTo(Dinero.euros(esperado));
    }

    @ParameterizedTest(name = "{0} x {1} = {2}")
    @CsvSource({"25.00, 2, 50.00", "9.99, 3, 29.97", "1.00, 0, 0.00"})
    void multiplica_por_un_entero(String importe, int factor, String esperado) {
        assertThat(Dinero.euros(importe).multiplicar(factor)).isEqualTo(Dinero.euros(esperado));
    }

    @Test
    void redondea_al_numero_de_decimales_de_la_moneda() {
        assertThat(Dinero.euros("10.005")).isEqualTo(Dinero.euros("10.00"));   // HALF_EVEN
        assertThat(Dinero.euros("10.015")).isEqualTo(Dinero.euros("10.02"));
    }

    @Test
    void no_suma_monedas_distintas() {
        Dinero usd = new Dinero(BigDecimal.ONE, Currency.getInstance("USD"));
        assertThatThrownBy(() -> Dinero.euros("1").sumar(usd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("monedas distintas");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "-100"})
    void no_admite_importes_negativos(String importe) {
        assertThatThrownBy(() -> Dinero.euros(importe))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void es_inmutable_sumar_devuelve_otro_objeto() {
        Dinero diez = Dinero.euros("10");
        Dinero resultado = diez.sumar(Dinero.euros("5"));

        assertThat(diez).isEqualTo(Dinero.euros("10"));
        assertThat(resultado).isNotSameAs(diez);
    }
}
```

```java
package com.curso.pedidos.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class CantidadYProductoIdTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void la_cantidad_debe_ser_positiva(int valor) {
        assertThatThrownBy(() -> Cantidad.de(valor)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 1000})
    void acepta_cantidades_positivas(int valor) {
        assertThatNoException().isThrownBy(() -> Cantidad.de(valor));
    }

    @Test
    void sumar_cantidades() {
        assertThat(Cantidad.de(2).sumar(Cantidad.de(3))).isEqualTo(Cantidad.de(5));
    }

    @Test
    void el_sku_se_normaliza_a_mayusculas_y_sin_espacios() {
        assertThat(new ProductoId("  tec-001 ")).isEqualTo(new ProductoId("TEC-001"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "AB", "CON ESPACIO", "ÑANDÚ-1", "DEMASIADO-LARGO-PARA-UN-SKU"})
    void rechaza_skus_invalidos(String sku) {
        assertThatThrownBy(() -> new ProductoId(sku)).isInstanceOf(RuntimeException.class);
    }
}
```

> **Nota:** con `null`, `Objects.requireNonNull` lanza `NullPointerException`; con el resto,
> `IllegalArgumentException`. Por eso el test agrupa en `RuntimeException`. Si queréis precisión,
> separad el caso `null` en su propio test.

---

## 7. Test de un servicio de dominio

Un servicio de dominio es una función pura: entrada → salida. Ni dobles necesita.

```java
package com.curso.pedidos.domain.service;

import com.curso.pedidos.domain.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CalculadoraDePreciosTest {

    private final CalculadoraDePrecios calculadora = new CalculadoraDePrecios();

    @ParameterizedTest(name = "total {0} → con descuento {1}")
    @CsvSource({
            "99.99,  99.99",    // justo por debajo del umbral: sin descuento
            "100.00, 90.00",    // en el umbral: 10 %
            "250.00, 225.00"
    })
    void aplica_un_10_por_ciento_a_partir_de_100_euros(String precio, String esperado) {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(new ProductoId("SKU-001"), Cantidad.de(1), Dinero.euros(precio));

        assertThat(calculadora.totalConDescuento(pedido)).isEqualTo(Dinero.euros(esperado));
    }
}
```

Fijaos en los **valores frontera** (99,99 / 100,00): ahí es donde se esconden los `>` que debían ser `>=`.

---

## 8. Variantes y trampas

### Object Mother / Test Data Builder

Cuando crear un pedido de prueba requiere varias líneas, centralizadlo:

```java
package com.curso.pedidos.domain.model;

public final class PedidoMother {

    private PedidoMother() { }

    public static Pedido borradorConLineas() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(new ProductoId("TEC-001"), Cantidad.de(2), Dinero.euros("25.00"));
        pedido.anadirLinea(new ProductoId("RAT-002"), Cantidad.de(1), Dinero.euros("9.99"));
        return pedido;
    }

    public static Pedido confirmado() {
        Pedido pedido = borradorConLineas();
        pedido.confirmar();
        pedido.extraerEventos();   // limpiamos para que el test empiece "en blanco"
        return pedido;
    }
}
```

Lo reutilizaremos en los tests de servicios de aplicación (bloque 5).

### Trampas

- **Trampa: probar getters.** `assertThat(new Cantidad(3).valor()).isEqualTo(3)` no aporta nada. Probad
  **reglas** y **comportamiento**.
- **Trampa: mockear el dominio.** Nunca hagáis `mock(Pedido.class)`. El dominio es la cosa que
  queréis probar *de verdad*; además es barato de construir.
- **Trampa: un test, veinte asserts sin relación.** Un test = un comportamiento. Varios `assertThat`
  están bien si describen el mismo comportamiento (estado + evento).
- **Trampa: `@SpringBootTest` "porque así funciona".** Si un test del paquete `domain` necesita
  Spring, el problema no es el test: es que el dominio depende de Spring.
- **Trampa: depender de la hora.** No comparéis `ocurridoEn` con `Instant.now()` exacto; usad
  `isNotNull()`, `isBeforeOrEqualTo(...)` o inyectad un `Clock`.

---

## 9. Resumen

| Qué probar                      | Cómo                                         | Herramienta                          |
|---------------------------------|----------------------------------------------|--------------------------------------|
| Invariantes del agregado        | Provocar la violación, esperar la excepción  | `assertThatThrownBy`                 |
| Transiciones de estado          | Matriz de estados                            | `@ParameterizedTest` + `@EnumSource` |
| Eventos emitidos                | `extraerEventos()` tras la operación         | `singleElement()`, `hasExactlyElementsOfTypes` |
| Igualdad de VOs                 | Mismos valores ⇒ iguales (y mismo hashCode)  | `isEqualTo`, `hasSameHashCodeAs`     |
| Validación de VOs               | Valores frontera e inválidos                 | `@ValueSource`, `@NullAndEmptySource`, `@CsvSource` |
| Servicios de dominio            | Entrada → salida                             | `@CsvSource`                         |
| Tiempo de ejecución esperado    | Toda la suite de dominio < 1 s               | Sin Spring, sin BD                   |

---

## Ejercicio

Escribe `EmailTest` para el value object `Email` del [bloque 1](01-componentes-internos-del-dominio.md):

1. Normaliza mayúsculas y espacios.
2. Rechaza al menos 5 formatos inválidos con un único `@ParameterizedTest` (incluye `null` y vacío).
3. Dos emails con distinta capitalización son iguales.

**Pista:** `@NullAndEmptySource` + `@ValueSource` se pueden combinar en el mismo método.

<details><summary>Solución</summary>

```java
package com.curso.pedidos.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTest {

    @Test
    void normaliza_a_minusculas_y_sin_espacios() {
        assertThat(new Email("  Ana@Tienda.ES ").valor()).isEqualTo("ana@tienda.es");
    }

    @Test
    void la_capitalizacion_no_afecta_a_la_igualdad() {
        assertThat(new Email("ANA@tienda.es")).isEqualTo(new Email("ana@TIENDA.es"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "ana", "ana@", "@tienda.es", "ana@tienda", "ana@@tienda.es", "a na@tienda.es"})
    void rechaza_formatos_invalidos(String valor) {
        assertThatThrownBy(() -> new Email(valor)).isInstanceOf(RuntimeException.class);
    }
}
```

</details>

---

## Referencias

- Martin Fowler / Ham Vocke, *The Practical Test Pyramid*: https://martinfowler.com/articles/practical-test-pyramid.html
- JUnit 5 User Guide (tests parametrizados, `@Nested`): https://junit.org/junit5/docs/current/user-guide/
- AssertJ documentation: https://assertj.github.io/doc/
- Vaughn Vernon, *Implementing Domain-Driven Design* (2013), cap. 10 (Aggregates).
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023), capítulo de testing.

## Mantra

> *"Si para probar una regla de negocio necesitas arrancar Spring, la regla no está en el dominio."*
