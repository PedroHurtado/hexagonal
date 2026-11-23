package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.CancelarPedidoUseCase;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.Pedido;

public class CancelarPedidoService implements CancelarPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final PublicadorDeEventos publicadorDeEventos;

    public CancelarPedidoService(PedidoRepository pedidoRepository, PublicadorDeEventos publicadorDeEventos) {
        this.pedidoRepository = pedidoRepository;
        this.publicadorDeEventos = publicadorDeEventos;
    }

    @Override
    public void ejecutar(CancelarPedidoCommand command) {
        Pedido pedido = pedidoRepository.buscarPorId(command.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(command.pedidoId()));
        pedido.cancelar();
        pedidoRepository.guardar(pedido);
        publicadorDeEventos.publicar(pedido.extraerEventos());
    }
}
