package com.curso.pedidos.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "lineas_pedido")
public class LineaPedidoEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne @JoinColumn(name = "pedido_id")
    @JsonBackReference
    private PedidoEntity pedido;

    private String sku;
    private int cantidad;               // puede ser 0 o negativo: nadie lo valida aquí
    private BigDecimal precioUnitario;

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public PedidoEntity getPedido() { return pedido; }
    public void setPedido(PedidoEntity pedido) { this.pedido = pedido; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public int getCantidad() { return cantidad; }
    public void setCantidad(int cantidad) { this.cantidad = cantidad; }
    public BigDecimal getPrecioUnitario() { return precioUnitario; }
    public void setPrecioUnitario(BigDecimal precioUnitario) { this.precioUnitario = precioUnitario; }
}
