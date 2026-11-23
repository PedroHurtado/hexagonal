package com.curso.pedidos.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Value object monetario. Inmutable, comparable por valor y sin importes negativos.
 * La escala se normaliza a 2 decimales para que 10 y 10.00 sean el mismo Dinero.
 */
public record Dinero(BigDecimal importe, Currency moneda) {

    public static final Currency EUR = Currency.getInstance("EUR");

    public Dinero {
        Objects.requireNonNull(importe, "El importe no puede ser nulo");
        Objects.requireNonNull(moneda, "La moneda no puede ser nula");
        if (importe.signum() < 0) {
            throw new IllegalArgumentException("El importe no puede ser negativo: " + importe);
        }
        importe = importe.setScale(2, RoundingMode.HALF_EVEN);
    }

    public static Dinero euros(String importe) {
        return new Dinero(new BigDecimal(importe), EUR);
    }

    public static Dinero euros(BigDecimal importe) {
        return new Dinero(importe, EUR);
    }

    public static Dinero cero(Currency moneda) {
        return new Dinero(BigDecimal.ZERO, moneda);
    }

    public Dinero sumar(Dinero otro) {
        exigirMismaMoneda(otro);
        return new Dinero(importe.add(otro.importe), moneda);
    }

    public Dinero multiplicar(int factor) {
        if (factor < 0) {
            throw new IllegalArgumentException("El factor no puede ser negativo: " + factor);
        }
        return new Dinero(importe.multiply(BigDecimal.valueOf(factor)), moneda);
    }

    public boolean mismaMonedaQue(Dinero otro) {
        return moneda.equals(otro.moneda);
    }

    private void exigirMismaMoneda(Dinero otro) {
        if (!mismaMonedaQue(otro)) {
            throw new IllegalArgumentException(
                    "No se pueden operar monedas distintas: " + moneda + " y " + otro.moneda);
        }
    }

    @Override
    public String toString() {
        return importe.toPlainString() + " " + moneda.getCurrencyCode();
    }
}
