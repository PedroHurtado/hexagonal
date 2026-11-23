# Día 7 — Bloque 4: La comparativa — Hexagonal vs Clean vs Vertical Slice

> *"Everything in software architecture is a trade-off."*
> — Mark Richards y Neal Ford, *Fundamentals of Software Architecture* (2020),
> "Primera ley de la arquitectura de software"

---

## 1. El problema en una frase

Hemos implementado **el mismo caso de uso tres veces**. Ahora toca medir, no
opinar: cuántos ficheros, cuántas líneas, qué tests, cuánto cuesta añadir una
feature, cuánto cuesta cambiar la base de datos… y, sobre todo, **en qué
contexto gana cada una**.

Antes de empezar, una aclaración conceptual imprescindible:

> **Hexagonal y Clean responden a "¿hacia dónde apuntan las dependencias?".
> Vertical Slice responde a "¿por dónde corto el código?".** Son ejes
> distintos. Por eso no son mutuamente excluyentes, y por eso los híbridos
> (sección 7) son lo que más se ve en proyectos reales.

```
                     ¿Por dónde corto el código?
                    POR CAPA ◄──────────────────► POR FEATURE
                       │                              │
  ¿Dependencias    ────┼──────────────────────────────┼────
   hacia el        INVERTIDAS                         │
   dominio?            │   Hexagonal / Clean      Híbrido: slices con
                       │   (paquete por capa)     núcleo hexagonal
                       │                              │
                   DIRECTAS                           │
                       │   Capas clásicas         VSA extremo
                       │   (Controller→Service    (JdbcClient en
                       │    →Repository JPA)       el handler)
```

---

## 2. Las tres, rebanada a rebanada

```
         HEXAGONAL / CLEAN                         VERTICAL SLICE
     (rebanadas HORIZONTALES)                  (rebanadas VERTICALES)

  ┌───────────────────────────────┐       ┌──────┐┌──────┐┌──────┐┌──────┐
  │ Adaptadores de entrada (web)  │       │Crear ││Añadir││Confir││Cance-│
  ├───────────────────────────────┤       │Pedido││Línea ││mar   ││lar   │
  │ Puertos de entrada / Boundaries│      │      ││      ││Pedido││Pedido│
  ├───────────────────────────────┤       │ web  ││ web  ││ web  ││ web  │
  │ Servicios / Interactors       │       │ lógi-││ lógi-││ lógi-││ lógi-│
  ├───────────────────────────────┤       │ ca   ││ ca   ││ ca   ││ ca   │
  │ Dominio (Pedido)              │       │ SQL  ││ SQL  ││ SQL  ││ SQL  │
  ├───────────────────────────────┤       └──────┘└──────┘└──────┘└──────┘
  │ Puertos de salida / Gateways  │            ▲ cada una autocontenida
  ├───────────────────────────────┤
  │ Adaptadores de salida (JPA)   │       Nueva feature = nueva columna.
  └───────────────────────────────┘       No se toca ninguna otra.
   Nueva feature = un trocito en
   (casi) cada fila.
```

---

## 3. Árbol de paquetes de las tres, lado a lado

Solo con las cinco features del curso (crear, añadir línea, confirmar,
consultar, cancelar):

```
HEXAGONAL (curso)                 CLEAN                              VSA EXTREMO
com.curso.pedidos                 com.curso.pedidos                  com.curso.pedidos
├── domain                        ├── entities                       ├── features
│   ├── model                     │   └── Pedido, Linea, VOs...      │   ├── crearpedido
│   │   └── Pedido, VOs...        ├── usecases                       │   │   └── CrearPedido.java
│   ├── event                     │   ├── crearpedido     (5 fich.)  │   ├── anadirlinea
│   └── exception                 │   ├── anadirlinea     (5 fich.)  │   │   └── AnadirLinea.java
├── application                   │   ├── confirmarpedido (5 fich.)  │   ├── confirmarpedido
│   ├── port                      │   ├── consultarpedido (5 fich.)  │   │   └── ConfirmarPedido.java
│   │   ├── in   (10 fich.)       │   ├── cancelarpedido  (5 fich.)  │   ├── consultarpedido
│   │   └── out  (PedidoRepo...)  │   └── gateways                   │   │   └── ConsultarPedido.java
│   └── service  (5 fich.)        ├── adapters                       │   └── cancelarpedido
└── infrastructure                │   ├── web/<feature>  (3 x 5)     │       └── CancelarPedido.java
    ├── adapter                   │   └── persistence                └── shared
    │   ├── in/web                └── frameworks/config                  └── ClockConfig.java
    │   │   └── PedidoController
    │   │       + DTOs
    │   └── out/persistence
    └── config
```

¿Qué "grita" cada árbol al abrirlo?

- **Hexagonal**: "soy una aplicación con dominio, puertos y adaptadores". La
  arquitectura grita; las features hay que buscarlas en `port/in`.
- **Clean**: "soy una aplicación con casos de uso" (si agrupas `usecases` por
  caso de uso, como aquí). Grita más el negocio, a costa de más ficheros.
- **VSA**: "soy una aplicación que crea, añade, confirma, consulta y cancela
  pedidos". Grita el negocio; la arquitectura no se ve.

---

## 4. El mismo caso de uso, medido

Contamos lo que **realmente** escribimos en los bloques 1, 2 y 3 para
`ConfirmarPedido`:

| Medida                                    | Clean (bloque 1)            | Hexagonal (bloque 2)        | VSA extremo (bloque 3)     |
|-------------------------------------------|-----------------------------|-----------------------------|----------------------------|
| Ficheros de producción **nuevos**         | **8**                       | **4**                       | **1**                      |
| Ficheros de producción **modificados**    | 1 (config)                  | 2 (controller, config)      | 0                          |
| Total de ficheros tocados                 | 9                           | 6                           | 1                          |
| Líneas de producción propias de la feature (aprox.) | ~220             | ~110                        | ~185                       |
| Código **compartido reutilizado**         | Entidades + gateway JPA     | Dominio + adaptador JPA     | Solo `ClockConfig`         |
| Dónde está el SQL                         | Adaptador compartido        | Adaptador compartido        | En la feature              |
| Tests nuevos                              | Interactor (unit) + Presenter (unit) + Controller (`@WebMvcTest`) | Service (unit) + Controller (`@WebMvcTest`) | 1 test de integración (4 escenarios) |
| Tiempo típico de la batería de la feature | milisegundos (unit) + ~1-2 s (web slice) | ídem              | segundos (contexto + contenedor, cacheados) |
| Pestañas de IDE para entender la feature  | 6-8                         | 4-5                         | 1                          |

> Lectura honesta de la tabla: VSA tiene **menos ficheros**, pero **no
> necesariamente menos líneas**: el SQL y las reglas que en hexagonal/Clean
> viven (y se reutilizan) en el dominio y en el adaptador de persistencia, en
> VSA viven dentro de la feature. La primera feature hexagonal es cara; la
> décima es barata porque reutiliza dominio y adaptadores. La primera feature
> VSA es barata; la décima cuesta lo mismo que la primera (para bien y para mal).

---

## 5. La tabla grande

| Criterio                          | Hexagonal                                       | Clean Architecture                              | Vertical Slice (extremo)                          |
|-----------------------------------|-------------------------------------------------|-------------------------------------------------|---------------------------------------------------|
| **Eje de organización**           | Dentro/fuera (puertos y adaptadores)            | Círculos concéntricos por nivel de política     | Feature / petición                                |
| **Ficheros por feature** (ejemplo real) | 4 nuevos + 2 modificados                  | 8 nuevos + 1 modificado                         | 1 nuevo                                           |
| **Acoplamiento a framework**      | Bajo: Spring solo en adaptadores y config       | Bajo: Spring solo en los 2 círculos exteriores  | Alto y asumido: Spring en cada feature            |
| **Testabilidad unitaria**         | Excelente: dominio y servicios sin Spring       | Excelente (incluido el presenter)               | Baja por diseño; se compensa con integración      |
| **Test principal**                | Unitarios de dominio/servicio + pocos de integración | Ídem + tests de presenter                  | Integración por slice (MockMvc + Testcontainers)  |
| **Coste de añadir una feature**   | Medio: tocar varias capas, pero patrón conocido | Alto: muchas piezas por caso de uso             | Bajo: un fichero, sin tocar nada existente        |
| **Coste de cambiar BD / framework** | Bajo: un adaptador nuevo                      | Bajo: un gateway nuevo                          | Alto: tocar **todas** las features                |
| **Coste de cambiar una regla de negocio compartida** | Bajo: en el dominio, un sitio | Bajo: en entidades                           | Alto si no se ha refactorizado hacia el dominio   |
| **Curva de aprendizaje**          | Media (puertos, adaptadores, DIP)               | Alta (mucho vocabulario, presenter)             | Baja para empezar; alta para hacerlo **bien** (saber cuándo refactorizar) |
| **Riesgo de duplicación**         | Bajo                                            | Bajo                                            | Alto (reglas y SQL repetidos entre slices)        |
| **Riesgo de sobreingeniería**     | Medio (puertos para todo, mappers por capa)     | Alto (ceremonia en CRUD triviales)              | Bajo                                              |
| **Encaje con DDD táctico**        | Muy bueno: el hexágono protege el modelo        | Muy bueno: Entities = modelo                    | Débil al inicio; bueno tras extraer dominio       |
| **Encaje con CQRS**               | Bueno (puertos de comando y de consulta)        | Bueno                                           | Natural: cada slice es un comando o una consulta  |
| **Encaje con microservicios**     | Muy bueno: un hexágono por servicio             | Bueno (pesado para servicios pequeños)          | Bueno para servicios pequeños y muy CRUD          |
| **Tamaño de equipo**              | Medio-grande; varios equipos sobre el mismo núcleo | Grande; necesita disciplina y vocabulario común | Pequeño-medio, o equipos alineados por feature    |
| **Onboarding**                    | Hay que entender la arquitectura antes de tocar | Más lento: más conceptos                        | Rápido: "abre el fichero de la feature"           |
| **Conflictos de merge**           | Medios (controller y config compartidos)        | Bajos (casi todo por caso de uso)               | Mínimos                                           |
| **Vida útil esperada que justifica** | Años; sistema con negocio rico               | Años; sistema grande con varias UIs             | Cualquiera, si el equipo refactoriza; ideal en apps CRUD-intensivas |

---

## 6. Cuándo elegir cada una: matriz de decisión

### 6.1 Preguntas que hay que hacerse

1. ¿La lógica de negocio es **rica** (invariantes, máquinas de estado, cálculos)
   o el sistema es mayoritariamente **CRUD + consultas**?
2. ¿Es **probable** cambiar infraestructura (BD, broker, proveedor de pagos) o
   tener **varios canales de entrada** (REST, colas, batch, CLI)?
3. ¿Cuánto vive el sistema? ¿Meses (prototipo, campaña) o años (core business)?
4. ¿Cómo es el equipo? ¿Tamaño, experiencia en DDD, rotación?
5. ¿Las features son **independientes** entre sí o comparten muchas reglas?

### 6.2 Matriz

| Situación                                                         | Recomendación                                   |
|-------------------------------------------------------------------|-------------------------------------------------|
| Core business con reglas ricas, larga vida, varios adaptadores    | **Hexagonal** (con dominio DDD dentro)           |
| Igual que lo anterior + varias UIs/salidas distintas por caso de uso | **Clean** completo (con Presenter)            |
| Backoffice / API mayoritariamente CRUD + muchas consultas         | **VSA**                                          |
| Prototipo, MVP, campaña de vida corta                              | **VSA extremo** (o capas clásicas)               |
| Muchas features independientes, equipo pequeño, cambios rápidos   | **VSA**, refactorizando hacia dominio cuando duela |
| Sistema grande con varios bounded contexts                        | **Híbrido**: hexágono por contexto + slices dentro |
| Microservicio pequeño con 2-3 endpoints y lógica trivial          | VSA o capas simples; hexagonal completo es caro |
| Microservicio con negocio rico e integraciones externas           | **Hexagonal**                                    |
| Equipo junior sin experiencia en DIP/DDD                          | Hexagonal "ligera" con ArchUnit, o VSA con revisiones de código estrictas |

### 6.3 Árbol de decisión rápido

```
                ¿Reglas de negocio ricas y compartidas entre features?
                         │                               │
                        SÍ                              NO
                         │                               │
         ¿Varios canales / infra cambiante?      ¿Vida larga y crecimiento?
            │                   │                   │               │
           SÍ                  NO                  SÍ              NO
            │                   │                   │               │
   ┌────────▼───────┐  ┌────────▼─────────┐ ┌───────▼────────┐ ┌────▼─────┐
   │  HEXAGONAL     │  │ HÍBRIDO: slices  │ │ VSA + refactor │ │   VSA    │
   │ (Clean si hay  │  │ con núcleo de    │ │ hacia dominio  │ │ extremo  │
   │  varias UIs)   │  │ dominio          │ │ cuando duela   │ │          │
   └────────────────┘  └──────────────────┘ └────────────────┘ └──────────┘
```

---

## 7. Híbridos reales (lo que más se ve en producción)

### 7.1 Hexágono por bounded context + slices dentro

Cada **bounded context** (Pedidos, Catálogo, Pagos) es un hexágono con su
dominio y sus adaptadores de salida. **Dentro**, las features se organizan por
paquete (slice), no por capa. Es el "paquete por feature con puertos".

```
com.curso.pedidos
├── pedidos                                   ← bounded context = hexágono
│   ├── domain                                (Pedido, EstadoPedido, eventos, excepciones)
│   ├── confirmarpedido                       ← slice de escritura
│   │   ├── ConfirmarPedido.java              (Command + UseCase + Service, sin Spring)
│   │   └── ConfirmarPedidoController.java    (adaptador de entrada de la slice)
│   ├── cancelarpedido
│   │   ├── CancelarPedido.java
│   │   └── CancelarPedidoController.java
│   ├── consultarpedido                       ← slice de lectura: VSA puro
│   │   └── ConsultarPedido.java              (JdbcClient directo a DTO, sin dominio)
│   ├── port/out                              (PedidoRepository, PublicadorDeEventos)
│   └── adapter/out                           (persistence, messaging)
├── catalogo                                  ← otro hexágono
│   └── ...
└── config
```

El fichero de la slice de escritura, compacto pero con el núcleo protegido:

```java
package com.curso.pedidos.pedidos.confirmarpedido;

import com.curso.pedidos.pedidos.domain.Pedido;
import com.curso.pedidos.pedidos.domain.PedidoConfirmado;
import com.curso.pedidos.pedidos.domain.PedidoId;
import com.curso.pedidos.pedidos.domain.PedidoNoEncontradoException;
import com.curso.pedidos.pedidos.port.out.PedidoRepository;
import com.curso.pedidos.pedidos.port.out.PublicadorDeEventos;
import java.time.Clock;

/** Slice "confirmar pedido": contrato + implementación. Java puro. */
public final class ConfirmarPedido {

    private ConfirmarPedido() {
    }

    public record Command(PedidoId pedidoId) {
    }

    /** Puerto de entrada de la slice. */
    public interface UseCase {
        Pedido ejecutar(Command command);
    }

    /** Implementación: orquesta dominio y puertos de salida compartidos del contexto. */
    public static final class Service implements UseCase {
        private final PedidoRepository pedidos;
        private final PublicadorDeEventos eventos;
        private final Clock clock;

        public Service(PedidoRepository pedidos, PublicadorDeEventos eventos, Clock clock) {
            this.pedidos = pedidos;
            this.eventos = eventos;
            this.clock = clock;
        }

        @Override
        public Pedido ejecutar(Command command) {
            Pedido pedido = pedidos.buscarPorId(command.pedidoId())
                    .orElseThrow(() -> new PedidoNoEncontradoException(command.pedidoId()));
            pedido.confirmar();
            pedidos.guardar(pedido);
            eventos.publicar(new PedidoConfirmado(pedido.id(), clock.instant()));
            return pedido;
        }
    }
}
```

**Recuento: 2 ficheros por feature** (slice + controller), dominio y puertos de
salida compartidos, test unitario del `Service` sin Spring. Es, para muchos
equipos, el punto dulce.

### 7.2 "Vertical slices con núcleo hexagonal"

Variante del anterior: **las escrituras** pasan por el núcleo hexagonal
(dominio + puertos), **las lecturas** son slices VSA puras que leen de la BD
(o de una vista/proyección) directamente. Es CQRS ligero sin dos bases de
datos.

```
   Escrituras (comandos)                       Lecturas (consultas)
   ┌───────────────────┐                       ┌───────────────────┐
   │ Controller slice  │                       │ ConsultarPedido   │
   └────────┬──────────┘                       │ (Endpoint+Handler)│
            ▼                                  └────────┬──────────┘
   ┌───────────────────┐                                │ SQL directo
   │ UseCase (slice)   │                                │ a DTO
   └────────┬──────────┘                                │
            ▼                                           │
   ┌───────────────────┐                                │
   │ Dominio: Pedido   │                                │
   └────────┬──────────┘                                │
            ▼                                           ▼
   ┌───────────────────┐   ┌──────────────────────────────┐
   │ PedidoRepository  │──►│        PostgreSQL            │
   │ (puerto + adapter)│   └──────────────────────────────┘
   └───────────────────┘
```

### 7.3 Package by component (Simon Brown)

En el capítulo 34 de *Clean Architecture* (*The Missing Chapter*, escrito por
Simon Brown), se comparan cuatro formas de empaquetar: **por capa**, **por
feature**, **puertos y adaptadores** y **por componente**. La de componente
agrupa la lógica de negocio y el acceso a datos de una funcionalidad detrás de
**una única interfaz pública**, y apoya la idea en un argumento muy práctico:
usar los **modificadores de acceso de Java** (clases package-private) para que
el compilador haga cumplir la arquitectura, en lugar de confiar en la
disciplina. Es otro híbrido entre "por capa" y "por feature".

### 7.4 Proteger los límites con ArchUnit

Cualquier híbrido sin tests de arquitectura se degrada. Dos reglas típicas:

```java
@AnalyzeClasses(packages = "com.curso.pedidos")
class ReglasDeArquitecturaTest {

    // VSA: las slices no se conocen entre sí
    @ArchTest
    static final ArchRule slices_independientes =
            SlicesRuleDefinition.slices()
                    .matching("com.curso.pedidos.features.(*)..")
                    .should().notDependOnEachOther();

    // Híbrido: el dominio no depende de ninguna slice ni de Spring
    @ArchTest
    static final ArchRule dominio_puro =
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("..pedidos.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                                        "..confirmarpedido..", "..cancelarpedido..");
}
```

---

## 8. Antipatrones de cada una

### Hexagonal

| Antipatrón                         | Síntoma                                                        |
|------------------------------------|----------------------------------------------------------------|
| **Puertos para todo**              | `ClockPort`, `UuidGeneratorPort`, `LoggerPort`… interfaces con una sola implementación que nunca cambiará |
| **Mapper hell**                    | `PedidoRequest` → `PedidoCommand` → `Pedido` → `PedidoJpaEntity` → `PedidoResponse` en un CRUD trivial |
| **Hexágono anémico**               | Dominio con getters/setters y toda la lógica en los servicios de aplicación |
| **Un puerto de entrada gigante**   | `PedidoUseCase` con 15 métodos: el `PedidoService` de siempre con otro nombre |
| **Fuga de infraestructura**        | `@Transactional`, `@Entity` o `Page<T>` de Spring Data en `application` |

### Clean

| Antipatrón                         | Síntoma                                                        |
|------------------------------------|----------------------------------------------------------------|
| **Ceremonia en CRUD**              | 8 ficheros para devolver una fila de una tabla                  |
| **Presenter singleton con estado** | Bug de concurrencia: dos peticiones comparten `viewModel`       |
| **Cuatro módulos por dogma**       | Módulos Maven `entities`, `usecases`, `adapters`, `frameworks` en un servicio de 3 endpoints |
| **Request/Response Models duplicados** | Copias 1:1 de las entidades "porque toca"                  |

### Vertical Slice

| Antipatrón                         | Síntoma                                                        |
|------------------------------------|----------------------------------------------------------------|
| **Transaction Script duplicado**   | La misma regla (`if estado == ...`) copiada en 6 handlers       |
| **Slices que se llaman entre sí**  | `CancelarPedido.Handler` inyecta `ConsultarPedido.Handler`: acoplamiento entre slices, la regla número uno rota |
| **"Shared" que crece sin control** | `shared/` acaba siendo la vieja capa de servicios               |
| **Ficheros de 1.000 líneas**       | La versión extrema llevada demasiado lejos: si la feature ya no cabe en una pantalla y media, pártela en un paquete |
| **Formatos de error inconsistentes** | Cada slice devuelve errores a su manera                       |
| **Migración de BD dolorosa**       | Cambiar una columna obliga a tocar el SQL de 12 features        |

---

## 9. Conclusión honesta

1. **Hexagonal y Clean son la misma familia.** Elegir entre ellas en un
   proyecto Spring REST es, casi siempre, decidir si quieres Presenter. En el
   curso usamos hexagonal sin Presenter porque es lo más idiomático en Spring.
2. **VSA no es "anti-hexagonal".** Es otro eje. Optimiza el **coste de cambio
   por feature** y la **localidad**; paga en **independencia tecnológica** y en
   **riesgo de duplicación**.
3. **El coste se mueve, no desaparece.** Hexagonal paga por adelantado
   (estructura, puertos, mappers) para abaratar cambios de infraestructura y de
   reglas compartidas. VSA paga después (duplicación, refactor hacia dominio)
   para abaratar el arranque y las features aisladas.
4. **La respuesta madura suele ser un híbrido**: un hexágono por bounded
   context para proteger el dominio, organizado **por feature** por dentro, con
   las lecturas como slices directas.
5. **Lo que NO es negociable** en ninguna de las tres: tests automáticos que
   cubran lo que el usuario ve, límites protegidos por ArchUnit, y un equipo que
   refactoriza cuando huele mal.

> Si alguien te dice que una de las tres es "la correcta" sin preguntarte
> antes por tu dominio, tu equipo y la vida esperada del sistema, no te está
> dando arquitectura: te está dando religión.

---

## Ejercicio

Tu empresa tiene tres sistemas. Para cada uno, elige arquitectura usando la
matriz y justifícalo en tres líneas:

1. Un **backoffice de gestión de catálogo**: 40 pantallas de alta/baja/
   modificación/listado con filtros, 3 desarrolladores, vida esperada 2 años.
2. El **motor de tarificación de seguros**: reglas complejas que cambian cada
   trimestre por normativa, se invoca por REST, por batch nocturno y por Kafka,
   vida esperada 10+ años.
3. Un **e-commerce** que empieza como monolito con contextos Pedidos, Catálogo,
   Pagos y Envíos, con intención de extraer microservicios si crece.

<details><summary>Solución (orientativa)</summary>

1. **VSA** (incluso extremo). Mayoría CRUD y consultas, features independientes,
   equipo pequeño, vida media. Una convención de errores y ArchUnit para que las
   slices no se acoplen. Si aparece lógica compartida (precios, publicación),
   se extrae a `shared/domain`.
2. **Hexagonal** con dominio rico (DDD táctico). Tres adaptadores de entrada
   para el mismo caso de uso es exactamente el escenario para el que Cockburn
   pensó el patrón; las reglas cambiantes deben estar aisladas y con tests
   unitarios rápidos. Clean con Presenter solo si las tres salidas necesitan
   formatos de resultado muy distintos.
3. **Híbrido**: monolito modular (Spring Modulith, bloque 5) con **un hexágono
   por bounded context** y slices dentro. Si un contexto se extrae, se lleva su
   hexágono entero como microservicio.
</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture* (2005):
  https://alistair.cockburn.us/hexagonal-architecture/
- Robert C. Martin, *The Clean Architecture* (2012):
  https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html ;
  *Clean Architecture* (2017), en especial el capítulo 34 *The Missing Chapter*
  (Simon Brown).
- Jeffrey Palermo, *The Onion Architecture: part 1* (2008):
  https://jeffreypalermo.com/2008/07/the-onion-architecture-part-1/
- Jimmy Bogard, *Vertical Slice Architecture* (2018):
  https://www.jimmybogard.com/vertical-slice-architecture/
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023);
  https://github.com/thombergs/buckpal
- Martin Fowler, catálogo PoEAA (Transaction Script, Domain Model):
  https://martinfowler.com/eaaCatalog/ ; *CQRS*: https://martinfowler.com/bliki/CQRS.html
- Eric Evans, *Domain-Driven Design* (2003); Vaughn Vernon, *Implementing
  Domain-Driven Design* (2013).
- Mark Richards y Neal Ford, *Fundamentals of Software Architecture* (O'Reilly, 2020).
- ArchUnit: https://www.archunit.org/userguide/html/000_Index.html

---

## Mantra

> *"El coste no desaparece: se mueve. Elige dónde quieres pagarlo."*
