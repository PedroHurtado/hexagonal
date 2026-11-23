package com.curso.pedidos;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// El único test del proyecto: solo comprueba que el contexto arranca.
// No hay ni un test de negocio. Ese es el problema (y vuestra fase 0).
@SpringBootTest
class PedidosLegacyApplicationTests {

    @Test
    void contextLoads() {
    }
}
