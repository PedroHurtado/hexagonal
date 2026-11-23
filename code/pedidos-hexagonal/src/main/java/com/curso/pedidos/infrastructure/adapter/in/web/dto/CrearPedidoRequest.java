package com.curso.pedidos.infrastructure.adapter.in.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CrearPedidoRequest(@NotNull UUID clienteId) {
}
