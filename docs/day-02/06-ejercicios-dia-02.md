# Día 2 — Bloque 6: Ejercicios y preguntas de repaso

> *"I hear and I forget. I see and I remember. I do and I understand."* — Proverbio (atribuido a Confucio)

---

## Cómo trabajar estos ejercicios

- Son **graduados**: del 1 (básico, solo dominio) al 4 (avanzado, puertos + servicio + dobles).
- En clase da tiempo a hacer el 1 y empezar el 2 (~25 min). El resto, para casa.
- **Regla de oro:** todo se resuelve **sin Spring**. Si os veis escribiendo `@SpringBootTest`,
  parad y repensad.
- Partid del código de los bloques 1–5. Las soluciones están en `<details>`: intentadlo antes.

| # | Ejercicio                                 | Nivel       | Toca                                   | Tiempo estimado |
|---|-------------------------------------------|-------------|----------------------------------------|-----------------|
| 1 | `Producto` con gestión de stock           | Básico      | Dominio + tests unitarios              | 15 min          |
| 2 | Caso de uso `CancelarPedido`              | Intermedio  | Puerto de entrada + servicio + fakes   | 25 min          |
| 3 | Consulta `ConsultarPedidoQuery`           | Intermedio  | Puerto de consulta + modelo de lectura | 15 min          |
| 4 | Caso de uso `PagarPedido` con pasarela    | Avanzado    | Puerto de salida `sealed` + Mockito    | 30 min          |

---

## Ejercicio 1 — `Producto` con stock y `StockInsuficienteException` (básico)

Amplía `Producto` (bloque 1) con:

1. `retirarStock(Cantidad cantidad)`: descuenta unidades. Si no hay suficientes, lanza
   `StockInsuficienteException` **sin modificar** el stock.
2. `reponerStock(Cantidad cantidad)`: añade unidades.
3. Tests unitarios que cubran: retirada correcta, retirada exacta (deja 0), retirada excesiva (con
   `solicitado` y `disponible` correctos en la excepción) y reposición. Usa al menos un
   `@ParameterizedTest`.

**Pista:** `Cantidad` ya garantiza que el número es positivo; no vuelvas a validarlo.

<details><summary>Solución</summary>

```java
// Métodos añadidos a com.curso.pedidos.domain.model.Producto
// (import com.curso.pedidos.domain.exception.StockInsuficienteException;)

public void retirarStock(Cantidad cantidad) {
    if (!hayStockPara(cantidad)) {
        throw new StockInsuficienteException(id, cantidad.valor(), stock);
    }
    stock -= cantidad.valor();
}

public void reponerStock(Cantidad cantidad) {
    stock += cantidad.valor();
}
```

```java
package com.curso.pedidos.domain.model;

import com.curso.pedidos.domain.exception.StockInsuficienteException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductoTest {

    private static final ProductoId SKU = new ProductoId("TEC-001");

    private Producto productoConStock(int stock) {
        return new Producto(SKU, "Teclado", Dinero.euros("25.00"), stock);
    }

    @ParameterizedTest(name = "stock {0} - retirar {1} = {2}")
    @CsvSource({"10, 3, 7", "10, 10, 0", "1, 1, 0"})
    void retira_unidades_si_hay_stock(int inicial, int retirar, int esperado) {
        Producto producto = productoConStock(inicial);

        producto.retirarStock(Cantidad.de(retirar));

        assertThat(producto.stock()).isEqualTo(esperado);
    }

    @Test
    void no_retira_mas_de_lo_disponible_y_no_modifica_el_stock() {
        Producto producto = productoConStock(5);

        assertThatThrownBy(() -> producto.retirarStock(Cantidad.de(6)))
                .isInstanceOfSatisfying(StockInsuficienteException.class, ex -> {
                    assertThat(ex.productoId()).isEqualTo(SKU);
                    assertThat(ex.solicitado()).isEqualTo(6);
                    assertThat(ex.disponible()).isEqualTo(5);
                });
        assertThat(producto.stock()).isEqualTo(5);
    }

    @Test
    void repone_stock() {
        Producto producto = productoConStock(0);

        producto.reponerStock(Cantidad.de(4));

        assertThat(producto.stock()).isEqualTo(4);
        assertThat(producto.hayStockPara(Cantidad.de(4))).isTrue();
        assertThat(producto.hayStockPara(Cantidad.de(5))).isFalse();
    }

    @Test
    void no_admite_stock_inicial_negativo() {
        assertThatThrownBy(() -> productoConStock(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

**Discusión:** ¿por qué `retirarStock` está en `Producto` y no en un servicio? Porque la invariante
"el stock nunca es negativo" es de `Producto` y solo él puede protegerla. ¿Quién lo llamará?
Un caso de uso (p. ej. al pagar o enviar), **en otra transacción** distinta de la del pedido: un
agregado por transacción.

</details>

---

## Ejercicio 2 — Caso de uso `CancelarPedido` (intermedio)

Implementa el caso de uso completo de cancelación:

1. Puerto de entrada `CancelarPedidoUseCase` en `application.port.in`.
2. `CancelarPedidoCommand(PedidoId pedidoId, String motivo)`: motivo obligatorio, no en blanco, máximo
   200 caracteres (recortando espacios).
3. `CancelarPedidoService` en `application.service`: carga, cancela (las reglas ya están en
   `Pedido.cancelar()`), guarda y publica eventos.
4. Tests con los **fakes** del bloque 5: cancelación correcta (estado + evento `PedidoCancelado`),
   pedido inexistente, pedido enviado (sin efectos), y validación del comando.

**Pista:** si el servicio te sale con un `if` sobre el estado, bórralo: esa regla ya la tiene el agregado.

<details><summary>Solución</summary>

```java
package com.curso.pedidos.application.port.in;

public interface CancelarPedidoUseCase {
    void ejecutar(CancelarPedidoCommand comando);
}
```

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;

import java.util.Objects;

public record CancelarPedidoCommand(PedidoId pedidoId, String motivo) {

    private static final int LONGITUD_MAXIMA_MOTIVO = 200;

    public CancelarPedidoCommand {
        Objects.requireNonNull(pedidoId, "El id del pedido es obligatorio");
        Objects.requireNonNull(motivo, "El motivo es obligatorio");
        motivo = motivo.trim();
        if (motivo.isEmpty()) {
            throw new IllegalArgumentException("El motivo no puede estar en blanco");
        }
        if (motivo.length() > LONGITUD_MAXIMA_MOTIVO) {
            throw new IllegalArgumentException("El motivo no puede superar " + LONGITUD_MAXIMA_MOTIVO + " caracteres");
        }
    }
}
```

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.CancelarPedidoCommand;
import com.curso.pedidos.application.port.in.CancelarPedidoUseCase;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.Pedido;

import java.util.Objects;

public class CancelarPedidoService implements CancelarPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final PublicadorDeEventos publicadorDeEventos;

    public CancelarPedidoService(PedidoRepository pedidoRepository, PublicadorDeEventos publicadorDeEventos) {
        this.pedidoRepository = Objects.requireNonNull(pedidoRepository);
        this.publicadorDeEventos = Objects.requireNonNull(publicadorDeEventos);
    }

    @Override
    public void ejecutar(CancelarPedidoCommand comando) {
        Pedido pedido = pedidoRepository.buscarPorId(comando.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(comando.pedidoId()));
        pedido.cancelar();
        pedidoRepository.guardar(pedido);
        publicadorDeEventos.publicar(pedido.extraerEventos());
    }
}
```

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.fakes.PedidoRepositoryEnMemoria;
import com.curso.pedidos.application.fakes.PublicadorDeEventosEnMemoria;
import com.curso.pedidos.application.port.in.CancelarPedidoCommand;
import com.curso.pedidos.domain.event.PedidoCancelado;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import com.curso.pedidos.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancelarPedidoServiceTest {

    private PedidoRepositoryEnMemoria pedidos;
    private PublicadorDeEventosEnMemoria publicador;
    private CancelarPedidoService servicio;

    @BeforeEach
    void setUp() {
        pedidos = new PedidoRepositoryEnMemoria();
        publicador = new PublicadorDeEventosEnMemoria();
        servicio = new CancelarPedidoService(pedidos, publicador);
    }

    private Pedido guardarPedidoEn(EstadoPedido estado) {
        Pedido pedido = Pedido.reconstituir(PedidoId.nuevo(), ClienteId.nuevo(),
                List.of(new LineaPedido(new ProductoId("TEC-001"), Cantidad.de(1), Dinero.euros("10"))),
                estado, Instant.now());
        pedidos.guardar(pedido);
        return pedido;
    }

    @Test
    void cancela_un_pedido_confirmado_y_publica_el_evento() {
        Pedido pedido = guardarPedidoEn(EstadoPedido.CONFIRMADO);

        servicio.ejecutar(new CancelarPedidoCommand(pedido.id(), "Me he equivocado de talla"));

        assertThat(pedidos.buscarPorId(pedido.id()))
                .map(Pedido::estado).hasValue(EstadoPedido.CANCELADO);
        assertThat(publicador.publicados()).singleElement()
                .isInstanceOfSatisfying(PedidoCancelado.class,
                        e -> assertThat(e.pedidoId()).isEqualTo(pedido.id()));
    }

    @Test
    void falla_si_el_pedido_no_existe() {
        assertThatThrownBy(() -> servicio.ejecutar(new CancelarPedidoCommand(PedidoId.nuevo(), "motivo")))
                .isInstanceOf(PedidoNoEncontradoException.class);
        assertThat(publicador.publicados()).isEmpty();
    }

    @Test
    void no_cancela_un_pedido_enviado_ni_publica_nada() {
        Pedido pedido = guardarPedidoEn(EstadoPedido.ENVIADO);

        assertThatThrownBy(() -> servicio.ejecutar(new CancelarPedidoCommand(pedido.id(), "Tarde")))
                .isInstanceOf(TransicionDeEstadoInvalidaException.class);
        assertThat(pedido.estado()).isEqualTo(EstadoPedido.ENVIADO);
        assertThat(publicador.publicados()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void el_comando_exige_motivo(String motivo) {
        assertThatThrownBy(() -> new CancelarPedidoCommand(PedidoId.nuevo(), motivo))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void el_comando_limita_la_longitud_del_motivo() {
        String largo = "x".repeat(201);
        assertThatThrownBy(() -> new CancelarPedidoCommand(PedidoId.nuevo(), largo))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CancelarPedidoCommand(PedidoId.nuevo(), "  " + "x".repeat(200) + "  ").motivo())
                .hasSize(200);
    }
}
```

**Para pensar:** el `motivo` no llega al evento `PedidoCancelado` (que según el modelo del curso solo
lleva `pedidoId` y `ocurridoEn`). Si el negocio necesita guardarlo, ¿dónde debería ir? Opciones:
`pedido.cancelar(MotivoCancelacion motivo)` con un nuevo value object, y añadir el campo al evento.
Fíjate en que **el cambio empieza en el dominio**, no en el servicio.

**Y si hay que avisar al cliente**, ampliaríamos `NotificadorPedidos` con `notificarCancelacion(...)`
o, mejor aún (ISP), un listener de `PedidoCancelado` en infraestructura (días 3–4).

</details>

---

## Ejercicio 3 — Consulta `ConsultarPedidoQuery` (intermedio)

Implementa `ConsultarPedidoService implements ConsultarPedidoQuery` (puerto definido en el bloque 3)
y sus tests:

- Devuelve un `PedidoDetalle` con total y líneas.
- Lanza `PedidoNoEncontradoException` si no existe.
- **No modifica** nada.

**Pista:** ¿qué puerto de salida necesitas? Solo uno.

<details><summary>Solución</summary>

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.ConsultarPedidoQuery;
import com.curso.pedidos.application.port.in.PedidoDetalle;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.PedidoId;

public class ConsultarPedidoService implements ConsultarPedidoQuery {

    private final PedidoRepository pedidoRepository;

    public ConsultarPedidoService(PedidoRepository pedidoRepository) {
        this.pedidoRepository = pedidoRepository;
    }

    @Override
    public PedidoDetalle ejecutar(PedidoId pedidoId) {
        return pedidoRepository.buscarPorId(pedidoId)
                .map(PedidoDetalle::desde)
                .orElseThrow(() -> new PedidoNoEncontradoException(pedidoId));
    }
}
```

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.fakes.PedidoRepositoryEnMemoria;
import com.curso.pedidos.application.port.in.PedidoDetalle;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsultarPedidoServiceTest {

    private final PedidoRepositoryEnMemoria pedidos = new PedidoRepositoryEnMemoria();
    private final ConsultarPedidoService servicio = new ConsultarPedidoService(pedidos);

    @Test
    void devuelve_el_detalle_del_pedido() {
        Pedido pedido = PedidoMother.borradorConLineas();   // 2 x 25,00 + 1 x 9,99
        pedidos.guardar(pedido);

        PedidoDetalle detalle = servicio.ejecutar(pedido.id());

        assertThat(detalle.estado()).isEqualTo(EstadoPedido.BORRADOR);
        assertThat(detalle.total()).isEqualTo(Dinero.euros("59.99"));
        assertThat(detalle.lineas()).extracting(PedidoDetalle.Linea::cantidad).containsExactly(2, 1);
        assertThat(pedidos.cantidad()).isEqualTo(1);
    }

    @Test
    void lanza_excepcion_si_no_existe() {
        PedidoId id = PedidoId.nuevo();
        assertThatThrownBy(() -> servicio.ejecutar(id))
                .isInstanceOf(PedidoNoEncontradoException.class)
                .hasMessageContaining(id.valor().toString());
    }
}
```

(`PedidoMother` vive en el paquete `com.curso.pedidos.domain.model` de test; impórtalo.)

**Para pensar:** cargar el agregado entero para leer es simple y suficiente aquí. Cuando las
consultas sean pesadas (listados, filtros), el puerto de consulta podrá tener **su propio puerto de
salida de lectura** (proyecciones) sin pasar por el agregado. Lo vemos en el día 6.

</details>

---

## Ejercicio 4 — Caso de uso `PagarPedido` con `PasarelaDePago` (avanzado)

1. Puerto `PagarPedidoUseCase` con `PagarPedidoCommand(PedidoId pedidoId)`.
2. `PagarPedidoService` que:
   - carga el pedido (o `PedidoNoEncontradoException`),
   - **comprueba en el dominio** que el pedido se puede pagar antes de cobrar (no queremos cobrar un
     pedido en `BORRADOR` y luego descubrir que no se podía marcar como pagado),
   - llama a `PasarelaDePago.cobrar(id, total)`,
   - si el resultado es `Rechazado`, lanza `PagoRechazadoException` (nueva, de dominio),
   - si es `Aprobado`, `marcarComoPagado()`, guarda y publica eventos.
3. Tests con **Mockito** para la pasarela (aprobado, rechazado, fallo técnico) y fake para el repositorio.

**Pistas:**

- En Java 17 usa `instanceof` con patrón para consumir el `sealed` `ResultadoPago`.
- Para "comprobar que se puede pagar" añade un método al agregado; no metas un `if (estado == ...)`
  en el servicio.

<details><summary>Solución</summary>

Método nuevo en `Pedido` (la regla sigue en el dominio):

```java
/** Lanza TransicionDeEstadoInvalidaException si el pedido no está CONFIRMADO. */
public void comprobarQueSePuedePagar() {
    exigirEstado(EstadoPedido.CONFIRMADO, EstadoPedido.PAGADO);
}
```

Y `marcarComoPagado()` puede reutilizarlo:

```java
public void marcarComoPagado() {
    comprobarQueSePuedePagar();
    estado = EstadoPedido.PAGADO;
    eventos.add(new PedidoPagado(id, Instant.now()));
}
```

Nueva excepción de dominio:

```java
package com.curso.pedidos.domain.exception;

import com.curso.pedidos.domain.model.PedidoId;

public class PagoRechazadoException extends PedidoException {
    public PagoRechazadoException(PedidoId id, String motivo) {
        super("El pago del pedido %s ha sido rechazado: %s".formatted(id.valor(), motivo));
    }
}
```

Puerto, comando y servicio:

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;
import java.util.Objects;

public record PagarPedidoCommand(PedidoId pedidoId) {
    public PagarPedidoCommand {
        Objects.requireNonNull(pedidoId, "El id del pedido es obligatorio");
    }
}
```

```java
package com.curso.pedidos.application.port.in;

public interface PagarPedidoUseCase {
    void ejecutar(PagarPedidoCommand comando);
}
```

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.PagarPedidoCommand;
import com.curso.pedidos.application.port.in.PagarPedidoUseCase;
import com.curso.pedidos.application.port.out.PasarelaDePago;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.application.port.out.ResultadoPago;
import com.curso.pedidos.domain.exception.PagoRechazadoException;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.Pedido;

public class PagarPedidoService implements PagarPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final PasarelaDePago pasarelaDePago;
    private final PublicadorDeEventos publicadorDeEventos;

    public PagarPedidoService(PedidoRepository pedidoRepository,
                              PasarelaDePago pasarelaDePago,
                              PublicadorDeEventos publicadorDeEventos) {
        this.pedidoRepository = pedidoRepository;
        this.pasarelaDePago = pasarelaDePago;
        this.publicadorDeEventos = publicadorDeEventos;
    }

    @Override
    public void ejecutar(PagarPedidoCommand comando) {
        Pedido pedido = pedidoRepository.buscarPorId(comando.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(comando.pedidoId()));

        pedido.comprobarQueSePuedePagar();

        ResultadoPago resultado = pasarelaDePago.cobrar(pedido.id(), pedido.total());
        if (resultado instanceof ResultadoPago.Rechazado rechazado) {
            throw new PagoRechazadoException(pedido.id(), rechazado.motivo());
        }

        pedido.marcarComoPagado();
        pedidoRepository.guardar(pedido);
        publicadorDeEventos.publicar(pedido.extraerEventos());
    }
}
```

> En Java 21 el `if` se puede escribir como `switch (resultado) { case Aprobado a -> ...; case
> Rechazado r -> throw ...; }`, exhaustivo y sin `default`. En Java 17 los patrones en `switch` son
> *preview*, por eso usamos `instanceof`.

Tests:

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.fakes.PedidoRepositoryEnMemoria;
import com.curso.pedidos.application.fakes.PublicadorDeEventosEnMemoria;
import com.curso.pedidos.application.port.in.PagarPedidoCommand;
import com.curso.pedidos.application.port.out.PasarelaDePago;
import com.curso.pedidos.application.port.out.ResultadoPago;
import com.curso.pedidos.domain.event.PedidoPagado;
import com.curso.pedidos.domain.exception.PagoRechazadoException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import com.curso.pedidos.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PagarPedidoServiceTest {

    @Mock PasarelaDePago pasarela;

    private PedidoRepositoryEnMemoria pedidos;
    private PublicadorDeEventosEnMemoria publicador;
    private PagarPedidoService servicio;

    @BeforeEach
    void setUp() {
        pedidos = new PedidoRepositoryEnMemoria();
        publicador = new PublicadorDeEventosEnMemoria();
        servicio = new PagarPedidoService(pedidos, pasarela, publicador);
    }

    private Pedido pedidoConfirmadoGuardado() {
        Pedido pedido = PedidoMother.confirmado();          // total 59,99 €
        pedidos.guardar(pedido);
        return pedido;
    }

    @Test
    void cobra_el_total_y_marca_como_pagado_si_la_pasarela_aprueba() {
        Pedido pedido = pedidoConfirmadoGuardado();
        when(pasarela.cobrar(pedido.id(), Dinero.euros("59.99")))
                .thenReturn(new ResultadoPago.Aprobado("ch_123"));

        servicio.ejecutar(new PagarPedidoCommand(pedido.id()));

        assertThat(pedido.estado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(publicador.publicados()).singleElement().isInstanceOf(PedidoPagado.class);
    }

    @Test
    void si_la_pasarela_rechaza_lanza_excepcion_y_el_pedido_sigue_confirmado() {
        Pedido pedido = pedidoConfirmadoGuardado();
        when(pasarela.cobrar(any(), any())).thenReturn(new ResultadoPago.Rechazado("Fondos insuficientes"));

        assertThatThrownBy(() -> servicio.ejecutar(new PagarPedidoCommand(pedido.id())))
                .isInstanceOf(PagoRechazadoException.class)
                .hasMessageContaining("Fondos insuficientes");
        assertThat(pedido.estado()).isEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(publicador.publicados()).isEmpty();
    }

    @Test
    void un_fallo_tecnico_de_la_pasarela_se_propaga_sin_cambiar_el_pedido() {
        Pedido pedido = pedidoConfirmadoGuardado();
        when(pasarela.cobrar(any(), any())).thenThrow(new IllegalStateException("timeout"));

        assertThatThrownBy(() -> servicio.ejecutar(new PagarPedidoCommand(pedido.id())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(pedido.estado()).isEqualTo(EstadoPedido.CONFIRMADO);
    }

    @Test
    void no_cobra_un_pedido_en_borrador() {
        Pedido borrador = PedidoMother.borradorConLineas();
        pedidos.guardar(borrador);

        assertThatThrownBy(() -> servicio.ejecutar(new PagarPedidoCommand(borrador.id())))
                .isInstanceOf(TransicionDeEstadoInvalidaException.class);
        verifyNoInteractions(pasarela);                      // ¡no se ha cobrado!
    }
}
```

Fijaos en la mezcla: **fake** para el repositorio (datos) y **Mockito** para la pasarela (efecto
externo y simulación de fallos). Es la recomendación del bloque 5 en acción.

**Para pensar:** ¿qué pasa si la pasarela aprueba pero `guardar` falla? Hemos cobrado y el pedido
sigue `CONFIRMADO`. Esto se resuelve con idempotencia en la pasarela (reintentar con la misma clave)
y/o con una saga y compensación (reembolso). Lo vemos en el día 6.

</details>

---

## Preguntas de repaso

Responde mentalmente (o en voz alta en clase) y despliega para comprobar.

<details><summary>1. ¿Cuál es la diferencia esencial entre una entidad y un value object?</summary>

La entidad tiene **identidad** que perdura aunque cambien sus datos (dos pedidos con los mismos
datos pero distinto `PedidoId` son pedidos distintos). El value object se define **solo por sus
valores** y es inmutable (`Dinero.euros("10")` es igual a cualquier otro `Dinero.euros("10.00")`).
En Java 17: entidades como `class` con `equals` por id; VOs como `record`.

</details>

<details><summary>2. ¿Por qué Dinero normaliza la escala del BigDecimal en su constructor?</summary>

Porque `BigDecimal.equals` compara también la escala (`10.0` ≠ `10.00`) y el `equals` del record
delega en el de cada campo. Sin normalizar, dos importes iguales no serían iguales como value objects.

</details>

<details><summary>3. ¿Qué es un agregado raíz y qué tres reglas prácticas de Vernon recuerdas?</summary>

Es la única entidad del agregado accesible desde fuera; protege las invariantes de todo el grupo.
Reglas: proteger invariantes reales dentro de la frontera, diseñar agregados pequeños, referenciar
otros agregados por id, y modificar un solo agregado por transacción (consistencia eventual entre
agregados mediante eventos).

</details>

<details><summary>4. Nombra tres síntomas de un modelo anémico.</summary>

Entidades con getters/setters y sin comportamiento; reglas de negocio en servicios (`if (p.getEstado()...)`);
campos derivados almacenados a mano (`total`); estado como `String`; excepciones genéricas. Para
probar una regla hace falta montar el servicio con mocks.

</details>

<details><summary>5. ¿Por qué el dominio no lleva @Entity ni Lombok?</summary>

JPA impone constructor vacío, campos mutables y diseño guiado por tablas; Lombok `@Data` genera
setters (rompen invariantes) y `equals` sobre todos los campos (rompe identidad). Además, ambos atan
el corazón del negocio a un framework. Sin ellos, el dominio se prueba con `new` en milisegundos.

</details>

<details><summary>6. ¿Dónde se valida un comando y qué tipo de validación se hace ahí?</summary>

En el **constructor compacto** del `record`. Validación sintáctica/de forma: obligatoriedad, longitud,
al menos una línea, copia defensiva. Las reglas de negocio (stock, estados) siguen en el dominio.

</details>

<details><summary>7. Primario, driving, de entrada: ¿qué tienen en común? ¿Y secundario, driven, de salida?</summary>

Son tres nombres para lo mismo. Los primarios/driving/de entrada los **invoca un actor externo**
(REST, test, cron) y exponen casos de uso. Los secundarios/driven/de salida los **invoca la
aplicación** para obtener algo del exterior (BD, pasarela, email). El criterio es **quién inicia**.

</details>

<details><summary>8. ¿Quién define el puerto PedidoRepository y quién lo implementa? ¿Por qué importa?</summary>

Lo define el hexágono (`application.port.out`) y lo implementa un adaptador de infraestructura
(`PedidoPersistenceAdapter`). Importa porque así la dependencia de código apunta hacia dentro (DIP):
el núcleo no conoce JPA, y podemos sustituir la implementación por un fake en tests.

</details>

<details><summary>9. ¿Qué tipos nunca deberían cruzar un puerto?</summary>

DTOs web (`PedidoRequest`), entidades JPA (`PedidoJpaEntity`) y tipos de framework
(`ResponseEntity`, `HttpServletRequest`, `Pageable`, `ConsumerRecord`, `JsonNode`). Sí cruzan:
comandos, resultados, value objects, agregados (en puertos de salida), eventos y excepciones de dominio.

</details>

<details><summary>10. ¿Un puerto por caso de uso o un "PedidoService" con todo? Argumenta.</summary>

Un puerto por caso de uso en la entrada: cumple ISP, los dobles son triviales, la carpeta `port.in`
documenta lo que hace la aplicación y facilita seguridad/transacciones por caso. El coste es tener
más ficheros. En la salida, un puerto por capacidad cohesiva (un repositorio por agregado).

</details>

<details><summary>11. ¿Qué hace y qué no hace un servicio de aplicación?</summary>

Hace: cargar agregados, invocar al dominio, guardar, publicar eventos, notificar; es la frontera
transaccional. No hace: decidir reglas de negocio, cambiar campos del agregado a mano, conocer
tecnologías concretas, capturar excepciones de dominio para ocultarlas.

</details>

<details><summary>12. ¿Cuándo prefieres un fake en memoria y cuándo Mockito?</summary>

Fakes para puertos de datos (repositorios, catálogos): verifican estado, sobreviven a refactors y se
validan con un test de contrato. Mockito para puertos de efectos (notificador, pasarela) cuando
importa *que* se llamó o el orden, y para simular fallos (`thenThrow`). Mezclarlos es válido.
Nunca se mockea el dominio.

</details>

<details><summary>13. ¿Qué es un test de contrato de un puerto de salida y para qué sirve?</summary>

Una clase de test abstracta con los comportamientos que toda implementación del puerto debe cumplir
(`devuelve_vacio_si_no_existe`, `guarda_y_recupera`...). El fake y el adaptador real heredan de ella.
Si ambos la pasan, podemos fiarnos del fake en los tests de aplicación.

</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture*: https://alistair.cockburn.us/hexagonal-architecture/
- Alistair Cockburn & Juan Manuel Garrido de Paz, *Hexagonal Architecture Explained* (2024).
- Eric Evans, *Domain-Driven Design* (2003); Vaughn Vernon, *Implementing Domain-Driven Design* (2013).
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023) y https://github.com/thombergs/buckpal
- Martin Fowler, *AnemicDomainModel*: https://martinfowler.com/bliki/AnemicDomainModel.html
- JUnit 5: https://junit.org/junit5/docs/current/user-guide/ ; AssertJ: https://assertj.github.io/doc/ ; Mockito: https://site.mockito.org/
- Chris Richardson, *Saga*: https://microservices.io/patterns/data/saga.html

## Mantra

> *"Primero la regla en el dominio, luego el puerto que la expone, luego el servicio que la orquesta.
> Y un test en cada paso."*
