package com.curso.pedidos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@SpringBootApplication
public class PedidosApplication {

    public static void main(String[] args) {
        SpringApplication.run(PedidosApplication.class, args);
    }

    // el RestTemplate "de toda la vida", definido en la clase main porque sí
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(30))   // 30 s con la transacción abierta...
                .build();
    }
}
