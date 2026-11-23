package com.curso.pedidos.application.service.fakes;

import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Fake en memoria del puerto de salida: más legible y robusto que un mock. */
public class InMemoryPedidoRepository implements PedidoRepository {

    private final Map<PedidoId, Pedido> pedidos = new HashMap<>();

    @Override
    public void guardar(Pedido pedido) {
        pedidos.put(pedido.id(), pedido);
    }

    @Override
    public Optional<Pedido> buscarPorId(PedidoId id) {
        return Optional.ofNullable(pedidos.get(id));
    }

    public int cuantos() {
        return pedidos.size();
    }
}
