package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;

import java.util.Objects;

/** Puerto de entrada: cancelar un pedido (publica PedidoCancelado). */
@FunctionalInterface
public interface CancelarPedidoUseCase {

    void ejecutar(CancelarPedidoCommand command);

    record CancelarPedidoCommand(PedidoId pedidoId) {
        public CancelarPedidoCommand {
            Objects.requireNonNull(pedidoId, "pedidoId obligatorio");
        }
    }
}
