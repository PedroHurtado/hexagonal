package com.curso.pedidos.infrastructure.adapter.out.catalogo;

import com.curso.pedidos.application.port.out.CatalogoProductos;
import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.Producto;
import com.curso.pedidos.domain.model.ProductoId;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Catálogo en memoria. En el día 4 se sustituye por un adaptador HTTP (RestClient)
 * sin tocar ni una línea de dominio ni de aplicación.
 */
@Component
public class InMemoryCatalogoProductosAdapter implements CatalogoProductos {

    private final Map<ProductoId, Producto> productos = Stream.of(
                    new Producto(new ProductoId("LIBRO-DDD"), "Domain-Driven Design (Evans)", Dinero.euros("45.00"), 10),
                    new Producto(new ProductoId("TECLADO-MEC"), "Teclado mecánico", Dinero.euros("89.90"), 5),
                    new Producto(new ProductoId("TAZA-JAVA"), "Taza Java", Dinero.euros("12.50"), 100),
                    new Producto(new ProductoId("MONITOR-27"), "Monitor 27 pulgadas", Dinero.euros("299.00"), 0))
            .collect(Collectors.toUnmodifiableMap(Producto::id, Function.identity()));

    @Override
    public Optional<Producto> buscarPorId(ProductoId id) {
        return Optional.ofNullable(productos.get(id));
    }
}
