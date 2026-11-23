package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.CrearPedidoUseCase;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;

import java.time.Clock;

/** Implementación del caso de uso. Java plano: sin @Service ni @Transactional. */
public class CrearPedidoService implements CrearPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final Clock clock;

    public CrearPedidoService(PedidoRepository pedidoRepository, Clock clock) {
        this.pedidoRepository = pedidoRepository;
        this.clock = clock;
    }

    @Override
    public PedidoId ejecutar(CrearPedidoCommand command) {
        Pedido pedido = Pedido.crear(command.clienteId(), clock);
        pedidoRepository.guardar(pedido);
        return pedido.id();
    }
}
