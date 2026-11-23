# Día 8 — Bloque 5: Solución de referencia

> *"Allow an application to equally be driven by users, programs, automated
> test or batch scripts, and to be developed and tested in isolation from its
> eventual run-time devices and databases."*
> — Alistair Cockburn, *Hexagonal Architecture* (2005)

---

## 1. Dónde está el código

El proyecto completo y compilable vive en:

```
../../code/pedidos-hexagonal
```

```bash
cd code/pedidos-hexagonal
mvn verify            # suite completa: dominio, casos de uso, web, JPA, E2E y ArchUnit
mvn spring-boot:run   # API en http://localhost:8080
```

> **Sugerencia para el aula:** el repositorio no trae *tags* por fase. Si
> queréis puntos de rescate para quien se quede atrás, cread un commit + tag
> al cerrar cada fase de la migración (`git tag fase-0-golden-master`,
> `fase-1-dominio`, `fase-2-puertos`, `fase-3-casos-de-uso`,
> `fase-4-adaptadores`, `fase-5-final`) y así cualquiera puede hacer
> `git checkout fase-3-casos-de-uso` y continuar desde ahí.

Este documento es el **resumen guiado** de esa solución: qué hay, dónde y por
qué. El detalle de cada clase está en
[02-migracion-arquitectonica.md](02-migracion-arquitectonica.md).

> Una solución de referencia no es **la** solución. Si tu proyecto es distinto
> pero pasa el golden master, ArchUnit y la revisión por pares, está bien.
> Compara decisiones, no líneas.

---

## 2. Árbol de paquetes final

```
src/main/java/com/curso/pedidos
├── PedidosApplication.java
├── domain
│   ├── model
│   │   ├── Pedido.java                    agregado raíz
│   │   ├── LineaPedido.java
│   │   ├── EstadoPedido.java
│   │   ├── Producto.java                  record
│   │   ├── PedidoId.java                  record
│   │   ├── ClienteId.java                 record
│   │   ├── ProductoId.java                record
│   │   ├── Cantidad.java                  record
│   │   ├── Dinero.java                    record
│   │   └── Email.java                     record
│   ├── event
│   │   ├── EventoDeDominio.java           sealed interface
│   │   ├── PedidoConfirmado.java
│   │   ├── PedidoPagado.java
│   │   └── PedidoCancelado.java
│   ├── exception
│   │   ├── PedidoException.java
│   │   ├── PedidoNoEncontradoException.java
│   │   ├── StockInsuficienteException.java
│   │   ├── TransicionDeEstadoInvalidaException.java
│   │   └── PagoRechazadoException.java
│   └── service
│       └── CalculadoraDePrecios.java
├── application
│   ├── port
│   │   ├── in
│   │   │   ├── CrearPedidoUseCase.java        (+ CrearPedidoCommand, LineaSolicitada)
│   │   │   ├── ConfirmarPedidoUseCase.java    (+ ConfirmarPedidoCommand)
│   │   │   ├── CancelarPedidoUseCase.java
│   │   │   └── ConsultarPedidoQuery.java
│   │   └── out
│   │       ├── PedidoRepository.java
│   │       ├── CatalogoProductos.java
│   │       ├── PasarelaDePago.java            (+ ResultadoPago sealed)
│   │       ├── NotificadorPedidos.java
│   │       ├── PublicadorDeEventos.java
│   │       └── UnidadDeTrabajo.java
│   └── service
│       ├── CrearPedidoService.java
│       ├── ConfirmarPedidoService.java
│       ├── CancelarPedidoService.java
│       └── ConsultarPedidoService.java
└── infrastructure
    ├── adapter
    │   ├── in
    │   │   ├── web
    │   │   │   ├── PedidoController.java
    │   │   │   ├── CrearPedidoRequest.java
    │   │   │   ├── PedidoResponse.java
    │   │   │   └── PedidoExceptionHandler.java
    │   │   └── messaging
    │   │       └── PagosExternosKafkaListener.java     (opcional, bloque 3)
    │   └── out
    │       ├── persistence
    │       │   ├── PedidoJpaEntity.java
    │       │   ├── LineaPedidoJpaEntity.java
    │       │   ├── ProductoJpaEntity.java
    │       │   ├── PedidoJpaRepository.java            Spring Data
    │       │   ├── ProductoJpaRepository.java          Spring Data
    │       │   ├── PedidoPersistenceMapper.java
    │       │   ├── PedidoPersistenceAdapter.java       implements PedidoRepository
    │       │   ├── CatalogoProductosPersistenceAdapter.java
    │       │   └── SpringUnidadDeTrabajoAdapter.java   implements UnidadDeTrabajo
    │       ├── payment
    │       │   ├── PagosProperties.java
    │       │   └── StripeLikePasarelaAdapter.java      RestClient
    │       ├── notification
    │       │   ├── EmailNotificadorAdapter.java
    │       │   └── PlantillaConfirmacion.java
    │       └── messaging
    │           ├── SpringPublicadorDeEventosAdapter.java
    │           └── KafkaPublicadorDeEventos.java       (opcional, bloque 3)
    ├── observability
    │   └── ConfirmarPedidoMedido.java                  decorador Micrometer
    └── config
        └── BeanConfiguration.java

src/main/resources
├── application.yml
└── db/migration
    └── V1__esquema_inicial.sql              (el esquema legacy, ahora versionado)

src/test/java/com/curso/pedidos
├── domain/model/PedidoTest.java, DineroTest.java, CantidadTest.java
├── domain/service/CalculadoraDePreciosTest.java
├── application/service/ConfirmarPedidoServiceTest.java, CrearPedidoServiceTest.java,
│   CancelarPedidoServiceTest.java
├── application/service/fakes/PedidoRepositoryEnMemoria.java, CatalogoEnMemoria.java
├── infrastructure/adapter/in/web/PedidoControllerTest.java          @WebMvcTest
├── infrastructure/adapter/out/persistence/PedidoPersistenceAdapterIT.java   Testcontainers
├── infrastructure/adapter/out/payment/StripeLikePasarelaAdapterTest.java    WireMock
├── characterization/PedidoApiCharacterizationTest.java, GoldenMaster.java
└── architecture/ArquitecturaHexagonalTest.java

src/test/resources
├── golden/*.json
├── sql/catalogo-y-limpieza.sql
└── archunit.properties
```

Recuento: **~45 clases de producción** (el legacy tenía 11), pero ninguna pasa
de 120 líneas y el `PedidoService` de 300 ha desaparecido.

---

## 3. Clases clave (resumen)

| Clase                            | Capa        | Responsabilidad en una línea                                              |
|----------------------------------|-------------|---------------------------------------------------------------------------|
| `Pedido`                         | dominio     | Protege invariantes y emite eventos; no sabe que existe una BD            |
| `Dinero`                         | dominio     | Aritmética monetaria con escala 2 y moneda; adiós `BigDecimal` suelto     |
| `CalculadoraDePrecios`           | dominio     | El descuento de noviembre que vivía en el controlador                     |
| `ConfirmarPedidoService`         | aplicación  | Orquesta: valida, cobra fuera de tx, persiste en tx corta, compensa, notifica |
| `UnidadDeTrabajo`                | aplicación  | Límite transaccional sin Spring                                           |
| `PasarelaDePago`                 | aplicación  | "Cobrar" y "anular cobro" en lenguaje de negocio; `ResultadoPago` sealed  |
| `PedidoPersistenceAdapter`       | infra       | Traduce `Pedido` ⇄ `PedidoJpaEntity` (tablas legacy intactas)             |
| `StripeLikePasarelaAdapter`      | infra       | `RestClient`, cabecera API key, 4xx → `Rechazado`, 5xx → excepción         |
| `EmailNotificadorAdapter`        | infra       | SMTP fuera de la transacción; fallo registrado, no propagado              |
| `PedidoController`               | infra       | Traduce HTTP ⇄ comandos/DTOs; cero lógica                                 |
| `PedidoExceptionHandler`         | infra       | Excepciones de dominio → `ProblemDetail` (404, 409, 402, 422)             |
| `BeanConfiguration`              | infra       | Único sitio donde se instancian los servicios de aplicación               |

### 3.1 El flujo de confirmar, de punta a punta

```
 POST /api/pedidos/{id}/confirmar?tokenTarjeta=tok
        │
        ▼
 ┌────────────────────┐  ConfirmarPedidoCommand
 │ PedidoController   │─────────────────────────┐
 └────────────────────┘                         ▼
                                  ┌──────────────────────────┐
                                  │ ConfirmarPedidoMedido    │ métricas
                                  └────────────┬─────────────┘
                                               ▼
                                  ┌──────────────────────────┐
                                  │ ConfirmarPedidoService   │
                                  └──┬──────┬──────┬──────┬──┘
           1 buscarPorId             │      │      │      │ 4 notificarConfirmacion
   ┌─────────────────────────────────┘      │      │      └──────────────────────┐
   ▼                              2 cobrar  ▼      ▼ 3 UnidadDeTrabajo            ▼
 ┌──────────────────┐   ┌────────────────────┐  ┌──────────────────────────┐  ┌──────────────┐
 │ PedidoRepository │   │ PasarelaDePago     │  │ descontarStock           │  │ Notificador- │
 │ (JPA adapter)    │   │ (RestClient)       │  │ marcarComoPagado         │  │ Pedidos      │
 └──────────────────┘   └────────────────────┘  │ guardar + publicar evts  │  │ (email)      │
                          si tx falla ◄──────── │ (una tx corta)           │  └──────────────┘
                          anularCobro           └──────────────────────────┘
```

### 3.2 `BeanConfiguration` (versión final, sin conmutador)

```java
package com.curso.pedidos.infrastructure.config;

import com.curso.pedidos.application.port.in.*;
import com.curso.pedidos.application.port.out.*;
import com.curso.pedidos.application.service.*;
import com.curso.pedidos.domain.service.CalculadoraDePrecios;
import com.curso.pedidos.infrastructure.adapter.out.payment.PagosProperties;
import com.curso.pedidos.infrastructure.observability.ConfirmarPedidoMedido;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(PagosProperties.class)
class BeanConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    CalculadoraDePrecios calculadoraDePrecios() {
        return new CalculadoraDePrecios();
    }

    @Bean
    CrearPedidoUseCase crearPedidoUseCase(PedidoRepository pedidos, CatalogoProductos catalogo,
                                          UnidadDeTrabajo uow, Clock clock) {
        return new CrearPedidoService(pedidos, catalogo, uow, clock);
    }

    @Bean
    ConfirmarPedidoUseCase confirmarPedidoUseCase(PedidoRepository pedidos, CatalogoProductos catalogo,
                                                  PasarelaDePago pasarela, NotificadorPedidos notificador,
                                                  PublicadorDeEventos publicador, UnidadDeTrabajo uow,
                                                  CalculadoraDePrecios calculadora, Clock clock,
                                                  MeterRegistry registry) {
        var servicio = new ConfirmarPedidoService(pedidos, catalogo, pasarela, notificador,
                publicador, uow, calculadora, clock);
        return new ConfirmarPedidoMedido(servicio, registry);
    }

    @Bean
    CancelarPedidoUseCase cancelarPedidoUseCase(PedidoRepository pedidos, PublicadorDeEventos publicador,
                                                UnidadDeTrabajo uow, Clock clock) {
        return new CancelarPedidoService(pedidos, publicador, uow, clock);
    }

    @Bean
    ConsultarPedidoQuery consultarPedidoQuery(PedidoRepository pedidos) {
        return new ConsultarPedidoService(pedidos);
    }
}
```

> `ConfirmarPedidoMedido` está en `infrastructure.observability`; para que
> `BeanConfiguration` lo instancie, su constructor debe ser `public` (o
> `BeanConfiguration` debe vivir en el mismo paquete). En la solución lo hemos
> hecho `public`.

> Alternativa pragmática que muchos equipos eligen: anotar los servicios con
> una anotación propia `@UseCase` (meta-anotada con `@Component`) definida en
> `application`. Ahorra `BeanConfiguration`, pero mete una dependencia de
> Spring en la capa de aplicación. Trade-off visto en el día 4; elige y
> escríbelo en un ADR.

### 3.3 `application.yml`

```yaml
spring:
  application:
    name: pedidos
  datasource:
    url: jdbc:postgresql://localhost:5432/tienda
    username: tienda
    password: ${DB_PASSWORD}
  jpa:
    open-in-view: false          # sin lazy loading desde el controlador
    hibernate:
      ddl-auto: validate         # el esquema lo gobierna Flyway
  flyway:
    baseline-on-migrate: true    # BD legacy existente: V1 = esquema actual

pagos:
  url: https://pagos.example.com
  api-key: ${PAGOS_API_KEY}
  timeout: 3s

management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
```

> Trampa: dejar `spring.jpa.open-in-view` en su valor por defecto (`true`).
> Mantiene la sesión de Hibernate abierta durante toda la petición HTTP y
> esconde problemas de carga perezosa que en un hexágono **no deberían
> existir** (el adaptador web nunca ve entidades JPA).

---

## 4. Tests clave

### 4.1 Mapa de la suite

| Nivel             | Clase                                   | Qué demuestra                                       | Tiempo aprox. |
|-------------------|-----------------------------------------|-----------------------------------------------------|---------------|
| Dominio           | `PedidoTest`, `DineroTest`, `CalculadoraDePreciosTest` | Invariantes y reglas, sin Spring          | < 0,3 s       |
| Aplicación        | `ConfirmarPedidoServiceTest` y hermanos | Orquestación, compensación, orden de efectos        | < 0,5 s       |
| Adaptador web     | `PedidoControllerTest` (`@WebMvcTest`)  | Mapeo HTTP y `ProblemDetail`                        | ~2 s          |
| Adaptador pago    | `StripeLikePasarelaAdapterTest` (WireMock) | Contrato HTTP con el proveedor                   | ~1 s          |
| Adaptador BD      | `PedidoPersistenceAdapterIT` (Testcontainers) | Mapeo contra PostgreSQL real, tablas legacy   | ~8 s          |
| Sistema           | `PedidoApiCharacterizationTest`         | El API se comporta como el legacy (golden master)   | ~10 s         |
| Arquitectura      | `ArquitecturaHexagonalTest`             | Reglas de dependencias y nombres                    | ~2 s          |

### 4.2 El test de persistencia con Testcontainers

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PedidoPersistenceAdapter.class, PedidoPersistenceMapper.class})
class PedidoPersistenceAdapterIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PedidoPersistenceAdapter adapter;

    @Test
    void guarda_y_reconstruye_un_pedido_pagado() {
        Instant ahora = Instant.parse("2026-03-10T10:00:00Z");
        Pedido pedido = Pedido.crear(new ClienteId(UUID.randomUUID()), new Email("ana@example.com"), ahora);
        pedido.anadirLinea(new ProductoId("TAZA-HEX"), new Cantidad(2), Dinero.euros("7.00"));
        pedido.confirmar(ahora);
        pedido.marcarComoPagado("ch_123", ahora);

        adapter.guardar(pedido);
        Pedido leido = adapter.buscarPorId(pedido.id()).orElseThrow();

        assertThat(leido.estado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(leido.total()).isEqualTo(Dinero.euros("14.00"));
        assertThat(leido.referenciaPago()).isEqualTo("ch_123");
        assertThat(leido.lineas()).singleElement()
                .satisfies(l -> assertThat(l.productoId().sku()).isEqualTo("TAZA-HEX"));
    }

    @Autowired TestEntityManager em;   // org.springframework.boot.test.autoconfigure.orm.jpa

    @Test
    void tolera_estados_historicos_en_minusculas() {
        Instant ahora = Instant.parse("2026-03-10T10:00:00Z");
        Pedido pedido = Pedido.crear(new ClienteId(UUID.randomUUID()), new Email("ana@example.com"), ahora);
        pedido.anadirLinea(new ProductoId("TAZA-HEX"), new Cantidad(1), Dinero.euros("7.00"));
        adapter.guardar(pedido);
        em.flush();

        // dato real de producción: 'confirmado' escrito por una versión antigua
        em.getEntityManager()
          .createNativeQuery("update pedidos set estado = 'confirmado' where id = :id")
          .setParameter("id", pedido.id().valor())
          .executeUpdate();
        em.clear();

        assertThat(adapter.buscarPorId(pedido.id()).orElseThrow().estado())
                .isEqualTo(EstadoPedido.CONFIRMADO);
    }
}
```

> `@ServiceConnection` (Spring Boot 3.1+) configura el `DataSource` a partir
> del contenedor sin `@DynamicPropertySource`. Flyway aplica `V1` al arrancar,
> así que el test valida también que el mapeo encaja con el **esquema legacy**.

### 4.3 Lo que se queda del golden master

Tras la fase 5, `PedidoApiCharacterizationTest` **no se borra**: pasa a ser
el test de contrato del API. Los ficheros de los casos de error se
actualizaron en un commit propio referenciado por el ADR-0001:

```
src/test/resources/golden/
├── crear-pedido.json                  ← intacto desde la fase 0
├── confirmar-pedido.json              ← intacto desde la fase 0
├── cancelar-pedido.json               ← intacto desde la fase 0
├── crear-pedido-sin-lineas.json       status 400 (intacto)
├── confirmar-pago-rechazado.json      status 500 → 402 (ADR-0001)
├── pedido-tras-pago-rechazado.json    intacto (sigue BORRADOR)
└── obtener-inexistente.json           200 vacío → 404 (ADR-0001)
```

---

## 5. Antes y después en números

| Indicador                                 | Legacy                 | Solución de referencia      |
|-------------------------------------------|------------------------|-----------------------------|
| Clases de producción                      | 11                     | ~45                         |
| Líneas de la clase más grande             | ~300 (`PedidoService`) | ~110 (`ConfirmarPedidoService`) |
| Tests                                     | 1 (`@Disabled`)        | ~70                         |
| Tiempo `mvn verify`                       | 6 s (sin tests)        | ~45 s                       |
| Cobertura de ramas en `domain`            | —                      | 96 %                        |
| Mutation score `domain` + `application`   | —                      | 87 %                        |
| Imports de framework en `domain`          | —                      | 0                           |
| Duración de la transacción en confirmar   | hasta 20 s             | < 50 ms                     |
| Llamadas externas dentro de transacción   | 2 (HTTP, SMTP)         | 0                           |

> Más clases no es un defecto si **cada una tiene una razón para cambiar**. El
> indicador malo sería tener 45 clases y que un cambio de pasarela siguiera
> tocando 12.

---

## 6. Puntos donde la referencia elige (y tú podrías elegir otra cosa)

| Decisión                                    | Referencia                          | Alternativa válida                          |
|---------------------------------------------|-------------------------------------|---------------------------------------------|
| Transacciones en aplicación                 | Puerto `UnidadDeTrabajo`            | `@Transactional` en servicios (pragmático)  |
| Registro de casos de uso                    | `BeanConfiguration`                 | Anotación `@UseCase` propia                 |
| Lo que devuelve `ConsultarPedidoQuery`      | El agregado `Pedido`                | Un `PedidoVista` de solo lectura            |
| Notificación                                | Llamada directa tras commit         | Evento + `@ApplicationModuleListener`/outbox |
| Mapeo                                       | Manual                              | MapStruct en adaptadores                    |
| Tiempo en el dominio                        | `Instant ahora` como parámetro      | Inyectar un puerto `Reloj` en servicios     |
| Comandos                                    | Records anidados en el puerto       | Records en su propio fichero                |

---

## Ejercicio

Compara tu proyecto con la referencia y rellena esta tabla en tu repo
(`docs/comparativa-referencia.md` o en el PR):

| Aspecto | Mi solución | Referencia | ¿Por qué elegí distinto? / ¿Cambiaría algo? |
|---------|-------------|------------|---------------------------------------------|

Mínimo **tres** filas. Al menos una debe ser algo que **tu** solución hace
mejor.

Pista: mira los puntos del §6, el manejo del pago rechazado y cómo
reconstruyes el agregado desde la BD.

<details><summary>Solución</summary>

Ejemplo de respuesta de un alumno:

| Aspecto | Mi solución | Referencia | Comentario |
|---------|-------------|------------|------------|
| Transacciones | `@Transactional` en los servicios | `UnidadDeTrabajo` | Me lo quedo: el equipo ya conoce `@Transactional` y solo confirmar necesita dos tramos; para ese caso extraje el cobro a un método fuera de la transacción. Lo documento en ADR-0002 y relajo la regla ArchUnit solo para `org.springframework.transaction.annotation`. |
| Notificación | Evento `PedidoPagado` + `@TransactionalEventListener(AFTER_COMMIT)` | Llamada directa | Mejor la mía: el caso de uso no conoce el notificador. Peor: más difícil de seguir leyendo el código. |
| Reconstrucción | `Pedido.reconstituir` igual | Igual | Añadiría validación de coherencia (un pedido PAGADO debe tener `referenciaPago`). |

</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture* (2005): https://alistair.cockburn.us/hexagonal-architecture/
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023) y repo de referencia: https://github.com/thombergs/buckpal
- Spring Boot docs (Testcontainers, `@ServiceConnection`): https://docs.spring.io/spring-boot/index.html
- Testcontainers for Java: https://java.testcontainers.org/
- Spring Data JPA: https://docs.spring.io/spring-data/jpa/reference/
- ArchUnit User Guide: https://www.archunit.org/userguide/html/000_Index.html

## Mantra

> *"La solución de referencia es un espejo, no un molde: compara decisiones,
> no líneas de código."*
