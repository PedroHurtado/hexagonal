package com.curso.pedidos.repository;

import com.curso.pedidos.entity.ProductoEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductoRepository extends JpaRepository<ProductoEntity, Long> {

    ProductoEntity findBySku(String sku);               // devuelve null si no existe (nada de Optional)
}
