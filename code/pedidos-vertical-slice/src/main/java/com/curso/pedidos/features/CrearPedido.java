package com.curso.pedidos.features;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Feature "Crear pedido" COMPLETA en un solo fichero: contrato (Command/Response),
 * lógica (Handler), entrada HTTP (Endpoint) y acceso a datos (JdbcClient).
 * Para entender o cambiar esta feature no hay que abrir ningún otro fichero.
 */
public final class CrearPedido {

    private CrearPedido() {
    }

    public record Command(@NotNull UUID clienteId, @NotEmpty List<@Valid Linea> lineas) {
        public record Linea(@NotBlank String sku, @Min(1) int cantidad) {
        }
    }

    public record Response(UUID id, String estado, BigDecimal total) {
    }

    @RestController
    static class Endpoint {

        private final Handler handler;

        Endpoint(Handler handler) {
            this.handler = handler;
        }

        @PostMapping("/api/pedidos")
        ResponseEntity<Response> crear(@Valid @RequestBody Command command) {
            Response response = handler.handle(command);
            return ResponseEntity.created(URI.create("/api/pedidos/" + response.id())).body(response);
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
            UUID id = UUID.randomUUID();
            jdbc.sql("INSERT INTO pedidos (id, cliente_id, estado, creado_en) VALUES (?, ?, 'BORRADOR', ?)")
                    .params(id, command.clienteId(), Timestamp.from(Instant.now()))
                    .update();

            BigDecimal total = BigDecimal.ZERO;
            for (Command.Linea linea : command.lineas()) {
                Producto producto = jdbc.sql("SELECT sku, precio, stock FROM productos WHERE sku = ?")
                        .param(linea.sku().trim().toUpperCase())
                        .query(Producto.class)
                        .optional()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "No existe el producto " + linea.sku()));
                if (producto.stock() < linea.cantidad()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Stock insuficiente para " + producto.sku());
                }
                jdbc.sql("INSERT INTO lineas_pedido (pedido_id, sku, cantidad, precio_unitario) VALUES (?, ?, ?, ?)")
                        .params(id, producto.sku(), linea.cantidad(), producto.precio())
                        .update();
                total = total.add(producto.precio().multiply(BigDecimal.valueOf(linea.cantidad())));
            }
            return new Response(id, "BORRADOR", total);
        }

        record Producto(String sku, BigDecimal precio, int stock) {
        }
    }
}
