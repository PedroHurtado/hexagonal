package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.event.EventoDeDominio;

import java.util.List;

/** Puerto de salida para difundir eventos de dominio (Spring events, Kafka, outbox...). */
public interface PublicadorDeEventos {

    void publicar(List<EventoDeDominio> eventos);
}
