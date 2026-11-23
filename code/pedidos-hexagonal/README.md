# pedidos-hexagonal — proyecto de referencia del curso

Proyecto de referencia del curso **Arquitectura Hexagonal con Java y Spring Boot**. Implementa el dominio común del curso, "Pedidos" (tienda online), con la estructura de paquetes canónica de puertos y adaptadores.

- Java 17 (compatible con 21; para un proyecto nuevo, mejor 21), Spring Boot 3.3.5, Maven.
- H2 en memoria y Flyway (`V1__crear_tablas_pedidos.sql`).
- JUnit 5, AssertJ, Mockito, Spring Test (slices) y ArchUnit.

---

## Cómo compilar y ejecutar

Necesitas un JDK 17 o superior en `JAVA_HOME`.

```bash
# Compilar y pasar todos los tests (unitarios, slices, end-to-end y ArchUnit)
mvn verify          # o ./mvnw verify  (Maven Wrapper incluido)

# Arrancar la aplicación en http://localhost:8080
mvn spring-boot:run # o ./mvnw spring-boot:run
```

Consola de H2: http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:pedidos`, usuario `sa`, sin contraseña).

---

## Endpoints

| Método | Ruta                               | Caso de uso              | Respuesta OK |
|--------|------------------------------------|--------------------------|--------------|
| POST   | `/api/pedidos`                     | `CrearPedidoUseCase`     | 201 + `Location` + `{id}` |
| POST   | `/api/pedidos/{id}/lineas`         | `AnadirLineaUseCase`     | 204 |
| POST   | `/api/pedidos/{id}/confirmacion`   | `ConfirmarPedidoUseCase` | 204 |
| POST   | `/api/pedidos/{id}/cancelacion`    | `CancelarPedidoUseCase`  | 204 |
| GET    | `/api/pedidos/{id}`                | `ConsultarPedidoQuery`   | 200 + pedido |

Productos del catálogo en memoria: `LIBRO-DDD` (45,00 €, stock 10), `TECLADO-MEC` (89,90 €, stock 5), `TAZA-JAVA` (12,50 €, stock 100) y `MONITOR-27` (299,00 €, **stock 0**, útil para provocar el 409).

```bash
# 1. Crear pedido
curl -i -X POST http://localhost:8080/api/pedidos \
  -H "Content-Type: application/json" \
  -d '{"clienteId":"3f1c2d4e-1111-2222-3333-444455556666"}'
# -> 201 Created, Location: http://localhost:8080/api/pedidos/<ID>, body {"id":"<ID>"}

ID=<pega aquí el id>

# 2. Añadir líneas (el precio lo pone el catálogo, no el cliente)
curl -i -X POST http://localhost:8080/api/pedidos/$ID/lineas \
  -H "Content-Type: application/json" -d '{"sku":"LIBRO-DDD","cantidad":2}'
curl -i -X POST http://localhost:8080/api/pedidos/$ID/lineas \
  -H "Content-Type: application/json" -d '{"sku":"TAZA-JAVA","cantidad":1}'

# 3. Confirmar
curl -i -X POST http://localhost:8080/api/pedidos/$ID/confirmacion

# 4. Consultar
curl -s http://localhost:8080/api/pedidos/$ID
# {"id":"...","clienteId":"...","estado":"CONFIRMADO","lineas":[...],"total":102.50,"moneda":"EUR","creadoEn":"..."}

# 5. Cancelar
curl -i -X POST http://localhost:8080/api/pedidos/$ID/cancelacion
```

Los errores se devuelven como **ProblemDetail (RFC 7807)**, con `Content-Type: application/problem+json`:

| Excepción                                                          | HTTP |
|--------------------------------------------------------------------|------|
| `PedidoNoEncontradoException`, `ProductoNoEncontradoException`     | 404  |
| `StockInsuficienteException`, `TransicionDeEstadoInvalidaException`| 409  |
| `PedidoException` (otras reglas: pedido vacío, añadir a confirmado)| 422  |
| Validación de DTO / `IllegalArgumentException` de los value objects| 400  |

```bash
curl -s -X POST http://localhost:8080/api/pedidos/$ID/lineas \
  -H "Content-Type: application/json" -d '{"sku":"MONITOR-27","cantidad":1}'
# {"type":"https://curso.example/problemas/StockInsuficienteException","title":"Conflicto con el estado actual","status":409,"detail":"Stock insuficiente para MONITOR-27: solicitadas 1, disponibles 0",...}
```

---

## Mapa de paquetes

```
com.curso.pedidos
├── PedidosApplication
├── domain                         ← Java puro, sin Spring/JPA/Jackson
│   ├── model      Pedido (agregado), LineaPedido, EstadoPedido, PedidoId, ClienteId,
│   │              ProductoId, Cantidad, Dinero, Email, Producto
│   ├── event      EventoDeDominio (sealed), PedidoConfirmado, PedidoPagado, PedidoCancelado
│   ├── exception  PedidoException y subclases
│   └── service    CalculadoraDePrecios (servicio de dominio de ejemplo)
├── application                    ← casos de uso, sin Spring
│   ├── port.in    CrearPedidoUseCase, AnadirLineaUseCase, ConfirmarPedidoUseCase,
│   │              CancelarPedidoUseCase, ConsultarPedidoQuery (+ commands record)
│   ├── port.out   PedidoRepository, CatalogoProductos, PublicadorDeEventos
│   └── service    XxxService (implementan los puertos de entrada)
└── infrastructure
    ├── adapter.in.web           PedidoController, dto/*, GlobalExceptionHandler
    ├── adapter.out.persistence  PedidoJpaEntity, LineaPedidoJpaEntity, PedidoJpaRepository,
    │                            PedidoPersistenceMapper, PedidoPersistenceAdapter
    ├── adapter.out.catalogo     InMemoryCatalogoProductosAdapter
    ├── adapter.out.events       SpringPublicadorDeEventosAdapter
    └── config                   BeanConfiguration (composition root + transacciones)
```

### Paquetes ↔ días del curso

| Día    | Tema                                                  | Dónde mirar en el código |
|--------|-------------------------------------------------------|--------------------------|
| day-01 | Fundamentos, principios, introducción al hexágono      | Visión general: `domain` ← `application` ← `infrastructure`; `ArquitecturaHexagonalTest` como "contrato" de dependencias |
| day-02 | Anatomía: dominio, puertos y su clasificación          | `domain.model` (agregado `Pedido`, value objects), `domain.event`, `domain.service`, `application.port.in` / `port.out` |
| day-03 | Adaptadores de entrada                                 | `infrastructure.adapter.in.web` (controller, DTOs, `GlobalExceptionHandler` con ProblemDetail) |
| day-04 | Adaptadores de salida y gestión de dependencias        | `adapter.out.persistence`, `adapter.out.catalogo`, `adapter.out.events`, `config.BeanConfiguration` |
| day-05 | Testing en arquitecturas desacopladas                  | `src/test`: dominio (`@Nested`, `@ParameterizedTest`), servicios con fakes (`application/service/fakes`) y con Mockito, `@WebMvcTest`, `@DataJpaTest`, `@SpringBootTest`, ArchUnit |
| day-06 | Persistencia, acceso a datos, transacciones            | Mapper dominio ↔ JPA, `Pedido.reconstituir`, `@Version`, Flyway, `TransactionTemplate` en `BeanConfiguration`, eventos tras guardar |
| day-07 | Hexagonal vs Clean vs Vertical Slice                   | Comparar con `../pedidos-vertical-slice` (mismo dominio, un fichero por feature) |
| day-08 | Proyecto final "Del monolito al hexágono"              | Este proyecto es el estado objetivo de la migración |

---

## Decisiones de diseño

1. **Dominio y aplicación sin Spring.** Los servicios de aplicación son clases Java planas. Se registran como beans en `infrastructure.config.BeanConfiguration`. Hay una alternativa pragmática: `@Service` o una anotación propia `@UseCase` en los servicios. Ahorra configuración, pero acopla la capa de aplicación al framework. Las reglas de ArchUnit impiden que esa dependencia aparezca sin querer.

2. **Transacciones con `TransactionTemplate` envolviendo cada caso de uso.** Los puertos de entrada son interfaces funcionales, así que en `BeanConfiguration` cada bean es una lambda:
   ```java
   ConfirmarPedidoUseCase service = new ConfirmarPedidoService(repo, publicador);
   return command -> transactionTemplate.executeWithoutResult(s -> service.ejecutar(command));
   ```
   Con esto, todo el caso de uso (cargar, mutar, guardar, publicar eventos) va en **una sola transacción**, y no hace falta `@Transactional` en la capa de aplicación. `ConsultarPedidoQuery` usa una plantilla `readOnly`. La alternativa más habitual es poner `@Transactional` en el servicio de aplicación. Es más simple, pero mete Spring en la aplicación. La otra opción, `@Transactional` en el adaptador de persistencia, se descarta porque la transacción quedaría por debajo del caso de uso y no lo cubriría entero.

3. **Agregado rico.** `Pedido` protege sus invariantes:
   - solo se añaden líneas en `BORRADOR`;
   - no se confirma un pedido vacío;
   - no se cancela un pedido `ENVIADO` ni uno ya `CANCELADO`;
   - todas las líneas van en la misma moneda.

   Las líneas solo se exponen como lista inmodificable. Los eventos se acumulan dentro del agregado y el servicio los extrae (`extraerEventos()`) **después de guardar**.

4. **Modelo de persistencia separado.** `PedidoJpaEntity` vive en infraestructura y `PedidoPersistenceMapper` traduce en ambos sentidos. Cuesta más código, pero el dominio no tiene ni anotaciones JPA, ni constructores vacíos, ni setters. `Pedido.reconstituir(...)` rehidrata el agregado sin repetir validaciones de transición y sin generar eventos. MapStruct es una alternativa válida para el mapper.

5. **El esquema lo gestiona Flyway** y Hibernate solo lo valida (`ddl-auto=validate`). `@Version` en la entidad da bloqueo optimista.

6. **Lectura con modelo propio.** `ConsultarPedidoQuery` devuelve `PedidoDetalle` (un record) y no el agregado. Así el adaptador web no puede mutar el dominio.

7. **CQS en HTTP.** Los comandos responden 204 (o 201 con `Location` al crear) y la lectura se hace con `GET`.

8. **Eventos in-process.** `SpringPublicadorDeEventosAdapter` deja traza en el log y publica con `ApplicationEventPublisher`. Para publicar fuera del proceso con garantías, ver el patrón *Transactional Outbox* (día 6).

9. **Clock inyectado.** Hace las fechas deterministas en los tests.

---

## Tests

| Tipo                    | Clase(s)                                             | Qué demuestra |
|-------------------------|------------------------------------------------------|---------------|
| Unitario de dominio     | `PedidoTest`, `DineroTest`, `ValueObjectsTest`       | Invariantes y value objects; `@Nested`, `@ParameterizedTest` |
| Unitario de aplicación  | `PedidoServicesTest` (fakes en memoria)              | Casos de uso sin Spring ni mocks |
| Unitario con Mockito    | `CancelarPedidoServiceTest`                          | Verificación de interacciones con puertos de salida |
| Slice web               | `PedidoControllerTest` (`@WebMvcTest`)               | Mapeo HTTP ↔ command y ProblemDetail |
| Slice de persistencia   | `PedidoPersistenceAdapterTest` (`@DataJpaTest`)      | Adaptador JPA + esquema Flyway sobre H2 |
| End-to-end              | `PedidosE2ETest` (`@SpringBootTest` + MockMvc)       | Flujo completo + `@RecordApplicationEvents` |
| Arquitectura            | `ArquitecturaHexagonalTest` (ArchUnit)               | domain/application no dependen de Spring/JPA/infraestructura; reglas onion |

**Sobre Testcontainers:** este proyecto no lo usa a propósito, para que `mvn verify` funcione en cualquier máquina sin Docker. En un proyecto real, el `@DataJpaTest` debería ir contra PostgreSQL con Testcontainers (`@AutoConfigureTestDatabase(replace = NONE)` + `@ServiceConnection`), porque H2 no se comporta igual que la base de datos de producción. Se ve en el día 5.

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture*: https://alistair.cockburn.us/hexagonal-architecture/
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture*, repo: https://github.com/thombergs/buckpal
- Spring Framework, transacciones: https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- ArchUnit: https://www.archunit.org/userguide/html/000_Index.html
