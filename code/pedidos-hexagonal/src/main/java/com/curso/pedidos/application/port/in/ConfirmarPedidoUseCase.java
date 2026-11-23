package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;

import java.util.Objects;

/** Puerto de entrada: confirmar un pedido (publica PedidoConfirmado). */
@FunctionalInterface
public interface ConfirmarPedidoUseCase {

    void ejecutar(ConfirmarPedidoCommand command);

    record ConfirmarPedidoCommand(PedidoId pedidoId) {
        public ConfirmarPedidoCommand {
            Objects.requireNonNull(pedidoId, "pedidoId obligatorio");
        }
    }
}
