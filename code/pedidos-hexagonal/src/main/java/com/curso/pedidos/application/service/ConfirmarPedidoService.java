package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.Pedido;

public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final PublicadorDeEventos publicadorDeEventos;

    public ConfirmarPedidoService(PedidoRepository pedidoRepository, PublicadorDeEventos publicadorDeEventos) {
        this.pedidoRepository = pedidoRepository;
        this.publicadorDeEventos = publicadorDeEventos;
    }

    @Override
    public void ejecutar(ConfirmarPedidoCommand command) {
        Pedido pedido = pedidoRepository.buscarPorId(command.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(command.pedidoId()));
        pedido.confirmar();
        pedidoRepository.guardar(pedido);
        publicadorDeEventos.publicar(pedido.extraerEventos());
    }
}
