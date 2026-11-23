package com.curso.pedidos.features;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConsultarPedidoTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void devuelveElPedidoConSusLineasYTotal() throws Exception {
        String body = mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clienteId":"%s","lineas":[{"sku":"TECLADO-MEC","cantidad":1},{"sku":"TAZA-JAVA","cantidad":2}]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mockMvc.perform(get("/api/pedidos/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("BORRADOR"))
                .andExpect(jsonPath("$.lineas.length()").value(2))
                .andExpect(jsonPath("$.lineas[0].sku").value("TECLADO-MEC"))
                .andExpect(jsonPath("$.total").value(114.90));
    }

    @Test
    void pedidoInexistenteDevuelve404() throws Exception {
        mockMvc.perform(get("/api/pedidos/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
