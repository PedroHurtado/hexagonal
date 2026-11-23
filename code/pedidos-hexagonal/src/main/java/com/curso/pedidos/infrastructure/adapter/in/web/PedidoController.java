package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.application.port.in.AnadirLineaUseCase;
import com.curso.pedidos.application.port.in.AnadirLineaUseCase.AnadirLineaCommand;
import com.curso.pedidos.application.port.in.CancelarPedidoUseCase;
import com.curso.pedidos.application.port.in.CancelarPedidoUseCase.CancelarPedidoCommand;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.in.ConsultarPedidoQuery;
import com.curso.pedidos.application.port.in.CrearPedidoUseCase;
import com.curso.pedidos.application.port.in.CrearPedidoUseCase.CrearPedidoCommand;
import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.ProductoId;
import com.curso.pedidos.infrastructure.adapter.in.web.dto.AnadirLineaRequest;
import com.curso.pedidos.infrastructure.adapter.in.web.dto.CrearPedidoRequest;
import com.curso.pedidos.infrastructure.adapter.in.web.dto.PedidoCreadoResponse;
import com.curso.pedidos.infrastructure.adapter.in.web.dto.PedidoResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Adaptador de entrada HTTP. Traduce HTTP -> command, invoca el puerto y traduce
 * la respuesta. Cero lógica de negocio aquí.
 */
@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    private final CrearPedidoUseCase crearPedido;
    private final AnadirLineaUseCase anadirLinea;
    private final ConfirmarPedidoUseCase confirmarPedido;
    private final CancelarPedidoUseCase cancelarPedido;
    private final ConsultarPedidoQuery consultarPedido;

    public PedidoController(CrearPedidoUseCase crearPedido, AnadirLineaUseCase anadirLinea,
                            ConfirmarPedidoUseCase confirmarPedido, CancelarPedidoUseCase cancelarPedido,
                            ConsultarPedidoQuery consultarPedido) {
        this.crearPedido = crearPedido;
        this.anadirLinea = anadirLinea;
        this.confirmarPedido = confirmarPedido;
        this.cancelarPedido = cancelarPedido;
        this.consultarPedido = consultarPedido;
    }

    @PostMapping
    public ResponseEntity<PedidoCreadoResponse> crear(@Valid @RequestBody CrearPedidoRequest request) {
        PedidoId id = crearPedido.ejecutar(new CrearPedidoCommand(new ClienteId(request.clienteId())));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(id.valor()).toUri();
        return ResponseEntity.created(location).body(new PedidoCreadoResponse(id.valor()));
    }

    @PostMapping("/{id}/lineas")
    public ResponseEntity<Void> anadirLinea(@PathVariable UUID id, @Valid @RequestBody AnadirLineaRequest request) {
        anadirLinea.ejecutar(new AnadirLineaCommand(
                new PedidoId(id), new ProductoId(request.sku()), new Cantidad(request.cantidad())));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/confirmacion")
    public ResponseEntity<Void> confirmar(@PathVariable UUID id) {
        confirmarPedido.ejecutar(new ConfirmarPedidoCommand(new PedidoId(id)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancelacion")
    public ResponseEntity<Void> cancelar(@PathVariable UUID id) {
        cancelarPedido.ejecutar(new CancelarPedidoCommand(new PedidoId(id)));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public PedidoResponse consultar(@PathVariable UUID id) {
        return PedidoResponse.desde(consultarPedido.consultar(new PedidoId(id)));
    }
}
