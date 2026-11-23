package com.curso.pedidos;

import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end: HTTP -> controller -> caso de uso -> JPA/H2 -> eventos. Todo real. */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class PedidosE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationEvents events;

    private String crearPedido() throws Exception {
        String body = mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clienteId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void flujoCompletoCrearAnadirConfirmarYConsultar() throws Exception {
        String id = crearPedido();

        mockMvc.perform(post("/api/pedidos/{id}/lineas", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"LIBRO-DDD\",\"cantidad\":2}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/pedidos/{id}/lineas", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"TAZA-JAVA\",\"cantidad\":1}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/pedidos/{id}/confirmacion", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/pedidos/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.lineas.length()").value(2))
                .andExpect(jsonPath("$.total").value(102.50))
                .andExpect(jsonPath("$.moneda").value("EUR"));

        assertThat(events.stream(PedidoConfirmado.class))
                .singleElement()
                .satisfies(e -> assertThat(e.pedidoId().toString()).isEqualTo(id));
    }

    @Test
    void productoSinStockDevuelve409() throws Exception {
        String id = crearPedido();

        mockMvc.perform(post("/api/pedidos/{id}/lineas", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"MONITOR-27\",\"cantidad\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void confirmarPedidoVacioDevuelve422() throws Exception {
        String id = crearPedido();

        mockMvc.perform(post("/api/pedidos/{id}/confirmacion", id))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cancelarDosVecesDevuelve409() throws Exception {
        String id = crearPedido();

        mockMvc.perform(post("/api/pedidos/{id}/cancelacion", id)).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/pedidos/{id}/cancelacion", id)).andExpect(status().isConflict());
    }
}
