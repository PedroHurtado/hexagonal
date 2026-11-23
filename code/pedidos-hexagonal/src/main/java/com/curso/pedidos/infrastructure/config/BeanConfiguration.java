package com.curso.pedidos.infrastructure.config;

import com.curso.pedidos.application.port.in.AnadirLineaUseCase;
import com.curso.pedidos.application.port.in.CancelarPedidoUseCase;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.in.ConsultarPedidoQuery;
import com.curso.pedidos.application.port.in.CrearPedidoUseCase;
import com.curso.pedidos.application.port.out.CatalogoProductos;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.application.service.AnadirLineaService;
import com.curso.pedidos.application.service.CancelarPedidoService;
import com.curso.pedidos.application.service.ConfirmarPedidoService;
import com.curso.pedidos.application.service.ConsultarPedidoService;
import com.curso.pedidos.application.service.CrearPedidoService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Composition root: aquí (y solo aquí) se cablean los servicios de aplicación, que son
 * Java plano. La transacción se aplica envolviendo cada caso de uso con un
 * TransactionTemplate: como los puertos de entrada son interfaces funcionales, el
 * "decorador transaccional" es una lambda. Dominio y aplicación siguen sin Spring.
 */
@Configuration
public class BeanConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    TransactionTemplate readOnlyTransactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    @Bean
    CrearPedidoUseCase crearPedidoUseCase(PedidoRepository pedidoRepository, Clock clock,
                                          TransactionTemplate transactionTemplate) {
        CrearPedidoUseCase service = new CrearPedidoService(pedidoRepository, clock);
        return command -> transactionTemplate.execute(status -> service.ejecutar(command));
    }

    @Bean
    AnadirLineaUseCase anadirLineaUseCase(PedidoRepository pedidoRepository, CatalogoProductos catalogoProductos,
                                          TransactionTemplate transactionTemplate) {
        AnadirLineaUseCase service = new AnadirLineaService(pedidoRepository, catalogoProductos);
        return command -> transactionTemplate.executeWithoutResult(status -> service.ejecutar(command));
    }

    @Bean
    ConfirmarPedidoUseCase confirmarPedidoUseCase(PedidoRepository pedidoRepository,
                                                  PublicadorDeEventos publicadorDeEventos,
                                                  TransactionTemplate transactionTemplate) {
        ConfirmarPedidoUseCase service = new ConfirmarPedidoService(pedidoRepository, publicadorDeEventos);
        return command -> transactionTemplate.executeWithoutResult(status -> service.ejecutar(command));
    }

    @Bean
    CancelarPedidoUseCase cancelarPedidoUseCase(PedidoRepository pedidoRepository,
                                                PublicadorDeEventos publicadorDeEventos,
                                                TransactionTemplate transactionTemplate) {
        CancelarPedidoUseCase service = new CancelarPedidoService(pedidoRepository, publicadorDeEventos);
        return command -> transactionTemplate.executeWithoutResult(status -> service.ejecutar(command));
    }

    @Bean
    ConsultarPedidoQuery consultarPedidoQuery(PedidoRepository pedidoRepository,
                                              TransactionTemplate readOnlyTransactionTemplate) {
        ConsultarPedidoQuery service = new ConsultarPedidoService(pedidoRepository);
        return id -> readOnlyTransactionTemplate.execute(status -> service.consultar(id));
    }
}
