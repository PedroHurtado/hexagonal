package com.curso.pedidos.features;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConfirmarPedidoTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    private UUID pedidoEnBorrador(boolean conLineas) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO pedidos (id, cliente_id, estado, creado_en) VALUES (?, ?, 'BORRADOR', ?)")
                .params(id, UUID.randomUUID(), Timestamp.from(Instant.now())).update();
        if (conLineas) {
            jdbc.sql("INSERT INTO lineas_pedido (pedido_id, sku, cantidad, precio_unitario) VALUES (?, 'LIBRO-DDD', 1, 45.00)")
                    .param(id).update();
        }
        return id;
    }

    @Test
    void confirmaUnPedidoEnBorradorConLineas() throws Exception {
        UUID id = pedidoEnBorrador(true);

        mockMvc.perform(post("/api/pedidos/{id}/confirmacion", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADO"));
    }

    @Test
    void noConfirmaDosVeces() throws Exception {
        UUID id = pedidoEnBorrador(true);
        mockMvc.perform(post("/api/pedidos/{id}/confirmacion", id)).andExpect(status().isOk());

        mockMvc.perform(post("/api/pedidos/{id}/confirmacion", id)).andExpect(status().isConflict());
    }

    @Test
    void noConfirmaUnPedidoVacio() throws Exception {
        UUID id = pedidoEnBorrador(false);

        mockMvc.perform(post("/api/pedidos/{id}/confirmacion", id))
                .andExpect(status().isUnprocessableEntity());
    }
}
