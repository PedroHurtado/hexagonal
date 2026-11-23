package com.curso.pedidos.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "lineas_pedido")
public class LineaPedidoJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "producto_id", nullable = false, length = 64)
    private String productoId;

    @Column(nullable = false)
    private int cantidad;

    @Column(name = "precio_unitario", nullable = false, precision = 19, scale = 2)
    private BigDecimal precioUnitario;

    @Column(nullable = false, length = 3)
    private String moneda;

    protected LineaPedidoJpaEntity() {
        // requerido por JPA
    }

    public LineaPedidoJpaEntity(String productoId, int cantidad, BigDecimal precioUnitario, String moneda) {
        this.productoId = productoId;
        this.cantidad = cantidad;
        this.precioUnitario = precioUnitario;
        this.moneda = moneda;
    }

    public Long getId() {
        return id;
    }

    public String getProductoId() {
        return productoId;
    }

    public int getCantidad() {
        return cantidad;
    }

    public BigDecimal getPrecioUnitario() {
        return precioUnitario;
    }

    public String getMoneda() {
        return moneda;
    }
}
