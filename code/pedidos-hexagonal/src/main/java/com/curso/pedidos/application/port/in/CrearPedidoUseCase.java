package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.PedidoId;

import java.util.Objects;

/** Puerto de entrada (driving): crear un pedido vacío en BORRADOR. */
@FunctionalInterface
public interface CrearPedidoUseCase {

    PedidoId ejecutar(CrearPedidoCommand command);

    record CrearPedidoCommand(ClienteId clienteId) {
        public CrearPedidoCommand {
            Objects.requireNonNull(clienteId, "clienteId obligatorio");
        }
    }
}
