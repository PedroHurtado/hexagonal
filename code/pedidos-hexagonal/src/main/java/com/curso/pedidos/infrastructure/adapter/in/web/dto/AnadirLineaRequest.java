package com.curso.pedidos.infrastructure.adapter.in.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record AnadirLineaRequest(@NotBlank String sku, @Min(1) int cantidad) {
}
