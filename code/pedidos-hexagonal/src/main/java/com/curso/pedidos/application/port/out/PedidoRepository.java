package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;

import java.util.Optional;

/** Puerto de salida (driven) con lenguaje de negocio: nada de JPA aquí. */
public interface PedidoRepository {

    void guardar(Pedido pedido);

    Optional<Pedido> buscarPorId(PedidoId id);
}
