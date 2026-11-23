package com.curso.pedidos.domain.exception;

import com.curso.pedidos.domain.model.ProductoId;

public class ProductoNoEncontradoException extends PedidoException {

    public ProductoNoEncontradoException(ProductoId id) {
        super("No existe el producto " + id + " en el catalogo");
    }
}
