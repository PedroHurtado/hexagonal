package com.curso.pedidos.service;

import com.curso.pedidos.entity.*;
import com.curso.pedidos.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional                       // TODO el servicio es transaccional, incluidas las llamadas HTTP
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    @Autowired private PedidoRepository pedidoRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private EmailService emailService;
    @Autowired private RestTemplate restTemplate;

    @Value("${pagos.url}")
    private String pagosUrl;

    @Value("${pagos.apiKey}")
    private String apiKey;

    public PedidoEntity guardar(PedidoEntity pedido) {
        BigDecimal total = BigDecimal.ZERO;
        for (LineaPedidoEntity l : pedido.getLineas()) {
            ProductoEntity prod = productoRepository.findBySku(l.getSku());
            if (prod == null) {
                throw new RuntimeException("Producto no existe: " + l.getSku());
            }
            if (prod.getStock() < l.getCantidad()) {
                throw new RuntimeException("No hay stock de " + l.getSku());
            }
            // el precio que manda el cliente se ignora... salvo que venga informado (!)
            if (l.getPrecioUnitario() == null) {
                l.setPrecioUnitario(prod.getPrecio());
            }
            total = total.add(l.getPrecioUnitario().multiply(BigDecimal.valueOf(l.getCantidad())));
        }
        pedido.setTotal(total.setScale(2, RoundingMode.HALF_UP));
        return pedidoRepository.save(pedido);
    }

    public PedidoEntity confirmarYCobrar(PedidoEntity p, String tokenTarjeta) {
        log.info("Confirmando pedido " + p.getId());

        if (p.getLineas().isEmpty()) {
            throw new IllegalStateException("Pedido vacío");
        }
        if (!"BORRADOR".equalsIgnoreCase(p.getEstado())) {
            throw new IllegalStateException("Estado no válido: " + p.getEstado());
        }

        // 1) descontar stock
        for (LineaPedidoEntity l : p.getLineas()) {
            ProductoEntity prod = productoRepository.findBySku(l.getSku());
            prod.setStock(prod.getStock() - l.getCantidad());       // puede quedar negativo
            productoRepository.save(prod);
        }

        p.setEstado("CONFIRMADO");
        pedidoRepository.save(p);

        // 2) cobrar con la pasarela, DENTRO de la transacción
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", apiKey);
        Map<String, Object> body = new HashMap<>();
        body.put("amount", p.getTotal().multiply(new BigDecimal(100)).intValue()); // céntimos
        body.put("currency", p.getMoneda());
        body.put("token", tokenTarjeta);
        body.put("reference", "PED-" + p.getId());

        ResponseEntity<Map> resp = restTemplate.postForEntity(
                pagosUrl + "/charges", new HttpEntity<>(body, headers), Map.class);

        if (resp.getStatusCode() != HttpStatus.OK || resp.getBody() == null) {
            throw new RuntimeException("Pago rechazado");
            // rollback del stock y del estado... pero el cargo YA se ha hecho si el 200 llegó tarde
        }
        p.setReferenciaPago((String) resp.getBody().get("id"));
        p.setEstado("PAGADO");

        // 3) email DENTRO de la transacción (SMTP lento = transacción abierta 20 s)
        try {
            emailService.enviar(p.getClienteEmail(),
                    "Tu pedido " + p.getId() + " está confirmado",
                    plantillaConfirmacion(p));
        } catch (Exception e) {
            log.error("Error enviando email", e);                    // se traga el error
        }

        // 4) fidelización: puntos por compra (añadido en 2024)
        if (p.getTotal().compareTo(new BigDecimal("100")) > 0) {
            int puntos = calcularPuntos(p);
            log.info("Cliente " + p.getClienteId() + " gana " + puntos + " puntos");
            // TODO guardar los puntos en algún sitio (la tabla de fidelización "llegará")
        }

        return pedidoRepository.save(p);
    }

    // ------------------------------------------------------------------
    // Fidelización (2024): nadie recuerda las reglas, pero "funcionan"
    // ------------------------------------------------------------------
    private int calcularPuntos(PedidoEntity p) {
        int puntos = 0;
        BigDecimal total = p.getTotal();
        if (total != null) {
            if (total.compareTo(new BigDecimal("100")) > 0) {
                puntos = total.intValue();
                if (total.compareTo(new BigDecimal("500")) > 0) {
                    puntos = puntos * 2;
                    if (total.compareTo(new BigDecimal("1000")) > 0) {
                        puntos = puntos + 500;
                    }
                } else {
                    if (p.getLineas().size() > 5) {
                        puntos = puntos + 50;
                    } else if (p.getLineas().size() > 2) {
                        puntos = puntos + 20;
                    }
                }
                for (LineaPedidoEntity l : p.getLineas()) {
                    if (l.getSku() != null && l.getSku().startsWith("PROMO-")) {
                        puntos = puntos + 10 * l.getCantidad();
                    } else if (l.getCantidad() > 10) {
                        if (l.getPrecioUnitario() != null
                                && l.getPrecioUnitario().compareTo(new BigDecimal("5")) < 0) {
                            puntos = puntos - 5;              // "para que no abusen"
                        }
                    }
                }
                if (!"EUR".equals(p.getMoneda())) {
                    puntos = puntos / 2;                      // nadie sabe por qué
                }
            }
        }
        if (puntos < 0) {
            puntos = 0;
        }
        return puntos;
    }

    // ------------------------------------------------------------------
    // Reembolsos: otra llamada HTTP dentro de la transacción, otro email
    // ------------------------------------------------------------------
    public PedidoEntity reembolsar(UUID id) {
        PedidoEntity p = pedidoRepository.findById(id).orElse(null);
        if (p == null) {
            throw new RuntimeException("Pedido no encontrado " + id);
        }
        if (!"PAGADO".equals(p.getEstado()) && !"ENVIADO".equals(p.getEstado())) {
            throw new RuntimeException("No se puede reembolsar un pedido en estado " + p.getEstado());
        }
        if (p.getReferenciaPago() == null) {
            throw new RuntimeException("Pedido sin referencia de pago");
        }

        RestTemplate rt = new RestTemplate();                 // otro RestTemplate, sin timeouts
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", apiKey);
        Map<String, Object> body = new HashMap<>();
        body.put("charge", p.getReferenciaPago());
        body.put("amount", p.getTotal().multiply(new BigDecimal(100)).intValue());

        ResponseEntity<Map> resp = rt.postForEntity(
                pagosUrl + "/refunds", new HttpEntity<>(body, headers), Map.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Reembolso rechazado: " + resp.getStatusCode());
        }

        // devolver el stock (si el producto sigue existiendo)
        for (LineaPedidoEntity l : p.getLineas()) {
            ProductoEntity prod = productoRepository.findBySku(l.getSku());
            if (prod != null) {
                prod.setStock(prod.getStock() + l.getCantidad());
                productoRepository.save(prod);
            }
        }
        p.setEstado("REEMBOLSADO");

        try {
            emailService.enviar(p.getClienteEmail(),
                    "Reembolso del pedido " + p.getId(),
                    "<html><body><p>Hemos reembolsado " + p.getTotal() + " " + p.getMoneda()
                            + "</p></body></html>");
        } catch (Exception e) {
            log.error("Error enviando email de reembolso", e);
        }
        return pedidoRepository.save(p);
    }

    // ------------------------------------------------------------------
    // Reintentos manuales: lo lanza soporte "a mano" desde un endpoint interno
    // ------------------------------------------------------------------
    public int reintentarCobrosPendientes(String tokenGenerico) {
        int ok = 0;
        List<PedidoEntity> pendientes = new ArrayList<>(pedidoRepository.findByEstado("CONFIRMADO"));
        pendientes.addAll(pedidoRepository.findByEstado("confirmado"));   // datos antiguos (sic)
        for (PedidoEntity p : pendientes) {
            for (int intento = 1; intento <= 3; intento++) {
                try {
                    p.setEstado("BORRADOR");                  // truco para pasar la validación
                    confirmarYCobrar(p, tokenGenerico);
                    ok++;
                    break;
                } catch (Exception e) {
                    log.warn("Reintento " + intento + " fallido para " + p.getId() + ": " + e.getMessage());
                    p.setEstado("CONFIRMADO");
                }
            }
        }
        return ok;
    }

    // ------------------------------------------------------------------
    // Informes de ventas (consultas en memoria sobre TODOS los pedidos)
    // ------------------------------------------------------------------
    @Transactional(readOnly = true)
    public Map<String, Object> informeVentas(int ultimosDias) {
        Instant desde = Instant.now().minus(ultimosDias, ChronoUnit.DAYS);
        BigDecimal facturado = BigDecimal.ZERO;
        int numPedidos = 0;
        Map<String, Integer> unidadesPorSku = new HashMap<>();
        for (PedidoEntity p : pedidoRepository.findAll()) {    // findAll() en producción...
            if (p.getCreadoEn() == null || p.getCreadoEn().isBefore(desde)) {
                continue;
            }
            if ("PAGADO".equals(p.getEstado()) || "ENVIADO".equals(p.getEstado())) {
                numPedidos++;
                facturado = facturado.add(p.getTotal() == null ? BigDecimal.ZERO : p.getTotal());
                for (LineaPedidoEntity l : p.getLineas()) {
                    unidadesPorSku.merge(l.getSku(), l.getCantidad(), Integer::sum);
                }
            }
        }
        Map<String, Object> informe = new LinkedHashMap<>();
        informe.put("desde", desde.toString());
        informe.put("pedidos", numPedidos);
        informe.put("facturado", facturado.setScale(2, RoundingMode.HALF_UP));
        informe.put("ticketMedio", numPedidos == 0 ? BigDecimal.ZERO
                : facturado.divide(BigDecimal.valueOf(numPedidos), 2, RoundingMode.HALF_UP));
        informe.put("unidadesPorSku", unidadesPorSku);
        return informe;
    }

    // ------------------------------------------------------------------
    // Exportación CSV (sin escapar comas, claro)
    // ------------------------------------------------------------------
    @Transactional(readOnly = true)
    public String exportarCsv() {
        StringBuilder sb = new StringBuilder("id,cliente,email,estado,total,moneda,creado\n");
        for (PedidoEntity p : pedidoRepository.findAll()) {
            sb.append(p.getId()).append(',')
              .append(p.getClienteId()).append(',')
              .append(p.getClienteEmail()).append(',')
              .append(p.getEstado()).append(',')
              .append(p.getTotal()).append(',')
              .append(p.getMoneda()).append(',')
              .append(p.getCreadoEn()).append('\n');
        }
        return sb.toString();
    }

    private String plantillaConfirmacion(PedidoEntity p) {
        StringBuilder sb = new StringBuilder("<html><body><h1>Gracias</h1><ul>");
        for (LineaPedidoEntity l : p.getLineas()) {
            sb.append("<li>").append(l.getSku()).append(" x ").append(l.getCantidad()).append("</li>");
        }
        return sb.append("</ul><p>Total: ").append(p.getTotal()).append("</p></body></html>").toString();
    }
}
