package com.curso.pedidos.domain.model;

import com.curso.pedidos.domain.event.EventoDeDominio;
import com.curso.pedidos.domain.event.PedidoCancelado;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.event.PedidoPagado;
import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Agregado raiz. Toda modificacion pasa por sus metodos, que protegen las invariantes:
 * <ul>
 *   <li>Solo se anaden lineas a un pedido en BORRADOR.</li>
 *   <li>No se confirma un pedido vacio.</li>
 *   <li>No se cancela un pedido ENVIADO (ni uno ya cancelado).</li>
 *   <li>Todas las lineas comparten moneda.</li>
 * </ul>
 * Los eventos de dominio se acumulan y la capa de aplicacion los extrae tras guardar.
 */
public class Pedido {

    private final PedidoId id;
    private final ClienteId clienteId;
    private final List<LineaPedido> lineas;
    private EstadoPedido estado;
    private final Instant creadoEn;
    private final Clock clock;
    private final List<EventoDeDominio> eventos = new ArrayList<>();

    private Pedido(PedidoId id, ClienteId clienteId, List<LineaPedido> lineas,
                   EstadoPedido estado, Instant creadoEn, Clock clock) {
        this.id = Objects.requireNonNull(id, "id obligatorio");
        this.clienteId = Objects.requireNonNull(clienteId, "clienteId obligatorio");
        this.lineas = new ArrayList<>(Objects.requireNonNull(lineas, "lineas obligatorias"));
        this.estado = Objects.requireNonNull(estado, "estado obligatorio");
        this.creadoEn = Objects.requireNonNull(creadoEn, "creadoEn obligatorio");
        this.clock = Objects.requireNonNull(clock, "clock obligatorio");
    }

    /** Factoria: un pedido nuevo siempre nace en BORRADOR y sin lineas. */
    public static Pedido crear(ClienteId clienteId) {
        return crear(clienteId, Clock.systemUTC());
    }

    public static Pedido crear(ClienteId clienteId, Clock clock) {
        return new Pedido(PedidoId.nuevo(), clienteId, List.of(), EstadoPedido.BORRADOR, clock.instant(), clock);
    }

    /** Reconstruccion desde persistencia: no valida transiciones ni genera eventos. */
    public static Pedido reconstituir(PedidoId id, ClienteId clienteId, List<LineaPedido> lineas,
                                      EstadoPedido estado, Instant creadoEn) {
        return new Pedido(id, clienteId, lineas, estado, creadoEn, Clock.systemUTC());
    }

    public void anadirLinea(ProductoId productoId, Cantidad cantidad, Dinero precioUnitario) {
        if (estado != EstadoPedido.BORRADOR) {
            throw new PedidoException(
                    "No se pueden anadir lineas a un pedido en estado " + estado + " (pedido " + id + ")");
        }
        if (!lineas.isEmpty() && !lineas.get(0).precioUnitario().mismaMonedaQue(precioUnitario)) {
            throw new PedidoException("Todas las lineas de un pedido deben tener la misma moneda");
        }
        Optional<LineaPedido> existente = lineas.stream()
                .filter(l -> l.productoId().equals(productoId))
                .findFirst();
        if (existente.isPresent()) {
            existente.get().incrementar(cantidad);
        } else {
            lineas.add(new LineaPedido(productoId, cantidad, precioUnitario));
        }
    }

    public void confirmar() {
        exigirEstado(EstadoPedido.BORRADOR, EstadoPedido.CONFIRMADO);
        if (lineas.isEmpty()) {
            throw new PedidoException("No se puede confirmar un pedido vacio (pedido " + id + ")");
        }
        estado = EstadoPedido.CONFIRMADO;
        eventos.add(new PedidoConfirmado(id, clock.instant()));
    }

    public void marcarComoPagado() {
        exigirEstado(EstadoPedido.CONFIRMADO, EstadoPedido.PAGADO);
        estado = EstadoPedido.PAGADO;
        eventos.add(new PedidoPagado(id, clock.instant()));
    }

    public void marcarComoEnviado() {
        exigirEstado(EstadoPedido.PAGADO, EstadoPedido.ENVIADO);
        estado = EstadoPedido.ENVIADO;
    }

    public void cancelar() {
        if (estado == EstadoPedido.ENVIADO || estado == EstadoPedido.CANCELADO) {
            throw new TransicionDeEstadoInvalidaException(id, estado, EstadoPedido.CANCELADO);
        }
        estado = EstadoPedido.CANCELADO;
        eventos.add(new PedidoCancelado(id, clock.instant()));
    }

    public Dinero total() {
        return lineas.stream()
                .map(LineaPedido::subtotal)
                .reduce(Dinero::sumar)
                .orElse(Dinero.cero(Dinero.EUR));
    }

    /** Devuelve los eventos pendientes y vacia la lista (se publican una sola vez). */
    public List<EventoDeDominio> extraerEventos() {
        List<EventoDeDominio> pendientes = List.copyOf(eventos);
        eventos.clear();
        return pendientes;
    }

    private void exigirEstado(EstadoPedido esperado, EstadoPedido destino) {
        if (estado != esperado) {
            throw new TransicionDeEstadoInvalidaException(id, estado, destino);
        }
    }

    public PedidoId id() {
        return id;
    }

    public ClienteId clienteId() {
        return clienteId;
    }

    public List<LineaPedido> lineas() {
        return Collections.unmodifiableList(lineas);
    }

    public EstadoPedido estado() {
        return estado;
    }

    public Instant creadoEn() {
        return creadoEn;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Pedido otro && id.equals(otro.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
