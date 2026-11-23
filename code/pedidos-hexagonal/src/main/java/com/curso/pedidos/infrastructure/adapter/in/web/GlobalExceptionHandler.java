package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.exception.ProductoNoEncontradoException;
import com.curso.pedidos.domain.exception.StockInsuficienteException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/**
 * Traduce excepciones de dominio a HTTP con RFC 7807 (ProblemDetail).
 * Extiende ResponseEntityExceptionHandler para que los errores de validación de Spring
 * (400) también salgan como ProblemDetail.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler({PedidoNoEncontradoException.class, ProductoNoEncontradoException.class})
    public ProblemDetail noEncontrado(PedidoException ex) {
        return problema(HttpStatus.NOT_FOUND, "Recurso no encontrado", ex);
    }

    @ExceptionHandler({StockInsuficienteException.class, TransicionDeEstadoInvalidaException.class})
    public ProblemDetail conflicto(PedidoException ex) {
        return problema(HttpStatus.CONFLICT, "Conflicto con el estado actual", ex);
    }

    @ExceptionHandler(PedidoException.class)
    public ProblemDetail reglaDeNegocio(PedidoException ex) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Regla de negocio violada", ex);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail peticionInvalida(IllegalArgumentException ex) {
        return problema(HttpStatus.BAD_REQUEST, "Petición inválida", ex);
    }

    private ProblemDetail problema(HttpStatus status, String titulo, RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(titulo);
        problem.setType(URI.create("https://curso.example/problemas/" + ex.getClass().getSimpleName()));
        return problem;
    }
}
