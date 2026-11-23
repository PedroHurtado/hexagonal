package com.curso.pedidos.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DineroTest {

    @ParameterizedTest(name = "{0} + {1} = {2}")
    @CsvSource({
            "10.00, 5.50, 15.50",
            "0, 0, 0.00",
            "0.10, 0.20, 0.30",
            "99.99, 0.01, 100.00"
    })
    void sumaImportesDeLaMismaMoneda(String a, String b, String esperado) {
        assertThat(Dinero.euros(a).sumar(Dinero.euros(b))).isEqualTo(Dinero.euros(esperado));
    }

    @ParameterizedTest(name = "{0} x {1} = {2}")
    @CsvSource({
            "12.50, 3, 37.50",
            "45.00, 0, 0.00",
            "0.33, 3, 0.99"
    })
    void multiplica(String importe, int factor, String esperado) {
        assertThat(Dinero.euros(importe).multiplicar(factor)).isEqualTo(Dinero.euros(esperado));
    }

    @Test
    void esIgualPorValorIndependientementeDeLaEscala() {
        assertThat(Dinero.euros("10")).isEqualTo(Dinero.euros("10.00"));
    }

    @Test
    void noAdmiteImportesNegativos() {
        assertThatIllegalArgumentException().isThrownBy(() -> Dinero.euros("-1"));
    }

    @Test
    void noSumaMonedasDistintas() {
        Dinero dolares = new Dinero(BigDecimal.ONE, Currency.getInstance("USD"));
        assertThatIllegalArgumentException().isThrownBy(() -> Dinero.euros("1").sumar(dolares));
    }
}
