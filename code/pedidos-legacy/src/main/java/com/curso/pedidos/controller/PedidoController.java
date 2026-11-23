package com.curso.pedidos.controller;

import com.curso.pedidos.entity.LineaPedidoEntity;
import com.curso.pedidos.entity.PedidoEntity;
import com.curso.pedidos.repository.PedidoRepository;
import com.curso.pedidos.service.PedidoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.UUID;

@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    @Autowired private PedidoService pedidoService;
    @Autowired private PedidoRepository pedidoRepository;   // ¡el controlador va directo al repo!

    @PostMapping
    public ResponseEntity<PedidoEntity> crear(@RequestBody PedidoEntity pedido) {
        // "validación"
        if (pedido.getLineas() == null || pedido.getLineas().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        for (LineaPedidoEntity l : pedido.getLineas()) {
            l.setPedido(pedido);                            // arreglo para la relación bidireccional
        }
        pedido.setEstado("BORRADOR");
        pedido.setCreadoEn(Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(pedidoService.guardar(pedido));
    }

    @GetMapping("/{id}")
    public PedidoEntity obtener(@PathVariable UUID id) {
        return pedidoRepository.findById(id).orElse(null);  // devuelve 200 con body vacío si no existe
    }

    @PostMapping("/{id}/confirmar")
    public ResponseEntity<?> confirmar(@PathVariable UUID id,
                                       @RequestParam(required = false) String tokenTarjeta) {
        PedidoEntity p = pedidoRepository.findById(id).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        if ("CANCELADO".equals(p.getEstado())) {
            return ResponseEntity.status(409).body("Pedido cancelado");
        }
        // descuento de Black Friday metido "temporalmente" en 2023
        if (LocalDate.now().getMonth() == Month.NOVEMBER && p.getLineas().size() > 3) {
            p.setTotal(p.getTotal().multiply(new BigDecimal("0.90")));
        }
        try {
            return ResponseEntity.ok(pedidoService.confirmarYCobrar(p, tokenTarjeta));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());   // filtra mensajes internos al cliente
        }
    }

    @PostMapping("/{id}/cancelar")
    public PedidoEntity cancelar(@PathVariable UUID id) {
        PedidoEntity p = pedidoRepository.findById(id).get();       // NoSuchElementException → 500
        p.setEstado("CANCELADO");
        return pedidoRepository.save(p);                            // ¿y si estaba ENVIADO?
    }
}
