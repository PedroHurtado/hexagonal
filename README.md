# Arquitectura Hexagonal con Java y Spring Boot

> *"Allow an application to equally be driven by users, programs, automated
> test or batch scripts, and to be developed and tested in isolation from its
> eventual run-time devices and databases."* — Alistair Cockburn, 2005

Material del curso de **Arquitectura Hexagonal (Ports & Adapters)** con
**Java 17** y **Spring Boot 3.3**. 8 sesiones de **3h45** (30 horas en total).

---

## Objetivos del curso

Al finalizar, el alumnado será capaz de:

1. **Estandarizar** el diseño de software desacoplado aplicando con rigor la
   Arquitectura Hexagonal, para asegurar la mantenibilidad y la evolución del
   software.
2. **Mitigar la deuda técnica y el riesgo operativo** creando perímetros de
   aislamiento que protejan la lógica transaccional de los cambios e
   inconsistencias de los sistemas externos.
3. **Mejorar su rendimiento** aplicando las técnicas del curso en su día a día.

---

## Mapa de sesiones

| Día | Módulo                                                            | Carpeta                    |
|-----|-------------------------------------------------------------------|----------------------------|
| 1   | Módulo 1 — Fundamentos                                            | [docs/day-01](docs/day-01/) |
| 2   | Módulo 2 — Anatomía del hexágono                                  | [docs/day-02](docs/day-02/) |
| 3   | Módulo 3 (I) — Comunicación y flujo de datos: adaptadores de entrada | [docs/day-03](docs/day-03/) |
| 4   | Módulo 3 (II) — Adaptadores de salida y gestión de dependencias   | [docs/day-04](docs/day-04/) |
| 5   | Módulo 4 — Estrategias de testing en arquitecturas desacopladas   | [docs/day-05](docs/day-05/) |
| 6   | Módulo 5 — Casos de uso reales y patrones avanzados               | [docs/day-06](docs/day-06/) |
| 7   | Comparativa: Hexagonal vs Clean Architecture vs Vertical Slice    | [docs/day-07](docs/day-07/) |
| 8   | Proyecto final — "Del monolito al hexágono"                       | [docs/day-08](docs/day-08/) |

Cada carpeta empieza por un `00-README.md` con la agenda del día y continúa
con un fichero por tema (`01-…md`, `02-…md`, …). Todos los temas siguen la
misma estructura: problema → definición → *Bad* → *Good* → diagrama →
trampas → **testing** → ejercicio → referencias → mantra.

---

## Dominio común: la tienda de *Pedidos*

Todo el curso trabaja sobre **el mismo dominio** para ver cómo crece el
diseño sin reescribirse:

- `Pedido` (agregado raíz) con `LineaPedido`, `EstadoPedido`
  (`BORRADOR → CONFIRMADO → PAGADO → ENVIADO`, o `CANCELADO`).
- Value objects: `PedidoId`, `ClienteId`, `ProductoId`, `Cantidad`, `Dinero`.
- Puertos de entrada: `CrearPedidoUseCase`, `ConfirmarPedidoUseCase`,
  `ConsultarPedidoQuery`…
- Puertos de salida: `PedidoRepository`, `CatalogoProductos`,
  `PasarelaDePago`, `NotificadorPedidos`, `PublicadorDeEventos`.

```
com.curso.pedidos
├── domain            ← reglas de negocio puras (sin Spring, sin JPA)
├── application       ← puertos (in/out) y casos de uso
└── infrastructure    ← adaptadores (web, persistencia, pagos…) y configuración
```

---

## Código de referencia

| Proyecto                                                   | Qué es                                                        |
|------------------------------------------------------------|---------------------------------------------------------------|
| [code/pedidos-legacy](code/pedidos-legacy/)                 | Monolito en capas "feo a propósito": punto de partida del proyecto final (día 8) |
| [code/pedidos-hexagonal](code/pedidos-hexagonal/)           | Aplicación completa en hexagonal con tests de todas las capas |
| [code/pedidos-vertical-slice](code/pedidos-vertical-slice/) | El mismo dominio en Vertical Slice extremo (un archivo por feature) |

```bash
cd code/pedidos-hexagonal
mvn verify            # compila y ejecuta toda la suite de tests
mvn spring-boot:run   # arranca la API en http://localhost:8080
```

---

## Requisitos

- JDK 17 o superior (recomendado 21 para proyectos nuevos).
- Maven 3.8+.
- IDE: IntelliJ IDEA, VS Code (Extension Pack for Java) o Eclipse/STS.
- Docker (opcional) para los ejemplos con Testcontainers del día 5 y 6.

---

## Bibliografía esencial

- Alistair Cockburn — [*Hexagonal Architecture*](https://alistair.cockburn.us/hexagonal-architecture/) (2005).
- Alistair Cockburn & Juan Manuel Garrido de Paz — *Hexagonal Architecture Explained* (2024).
- Robert C. Martin — [*The Clean Architecture*](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html) (2012) y *Clean Architecture* (2017).
- Jimmy Bogard — [*Vertical Slice Architecture*](https://www.jimmybogard.com/vertical-slice-architecture/) (2018).
- Tom Hombergs — *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023).
- Eric Evans — *Domain-Driven Design* (2003).
- Martin Fowler — [*Patterns of Enterprise Application Architecture*](https://martinfowler.com/eaaCatalog/) (2002).

---

> *"El dominio no sabe que existe Spring. Y esa es precisamente su mayor
> fortaleza."*
