package com.curso.pedidos.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pedidos")
public class PedidoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)    // Hibernate 6: UUID generado al persistir
    private UUID id;

    private UUID clienteId;
    private String clienteEmail;
    private String estado;              // "BORRADOR", "CONFIRMADO", "PAGADO", "ENVIADO", "CANCELADO", "confirmado" (sic)
    private BigDecimal total;           // se calcula... a veces
    private String moneda = "EUR";
    private Instant creadoEn;
    private String referenciaPago;

    @JsonIgnore                         // para que no salga en el JSON (se añadió tras un incidente)
    private String tokenTarjeta;

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.EAGER)
    @JsonManagedReference
    private List<LineaPedidoEntity> lineas = new ArrayList<>();

    // getters y setters públicos para TODO (generados por el IDE)
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getClienteId() { return clienteId; }
    public void setClienteId(UUID clienteId) { this.clienteId = clienteId; }
    public String getClienteEmail() { return clienteEmail; }
    public void setClienteEmail(String clienteEmail) { this.clienteEmail = clienteEmail; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public String getMoneda() { return moneda; }
    public void setMoneda(String moneda) { this.moneda = moneda; }
    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }
    public String getReferenciaPago() { return referenciaPago; }
    public void setReferenciaPago(String referenciaPago) { this.referenciaPago = referenciaPago; }
    public String getTokenTarjeta() { return tokenTarjeta; }
    public void setTokenTarjeta(String tokenTarjeta) { this.tokenTarjeta = tokenTarjeta; }
    public List<LineaPedidoEntity> getLineas() { return lineas; }
    public void setLineas(List<LineaPedidoEntity> lineas) { this.lineas = lineas; }
}
