package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.ConsultarPedidoQuery;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.PedidoId;

public class ConsultarPedidoService implements ConsultarPedidoQuery {

    private final PedidoRepository pedidoRepository;

    public ConsultarPedidoService(PedidoRepository pedidoRepository) {
        this.pedidoRepository = pedidoRepository;
    }

    @Override
    public PedidoDetalle consultar(PedidoId pedidoId) {
        return pedidoRepository.buscarPorId(pedidoId)
                .map(PedidoDetalle::desde)
                .orElseThrow(() -> new PedidoNoEncontradoException(pedidoId));
    }
}
