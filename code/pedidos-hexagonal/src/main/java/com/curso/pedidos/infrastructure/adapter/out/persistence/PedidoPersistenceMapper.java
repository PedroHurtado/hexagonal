package com.curso.pedidos.infrastructure.adapter.out.persistence;

import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.LineaPedido;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.ProductoId;
import org.springframework.stereotype.Component;

import java.util.Currency;
import java.util.List;

/** Mapper manual dominio <-> JPA (alternativa: MapStruct). */
@Component
public class PedidoPersistenceMapper {

    public Pedido aDominio(PedidoJpaEntity entity) {
        List<LineaPedido> lineas = entity.getLineas().stream()
                .map(l -> new LineaPedido(
                        new ProductoId(l.getProductoId()),
                        new Cantidad(l.getCantidad()),
                        new Dinero(l.getPrecioUnitario(), Currency.getInstance(l.getMoneda()))))
                .toList();
        return Pedido.reconstituir(new PedidoId(entity.getId()), new ClienteId(entity.getClienteId()),
                lineas, entity.getEstado(), entity.getCreadoEn());
    }

    /** Copia el estado del agregado sobre una entidad (nueva o ya gestionada por JPA). */
    public void copiar(Pedido pedido, PedidoJpaEntity entity) {
        entity.setClienteId(pedido.clienteId().valor());
        entity.setEstado(pedido.estado());
        entity.setCreadoEn(pedido.creadoEn());
        entity.getLineas().clear();
        pedido.lineas().forEach(l -> entity.getLineas().add(new LineaPedidoJpaEntity(
                l.productoId().sku(), l.cantidad().valor(),
                l.precioUnitario().importe(), l.precioUnitario().moneda().getCurrencyCode())));
    }
}
