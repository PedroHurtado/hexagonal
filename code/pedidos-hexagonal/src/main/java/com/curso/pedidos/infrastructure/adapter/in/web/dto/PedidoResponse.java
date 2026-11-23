package com.curso.pedidos.infrastructure.adapter.in.web.dto;

import com.curso.pedidos.application.port.in.ConsultarPedidoQuery.PedidoDetalle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTO de salida HTTP: contrato público de la API, independiente del dominio. */
public record PedidoResponse(UUID id, UUID clienteId, String estado, List<LineaResponse> lineas,
                             BigDecimal total, String moneda, Instant creadoEn) {

    public record LineaResponse(String sku, int cantidad, BigDecimal precioUnitario, BigDecimal subtotal) {
    }

    public static PedidoResponse desde(PedidoDetalle detalle) {
        return new PedidoResponse(detalle.id(), detalle.clienteId(), detalle.estado(),
                detalle.lineas().stream()
                        .map(l -> new LineaResponse(l.sku(), l.cantidad(), l.precioUnitario(), l.subtotal()))
                        .toList(),
                detalle.total(), detalle.moneda(), detalle.creadoEn());
    }
}
