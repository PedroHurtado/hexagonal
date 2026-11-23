package com.curso.pedidos.domain.service;

import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.Pedido;

import java.math.BigDecimal;

/**
 * Servicio de dominio puro (sin estado ni dependencias tecnicas). Ejemplo para el dia 2:
 * logica que no pertenece de forma natural a una sola entidad.
 */
public class CalculadoraDePrecios {

    private static final BigDecimal UMBRAL_ENVIO_GRATIS = new BigDecimal("50.00");
    private static final Dinero GASTOS_DE_ENVIO = Dinero.euros("4.95");

    public Dinero gastosDeEnvio(Pedido pedido) {
        Dinero total = pedido.total();
        if (total.importe().compareTo(UMBRAL_ENVIO_GRATIS) >= 0) {
            return Dinero.cero(total.moneda());
        }
        return GASTOS_DE_ENVIO;
    }
}
