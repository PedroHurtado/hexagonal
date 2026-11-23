# pedidos-legacy

Monolito "clásico" en capas (controller → service → repository) del backend de
pedidos de la tienda online. Es el **punto de partida del proyecto final** (día 8):
lo que se entrega al alumno para convertirlo en un hexágono.

Enunciado completo: [../../docs/day-08/01-enunciado-proyecto-final.md](../../docs/day-08/01-enunciado-proyecto-final.md)

## Está mal a propósito

Cada fealdad es real y forma parte del ejercicio. **No la arregles aquí**: el
objetivo es arreglarla con tests de caracterización delante. Entre otras:

- `PedidoEntity` es a la vez entidad JPA y JSON del API (`@JsonIgnore` como parche de seguridad).
- Modelo anémico, estado como `String`, getters/setters para todo.
- Lógica de negocio en `PedidoController` (descuento de Black Friday, cancelación) y acceso directo al repositorio.
- `PedidoService` es `@Transactional` entero: el cobro por `RestTemplate` y el email SMTP ocurren **dentro** de la transacción.
- Errores como `RuntimeException` → 500 con el mensaje interno; `GET` de un id inexistente → 200 vacío.
- URL y API key de pagos inyectadas con `@Value` en el servicio; inyección por campo `@Autowired`.
- Sin tests de negocio: solo `PedidosLegacyApplicationTests` (`contextLoads`).

## Arrancar

Requisitos: Java 17+. Base de datos H2 en memoria con 4 productos de ejemplo
(`LIBRO-001`, `LIBRO-002`, `TAZA-001`, `PROMO-001`), cargados desde `data.sql`.

```bash
./mvnw -q verify          # compila y pasa el único test (contextLoads)
./mvnw spring-boot:run    # http://localhost:8080  (consola H2 en /h2-console)
```

Arranca sin servicios externos. Se configuran en `application.properties`
(o por variables de entorno):

| Propiedad         | Variable        | Por defecto             | Sin servicio…                                       |
|-------------------|-----------------|-------------------------|-----------------------------------------------------|
| `pagos.url`       | `PAGOS_URL`     | `http://localhost:9090` | `confirmar` responde 500 con el mensaje interno     |
| `pagos.apiKey`    | `PAGOS_API_KEY` | `sk_test_legacy_123`    | —                                                   |
| `spring.mail.host`/`port` | `MAIL_HOST`/`MAIL_PORT` | `localhost:2525` | el envío falla y el error se traga (`log.error`) |

La pasarela espera `POST {pagos.url}/charges` y responder 200 con `{"id": "..."}`.

## Endpoints

```bash
# Crear pedido (201; 400 si no hay líneas; 500 si el SKU no existe o no hay stock)
curl -i -X POST http://localhost:8080/api/pedidos \
  -H "Content-Type: application/json" \
  -d '{"clienteId":"11111111-1111-1111-1111-111111111111","clienteEmail":"ana@example.com",
       "lineas":[{"sku":"LIBRO-001","cantidad":2},{"sku":"TAZA-001","cantidad":1}]}'

# Consultar (200 con body vacío si no existe)
curl -i http://localhost:8080/api/pedidos/{id}

# Confirmar y cobrar (404, 409 si está cancelado, 500 con el mensaje si falla el pago)
curl -i -X POST "http://localhost:8080/api/pedidos/{id}/confirmar?tokenTarjeta=tok_visa"

# Cancelar (500 si no existe; cancela aunque esté ENVIADO)
curl -i -X POST http://localhost:8080/api/pedidos/{id}/cancelar
```
