package com.curso.pedidos.infrastructure.adapter.out.events;

import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.EventoDeDominio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Publica los eventos de dominio como eventos de aplicación de Spring (in-process).
 * Cualquier @EventListener / @TransactionalEventListener puede reaccionar.
 * En el día 6 se discute el Transactional Outbox para publicarlos fuera del proceso.
 */
@Component
public class SpringPublicadorDeEventosAdapter implements PublicadorDeEventos {

    private static final Logger log = LoggerFactory.getLogger(SpringPublicadorDeEventosAdapter.class);

    private final ApplicationEventPublisher publisher;

    public SpringPublicadorDeEventosAdapter(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publicar(List<EventoDeDominio> eventos) {
        eventos.forEach(evento -> {
            log.info("Evento de dominio publicado: {}", evento);
            publisher.publishEvent(evento);
        });
    }
}
