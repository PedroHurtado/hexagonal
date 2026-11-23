package com.curso.pedidos.domain.model;

import java.util.Objects;

/** Producto tal y como lo ve el contexto de Pedidos (datos que nos da el catalogo). */
public record Producto(ProductoId id, String nombre, Dinero precio, int stock) {

    public Producto {
        Objects.requireNonNull(id, "El id del producto no puede ser nulo");
        Objects.requireNonNull(nombre, "El nombre no puede ser nulo");
        Objects.requireNonNull(precio, "El precio no puede ser nulo");
        if (stock < 0) {
            throw new IllegalArgumentException("El stock no puede ser negativo");
        }
    }

    public boolean hayStockPara(Cantidad cantidad) {
        return stock >= cantidad.valor();
    }
}
