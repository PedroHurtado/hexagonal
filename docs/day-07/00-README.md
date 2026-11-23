# Día 7 — Hexagonal vs Clean Architecture vs Vertical Slice (y arquitecturas modernas)

Penúltimo día. Sesión de **3h45** (14:00–17:45, con un descanso de 15 min).
Seis días construyendo un hexágono y hoy toca lo más sano que se puede hacer
con una arquitectura: **ponerla al lado de sus competidoras y mirarla sin
cariño**.

El cliente ha pedido expresamente este capítulo. No es un día de "la
hexagonal es la mejor": es un día de **criterio**. Al terminar, el alumno debe
poder defender delante de su equipo por qué elige una u otra, y también saber
cuándo **no** elegir la hexagonal.

---

## Objetivos

Al acabar el día, el alumno será capaz de:

1. Explicar **Clean Architecture** (Robert C. Martin, 2012/2017) con su
   vocabulario propio: Entities, Use Cases, Interface Adapters, Frameworks &
   Drivers, Input/Output Boundary, Interactor, Presenter, Request/Response
   Models.
2. Distinguir **qué comparten de verdad** Hexagonal, Clean y Onion (la regla de
   dependencia) y **en qué difieren de verdad** (simetría, número de capas,
   presenter, vocabulario).
3. Explicar **Vertical Slice Architecture** (Jimmy Bogard, 2018) y construir una
   feature en su versión **extrema: un solo fichero Java por feature**.
4. Comparar las tres con **el mismo caso de uso** (`ConfirmarPedido`): ficheros,
   líneas, tests, coste de cambio.
5. Elegir con una **matriz de decisión** y reconocer los **híbridos** que se
   usan en la industria (hexágono por bounded context + slices dentro).
6. Situar la hexagonal en **arquitecturas modernas**: microservicios, monolito
   modular con Spring Modulith, event-driven con Kafka, serverless con Spring
   Cloud Function.

---

## Estructura del día

| #  | Bloque                                                        | Duración orientativa | Fichero |
|----|---------------------------------------------------------------|----------------------|---------|
| 1  | Clean Architecture: círculos, boundaries, presenter           | 40 min               | [01-clean-architecture.md](01-clean-architecture.md) |
| 2  | Hexagonal vs Clean (y Onion): familia, no rivales             | 25 min               | [02-hexagonal-vs-clean.md](02-hexagonal-vs-clean.md) |
| —  | Descanso                                                      | 15 min               | — |
| 3  | Vertical Slice Architecture (versión extrema: 1 fichero)      | 45 min               | [03-vertical-slice-architecture.md](03-vertical-slice-architecture.md) |
| 4  | La comparativa central + matriz de decisión + híbridos        | 40 min               | [04-comparativa-hexagonal-clean-vertical-slice.md](04-comparativa-hexagonal-clean-vertical-slice.md) |
| 5  | Aplicación en arquitecturas modernas                          | 25 min               | [05-aplicacion-en-arquitecturas-modernas.md](05-aplicacion-en-arquitecturas-modernas.md) |
| 6  | Ejercicios: CancelarPedido en VSA y en hexagonal + debate     | 35 min               | [06-ejercicios-dia-07.md](06-ejercicios-dia-07.md) |
|    | **Total**                                                     | **3h45**             | |

Horario orientativo empezando a las 14:00:

```
14:00 ─ 14:40  Bloque 1  Clean Architecture
14:40 ─ 15:05  Bloque 2  Hexagonal vs Clean
15:05 ─ 15:20  Descanso
15:20 ─ 16:05  Bloque 3  Vertical Slice extremo
16:05 ─ 16:45  Bloque 4  Comparativa + matriz de decisión
16:45 ─ 17:10  Bloque 5  Arquitecturas modernas
17:10 ─ 17:45  Bloque 6  Ejercicios + debate
```

---

## Cómo enfocar el día

Los markdowns son **material extendido**: el alumno se los lleva. En clase se
aprieta el ritmo y se trabaja sobre el código.

La decisión didáctica clave del día:

> **El mismo caso de uso, `ConfirmarPedido`, implementado tres veces.**

Mismo dominio, mismas reglas de negocio (no se confirma un pedido vacío, solo
se confirma desde `BORRADOR`), misma API HTTP (`POST /pedidos/{id}/confirmacion`),
mismo evento (`PedidoConfirmado`). Lo único que cambia es **la arquitectura**.
Así la comparación es honesta: no comparamos un ejemplo bonito contra un
ejemplo feo, comparamos tres formas de organizar **lo mismo**.

```
                  ConfirmarPedido
                         │
       ┌─────────────────┼─────────────────┐
       ▼                 ▼                 ▼
  ┌──────────┐     ┌──────────┐     ┌──────────────┐
  │  CLEAN   │     │HEXAGONAL │     │VERTICAL SLICE│
  │ 8 nuevos │     │ 4 nuevos │     │  1 fichero   │
  │ ficheros │     │ ficheros │     │  (extremo)   │
  └──────────┘     └──────────┘     └──────────────┘
       │                 │                 │
       └─────────────────┼─────────────────┘
                         ▼
          Misma API, mismas reglas, mismo evento
```

Tres ideas que deben quedar claras, aunque se vaya con prisa:

1. **Hexagonal, Clean y Onion son la misma familia.** Comparten la regla de
   dependencia. Las diferencias son de énfasis y de vocabulario, no de
   filosofía.
2. **Vertical Slice es otro eje.** No compite en "hacia dónde apuntan las
   dependencias" sino en "cómo corto el código": por capa o por feature. Por
   eso se puede **combinar** con hexagonal.
3. **No hay ganadora universal.** Hay contextos. La matriz de decisión del
   bloque 4 es la herramienta, no el dogma.

### Si el ritmo se desvía

- El bloque 1 (Clean) se puede comprimir a 30 min si el grupo ya conoce el
  libro de Uncle Bob: se salta la teoría y se va directo al código del
  Interactor + Presenter.
- El bloque 5 (modernas) se puede reducir a 15 min: es un mapa conceptual. El
  diagrama del sistema con varios hexágonos **siempre se enseña**.
- El bloque 4 (comparativa) **no se recorta**: es lo que el cliente ha pedido.
- Si falta tiempo en el bloque 6, se hace el debate guiado y la implementación
  de `CancelarPedido` queda como deberes.

---

## Conexión con el resto del curso

| Día     | Qué aportó                                                | Cómo se usa hoy |
|---------|-----------------------------------------------------------|-----------------|
| day-01  | Historia, principios (DIP, SRP), intro hexagonal           | DIP es la base común de las tres "cebollas" |
| day-02  | Dominio, puertos, clasificación de puertos                 | Mapeo puerto ↔ boundary ↔ interfaz de Onion |
| day-03  | Adaptadores de entrada                                    | Controller hexagonal vs Controller + Presenter de Clean vs Endpoint de VSA |
| day-04  | Adaptadores de salida, cableado con `BeanConfiguration`    | `PedidoRepository` vs `PedidoGateway` vs `JdbcClient` directo |
| day-05  | Testing en arquitecturas desacopladas                      | Pirámide de tests de cada arquitectura (unitarios vs integración por slice) |
| day-06  | Persistencia, transacciones                                | Dónde vive `@Transactional` en cada estilo |
| day-08  | Proyecto final "Del monolito al hexágono"                  | Hoy decidimos **qué** arquitectura; mañana **migramos** hacia ella |

Enlaces útiles de días anteriores:
[Definición de puertos](../day-02/03-definicion-de-puertos.md) ·
[Testing](../day-05/00-README.md) ·
[Persistencia y transacciones](../day-06/00-README.md).

---

## Punto de partida (código)

Partimos del proyecto hexagonal que llevamos construyendo desde el día 2
(`code/`), con la estructura canónica:

```
com.curso.pedidos
├── domain          (Pedido, LineaPedido, EstadoPedido, value objects, eventos, excepciones)
├── application     (port/in, port/out, service)
└── infrastructure  (adapter/in/web, adapter/out/persistence, config)
```

Para hoy:

- El **dominio** (`Pedido.confirmar()`, invariantes, `PedidoConfirmado`) es el
  mismo en hexagonal y en Clean.
- La versión **Vertical Slice extrema** es una aplicación aparte
  (`com.curso.pedidos.features...`) que usa el mismo esquema de base de datos
  (tablas `pedidos` y `lineas_pedido`) para que la comparación sea justa.
- Stack: Java 17 (21 recomendado en proyectos nuevos), Spring Boot 3.3,
  JUnit 5, AssertJ, Mockito, Testcontainers (PostgreSQL), Flyway.

---

## Mantra del día

> *"Hexagonal y Clean discuten hacia dónde apuntan las flechas; Vertical Slice
> discute por dónde se corta la tarta. Elige con criterio, no con camiseta."*
