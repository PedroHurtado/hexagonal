package com.curso.pedidos.infrastructure.adapter.out.persistence;

import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Adaptador de salida: implementa el puerto PedidoRepository usando Spring Data JPA. */
@Component
public class PedidoPersistenceAdapter implements PedidoRepository {

    private final PedidoJpaRepository jpaRepository;
    private final PedidoPersistenceMapper mapper;

    public PedidoPersistenceAdapter(PedidoJpaRepository jpaRepository, PedidoPersistenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public void guardar(Pedido pedido) {
        PedidoJpaEntity entity = jpaRepository.findById(pedido.id().valor())
                .orElseGet(() -> new PedidoJpaEntity(pedido.id().valor()));
        mapper.copiar(pedido, entity);
        jpaRepository.save(entity);
    }

    @Override
    public Optional<Pedido> buscarPorId(PedidoId id) {
        return jpaRepository.findById(id.valor()).map(mapper::aDominio);
    }
}
