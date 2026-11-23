package com.curso.pedidos.application.service.fakes;

import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.EventoDeDominio;

import java.util.ArrayList;
import java.util.List;

public class PublicadorDeEventosEnMemoria implements PublicadorDeEventos {

    private final List<EventoDeDominio> publicados = new ArrayList<>();

    @Override
    public void publicar(List<EventoDeDominio> eventos) {
        publicados.addAll(eventos);
    }

    public List<EventoDeDominio> publicados() {
        return List.copyOf(publicados);
    }
}
