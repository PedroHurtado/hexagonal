package com.curso.pedidos.domain.exception;

import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.PedidoId;

public class TransicionDeEstadoInvalidaException extends PedidoException {

    public TransicionDeEstadoInvalidaException(PedidoId id, EstadoPedido actual, EstadoPedido destino) {
        super("El pedido " + id + " no puede pasar de " + actual + " a " + destino);
    }
}
