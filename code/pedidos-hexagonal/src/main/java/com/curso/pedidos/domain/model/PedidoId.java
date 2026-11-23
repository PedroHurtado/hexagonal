package com.curso.pedidos.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identidad del agregado Pedido. Value object inmutable. */
public record PedidoId(UUID valor) {

    public PedidoId {
        Objects.requireNonNull(valor, "El id del pedido no puede ser nulo");
    }

    public static PedidoId nuevo() {
        return new PedidoId(UUID.randomUUID());
    }

    public static PedidoId de(String valor) {
        return new PedidoId(UUID.fromString(valor));
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
