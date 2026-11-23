package com.curso.pedidos.domain.model;

import java.util.Objects;

/** Identificador de producto en el catalogo (SKU). */
public record ProductoId(String sku) {

    public ProductoId {
        Objects.requireNonNull(sku, "El SKU no puede ser nulo");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("El SKU no puede estar vacio");
        }
        sku = sku.trim().toUpperCase();
    }

    @Override
    public String toString() {
        return sku;
    }
}
