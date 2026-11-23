package com.curso.pedidos.domain.exception;

import com.curso.pedidos.domain.model.PedidoId;

public class PedidoNoEncontradoException extends PedidoException {

    public PedidoNoEncontradoException(PedidoId id) {
        super("No existe el pedido " + id);
    }
}
