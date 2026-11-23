package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Puerto de entrada de lectura. Devuelve un modelo de lectura inmutable (PedidoDetalle)
 * en lugar del agregado: el adaptador web no puede invocar confirmar() por accidente.
 */
@FunctionalInterface
public interface ConsultarPedidoQuery {

    PedidoDetalle consultar(PedidoId pedidoId);

    record PedidoDetalle(UUID id, UUID clienteId, String estado, List<LineaDetalle> lineas,
                         BigDecimal total, String moneda, Instant creadoEn) {

        public static PedidoDetalle desde(Pedido pedido) {
            List<LineaDetalle> lineas = pedido.lineas().stream()
                    .map(l -> new LineaDetalle(l.productoId().sku(), l.cantidad().valor(),
                            l.precioUnitario().importe(), l.subtotal().importe()))
                    .toList();
            return new PedidoDetalle(pedido.id().valor(), pedido.clienteId().valor(),
                    pedido.estado().name(), lineas, pedido.total().importe(),
                    pedido.total().moneda().getCurrencyCode(), pedido.creadoEn());
        }
    }

    record LineaDetalle(String sku, int cantidad, BigDecimal precioUnitario, BigDecimal subtotal) {
    }
}
