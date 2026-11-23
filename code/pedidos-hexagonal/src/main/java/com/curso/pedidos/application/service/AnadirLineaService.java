package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.AnadirLineaUseCase;
import com.curso.pedidos.application.port.out.CatalogoProductos;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.exception.ProductoNoEncontradoException;
import com.curso.pedidos.domain.exception.StockInsuficienteException;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.Producto;

/**
 * Orquesta: carga el pedido, consulta el catálogo (precio y stock) y delega la regla
 * de negocio en el agregado. El precio lo decide el catálogo, nunca el cliente.
 */
public class AnadirLineaService implements AnadirLineaUseCase {

    private final PedidoRepository pedidoRepository;
    private final CatalogoProductos catalogoProductos;

    public AnadirLineaService(PedidoRepository pedidoRepository, CatalogoProductos catalogoProductos) {
        this.pedidoRepository = pedidoRepository;
        this.catalogoProductos = catalogoProductos;
    }

    @Override
    public void ejecutar(AnadirLineaCommand command) {
        Pedido pedido = pedidoRepository.buscarPorId(command.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(command.pedidoId()));
        Producto producto = catalogoProductos.buscarPorId(command.productoId())
                .orElseThrow(() -> new ProductoNoEncontradoException(command.productoId()));
        if (!producto.hayStockPara(command.cantidad())) {
            throw new StockInsuficienteException(producto.id(), command.cantidad(), producto.stock());
        }
        pedido.anadirLinea(producto.id(), command.cantidad(), producto.precio());
        pedidoRepository.guardar(pedido);
    }
}
