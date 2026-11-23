package com.curso.pedidos.infrastructure.adapter.out.persistence;

import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.ProductoId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de integración del adaptador de salida contra H2 con el esquema de Flyway.
 * En un proyecto real: PostgreSQL con Testcontainers (ver README).
 */
@DataJpaTest
@Import({PedidoPersistenceAdapter.class, PedidoPersistenceMapper.class})
class PedidoPersistenceAdapterTest {

    @Autowired
    private PedidoPersistenceAdapter adapter;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void guardaYRecuperaUnPedidoConSusLineas() {
        Pedido pedido = Pedido.crear(new ClienteId(UUID.randomUUID()));
        pedido.anadirLinea(new ProductoId("LIBRO-DDD"), Cantidad.de(2), Dinero.euros("45.00"));
        pedido.anadirLinea(new ProductoId("TAZA-JAVA"), Cantidad.de(1), Dinero.euros("12.50"));

        adapter.guardar(pedido);
        entityManager.flush();
        entityManager.clear();

        Pedido recuperado = adapter.buscarPorId(pedido.id()).orElseThrow();
        assertThat(recuperado.id()).isEqualTo(pedido.id());
        assertThat(recuperado.clienteId()).isEqualTo(pedido.clienteId());
        assertThat(recuperado.estado()).isEqualTo(EstadoPedido.BORRADOR);
        assertThat(recuperado.lineas()).hasSize(2);
        assertThat(recuperado.total()).isEqualTo(Dinero.euros("102.50"));
    }

    @Test
    void actualizaEstadoYLineasDeUnPedidoExistente() {
        Pedido pedido = Pedido.crear(new ClienteId(UUID.randomUUID()));
        pedido.anadirLinea(new ProductoId("LIBRO-DDD"), Cantidad.de(1), Dinero.euros("45.00"));
        adapter.guardar(pedido);
        entityManager.flush();
        entityManager.clear();

        Pedido cargado = adapter.buscarPorId(pedido.id()).orElseThrow();
        cargado.anadirLinea(new ProductoId("LIBRO-DDD"), Cantidad.de(2), Dinero.euros("45.00"));
        cargado.confirmar();
        adapter.guardar(cargado);
        entityManager.flush();
        entityManager.clear();

        Pedido recuperado = adapter.buscarPorId(pedido.id()).orElseThrow();
        assertThat(recuperado.estado()).isEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(recuperado.lineas()).singleElement()
                .satisfies(l -> assertThat(l.cantidad()).isEqualTo(Cantidad.de(3)));
    }

    @Test
    void devuelveVacioSiNoExiste() {
        assertThat(adapter.buscarPorId(PedidoId.nuevo())).isEmpty();
    }
}
