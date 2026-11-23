# Día 3 — Módulo 3 (I). Comunicación y flujo de datos: adaptadores de entrada

Material de apoyo para la tercera sesión del curso (**3h45**, con descanso).

> *"Allow an application to equally be driven by users, programs, automated test or batch scripts, and to be developed and tested in isolation from its eventual run-time devices and databases."*
> — Alistair Cockburn, *Hexagonal Architecture* (2005)

---

## Objetivos del día

Al terminar la sesión, el alumno será capaz de:

1. Explicar **qué es un adaptador de entrada** (driving adapter) y qué responsabilidades tiene… y cuáles **no**.
2. Seguir el **flujo de datos completo** de una petición: protocolo → adaptador → puerto de entrada → dominio → respuesta.
3. Construir un **adaptador REST con Spring MVC** (`PedidoController`) con DTOs `record`, Bean Validation en el borde y códigos HTTP correctos.
4. Decidir una **estrategia de mapeo** DTO ↔ comando ↔ dominio (manual, estático, MapStruct) con criterio.
5. Traducir **excepciones de dominio a `ProblemDetail`** (RFC 9457) con `@RestControllerAdvice`.
6. Añadir **otros adaptadores de entrada** (Kafka, `@Scheduled`, CLI) que reutilizan **los mismos casos de uso sin tocar el hexágono**.
7. Probar cada adaptador **aislado**: `@WebMvcTest` + `MockMvc`, y tests unitarios sin broker.

---

## Estructura del día

| # | Bloque                                   | Duración orientativa | Fichero                                                                          |
|---|------------------------------------------|----------------------|----------------------------------------------------------------------------------|
| 0 | Repaso día 2 + punto de partida          | ~10 min              | `00-README.md`                                                                   |
| 1 | Qué es un adaptador de entrada           | ~30 min              | [`01-que-es-un-adaptador-de-entrada.md`](01-que-es-un-adaptador-de-entrada.md)   |
| 2 | Adaptador REST con Spring MVC            | ~50 min              | [`02-adaptador-rest-spring-mvc.md`](02-adaptador-rest-spring-mvc.md)             |
| — | Descanso                                 | ~15 min              | —                                                                                |
| 3 | Mapeo y DTOs                             | ~30 min              | [`03-mapeo-y-dtos.md`](03-mapeo-y-dtos.md)                                       |
| 4 | Gestión de errores en el borde           | ~30 min              | [`04-gestion-de-errores-en-el-borde.md`](04-gestion-de-errores-en-el-borde.md)   |
| 5 | Otros adaptadores de entrada             | ~30 min              | [`05-otros-adaptadores-de-entrada.md`](05-otros-adaptadores-de-entrada.md)       |
| 6 | Ejercicios + preguntas de repaso         | ~30 min              | [`06-ejercicios-dia-03.md`](06-ejercicios-dia-03.md)                             |

**Total: ~3h45** (3h30 de contenido y práctica + 15 min de descanso).

---

## Decisión de ritmo

Los markdowns están escritos con **material extendido** (variantes, trampas, tests completos). En clase se da **más rápido**: lo extenso es para que os lo llevéis. El bloque 2 (REST) es el corazón del día; si vamos justos, el bloque 5 se puede ver en modo demo y dejar sus tests como deberes.

---

## Cómo enfocar el bloque

Ayer construimos **el interior** del hexágono: el agregado `Pedido`, sus value objects, los puertos de entrada (`CrearPedidoUseCase`, `ConfirmarPedidoUseCase`, `ConsultarPedidoQuery`…) y los servicios de aplicación que los implementan. Todo eso compila y se prueba **sin Spring**.

Hoy salimos al **borde izquierdo** del hexágono: el lado que *conduce* la aplicación. La pregunta del día es:

> ¿Cómo llega una petición HTTP (o un mensaje Kafka, o un cron, o una línea de comandos) hasta `Pedido.confirmar()` **sin que el dominio se entere de que existe HTTP**?

La respuesta es siempre la misma pieza con distintos disfraces: **un adaptador de entrada**. Traduce protocolo → comando, invoca el puerto, y traduce resultado/excepción → protocolo. Nada más.

```
          LADO IZQUIERDO (hoy)                         LADO DERECHO (día 4)
   ┌──────────────────────────┐                  ┌──────────────────────────┐
   │ PedidoController (REST)  │──┐            ┌─►│ PedidoPersistenceAdapter │
   │ PedidoKafkaListener      │──┤  ┌──────┐  │  │ StripeLikePasarelaAdapter│
   │ Job @Scheduled           │──┼─►│HEXÁG.│──┤  │ EmailNotificadorAdapter  │
   │ CLI (CommandLineRunner)  │──┘  └──────┘  └─►│ KafkaPublicadorDeEventos │
   └──────────────────────────┘                  └──────────────────────────┘
        adaptadores de entrada                      adaptadores de salida
        (llaman a puertos in)                      (implementan puertos out)
```

---

## Punto de partida (lo que traemos del día 2)

Paquetes y piezas que **ya existen** y que hoy **no vamos a modificar**:

```
com.curso.pedidos
├── domain
│   ├── model      Pedido, LineaPedido, EstadoPedido, PedidoId, ClienteId,
│   │              ProductoId, Cantidad, Dinero
│   └── exception  PedidoException, PedidoNoEncontradoException,
│                  StockInsuficienteException, TransicionDeEstadoInvalidaException
├── application
│   ├── port.in    CrearPedidoUseCase, AnadirLineaPedidoUseCase,
│   │              ConfirmarPedidoUseCase, CancelarPedidoUseCase, ConsultarPedidoQuery
│   ├── port.out   PedidoRepository, CatalogoProductos, ...
│   └── service    CrearPedidoService, ConfirmarPedidoService, ...
└── infrastructure
    └── config     BeanConfiguration   (cablea los servicios como @Bean)
```

Las firmas de los puertos de entrada que usaremos durante todo el día:

```java
package com.curso.pedidos.application.port.in;

public interface CrearPedidoUseCase {
    PedidoId ejecutar(CrearPedidoCommand command);
}
public record CrearPedidoCommand(ClienteId clienteId) {
    public CrearPedidoCommand { Objects.requireNonNull(clienteId, "clienteId"); }
}

public interface AnadirLineaPedidoUseCase {
    void ejecutar(AnadirLineaPedidoCommand command);   // el precio lo pone el catálogo
}
public record AnadirLineaPedidoCommand(PedidoId pedidoId, ProductoId productoId, Cantidad cantidad) { }

public interface ConfirmarPedidoUseCase {
    void ejecutar(ConfirmarPedidoCommand command);
}
public record ConfirmarPedidoCommand(PedidoId pedidoId) { }

public interface CancelarPedidoUseCase {
    void ejecutar(CancelarPedidoCommand command);
}
public record CancelarPedidoCommand(PedidoId pedidoId) { }

public interface ConsultarPedidoQuery {
    Pedido consultar(PedidoId pedidoId);   // lanza PedidoNoEncontradoException
}
```

> Nota: `ConsultarPedidoQuery` devuelve el agregado `Pedido` por sencillez didáctica. En el día 6 veremos la alternativa CQRS (devolver un *read model* propio). Hoy esa decisión nos sirve para justificar por qué **el adaptador nunca devuelve `Pedido` al cliente HTTP** (bloque 3).

Hoy añadimos **solo** código en `infrastructure.adapter.in.*`:

```
infrastructure.adapter.in
├── web        PedidoController, CrearPedidoRequest, AnadirLineaRequest,
│              PedidoResponse, LineaPedidoResponse, PedidoWebMapper,
│              GlobalExceptionHandler
├── messaging  PedidoSolicitadoKafkaListener, PedidoSolicitadoMessage
├── scheduler  CancelarBorradoresAbandonadosJob
└── cli        ImportarPedidoCliRunner
```

---

## Conexión con días anteriores y siguientes

- **Día 1** — Vimos el patrón Adapter del GoF y el principio de inversión de dependencias. Hoy el Adapter deja de ser un patrón de clases y se convierte en **una capa de la arquitectura**.
- **Día 2** — Definimos los puertos de entrada como **la API del hexágono**. Hoy comprobamos que esa API es buena: si el controlador queda fino, el puerto estaba bien diseñado; si el controlador engorda, el puerto se quedó corto.
- **Día 4** — El espejo: adaptadores **de salida** (JPA, pasarela de pago, notificaciones) y cómo se cablea todo con `BeanConfiguration`.
- **Día 5** — Estrategia de testing completa: hoy hacemos *slice tests* (`@WebMvcTest`) y tests unitarios de listeners; el día 5 añadimos Testcontainers (Kafka, PostgreSQL), ArchUnit y tests end-to-end.

---

## Material necesario

- JDK 17 (21 recomendado para proyectos nuevos), Maven 3.9+, IDE.
- Spring Boot **3.3.x** con `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-test`.
- Opcional para el bloque 5: `spring-kafka` (no hace falta broker para los tests de hoy).
- Un cliente HTTP (curl, HTTPie, Postman o el `.http` del IDE).

---

## Mantra del día

> *"El adaptador traduce, el puerto decide la frontera, el dominio decide el negocio. Si tu controlador piensa, alguien te ha robado una regla del dominio."*
