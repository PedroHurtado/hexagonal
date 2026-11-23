package com.curso.pedidos.application.service.fakes;

import com.curso.pedidos.application.port.out.CatalogoProductos;
import com.curso.pedidos.domain.model.Producto;
import com.curso.pedidos.domain.model.ProductoId;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class FakeCatalogoProductos implements CatalogoProductos {

    private final Map<ProductoId, Producto> productos = new HashMap<>();

    public FakeCatalogoProductos con(Producto producto) {
        productos.put(producto.id(), producto);
        return this;
    }

    @Override
    public Optional<Producto> buscarPorId(ProductoId id) {
        return Optional.ofNullable(productos.get(id));
    }
}
