package com.curso.pedidos.domain.event;

import com.curso.pedidos.domain.model.PedidoId;

import java.time.Instant;

/** Marca comun de los eventos de dominio del agregado Pedido. */
public sealed interface EventoDeDominio permits PedidoConfirmado, PedidoPagado, PedidoCancelado {

    PedidoId pedidoId();

    Instant ocurridoEn();
}
