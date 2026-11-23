# Día 8 — Proyecto final: "Del monolito al hexágono"

> *"Legacy code is simply code without tests."*
> — Michael Feathers, *Working Effectively with Legacy Code* (2004)

Último día. Una sesión de **3h45** que es, sobre todo, **práctica guiada**.
Hoy no presentamos teoría nueva: cogemos un monolito Spring Boot "de los de
toda la vida" —ese que todos hemos heredado alguna vez— y lo llevamos, paso a
paso y sin romper nada, a la arquitectura hexagonal que hemos construido
durante siete sesiones.

En cristiano: **hoy demostramos que todo lo anterior sirve en un proyecto real,
con código feo, sin tests y con prisa**. Que es exactamente donde lo vais a
aplicar el lunes.

---

## Objetivos del día

Al terminar la sesión el alumno será capaz de:

1. **Diagnosticar** un monolito en capas: localizar lógica de negocio fuera de
   sitio, acoplamientos a infraestructura y efectos secundarios dentro de
   transacciones.
2. **Poner una red de seguridad** antes de tocar nada: tests de caracterización
   (*golden master*) sobre el API REST.
3. **Migrar de forma incremental** aplicando Strangler Fig y Branch by
   Abstraction, con commits pequeños y siempre en verde.
4. **Extraer** un dominio rico (`Pedido`) de una entidad JPA anémica, definir
   puertos y escribir adaptadores.
5. **Evaluar** el resultado con criterios objetivos: fitness functions con
   ArchUnit, cobertura del dominio, mutation testing, acoplamiento.
6. **Documentar** la decisión con un ADR y saber defenderla ante un equipo.
7. **Proyectar** el hexágono hacia arquitecturas modernas: microservicio
   extraído, monolito modular con Spring Modulith, Kafka, observabilidad.

---

## Estructura del día

| #  | Bloque                                               | Duración orientativa | Fichero                                                                              |
|----|------------------------------------------------------|----------------------|--------------------------------------------------------------------------------------|
| 0  | Bienvenida y recorrido del curso                     | 10 min               | este fichero                                                                         |
| 1  | Enunciado: el monolito heredado                      | 20 min               | [01-enunciado-proyecto-final.md](01-enunciado-proyecto-final.md)                     |
| 2  | Migración arquitectónica (práctica guiada, fases 0–2) | 55 min               | [02-migracion-arquitectonica.md](02-migracion-arquitectonica.md)                     |
| —  | **Descanso**                                         | 15 min               | —                                                                                    |
| 3  | Migración arquitectónica (práctica guiada, fases 3–5) | 50 min               | [02-migracion-arquitectonica.md](02-migracion-arquitectonica.md)                     |
| 4  | Aplicación en arquitecturas modernas                 | 15 min               | [03-aplicacion-en-arquitecturas-modernas.md](03-aplicacion-en-arquitecturas-modernas.md) |
| 5  | Evaluación del sistema (ArchUnit, métricas, ADR)     | 25 min               | [04-evaluacion-del-sistema.md](04-evaluacion-del-sistema.md)                         |
| 6  | Solución de referencia y revisión por pares          | 20 min               | [05-solucion-de-referencia.md](05-solucion-de-referencia.md)                         |
| 7  | Tips, cierre del curso y encuesta                    | 15 min               | [06-tips-y-cierre-del-curso.md](06-tips-y-cierre-del-curso.md)                       |
|    | **Total**                                            | **3h45**             |                                                                                      |

---

## Cómo enfocar el día

Los markdowns son, como siempre, **material extendido**: el alumno se los lleva.
En clase **se programa**. El docente proyecta, el alumno teclea en su máquina
(o en pareja), y cada fase termina con un `mvn verify` en verde y un commit.

Tres reglas de la sesión:

- **Nunca en rojo más de 10 minutos.** Si una fase se atasca, se hace
  `git stash`, se coge el *tag* de la fase en el repo de referencia y se sigue.
  Lo importante es el recorrido, no teclear cada línea.
- **Commits pequeños con mensaje con intención.** "Extrae Pedido de PedidoEntity"
  vale; "cambios" no vale.
- **El golden master manda.** Si el test de caracterización se rompe, no se
  "arregla el test": se entiende qué comportamiento hemos cambiado.

### Si el ritmo se desvía

- Las **fases 0–3** (red de seguridad, dominio, puertos, servicio de
  aplicación) son el corazón. **Se defienden siempre.**
- La **fase 4** (adaptadores) puede hacerse solo para persistencia y pago; el
  email se deja como ejercicio para casa.
- El bloque de **arquitecturas modernas** se puede comprimir a 10 min: el día 7
  ya cubrió la parte conceptual.
- El **cierre del curso y la encuesta** no se recortan nunca.

---

## El recorrido de las 8 sesiones

Hoy es el día en que todo encaja. Repasemos de dónde venimos:

| Día | Módulo                                         | Lo que nos llevamos                                                                                                        |
|-----|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| 1   | Fundamentos                                    | Historia de los patrones (Alexander, GoF), SOLID, DIP como bisagra. Cockburn y la intención del hexágono: *ports & adapters*. |
| 2   | Anatomía del hexágono                          | Dominio rico (`Pedido`, value objects, invariantes). Puertos de entrada/salida (driving/driven). Clasificación de puertos.   |
| 3   | Adaptadores de entrada                         | `PedidoController` delgado, DTOs, mappers, `@ControllerAdvice`, listeners de mensajería, CLI y scheduler.                    |
| 4   | Adaptadores de salida y dependencias           | `PedidoPersistenceAdapter`, `RestClient` para pagos, `BeanConfiguration`, la regla de dependencias apuntando hacia dentro.   |
| 5   | Testing en arquitecturas desacopladas          | Pirámide de tests: dominio puro, casos de uso con dobles, slices, Testcontainers, WireMock, ArchUnit.                       |
| 6   | Casos reales y patrones avanzados              | Persistencia separada del modelo, repositorios, transacciones en la capa de aplicación, outbox, eventos de dominio.         |
| 7   | Hexagonal vs Clean vs Vertical Slice           | Comparativa honesta, cuándo cada una, y aplicación en microservicios, Modulith, CQRS y mensajería.                          |
| **8** | **Proyecto final**                           | **Migrar un monolito real**, evaluar el resultado con métricas y defender la decisión con un ADR.                          |

```
 Día 1        Día 2        Día 3–4        Día 5        Día 6        Día 7        Día 8
┌──────┐    ┌──────┐    ┌──────────┐   ┌──────┐    ┌──────┐    ┌──────┐    ┌──────────┐
│ Por  │──► │Dentro│──► │ Bordes   │──►│Probar│──► │Casos │──► │Compa-│──► │ Migrar y │
│ qué  │    │ del  │    │(adapta-  │   │ sin  │    │reales│    │rar y │    │ evaluar  │
│      │    │hexág.│    │ dores)   │   │dolor │    │      │    │escalar│   │          │
└──────┘    └──────┘    └──────────┘   └──────┘    └──────┘    └──────┘    └──────────┘
```

---

## Conexión con días anteriores

- El **dominio objetivo** es exactamente el de los días 2–6: `Pedido`,
  `LineaPedido`, `EstadoPedido`, `Dinero`, `Cantidad`… Si tienes dudas sobre
  una invariante, vuelve al día 2.
- Los **tests** que escribimos hoy (caracterización, unitarios de dominio,
  casos de uso con dobles, ArchUnit) son los del día 5 aplicados sobre código
  heredado.
- La **gestión transaccional** (dónde va `@Transactional`, qué hacer con los
  efectos secundarios) es la del día 6.
- La comparación con otras arquitecturas y las arquitecturas modernas son del
  día 7; hoy solo añadimos lo que cambia **después de migrar**.

## Y después del curso

No hay día 9. Lo que viene después es vuestro proyecto real. El fichero
[06-tips-y-cierre-del-curso.md](06-tips-y-cierre-del-curso.md) recoge los diez
consejos que más os van a ahorrar, los errores más comunes y lecturas para
seguir.

---

## Punto de partida (código)

```
code/
├── pedidos-legacy/          ← monolito en capas (lo que se entrega al alumno)
│   └── src/main/java/com/curso/pedidos/
│       ├── controller/PedidoController.java
│       ├── service/PedidoService.java        (≈300 líneas, @Transactional)
│       ├── entity/PedidoEntity.java, LineaPedidoEntity.java
│       └── repository/PedidoRepository.java  (Spring Data)
└── pedidos-hexagonal/       ← solución de referencia (hexagonal, con tests)
```

Requisitos de la máquina:

- **Java 17** (vale 21; para proyectos nuevos recomendamos 21).
- **Maven 3.9+**, Docker (para Testcontainers), un IDE con soporte Spring.
- `git` configurado: vamos a hacer muchos commits pequeños.

```bash
cd code/pedidos-legacy
mvn -q verify          # compila... y no hay tests. Ese es el problema.
mvn spring-boot:run    # arranca en http://localhost:8080
```

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture* (2005): https://alistair.cockburn.us/hexagonal-architecture/
- Michael Feathers, *Working Effectively with Legacy Code* (Prentice Hall, 2004).
- Martin Fowler, *Strangler Fig Application*: https://martinfowler.com/bliki/StranglerFigApplication.html
- Martin Fowler, *Branch by Abstraction*: https://martinfowler.com/bliki/BranchByAbstraction.html
- Neal Ford, Rebecca Parsons, Patrick Kua (y Pramod Sadalage en la 2ª ed.), *Building Evolutionary Architectures* (O'Reilly, 2017; 2ª ed. 2022).
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023): https://github.com/thombergs/buckpal

## Mantra del día

> *"No reescribimos: estrangulamos. Un test, un paso, un commit — y el
> monolito se convierte en hexágono sin que el cliente se entere."*
