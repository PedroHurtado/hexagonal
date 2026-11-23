package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.CancelarPedidoUseCase.CancelarPedidoCommand;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.EventoDeDominio;
import com.curso.pedidos.domain.event.PedidoCancelado;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/** Mismo estilo de test, pero con Mockito: útil para verificar interacciones con puertos. */
@ExtendWith(MockitoExtension.class)
class CancelarPedidoServiceTest {

    @Mock
    private PedidoRepository pedidoRepository;

    @Mock
    private PublicadorDeEventos publicadorDeEventos;

    @InjectMocks
    private CancelarPedidoService service;

    @Captor
    private ArgumentCaptor<List<EventoDeDominio>> eventosCaptor;

    @Test
    void cancelaGuardaYPublicaPedidoCancelado() {
        Pedido pedido = Pedido.crear(new ClienteId(UUID.randomUUID()));
        given(pedidoRepository.buscarPorId(pedido.id())).willReturn(Optional.of(pedido));

        service.ejecutar(new CancelarPedidoCommand(pedido.id()));

        then(pedidoRepository).should().guardar(pedido);
        then(publicadorDeEventos).should().publicar(eventosCaptor.capture());
        assertThat(pedido.estado()).isEqualTo(EstadoPedido.CANCELADO);
        assertThat(eventosCaptor.getValue()).singleElement().isInstanceOf(PedidoCancelado.class);
    }

    @Test
    void siNoExisteNoGuardaNiPublica() {
        PedidoId id = PedidoId.nuevo();
        given(pedidoRepository.buscarPorId(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.ejecutar(new CancelarPedidoCommand(id)))
                .isInstanceOf(PedidoNoEncontradoException.class);

        then(pedidoRepository).should(never()).guardar(any());
        then(publicadorDeEventos).shouldHaveNoInteractions();
    }
}
