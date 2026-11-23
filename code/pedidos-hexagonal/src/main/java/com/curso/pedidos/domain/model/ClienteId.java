package com.curso.pedidos.domain.model;

import java.util.Objects;
import java.util.UUID;

public record ClienteId(UUID valor) {

    public ClienteId {
        Objects.requireNonNull(valor, "El id del cliente no puede ser nulo");
    }

    public static ClienteId de(String valor) {
        return new ClienteId(UUID.fromString(valor));
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
