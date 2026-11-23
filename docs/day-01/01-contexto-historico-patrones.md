# Día 1 — Bloque 1: Contexto histórico de los patrones

> *"Cada patrón describe un problema que ocurre una y otra vez en nuestro
> entorno, y describe el núcleo de la solución a ese problema, de tal forma que
> puedas usar esa solución un millón de veces sin hacerlo nunca dos veces de la
> misma manera."* — Christopher Alexander, *A Pattern Language* (1977)

---

## 1. El problema en una frase

Si no sabemos **de dónde viene** la arquitectura hexagonal, la aplicaremos como
una receta de carpetas (`port/in`, `port/out`…) sin entender **qué problema
resuelve**, y acabaremos con el mismo acoplamiento de siempre, solo que
repartido en más ficheros.

En cristiano: el hexágono es la respuesta a una pregunta que la industria lleva
haciéndose desde los 70. Conocer la pregunta es más útil que memorizar la
respuesta.

---

## 2. Qué es un patrón (y qué no)

Un patrón **no** es código para copiar. Es una **solución con nombre** a un
problema recurrente **en un contexto**. Tiene cuatro partes (formato GoF):

| Parte          | Pregunta que responde                      | Ejemplo (Adapter)                                     |
|----------------|--------------------------------------------|-------------------------------------------------------|
| Nombre         | ¿Cómo lo llamamos para hablar de ello?     | Adapter                                               |
| Problema       | ¿Cuándo aplica?                            | Tengo una interfaz que no encaja con la que espero    |
| Solución       | ¿Qué estructura propone?                   | Una clase intermedia que traduce una a otra           |
| Consecuencias  | ¿Qué gano y qué pago?                      | Desacoplo; añado una indirección                      |

El **nombre** es lo más infravalorado: cuando alguien dice "esto es un adaptador
de salida", todo el equipo sabe dónde vive, de qué depende y cómo se prueba.
**Un patrón es vocabulario compartido.**

---

## 3. Christopher Alexander (1977): todo empezó en la arquitectura de edificios

Christopher Alexander era **arquitecto de edificios**, no de software. En *A
Pattern Language* (1977) catalogó 253 patrones para diseñar ciudades, barrios y
casas: "Luz por dos lados de cada habitación", "Ventana con asiento",
"Calles en red".

Ideas clave que heredamos:

- Un patrón resuelve **fuerzas en tensión** (luz vs privacidad; flexibilidad vs
  simplicidad).
- Los patrones forman un **lenguaje**: se combinan, unos contienen a otros.
- La calidad emerge de aplicar patrones **en el orden correcto**, de lo grande
  a lo pequeño.

La arquitectura hexagonal es exactamente eso: un patrón **grande** (forma del
sistema) que contiene patrones **pequeños** (Adapter, Repository, Strategy,
Factory…).

---

## 4. Gang of Four (1994): los patrones llegan al software

Erich Gamma, Richard Helm, Ralph Johnson y John Vlissides publican *Design
Patterns: Elements of Reusable Object-Oriented Software* (1994). 23 patrones en
tres familias:

| Familia          | Qué resuelven                        | Los que más veremos en el curso          |
|------------------|--------------------------------------|------------------------------------------|
| Creacionales     | Cómo se crean los objetos            | Factory Method, Builder                  |
| Estructurales    | Cómo se componen los objetos         | **Adapter**, Facade, Decorator, Proxy    |
| Comportamiento   | Cómo colaboran y reparten trabajo    | Strategy, Observer, Command, Template    |

Dos frases del libro que son el ADN del hexágono:

> *"Program to an interface, not an implementation."*
>
> *"Favor object composition over class inheritance."*

Y un patrón estructural que da nombre a la mitad de nuestra arquitectura:
**Adapter** — "convierte la interfaz de una clase en otra interfaz que los
clientes esperan". Un `PedidoPersistenceAdapter` es literalmente un Adapter GoF:
traduce entre lo que el dominio espera (`PedidoRepository`) y lo que JPA
ofrece (`JpaRepository<PedidoJpaEntity, UUID>`).

---

## 5. Martin Fowler, PoEAA (2002): patrones de aplicación empresarial

*Patterns of Enterprise Application Architecture* (2002) sube un nivel: ya no
hablamos de objetos sueltos sino de **aplicaciones empresariales** (BD,
transacciones, presentación web, concurrencia).

Patrones de PoEAA que veremos aparecer (algunos para criticarlos):

| Patrón PoEAA           | Qué es                                                        | En el curso                                    |
|------------------------|---------------------------------------------------------------|------------------------------------------------|
| Layers                 | Organizar en capas: presentación, dominio, datos              | Día 1: lo que el hexágono corrige              |
| Transaction Script     | Un procedimiento por operación de negocio                     | Día 1: el `PedidoService` gordo                |
| Domain Model           | Objetos con datos **y** comportamiento                        | Día 2: `Pedido` rico                           |
| Service Layer          | Frontera de la aplicación que coordina operaciones            | Día 1–2: nuestros casos de uso                 |
| Repository             | Colección en memoria aparente de objetos de dominio           | Día 2 y 4: `PedidoRepository`                  |
| Data Mapper            | Separa objetos de dominio de su representación en BD          | Día 4: mapper JPA ↔ dominio                    |
| Unit of Work           | Agrupa cambios y los confirma juntos                          | Día 6: transacciones                           |

Catálogo online: https://martinfowler.com/eaaCatalog/

---

## 6. Eric Evans, DDD (2003): el dominio en el centro

*Domain-Driven Design: Tackling Complexity in the Heart of Software* (2003)
pone el foco donde duele: **el modelo de negocio**. Conceptos que usaremos sin
parar:

- **Lenguaje ubicuo**: el código habla como el negocio (`confirmar()`, no
  `setEstado(2)`).
- **Entidades, Value Objects, Agregados**: `Pedido`, `Dinero`, `Pedido` como
  raíz de agregado con sus `LineaPedido`.
- **Repositorios** y **Factorías**.
- **Bounded Contexts**: Pedidos, Catálogo y Pagos son contextos distintos.
- **Capa de dominio aislada**: Evans ya pedía que el dominio no estuviera
  mezclado con la infraestructura. El hexágono le da **forma física** a esa
  petición.

DDD y hexagonal **no son lo mismo**, pero se llevan de maravilla: DDD dice
**qué** va en el centro; hexagonal dice **cómo protegerlo**.

---

## 7. Evolución de las arquitecturas

### 7.1 Big Ball of Mud (sin arquitectura)

Brian Foote y Joseph Yoder (1997) bautizaron el estilo más extendido del mundo:
el **gran barro**. Código sin estructura reconocible, donde todo llama a todo.

```
┌──────────────────────────────────────────┐
│   UI ◄──► SQL ◄──► Reglas ◄──► HTTP      │
│    ▲        ▲         ▲         ▲        │
│    └────────┴────┬────┴─────────┘        │
│              (todo con todo)             │
└──────────────────────────────────────────┘
```

¿Qué duele? Cada cambio es una ruleta. Nadie sabe qué rompe qué.

### 7.2 Arquitectura en capas / n-tier

La primera gran respuesta: **separar por responsabilidad técnica**.

```
┌─────────────────────┐
│    Presentación     │
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ Negocio (servicios) │
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│  Acceso a datos     │
└──────────┬──────────┘
           ▼
        [ BD ]
```

Mejora enorme sobre el barro, pero con un defecto de fábrica: **el negocio
depende de la capa de datos**. La flecha apunta hacia la BD. Lo desarrollamos a
fondo en [03-el-problema-de-la-arquitectura-en-capas.md](03-el-problema-de-la-arquitectura-en-capas.md).

### 7.3 MVC

Trygve Reenskaug (Smalltalk, 1979) separa **Modelo**, **Vista** y
**Controlador**. Nació para interfaces de escritorio; la web lo adoptó (Struts,
Spring MVC, Rails).

Trampa: MVC es un patrón de **presentación**, no una arquitectura de
aplicación. En Spring, "la M" suele acabar siendo entidades JPA anémicas y
toda la lógica en `@Service`. MVC organiza el **borde**, no el **centro**.

### 7.4 Ports and Adapters — Alistair Cockburn (2005)

Cockburn publica *Hexagonal Architecture* (las primeras versiones circulaban
desde finales de los 90 en el wiki de Ward Cunningham). La idea:

> *"Allow an application to equally be driven by users, programs, automated
> test or batch scripts, and to be developed and tested in isolation from its
> eventual run-time devices and databases."*

Sin capas de arriba abajo: hay un **dentro** (la aplicación) y un **fuera**
(todo lo demás), conectados por **puertos** y **adaptadores**. Lo vemos en
[04-introduccion-arquitectura-hexagonal.md](04-introduccion-arquitectura-hexagonal.md).

### 7.5 Onion Architecture — Jeffrey Palermo (2008)

Palermo dibuja **círculos concéntricos**: modelo de dominio en el centro,
servicios de dominio, servicios de aplicación, y la infraestructura en la piel.
Regla: **las dependencias solo apuntan hacia dentro**. Es el hexágono con más
capas internas explícitas.

### 7.6 Clean Architecture — Robert C. Martin (2012)

Uncle Bob unifica Hexagonal, Onion, DCI y BCE en un único diagrama: Entities →
Use Cases → Interface Adapters → Frameworks & Drivers, con la famosa
**Dependency Rule**:

> *"Source code dependencies can only point inwards."*

Lo comparamos a fondo en el día 7.

### 7.7 Microservicios (≈2014)

James Lewis y Martin Fowler popularizan el término en 2014. Ojo: los
microservicios son una decisión de **despliegue y organización**, no de diseño
interno. Un microservicio puede ser un gran barro en miniatura. El hexágono es
justamente cómo se diseña **por dentro** cada servicio para que su lógica
sobreviva a cambios de protocolo (REST → gRPC → Kafka) y de almacenamiento.

---

## 8. Línea temporal

```
1977        1979      1994        1997          2002          2003
 │           │         │           │             │             │
 ▼           ▼         ▼           ▼             ▼             ▼
Alexander   MVC       GoF        Big Ball      Fowler        Evans
"A Pattern  Reensk.   Design     of Mud        PoEAA         DDD
Language"             Patterns   (Foote/Yoder) (Layers,      (dominio en
                                                Repository)   el centro)
──────────────────────────────────────────────────────────────────────►
2005           2008          2012           2014          2017-2024
 │              │             │              │              │
 ▼              ▼             ▼              ▼              ▼
Cockburn      Palermo       Uncle Bob      Lewis/Fowler   Libro Clean Arch.
Hexagonal     Onion         Clean Arch.    Microservicios Vertical Slice (2018)
(Ports &      (círculos)    (Dependency                   Hombergs (2019/2023)
Adapters)                   Rule)                         Cockburn & Garrido
                                                          "Hex. Explained" (2024)
```

Lectura de la línea: **todo converge** hacia la misma idea. Separar lo que
cambia por motivos de negocio de lo que cambia por motivos tecnológicos, y
hacer que lo tecnológico dependa de lo de negocio, nunca al revés.

---

## 9. Lo que comparten Hexagonal, Onion y Clean

```
       Hexagonal (2005)          Onion (2008)            Clean (2012)
     ┌───────────────────┐   ┌─────────────────┐    ┌──────────────────┐
     │   adaptadores     │   │ infraestructura │    │ frameworks/drivers│
     │  ┌─────────────┐  │   │ ┌─────────────┐ │    │ ┌──────────────┐ │
     │  │ aplicación  │  │   │ │ app services│ │    │ │  adapters    │ │
     │  │ + dominio   │  │   │ │ ┌─────────┐ │ │    │ │ ┌──────────┐ │ │
     │  │  (dentro)   │  │   │ │ │ dominio │ │ │    │ │ │use cases │ │ │
     │  └─────────────┘  │   │ │ └─────────┘ │ │    │ │ │┌────────┐│ │ │
     └───────────────────┘   │ └─────────────┘ │    │ │ ││entities││ │ │
                             └─────────────────┘    │ │ │└────────┘│ │ │
                                                    │ │ └──────────┘ │ │
      dependencias ──► hacia dentro, siempre        │ └──────────────┘ │
                                                    └──────────────────┘
```

| Aspecto                     | Hexagonal               | Onion                   | Clean                     |
|-----------------------------|-------------------------|-------------------------|---------------------------|
| Metáfora                    | Dentro / fuera, puertos | Capas de cebolla        | Círculos concéntricos     |
| Regla de dependencia        | Hacia la aplicación     | Hacia el centro         | Hacia dentro              |
| Capas internas prescritas   | No (libre)              | Sí (dominio, servicios) | Sí (entities, use cases)  |
| Énfasis                     | Simetría driving/driven | Dominio en el núcleo    | Casos de uso explícitos   |

Trampa: pelearse por los nombres. En la práctica, un buen proyecto hexagonal
en Spring (como el de Hombergs, *buckpal*) se parece muchísimo a uno "Clean".
Lo importante es **la dirección de las dependencias**.

---

## 10. Testing: ¿qué tiene que ver la historia con los tests?

Más de lo que parece. La motivación original de Cockburn incluía literalmente
"**automated test**" como uno de los actores que conducen la aplicación. Un
síntoma de buena arquitectura es poder escribir esto:

```java
@Test
void un_pedido_nuevo_nace_en_borrador() {
    Pedido pedido = Pedido.crear(ClienteId.nuevo());

    assertThat(pedido.estado()).isEqualTo(EstadoPedido.BORRADOR);
}
```

Sin `@SpringBootTest`, sin base de datos, sin red. Si en tu proyecto actual no
puedes, la historia te dice por qué: tu dominio depende de la infraestructura.

---

## 11. Resumen

| Hito              | Año  | Aportación clave para el hexágono                         |
|-------------------|------|-----------------------------------------------------------|
| Alexander         | 1977 | Patrón = solución con nombre a fuerzas en tensión         |
| GoF               | 1994 | Interfaces, composición, **Adapter**                      |
| Big Ball of Mud   | 1997 | Nombre al antipatrón que queremos evitar                  |
| Fowler PoEAA      | 2002 | Layers, Service Layer, Repository, Data Mapper            |
| Evans DDD         | 2003 | Dominio rico y aislado, lenguaje ubicuo                   |
| Cockburn          | 2005 | **Ports & Adapters**: dentro/fuera, simetría              |
| Palermo           | 2008 | Onion: dependencias hacia el centro                       |
| Uncle Bob         | 2012 | Clean: Dependency Rule, casos de uso explícitos           |
| Microservicios    | 2014 | El diseño interno de cada servicio sigue importando       |

---

## Ejercicio

Piensa en el último proyecto Spring en el que hayas trabajado y contesta:

1. ¿En qué punto de la línea temporal está su arquitectura? (barro, capas,
   algo hexagonal…)
2. Identifica **un patrón GoF** y **un patrón PoEAA** que uses sin saberlo.
3. ¿Hacia dónde apunta la dependencia entre tu `@Service` y tu
   `JpaRepository`?

*Pista*: mira los `import` de tu clase de servicio principal.

<details><summary>Solución orientativa</summary>

1. La inmensa mayoría de proyectos Spring Boot son **arquitectura en capas**
   (n-tier) con tendencia a gran barro en los servicios más antiguos.
2. GoF: `JpaRepository` es un **Proxy** generado dinámicamente; los
   `@Transactional` funcionan con **Proxy/Decorator**; `RestTemplate` usa
   **Template Method**. PoEAA: **Repository**, **Service Layer**, y muy a
   menudo **Transaction Script** en los servicios.
3. Casi siempre: `PedidoService` → `import ...jpa.PedidoJpaRepository`. La
   lógica de negocio **depende** de la persistencia. Eso es lo que el
   hexágono invierte.

</details>

---

## Referencias

- Christopher Alexander, *A Pattern Language* (1977).
- Gamma, Helm, Johnson, Vlissides, *Design Patterns* (GoF, 1994).
- Martin Fowler, *Patterns of Enterprise Application Architecture* (2002). Catálogo: https://martinfowler.com/eaaCatalog/
- Eric Evans, *Domain-Driven Design* (2003).
- Alistair Cockburn, *Hexagonal Architecture* (2005): https://alistair.cockburn.us/hexagonal-architecture/
- Jeffrey Palermo, *The Onion Architecture* (2008): https://jeffreypalermo.com/2008/07/the-onion-architecture-part-1/
- Robert C. Martin, *The Clean Architecture* (2012): https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- Jimmy Bogard, *Vertical Slice Architecture* (2018): https://www.jimmybogard.com/vertical-slice-architecture/
- Cockburn & Garrido de Paz, *Hexagonal Architecture Explained* (2024).
- Martin Fowler, bliki: https://martinfowler.com/bliki/

---

## Mantra

> *"Las arquitecturas cambian de nombre; la pregunta es siempre la misma: ¿hacia dónde apuntan las dependencias?"*
