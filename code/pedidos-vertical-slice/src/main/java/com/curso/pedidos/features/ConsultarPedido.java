package com.curso.pedidos.features;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Feature "Consultar pedido": lectura directa a SQL proyectando al DTO de respuesta.
 * Sin agregado, sin mapper, sin repositorio: el read model ES la query.
 */
public final class ConsultarPedido {

    private ConsultarPedido() {
    }

    public record Query(UUID pedidoId) {
    }

    public record Response(UUID id, UUID clienteId, String estado, List<Linea> lineas, BigDecimal total) {
        public record Linea(String sku, int cantidad, BigDecimal precioUnitario, BigDecimal subtotal) {
        }
    }

    @RestController
    static class Endpoint {

        private final Handler handler;

        Endpoint(Handler handler) {
            this.handler = handler;
        }

        @GetMapping("/api/pedidos/{id}")
        Response consultar(@PathVariable UUID id) {
            return handler.handle(new Query(id));
        }
    }

    @Component
    static class Handler {

        private final JdbcClient jdbc;

        Handler(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        Response handle(Query query) {
            Cabecera cabecera = jdbc.sql("SELECT id, cliente_id, estado FROM pedidos WHERE id = ?")
                    .param(query.pedidoId())
                    .query(Cabecera.class)
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "No existe el pedido " + query.pedidoId()));
            List<Response.Linea> lineas = jdbc.sql("""
                            SELECT sku, cantidad, precio_unitario, cantidad * precio_unitario AS subtotal
                            FROM lineas_pedido WHERE pedido_id = ? ORDER BY id""")
                    .param(query.pedidoId())
                    .query(Response.Linea.class)
                    .list();
            BigDecimal total = lineas.stream()
                    .map(Response.Linea::subtotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Response(cabecera.id(), cabecera.clienteId(), cabecera.estado(), lineas, total);
        }

        record Cabecera(UUID id, UUID clienteId, String estado) {
        }
    }
}
