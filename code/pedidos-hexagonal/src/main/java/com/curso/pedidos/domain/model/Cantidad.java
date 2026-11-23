package com.curso.pedidos.domain.model;

/** Cantidad de unidades de una linea. Siempre estrictamente positiva. */
public record Cantidad(int valor) {

    public Cantidad {
        if (valor <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor que cero: " + valor);
        }
    }

    public static Cantidad de(int valor) {
        return new Cantidad(valor);
    }

    public Cantidad sumar(Cantidad otra) {
        return new Cantidad(this.valor + otra.valor);
    }
}
