# Día 4 — Módulo 3 (II): Adaptadores de salida y gestión de dependencias

Material de apoyo para la cuarta jornada del curso (sesión de **3h45**).

> *"Allow an application to equally be driven by users, programs, automated
> test or batch scripts, and to be developed and tested in isolation from its
> eventual run-time devices and databases."* — Alistair Cockburn, *Hexagonal
> Architecture* (2005)

Ayer nos quedamos con la **mitad izquierda** del hexágono: el mundo exterior
*empuja* a la aplicación (REST, mensajería, CLI). Hoy toca la **mitad
derecha**: la aplicación *tira* del mundo exterior (base de datos, pasarela de
pago, correo, Kafka)… **sin enterarse de que existe**.

Y como al final del día tendremos media docena de adaptadores, cerramos con la
pregunta que todo equipo acaba haciéndose: **¿quién cablea todo esto y cómo
impedimos que alguien se salte las reglas?**

---

## Objetivos del día

Al terminar la sesión el alumno será capaz de:

1. Explicar el rol de un **adaptador de salida** y por qué el puerto lo define
   el hexágono, no la infraestructura (inversión de dependencias).
2. Implementar un **adaptador de persistencia JPA** con modelo propio
   (`PedidoJpaEntity`) y un mapper dominio ↔ JPA, y probarlo con `@DataJpaTest`.
3. Implementar un **adaptador cliente HTTP** con `RestClient` (Spring 6.1),
   traduciendo errores HTTP a excepciones de dominio, con timeouts y
   reintentos, y probarlo con **WireMock**.
4. Implementar adaptadores de **notificación y mensajería** y sus **fakes en
   memoria**, seleccionables por **perfil** de Spring.
5. Decidir **cómo cablear** los casos de uso (`@Bean` vs `@Service` vs
   `@UseCase`) y cómo usar **módulos Maven** para que el compilador imponga la
   regla de dependencia.
6. Escribir tests **ArchUnit** que hagan fallar el build si alguien rompe la
   arquitectura.

---

## Estructura del día

| # | Bloque                                     | Duración orientativa | Fichero                                         |
|---|--------------------------------------------|----------------------|-------------------------------------------------|
| 1 | Adaptadores de salida: rol y anatomía      | ~20 min              | `01-adaptadores-de-salida.md`                   |
| 2 | Adaptador de persistencia JPA              | ~40 min              | `02-adaptador-de-persistencia-jpa.md`           |
| 3 | Adaptador cliente HTTP (RestClient)        | ~35 min              | `03-adaptador-cliente-http.md`                  |
| — | Descanso                                   | ~15 min              | —                                               |
| 4 | Notificación, mensajería y fakes           | ~25 min              | `04-adaptadores-notificacion-y-mensajeria.md`   |
| 5 | Gestión de dependencias y módulos Maven    | ~35 min              | `05-gestion-de-dependencias.md`                 |
| 6 | Verificar la arquitectura con ArchUnit     | ~20 min              | `06-verificar-la-arquitectura-con-archunit.md`  |
| 7 | Ejercicios y repaso                        | ~35 min              | `07-ejercicios-dia-04.md`                       |

Total: **~3h45** (incluye descanso y ejercicios).

---

## Decisión de ritmo

Los markdowns están escritos con **material extendido** (variantes, trampas,
tests completos). En clase se da **más rápido**: lo extenso es para que el
alumno se lo lleve. Priorizad en vivo:

- Bloque 2 (JPA): el **mapper** y el **test `@DataJpaTest`**. El resto se lee.
- Bloque 3 (HTTP): la **traducción de errores** y el **test WireMock**.
- Bloque 5: el **pom multi-módulo**. Que vean que el compilador *no deja*
  importar JPA desde el dominio.

---

## Cómo enfocar el bloque

Todos los temas siguen el mismo hilo:

1. **El problema** en una frase.
2. **Bad**: el caso de uso hablando directamente con la infraestructura. Que
   se vea el dolor.
3. **Good**: puerto de salida (ya definido en el [día 2](../day-02/00-README.md))
   + adaptador en `infrastructure.adapter.out.*`.
4. **Diagrama ASCII** para fijar quién depende de quién.
5. **Trampas** habituales.
6. **Testing** del adaptador **aislado**: el adaptador se prueba contra su
   tecnología (H2, WireMock…), nunca a través del caso de uso.

La idea fuerza del día:

```
          DEPENDENCIA DE CÓDIGO                FLUJO DE CONTROL
  ┌──────────────────────────────┐     ┌──────────────────────────────┐
  │ Adapter ──implements──► Port │     │ Service ──llama──► Port      │
  │  (infra)              (app)  │     │            └──► Adapter      │
  └──────────────────────────────┘     └──────────────────────────────┘
     La flecha de código apunta            El control fluye hacia fuera
     HACIA DENTRO (hacia el hexágono)      (hacia la infraestructura)
```

---

## Punto de partida (código)

Partimos del estado al final del día 3: dominio `Pedido` completo, casos de
uso implementados y adaptadores de entrada (`PedidoController`) funcionando.
Los puertos de salida existen, pero **solo tienen implementaciones en
memoria** de los tests:

```java
package com.curso.pedidos.application.port.out;

public interface PedidoRepository {
    Pedido guardar(Pedido pedido);
    Optional<Pedido> buscarPorId(PedidoId id);
}

public interface CatalogoProductos {
    Optional<Producto> buscarPorId(ProductoId id);
}

public interface PasarelaDePago {
    ResultadoPago cobrar(PedidoId pedidoId, Dinero importe);
}

public interface NotificadorPedidos {
    void notificarConfirmacion(Pedido pedido, Email destinatario);
}

public interface PublicadorDeEventos {
    void publicar(EventoDeDominio evento);
}
```

```
com.curso.pedidos
├── domain                 ✅ (día 2)
├── application
│   ├── port.in            ✅ (día 2)
│   ├── port.out           ✅ (día 2)  ← hoy les damos implementación real
│   └── service            ✅ (día 2-3)
└── infrastructure
    ├── adapter.in.web     ✅ (día 3)
    ├── adapter.out.*      🆕 HOY
    └── config             🆕 HOY (BeanConfiguration)
```

> Nota: `ResultadoPago` y `EventoDeDominio` son tipos de la aplicación/dominio
> (un `sealed interface` y una interfaz marcadora implementada por
> `PedidoConfirmado`, `PedidoPagado` y `PedidoCancelado`). Los detallamos en
> los bloques 3 y 4.

---

## Conexión con días anteriores

- **Día 1** nos dio el **DIP**: el alto nivel define la abstracción, el bajo
  nivel la implementa. Hoy es DIP aplicado a cada pieza de infraestructura.
- **Día 2** nos dio los **puertos de salida** con lenguaje de negocio
  (`PedidoRepository`, nunca `PedidoJpaRepository`). Hoy los implementamos.
- **Día 3** nos dio los **adaptadores de entrada**: traducían HTTP → comando.
  Hoy hacemos el viaje inverso: dominio → SQL / HTTP / SMTP / Kafka. Misma
  idea de **traducción en la frontera**, sentido contrario.

## Conexión con días siguientes

- **Día 5** (testing): generalizaremos lo de hoy en una estrategia completa
  (unitarios del dominio, tests de adaptador, Testcontainers, tests de
  contrato, E2E).
- **Día 6** (persistencia avanzada): transacciones, outbox, agregados grandes
  y patrones de acceso a datos. Hoy sentamos la base del adaptador JPA.

---

## Mantra del día

> *"El hexágono dice lo que necesita; los adaptadores se lo consiguen.
> Nunca al revés."*
