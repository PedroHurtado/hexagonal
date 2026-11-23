package com.curso.pedidos.infrastructure.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Repositorio Spring Data: detalle técnico, oculto tras PedidoPersistenceAdapter. */
public interface PedidoJpaRepository extends JpaRepository<PedidoJpaEntity, UUID> {
}
