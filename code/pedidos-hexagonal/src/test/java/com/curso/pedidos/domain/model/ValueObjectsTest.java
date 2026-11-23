package com.curso.pedidos.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ValueObjectsTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    void cantidadDebeSerPositiva(int valor) {
        assertThatIllegalArgumentException().isThrownBy(() -> Cantidad.de(valor));
    }

    @Test
    void cantidadSeSuma() {
        assertThat(Cantidad.de(2).sumar(Cantidad.de(3))).isEqualTo(Cantidad.de(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void skuNoPuedeEstarVacio(String sku) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProductoId(sku));
    }

    @Test
    void skuSeNormaliza() {
        assertThat(new ProductoId(" libro-ddd ")).isEqualTo(new ProductoId("LIBRO-DDD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sin-arroba", "a@b", "@dominio.com", "dos@@arrobas.com"})
    void emailInvalido(String valor) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Email(valor));
    }

    @Test
    void emailValidoSeNormalizaEnMinusculas() {
        assertThat(new Email("Ana@Curso.ES").valor()).isEqualTo("ana@curso.es");
    }
}
