package com.curso.pedidos.infrastructure.adapter.out.persistence;

import com.curso.pedidos.domain.model.EstadoPedido;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Modelo de persistencia. Vive en infraestructura; el dominio no sabe que existe. */
@Entity
@Table(name = "pedidos")
public class PedidoJpaEntity {

    @Id
    private UUID id;

    @Column(name = "cliente_id", nullable = false)
    private UUID clienteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoPedido estado;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Version
    private Long version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "pedido_id", nullable = false)
    @OrderBy("id")
    private List<LineaPedidoJpaEntity> lineas = new ArrayList<>();

    protected PedidoJpaEntity() {
        // requerido por JPA
    }

    public PedidoJpaEntity(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public UUID getClienteId() {
        return clienteId;
    }

    public void setClienteId(UUID clienteId) {
        this.clienteId = clienteId;
    }

    public EstadoPedido getEstado() {
        return estado;
    }

    public void setEstado(EstadoPedido estado) {
        this.estado = estado;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public void setCreadoEn(Instant creadoEn) {
        this.creadoEn = creadoEn;
    }

    public Long getVersion() {
        return version;
    }

    public List<LineaPedidoJpaEntity> getLineas() {
        return lineas;
    }
}
