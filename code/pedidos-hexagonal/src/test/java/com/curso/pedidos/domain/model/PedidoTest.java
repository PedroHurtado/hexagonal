package com.curso.pedidos.domain.model;

import com.curso.pedidos.domain.event.PedidoCancelado;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Pedido (agregado raíz)")
class PedidoTest {

    private static final Instant AHORA = Instant.parse("2026-11-23T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(AHORA, ZoneOffset.UTC);
    private static final ProductoId LIBRO = new ProductoId("LIBRO-DDD");
    private static final ProductoId TAZA = new ProductoId("TAZA-JAVA");

    private Pedido pedido;

    @BeforeEach
    void setUp() {
        pedido = Pedido.crear(new ClienteId(UUID.randomUUID()), CLOCK);
    }

    @Nested
    @DisplayName("al crearlo")
    class AlCrear {

        @Test
        void naceEnBorradorSinLineasYConTotalCero() {
            assertThat(pedido.estado()).isEqualTo(EstadoPedido.BORRADOR);
            assertThat(pedido.lineas()).isEmpty();
            assertThat(pedido.total()).isEqualTo(Dinero.euros("0"));
            assertThat(pedido.creadoEn()).isEqualTo(AHORA);
            assertThat(pedido.extraerEventos()).isEmpty();
        }
    }

    @Nested
    @DisplayName("al añadir líneas")
    class AlAnadirLineas {

        @Test
        void calculaElTotalSumandoSubtotales() {
            pedido.anadirLinea(LIBRO, Cantidad.de(2), Dinero.euros("45.00"));
            pedido.anadirLinea(TAZA, Cantidad.de(3), Dinero.euros("12.50"));

            assertThat(pedido.lineas()).hasSize(2);
            assertThat(pedido.total()).isEqualTo(Dinero.euros("127.50"));
        }

        @Test
        void agrupaLasLineasDelMismoProducto() {
            pedido.anadirLinea(LIBRO, Cantidad.de(1), Dinero.euros("45.00"));
            pedido.anadirLinea(LIBRO, Cantidad.de(2), Dinero.euros("45.00"));

            assertThat(pedido.lineas()).singleElement()
                    .satisfies(l -> assertThat(l.cantidad()).isEqualTo(Cantidad.de(3)));
        }

        @Test
        void noPermiteModificarLaListaDesdeFuera() {
            assertThatThrownBy(() -> pedido.lineas().add(new LineaPedido(LIBRO, Cantidad.de(1), Dinero.euros("1"))))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void rechazaLineasEnUnPedidoConfirmado() {
            pedido.anadirLinea(LIBRO, Cantidad.de(1), Dinero.euros("45.00"));
            pedido.confirmar();

            assertThatThrownBy(() -> pedido.anadirLinea(TAZA, Cantidad.de(1), Dinero.euros("12.50")))
                    .isInstanceOf(PedidoException.class)
                    .hasMessageContaining("CONFIRMADO");
        }
    }

    @Nested
    @DisplayName("al confirmarlo")
    class AlConfirmar {

        @Test
        void pasaAConfirmadoYRegistraElEvento() {
            pedido.anadirLinea(LIBRO, Cantidad.de(1), Dinero.euros("45.00"));

            pedido.confirmar();

            assertThat(pedido.estado()).isEqualTo(EstadoPedido.CONFIRMADO);
            assertThat(pedido.extraerEventos())
                    .containsExactly(new PedidoConfirmado(pedido.id(), AHORA));
        }

        @Test
        void losEventosSeExtraenUnaSolaVez() {
            pedido.anadirLinea(LIBRO, Cantidad.de(1), Dinero.euros("45.00"));
            pedido.confirmar();
            pedido.extraerEventos();

            assertThat(pedido.extraerEventos()).isEmpty();
        }

        @Test
        void noSePuedeConfirmarUnPedidoVacio() {
            assertThatThrownBy(pedido::confirmar)
                    .isInstanceOf(PedidoException.class)
                    .hasMessageContaining("vacio");
            assertThat(pedido.estado()).isEqualTo(EstadoPedido.BORRADOR);
        }

        @Test
        void noSePuedeConfirmarDosVeces() {
            pedido.anadirLinea(LIBRO, Cantidad.de(1), Dinero.euros("45.00"));
            pedido.confirmar();

            assertThatThrownBy(pedido::confirmar)
                    .isInstanceOf(TransicionDeEstadoInvalidaException.class);
        }
    }

    @Nested
    @DisplayName("al cancelarlo")
    class AlCancelar {

        @ParameterizedTest(name = "se puede cancelar desde {0}")
        @EnumSource(value = EstadoPedido.class, names = {"BORRADOR", "CONFIRMADO", "PAGADO"})
        void sePuedeCancelarSiNoEstaEnviado(EstadoPedido estado) {
            Pedido p = Pedido.reconstituir(PedidoId.nuevo(), new ClienteId(UUID.randomUUID()),
                    java.util.List.of(), estado, AHORA);

            p.cancelar();

            assertThat(p.estado()).isEqualTo(EstadoPedido.CANCELADO);
            assertThat(p.extraerEventos()).singleElement().isInstanceOf(PedidoCancelado.class);
        }

        @ParameterizedTest(name = "no se puede cancelar desde {0}")
        @EnumSource(value = EstadoPedido.class, names = {"ENVIADO", "CANCELADO"})
        void noSePuedeCancelarUnPedidoEnviadoOCancelado(EstadoPedido estado) {
            Pedido p = Pedido.reconstituir(PedidoId.nuevo(), new ClienteId(UUID.randomUUID()),
                    java.util.List.of(), estado, AHORA);

            assertThatThrownBy(p::cancelar).isInstanceOf(TransicionDeEstadoInvalidaException.class);
        }
    }

    @Nested
    @DisplayName("ciclo de vida completo")
    class CicloDeVida {

        @Test
        void borradorConfirmadoPagadoEnviado() {
            pedido.anadirLinea(LIBRO, Cantidad.de(1), Dinero.euros("45.00"));
            pedido.confirmar();
            pedido.marcarComoPagado();
            pedido.marcarComoEnviado();

            assertThat(pedido.estado()).isEqualTo(EstadoPedido.ENVIADO);
            assertThat(pedido.extraerEventos()).hasSize(2);
        }

        @Test
        void noSePuedePagarUnBorrador() {
            assertThatThrownBy(pedido::marcarComoPagado)
                    .isInstanceOf(TransicionDeEstadoInvalidaException.class)
                    .hasMessageContaining("BORRADOR");
        }
    }
}
