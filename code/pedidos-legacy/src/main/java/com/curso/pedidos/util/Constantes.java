package com.curso.pedidos.util;

// Clase de constantes que casi nadie usa: el código sigue escribiendo los literales a mano.
public final class Constantes {

    public static final String ESTADO_BORRADOR = "BORRADOR";
    public static final String ESTADO_CONFIRMADO = "CONFIRMADO";
    public static final String ESTADO_PAGADO = "PAGADO";
    public static final String ESTADO_ENVIADO = "ENVIADO";
    public static final String ESTADO_CANCELADO = "CANCELADO";
    public static final String ESTADO_REEMBOLSADO = "REEMBOLSADO";

    public static final String MONEDA_POR_DEFECTO = "EUR";
    public static final String PREFIJO_REFERENCIA = "PED-";

    public static final int PUNTOS_POR_EURO = 1;
    public static final int MAX_REINTENTOS_PAGO = 3;

    private Constantes() {
    }
}
