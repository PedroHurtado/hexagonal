package com.curso.pedidos.domain.event;

import com.curso.pedidos.domain.model.PedidoId;

import java.time.Instant;

public record PedidoCancelado(PedidoId pedidoId, Instant ocurridoEn) implements EventoDeDominio {
}
