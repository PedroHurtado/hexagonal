package com.curso.pedidos.domain.exception;

/** Excepcion base del dominio: violacion de una regla de negocio. */
public class PedidoException extends RuntimeException {

    public PedidoException(String mensaje) {
        super(mensaje);
    }
}
