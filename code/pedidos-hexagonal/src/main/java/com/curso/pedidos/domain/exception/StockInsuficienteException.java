package com.curso.pedidos.domain.exception;

import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.ProductoId;

public class StockInsuficienteException extends PedidoException {

    public StockInsuficienteException(ProductoId id, Cantidad solicitada, int disponible) {
        super("Stock insuficiente para " + id + ": solicitadas " + solicitada.valor()
                + ", disponibles " + disponible);
    }
}
