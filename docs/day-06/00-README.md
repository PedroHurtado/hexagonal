# Día 6 — Módulo 5. Casos de uso reales y patrones avanzados

Sesión de **3h45**. Hasta ahora el hexágono ha vivido en un mundo amable:
un `PedidoRepository` que guarda y busca, un servicio que orquesta y unos
tests que pasan en verde. Hoy bajamos al barro. Bases de datos de verdad,
migraciones, versiones concurrentes, transacciones que hay que poner en
algún sitio, eventos que no se pueden perder y una pasarela de pago que
tarda tres segundos y a veces devuelve un 503.

Es el día en el que el alumno descubre que **la arquitectura hexagonal no
elimina los problemas de infraestructura: los coloca en su sitio**. Y que
colocarlos bien es justo lo que separa una demo de un sistema que aguanta
un Black Friday.

---

## Objetivos del día

Al terminar la sesión, el alumno será capaz de:

1. Decidir con criterio entre **separar modelo de dominio y modelo de
   persistencia** (con mapper) o **anotar el dominio con JPA**, sabiendo lo
   que cuesta cada opción.
2. Gestionar **migraciones con Flyway**, **identidad generada por el
   dominio** (UUID) y **bloqueo optimista** con `@Version` sin ensuciar el
   dominio.
3. Distinguir **Repository, DAO, Active Record y Table Data Gateway**, y
   aplicar **Specification** y un **CQRS ligero** para las lecturas.
4. Colocar `@Transactional` en el sitio adecuado (o sustituirlo por un
   puerto propio) y evitar las trampas clásicas: *self-invocation*,
   propagación mal entendida, transacciones gigantes.
5. Publicar **eventos de dominio** de forma fiable con
   `@TransactionalEventListener` y **Transactional Outbox**, y razonar
   sobre **consistencia eventual, idempotencia y Sagas**.
6. Implementar de punta a punta un caso de uso real: **PagarPedido**, con
   pasarela externa fuera de la transacción, clave de idempotencia y
   manejo de fallos.

---

## Estructura del día

| #  | Bloque                                          | Duración orientativa | Fichero                                                                              |
|----|-------------------------------------------------|----------------------|--------------------------------------------------------------------------------------|
| 0  | Arranque: dónde lo dejamos (day-04 y day-05)    | 10 min               | este README                                                                          |
| 1  | Persistencia y bases de datos                   | 40 min               | [01-persistencia-y-bases-de-datos.md](01-persistencia-y-bases-de-datos.md)           |
| 2  | Patrones de acceso a datos                      | 35 min               | [02-patrones-de-acceso-a-datos.md](02-patrones-de-acceso-a-datos.md)                 |
| 3  | Gestión de transacciones                        | 35 min               | [03-gestion-de-transacciones.md](03-gestion-de-transacciones.md)                     |
| —  | Descanso                                        | 15 min               | —                                                                                    |
| 4  | Eventos de dominio y consistencia               | 35 min               | [04-eventos-de-dominio-y-consistencia.md](04-eventos-de-dominio-y-consistencia.md)   |
| 5  | Caso real completo: pago de pedido              | 30 min               | [05-caso-real-completo-pago-de-pedido.md](05-caso-real-completo-pago-de-pedido.md)   |
| 6  | Ejercicios y repaso                             | 25 min               | [06-ejercicios-dia-06.md](06-ejercicios-dia-06.md)                                   |
|    | **Total**                                       | **3h45**             |                                                                                      |

---

## Cómo enfocar el día

Los markdowns son **material extendido**: el alumno se los lleva a casa.
En clase se aprieta el ritmo y se usa el código como hilo conductor.

- **Bloques 1–3 (antes del descanso): la base de datos manda.** Partimos del
  `PedidoPersistenceAdapter` del día 4 y lo endurecemos: migraciones,
  versión, identidad, lecturas rápidas, transacciones. Es la parte "de
  fontanería", pero es donde se rompen los proyectos reales.
- **Bloque 4: el sistema deja de ser una sola transacción.** Eventos,
  outbox, sagas. Aquí la pregunta cambia de "¿cómo guardo?" a "¿qué pasa si
  guardo y luego falla lo siguiente?".
- **Bloque 5: todo junto.** `PagarPedido` combina todo lo del día. Es el
  bloque que más se recuerda; **defiéndelo** aunque vayas con retraso.

### Si el ritmo se desvía

- **Bloque 2** se puede comprimir a 25 min: la tabla Repository/DAO/Active
  Record/TDG se da rápido; Specification puede quedarse en "leedlo en casa".
  **CQRS ligero no se recorta**: es lo que más usarán el lunes.
- **Sagas** (final del bloque 4) se puede quedar en el diagrama ASCII y la
  tabla coreografía vs orquestación.
- **El bloque 5 siempre se da**, aunque sea solo el diagrama de secuencia y
  el servicio `PagarPedidoService`.

---

## Conexión con días anteriores y siguientes

```
  day-04                     day-05                      day-06 (HOY)
┌──────────────────┐     ┌──────────────────┐     ┌────────────────────────────┐
│ Adaptadores de   │     │ Testing:         │     │ Persistencia real          │
│ salida:          │────►│ unitarios,       │────►│ Transacciones              │
│ PedidoPersistence│     │ slices, Testcont.│     │ Eventos + Outbox + Sagas   │
│ Adapter, mapper  │     │ WireMock, ArchUnit│    │ Caso real: PagarPedido     │
└──────────────────┘     └──────────────────┘     └─────────────┬──────────────┘
                                                                │
                                                                ▼
                                                  day-07: Hexagonal vs Clean
                                                  vs Vertical Slice
```

- **Del día 4** traemos el adaptador JPA (`PedidoJpaEntity`,
  `PedidoJpaRepository`, `PedidoPersistenceAdapter`) y el mapper. Hoy
  responderemos a las preguntas que dejamos abiertas: ¿y la versión? ¿y las
  migraciones? ¿y si el agregado tiene 200 líneas?
- **Del día 5** traemos la caja de herramientas de testing: tests unitarios
  del dominio sin Spring, `@DataJpaTest`, **Testcontainers** con PostgreSQL
  y **WireMock** para la pasarela. Hoy cada tema termina con su test.
- **Hacia el día 7**: veremos que buena parte de lo de hoy (CQRS ligero,
  transacciones, outbox) es independiente del estilo arquitectónico, y que
  Vertical Slice lleva el CQRS ligero al extremo.

---

## Punto de partida (código)

Se parte del proyecto en `code/` tal como quedó al final del día 5:

```
com.curso.pedidos
├── domain
│   ├── model        Pedido, LineaPedido, EstadoPedido, PedidoId, ClienteId, ProductoId, Cantidad, Dinero
│   ├── event        PedidoConfirmado, PedidoPagado, PedidoCancelado
│   └── exception    PedidoException, PedidoNoEncontradoException, ...
├── application
│   ├── port/in      CrearPedidoUseCase, ConfirmarPedidoUseCase, ConsultarPedidoQuery
│   ├── port/out     PedidoRepository, CatalogoProductos, PasarelaDePago, NotificadorPedidos, PublicadorDeEventos
│   └── service      CrearPedidoService, ConfirmarPedidoService
└── infrastructure
    ├── adapter/in/web               PedidoController
    ├── adapter/out/persistence      PedidoJpaEntity, PedidoJpaRepository, PedidoPersistenceAdapter
    └── config                       BeanConfiguration
```

Dependencias nuevas que usaremos hoy (en el `pom.xml`):

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<!-- Flyway 10 (el que trae Boot 3.3) separa el soporte de cada BD -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

---

## Las cinco preguntas del día

Si al final del día el alumno sabe responder a estas cinco, el día ha
cumplido:

1. ¿Por qué mi entidad de dominio **no** debería tener `@Entity`… y cuándo
   me compensa ponérselo igualmente?
2. ¿Por qué una consulta de listado **no** tiene por qué pasar por el
   agregado `Pedido`?
3. ¿Dónde pongo `@Transactional` y qué pasa si llamo a un método
   `@Transactional` desde la misma clase?
4. Si guardo el pedido y luego falla el envío del evento a Kafka, ¿qué he
   roto y cómo lo evito?
5. ¿Por qué la llamada a la pasarela de pago **no** debe ir dentro de la
   transacción de base de datos?

---

## Referencias del día

- Martin Fowler, *Catalog of Patterns of Enterprise Application Architecture*: https://martinfowler.com/eaaCatalog/
- Martin Fowler, *CQRS*: https://martinfowler.com/bliki/CQRS.html
- Chris Richardson, *Saga*: https://microservices.io/patterns/data/saga.html
- Chris Richardson, *Transactional Outbox*: https://microservices.io/patterns/data/transactional-outbox.html
- Spring Framework, *Transaction Management*: https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- Spring Data JPA: https://docs.spring.io/spring-data/jpa/reference/
- Flyway: https://documentation.red-gate.com/flyway
- Testcontainers: https://java.testcontainers.org/
- Vlad Mihalcea (blog sobre JPA/Hibernate y concurrencia): https://vladmihalcea.com/
- Eric Evans, *Domain-Driven Design* (2003); Vaughn Vernon, *Implementing Domain-Driven Design* (2013).

---

## Mantra del día

> *"El hexágono no hace desaparecer la base de datos, la transacción ni la
> red. Los pone en su sitio, para que cuando fallen —y fallarán— sepas
> exactamente dónde mirar."*
