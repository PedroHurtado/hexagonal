package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.ProductoId;

import java.util.Objects;

/** Puerto de entrada: añadir una línea (producto + cantidad) a un pedido en BORRADOR. */
@FunctionalInterface
public interface AnadirLineaUseCase {

    void ejecutar(AnadirLineaCommand command);

    record AnadirLineaCommand(PedidoId pedidoId, ProductoId productoId, Cantidad cantidad) {
        public AnadirLineaCommand {
            Objects.requireNonNull(pedidoId, "pedidoId obligatorio");
            Objects.requireNonNull(productoId, "productoId obligatorio");
            Objects.requireNonNull(cantidad, "cantidad obligatoria");
        }
    }
}
