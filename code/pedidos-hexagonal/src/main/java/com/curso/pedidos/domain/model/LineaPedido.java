package com.curso.pedidos.domain.model;

import java.util.Objects;

/**
 * Entidad interna del agregado Pedido. Solo se modifica a traves de Pedido
 * (por eso el metodo que la cambia es package-private).
 */
public class LineaPedido {

    private final ProductoId productoId;
    private Cantidad cantidad;
    private final Dinero precioUnitario;

    public LineaPedido(ProductoId productoId, Cantidad cantidad, Dinero precioUnitario) {
        this.productoId = Objects.requireNonNull(productoId, "productoId obligatorio");
        this.cantidad = Objects.requireNonNull(cantidad, "cantidad obligatoria");
        this.precioUnitario = Objects.requireNonNull(precioUnitario, "precioUnitario obligatorio");
    }

    public Dinero subtotal() {
        return precioUnitario.multiplicar(cantidad.valor());
    }

    void incrementar(Cantidad adicional) {
        this.cantidad = this.cantidad.sumar(adicional);
    }

    public ProductoId productoId() {
        return productoId;
    }

    public Cantidad cantidad() {
        return cantidad;
    }

    public Dinero precioUnitario() {
        return precioUnitario;
    }

    @Override
    public String toString() {
        return "LineaPedido[" + productoId + " x" + cantidad.valor() + " @ " + precioUnitario + "]";
    }
}
