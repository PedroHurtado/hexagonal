package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.Producto;
import com.curso.pedidos.domain.model.ProductoId;

import java.util.Optional;

/** Puerto de salida hacia el catálogo (otro sistema / bounded context). */
public interface CatalogoProductos {

    Optional<Producto> buscarPorId(ProductoId id);
}
