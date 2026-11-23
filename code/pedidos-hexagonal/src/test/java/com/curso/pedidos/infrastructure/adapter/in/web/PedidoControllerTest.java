package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.application.port.in.AnadirLineaUseCase;
import com.curso.pedidos.application.port.in.AnadirLineaUseCase.AnadirLineaCommand;
import com.curso.pedidos.application.port.in.CancelarPedidoUseCase;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.in.ConsultarPedidoQuery;
import com.curso.pedidos.application.port.in.ConsultarPedidoQuery.LineaDetalle;
import com.curso.pedidos.application.port.in.ConsultarPedidoQuery.PedidoDetalle;
import com.curso.pedidos.application.port.in.CrearPedidoUseCase;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.exception.StockInsuficienteException;
import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.ProductoId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Test de slice web: solo el adaptador HTTP; los puertos de entrada son mocks. */
@WebMvcTest(PedidoController.class)
class PedidoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CrearPedidoUseCase crearPedido;
    @MockBean
    private AnadirLineaUseCase anadirLinea;
    @MockBean
    private ConfirmarPedidoUseCase confirmarPedido;
    @MockBean
    private CancelarPedidoUseCase cancelarPedido;
    @MockBean
    private ConsultarPedidoQuery consultarPedido;

    @Test
    void crearDevuelve201ConLocation() throws Exception {
        PedidoId id = PedidoId.nuevo();
        given(crearPedido.ejecutar(any())).willReturn(id);

        mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clienteId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/pedidos/" + id))
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void crearSinClienteDevuelve400ProblemDetail() throws Exception {
        mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE));
    }

    @Test
    void anadirLineaTraduceElRequestACommand() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/api/pedidos/{id}/lineas", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"LIBRO-DDD\",\"cantidad\":2}"))
                .andExpect(status().isNoContent());

        then(anadirLinea).should().ejecutar(
                new AnadirLineaCommand(new PedidoId(id), new ProductoId("LIBRO-DDD"), Cantidad.de(2)));
    }

    @Test
    void stockInsuficienteDevuelve409() throws Exception {
        willThrow(new StockInsuficienteException(new ProductoId("MONITOR-27"), Cantidad.de(1), 0))
                .given(anadirLinea).ejecutar(any());

        mockMvc.perform(post("/api/pedidos/{id}/lineas", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"MONITOR-27\",\"cantidad\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicto con el estado actual"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("MONITOR-27")));
    }

    @Test
    void consultarDevuelveElPedido() throws Exception {
        UUID id = UUID.randomUUID();
        given(consultarPedido.consultar(new PedidoId(id))).willReturn(new PedidoDetalle(
                id, UUID.randomUUID(), "BORRADOR",
                List.of(new LineaDetalle("LIBRO-DDD", 2, new BigDecimal("45.00"), new BigDecimal("90.00"))),
                new BigDecimal("90.00"), "EUR", Instant.parse("2026-11-23T14:00:00Z")));

        mockMvc.perform(get("/api/pedidos/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("BORRADOR"))
                .andExpect(jsonPath("$.total").value(90.00))
                .andExpect(jsonPath("$.lineas[0].sku").value("LIBRO-DDD"));
    }

    @Test
    void pedidoInexistenteDevuelve404() throws Exception {
        UUID id = UUID.randomUUID();
        given(consultarPedido.consultar(any())).willThrow(new PedidoNoEncontradoException(new PedidoId(id)));

        mockMvc.perform(get("/api/pedidos/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
