package com.curso.pedidos.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** Value object de ejemplo (dia 2): valida el formato en el constructor compacto. */
public record Email(String valor) {

    private static final Pattern FORMATO = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public Email {
        Objects.requireNonNull(valor, "El email no puede ser nulo");
        if (!FORMATO.matcher(valor).matches()) {
            throw new IllegalArgumentException("Email no valido: " + valor);
        }
        valor = valor.toLowerCase();
    }
}
