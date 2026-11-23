package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.AnadirLineaUseCase.AnadirLineaCommand;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.in.ConsultarPedidoQuery.PedidoDetalle;
import com.curso.pedidos.application.port.in.CrearPedidoUseCase.CrearPedidoCommand;
import com.curso.pedidos.application.service.fakes.FakeCatalogoProductos;
import com.curso.pedidos.application.service.fakes.InMemoryPedidoRepository;
import com.curso.pedidos.application.service.fakes.PublicadorDeEventosEnMemoria;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.exception.ProductoNoEncontradoException;
import com.curso.pedidos.domain.exception.StockInsuficienteException;
import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.Producto;
import com.curso.pedidos.domain.model.ProductoId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests de casos de uso con fakes en memoria: rápidos, sin Spring, sin mocks. */
class PedidoServicesTest {

    private static final ProductoId LIBRO = new ProductoId("LIBRO-DDD");
    private static final ProductoId AGOTADO = new ProductoId("AGOTADO");

    private InMemoryPedidoRepository repository;
    private PublicadorDeEventosEnMemoria publicador;
    private CrearPedidoService crearPedido;
    private AnadirLineaService anadirLinea;
    private ConfirmarPedidoService confirmarPedido;
    private ConsultarPedidoService consultarPedido;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPedidoRepository();
        publicador = new PublicadorDeEventosEnMemoria();
        FakeCatalogoProductos catalogo = new FakeCatalogoProductos()
                .con(new Producto(LIBRO, "DDD", Dinero.euros("45.00"), 10))
                .con(new Producto(AGOTADO, "Agotado", Dinero.euros("1.00"), 0));
        Clock clock = Clock.fixed(Instant.parse("2026-11-23T14:00:00Z"), ZoneOffset.UTC);
        crearPedido = new CrearPedidoService(repository, clock);
        anadirLinea = new AnadirLineaService(repository, catalogo);
        confirmarPedido = new ConfirmarPedidoService(repository, publicador);
        consultarPedido = new ConsultarPedidoService(repository);
    }

    private PedidoId pedidoNuevo() {
        return crearPedido.ejecutar(new CrearPedidoCommand(new ClienteId(UUID.randomUUID())));
    }

    @Nested
    class CrearPedido {

        @Test
        void guardaUnPedidoEnBorrador() {
            PedidoId id = pedidoNuevo();

            assertThat(repository.cuantos()).isEqualTo(1);
            assertThat(repository.buscarPorId(id)).get()
                    .extracting(p -> p.estado()).isEqualTo(EstadoPedido.BORRADOR);
        }
    }

    @Nested
    class AnadirLinea {

        @Test
        void usaElPrecioDelCatalogo() {
            PedidoId id = pedidoNuevo();

            anadirLinea.ejecutar(new AnadirLineaCommand(id, LIBRO, Cantidad.de(2)));

            PedidoDetalle detalle = consultarPedido.consultar(id);
            assertThat(detalle.total()).isEqualByComparingTo(new BigDecimal("90.00"));
            assertThat(detalle.lineas()).singleElement()
                    .satisfies(l -> assertThat(l.sku()).isEqualTo("LIBRO-DDD"));
        }

        @Test
        void fallaSiNoHayStock() {
            PedidoId id = pedidoNuevo();

            assertThatThrownBy(() -> anadirLinea.ejecutar(new AnadirLineaCommand(id, AGOTADO, Cantidad.de(1))))
                    .isInstanceOf(StockInsuficienteException.class);
        }

        @Test
        void fallaSiElProductoNoExiste() {
            PedidoId id = pedidoNuevo();

            assertThatThrownBy(() -> anadirLinea.ejecutar(
                    new AnadirLineaCommand(id, new ProductoId("NO-EXISTE"), Cantidad.de(1))))
                    .isInstanceOf(ProductoNoEncontradoException.class);
        }

        @Test
        void fallaSiElPedidoNoExiste() {
            assertThatThrownBy(() -> anadirLinea.ejecutar(
                    new AnadirLineaCommand(PedidoId.nuevo(), LIBRO, Cantidad.de(1))))
                    .isInstanceOf(PedidoNoEncontradoException.class);
        }
    }

    @Nested
    class ConfirmarPedido {

        @Test
        void confirmaYPublicaElEvento() {
            PedidoId id = pedidoNuevo();
            anadirLinea.ejecutar(new AnadirLineaCommand(id, LIBRO, Cantidad.de(1)));

            confirmarPedido.ejecutar(new ConfirmarPedidoCommand(id));

            assertThat(consultarPedido.consultar(id).estado()).isEqualTo("CONFIRMADO");
            assertThat(publicador.publicados()).singleElement()
                    .isInstanceOfSatisfying(PedidoConfirmado.class,
                            e -> assertThat(e.pedidoId()).isEqualTo(id));
        }

        @Test
        void noPublicaNadaSiLaReglaDeNegocioFalla() {
            PedidoId id = pedidoNuevo();

            assertThatThrownBy(() -> confirmarPedido.ejecutar(new ConfirmarPedidoCommand(id)));
            assertThat(publicador.publicados()).isEmpty();
        }
    }
}
