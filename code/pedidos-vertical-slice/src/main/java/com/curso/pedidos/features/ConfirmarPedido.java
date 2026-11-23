package com.curso.pedidos.features;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Feature "Confirmar pedido". Las reglas (solo desde BORRADOR, nunca vacío) viven
 * en el Handler: no hay agregado compartido. Es el trade-off del slice extremo.
 */
public final class ConfirmarPedido {

    private ConfirmarPedido() {
    }

    public record Command(UUID pedidoId) {
    }

    public record Response(UUID id, String estado) {
    }

    @RestController
    static class Endpoint {

        private final Handler handler;

        Endpoint(Handler handler) {
            this.handler = handler;
        }

        @PostMapping("/api/pedidos/{id}/confirmacion")
        Response confirmar(@PathVariable UUID id) {
            return handler.handle(new Command(id));
        }
    }

    @Component
    static class Handler {

        private final JdbcClient jdbc;

        Handler(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional
        Response handle(Command command) {
            String estado = jdbc.sql("SELECT estado FROM pedidos WHERE id = ? FOR UPDATE")
                    .param(command.pedidoId())
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "No existe el pedido " + command.pedidoId()));
            if (!"BORRADOR".equals(estado)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "El pedido no puede pasar de " + estado + " a CONFIRMADO");
            }
            Integer lineas = jdbc.sql("SELECT COUNT(*) FROM lineas_pedido WHERE pedido_id = ?")
                    .param(command.pedidoId())
                    .query(Integer.class)
                    .single();
            if (lineas == 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No se puede confirmar un pedido vacío");
            }
            jdbc.sql("UPDATE pedidos SET estado = 'CONFIRMADO' WHERE id = ?")
                    .param(command.pedidoId())
                    .update();
            return new Response(command.pedidoId(), "CONFIRMADO");
        }
    }
}
