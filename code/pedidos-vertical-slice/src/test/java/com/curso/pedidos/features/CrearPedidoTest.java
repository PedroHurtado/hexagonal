package com.curso.pedidos.features;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CrearPedidoTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void creaUnPedidoEnBorradorConElTotalCalculado() throws Exception {
        mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clienteId":"%s","lineas":[{"sku":"LIBRO-DDD","cantidad":2},{"sku":"TAZA-JAVA","cantidad":1}]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.estado").value("BORRADOR"))
                .andExpect(jsonPath("$.total").value(102.50));
    }

    @Test
    void sinStockDevuelve409() throws Exception {
        mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clienteId":"%s","lineas":[{"sku":"MONITOR-27","cantidad":1}]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isConflict());
    }

    @Test
    void sinLineasDevuelve400() throws Exception {
        mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clienteId\":\"%s\",\"lineas\":[]}".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }
}
