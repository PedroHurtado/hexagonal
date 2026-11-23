package com.curso.pedidos.repository;

import com.curso.pedidos.entity.PedidoEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PedidoRepository extends JpaRepository<PedidoEntity, UUID> {

    List<PedidoEntity> findByEstado(String estado);     // ojo: "CONFIRMADO" != "confirmado"
}
