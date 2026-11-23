# Día 8 — Bloque 6: Tips y cierre del curso

> *"Architecture is about the important stuff. Whatever that is."*
> — Ralph Johnson, citado por Martin Fowler en *Who Needs an Architect?* (2003)

---

## 1. Ocho sesiones en una frase

Empezamos preguntándonos **por qué** separar el negocio de la tecnología y
terminamos **migrando un monolito real** sin romper su API. Entre medias, el
mismo `Pedido` ha crecido día a día: de un `record` con dos campos a un
agregado con invariantes, eventos, puertos, adaptadores, tests en cuatro
niveles y reglas de arquitectura en el pipeline.

---

## 2. Los 10 tips más importantes del curso

### Tip 1 — El dominio no importa nada que no sea Java

Si en `domain` aparece `org.springframework`, `jakarta.persistence` o
`com.fasterxml.jackson`, el hexágono tiene una grieta. No es purismo: es lo que
permite probar las reglas en milisegundos y cambiar de framework sin tocar el
negocio. **Ponlo en ArchUnit el primer día.**

### Tip 2 — Nombra los puertos con el idioma del negocio

`PasarelaDePago`, no `StripeClient`. `NotificadorPedidos`, no `EmailSender`.
El nombre del puerto dice **qué necesita** la aplicación; el del adaptador,
**cómo** se consigue. Si el puerto lleva el nombre de una tecnología, el día
que la cambies tendrás que renombrar media aplicación.

### Tip 3 — Un modelo rico vale más que diez servicios

Las reglas viven en `Pedido.confirmar()`, no en un `if` repetido en tres
servicios. Si tu agregado solo tiene getters y setters, tienes un *Anemic
Domain Model* (Fowler) y la arquitectura hexagonal solo te dará más carpetas.

### Tip 4 — La entidad JPA no es tu modelo de dominio

Dos modelos, dos razones para cambiar. Sí, hay un mapper. Sí, es "duplicación".
Es la duplicación más barata que vas a pagar: la alternativa es que una
columna nueva o un `@JsonIgnore` decidan cómo es tu negocio.

### Tip 5 — Nada externo dentro de una transacción

HTTP, SMTP, Kafka: fuera. Cobrar dentro de `@Transactional` acabó, en nuestro
monolito, cobrando pedidos que no existían. Persistir en una transacción
corta, efectos externos antes (con compensación) o después (con outbox).

### Tip 6 — Los adaptadores son delgados y aburridos

Un controlador traduce, llama al puerto y traduce. Un adaptador de persistencia
mapea y guarda. Si un adaptador tiene un `if` de negocio, ese `if` se ha
escapado del hexágono. El código interesante tiene que estar **dentro**.

### Tip 7 — Prueba cada capa con su herramienta

Dominio: JUnit puro. Casos de uso: fakes y mocks de los puertos. Adaptadores:
slices (`@WebMvcTest`, `@DataJpaTest`), Testcontainers, WireMock. Sistema:
pocos tests de extremo a extremo. La pirámide no es un dogma; es la forma de
tener una suite **rápida y fiable**.

### Tip 8 — Antes de cambiar código heredado, caracterízalo

Golden master primero, refactor después. El test de caracterización no dice
lo que el sistema *debería* hacer, sino lo que *hace*. Y casi siempre descubre
algo (un 200 donde debería haber un 404, un precio que viene del cliente…).

### Tip 9 — Migra por trozos y borra lo viejo

Strangler Fig por endpoint, Branch by Abstraction por componente, commits
pequeños siempre en verde. Y la fase que nadie quiere hacer: **borrar el
código viejo**. Dos caminos vivos son peor que uno feo.

### Tip 10 — El hexágono es una herramienta, no una religión

Para un CRUD sin reglas, Vertical Slice o unas capas simples bastan (día 7).
El hexágono paga cuando hay **reglas de negocio que proteger** y **tecnología
que va a cambiar**. Decide con criterio, escríbelo en un ADR y revisa la
decisión cuando cambie el contexto.

---

## 3. Errores más comunes (los hemos visto todos)

| # | Error                                                       | Síntoma                                                   | Remedio                                                   |
|---|-------------------------------------------------------------|-----------------------------------------------------------|-----------------------------------------------------------|
| 1 | `@Entity` en el dominio "para no duplicar"                  | Cambiar una columna obliga a tocar reglas de negocio      | Modelo de dominio + entidad JPA + mapper                  |
| 2 | Puertos que son copias 1:1 de `JpaRepository`              | `findAllByEstadoAndCreadoEnBetween…` en `application`     | Puertos con lo que el caso de uso necesita, nada más      |
| 3 | Un puerto por clase "por si acaso"                          | Interfaces con una implementación que nunca cambiará      | Puertos solo en las fronteras (entrada/salida)            |
| 4 | Lógica de negocio en el controlador o en el adaptador       | Imposible reutilizar desde Kafka o un batch               | Moverla al dominio o al caso de uso                       |
| 5 | Casos de uso que llaman a otros casos de uso                | Grafo de dependencias entre servicios, transacciones anidadas | Extraer la lógica común al dominio                    |
| 6 | Devolver entidades JPA o el agregado en el JSON             | `LazyInitializationException`, campos sensibles expuestos | DTOs de respuesta en el adaptador web                     |
| 7 | Excepciones genéricas `RuntimeException`                    | Todo acaba en 500                                         | Excepciones de dominio + `@RestControllerAdvice`          |
| 8 | Tests de dominio con `@SpringBootTest`                      | Suite lenta; nadie la ejecuta                             | JUnit puro para dominio y aplicación                      |
| 9 | Mockear lo que no es tuyo (p. ej. `RestClient`)             | Tests frágiles que no prueban el contrato real            | Mockea tus puertos; prueba adaptadores con WireMock       |
| 10 | Reglas de arquitectura solo en un PowerPoint               | A los seis meses el dominio importa Spring                | ArchUnit en el CI desde el primer día                     |
| 11 | Big bang: "reescribimos todo en hexagonal"                 | Meses sin entregar, proyecto cancelado                    | Strangler Fig + golden master                             |
| 12 | Hexágono para un CRUD de tablas maestras                    | Cinco clases para guardar un nombre                       | Vertical Slice o capas simples; hexágono donde haya reglas |

---

## 4. Mapa de lo aprendido

```
                                 ┌────────────────────────────┐
                                 │  DÍA 1  Fundamentos        │
                                 │  Patrones, SOLID, DIP,     │
                                 │  Cockburn: ports&adapters  │
                                 └─────────────┬──────────────┘
                                               ▼
                                 ┌────────────────────────────┐
                                 │  DÍA 2  Anatomía           │
                                 │  Pedido rico, VOs, eventos │
                                 │  Puertos in/out            │
                                 └──────┬──────────────┬──────┘
                                        ▼              ▼
                    ┌────────────────────────┐  ┌────────────────────────┐
                    │ DÍA 3  Adaptadores in  │  │ DÍA 4  Adaptadores out │
                    │ REST, mensajería, CLI  │  │ JPA, RestClient, email │
                    │ DTOs, ControllerAdvice │  │ BeanConfiguration      │
                    └───────────┬────────────┘  └────────────┬───────────┘
                                └─────────────┬──────────────┘
                                              ▼
                                 ┌────────────────────────────┐
                                 │  DÍA 5  Testing            │
                                 │  Pirámide, fakes, slices,  │
                                 │  Testcontainers, WireMock, │
                                 │  ArchUnit                  │
                                 └─────────────┬──────────────┘
                                               ▼
                                 ┌────────────────────────────┐
                                 │  DÍA 6  Casos reales       │
                                 │  Persistencia, repos,      │
                                 │  transacciones, outbox     │
                                 └─────────────┬──────────────┘
                                               ▼
                                 ┌────────────────────────────┐
                                 │  DÍA 7  Comparativa        │
                                 │  Hexagonal / Clean / VSA   │
                                 │  Microservicios, Modulith  │
                                 └─────────────┬──────────────┘
                                               ▼
                                 ┌────────────────────────────┐
                                 │  DÍA 8  Del monolito al    │
                                 │  hexágono: migrar, evaluar,│
                                 │  decidir (ADR)             │
                                 └────────────────────────────┘
```

### Lo que se llevan, en tabla

| Día | Idea fuerza                                                    | Artefacto que lo demuestra                       |
|-----|----------------------------------------------------------------|--------------------------------------------------|
| 1   | La dependencia apunta al negocio, no a la tecnología           | Diagrama del hexágono de Cockburn                |
| 2   | El dominio protege sus reglas; los puertos definen fronteras   | `Pedido`, `Dinero`, `ConfirmarPedidoUseCase`     |
| 3   | La entrada traduce y delega                                    | `PedidoController`, `PedidoExceptionHandler`     |
| 4   | La salida implementa lo que el núcleo pide                     | `PedidoPersistenceAdapter`, `BeanConfiguration`  |
| 5   | Cada capa con su test; la arquitectura también se testea       | `PedidoTest`, WireMock, `ArquitecturaHexagonalTest` |
| 6   | Persistencia y transacciones sin contaminar el dominio         | Mapper, `UnidadDeTrabajo`, outbox                |
| 7   | No hay arquitectura universal; hay contexto                    | Tabla comparativa Hexagonal/Clean/VSA            |
| 8   | Se migra por trozos, se mide y se documenta                    | Golden master, Strangler Fig, ADR-0001           |

---

## 5. Pregunta de cierre

Si dentro de un año el negocio os dice "cambiamos de proveedor de pagos y,
de paso, las confirmaciones también llegarán por Kafka", ¿qué tocáis?

> Un adaptador nuevo de `PasarelaDePago` con su test de WireMock. Un
> `@KafkaListener` nuevo que llama a `ConfirmarPedidoUseCase`. Dos líneas en
> `BeanConfiguration` (o ninguna). **Cero** cambios en `Pedido`. **Cero**
> cambios en `ConfirmarPedidoService`. **Cero** tests de dominio modificados.
> ArchUnit en verde.

Esa es la mejor métrica de que la arquitectura funciona: **cambio de
tecnología = cambio en el borde, nunca en el centro.**

---

## 6. Lecturas recomendadas para seguir

### Imprescindibles

| Recurso                                                                                  | Por qué                                                          |
|------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| Alistair Cockburn, *Hexagonal Architecture* (2005) — https://alistair.cockburn.us/hexagonal-architecture/ | La fuente. Corto, y se entiende mejor después del curso. |
| Cockburn & Garrido de Paz, *Hexagonal Architecture Explained* (2024)                     | El libro del propio autor, con ejemplos y FAQ                    |
| Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023) — https://github.com/thombergs/buckpal | Lo más cercano a este curso en Java/Spring |
| Michael Feathers, *Working Effectively with Legacy Code* (2004)                          | Para el día a día real: costuras, caracterización, dependencias  |

### Para profundizar

| Recurso                                                                                  | Tema                                                             |
|------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| Eric Evans, *Domain-Driven Design* (2003)                                                | Agregados, lenguaje ubicuo, contextos delimitados                |
| Vaughn Vernon, *Implementing Domain-Driven Design* (2013)                                | DDD táctico aplicado                                             |
| Robert C. Martin, *Clean Architecture* (2017)                                            | Regla de dependencias, principios de componentes, métricas       |
| Neal Ford et al., *Building Evolutionary Architectures* (2ª ed., 2022)                   | Fitness functions y arquitectura que evoluciona                  |
| Martin Fowler, *Strangler Fig* — https://martinfowler.com/bliki/StranglerFigApplication.html | Migración incremental                                        |
| Martin Fowler, *Branch by Abstraction* — https://martinfowler.com/bliki/BranchByAbstraction.html | Cambiar componentes sin ramas largas                     |
| Chris Richardson, microservices.io — https://microservices.io/patterns/data/saga.html     | Sagas, outbox, microservicios                                    |
| Netflix Tech Blog, *Ready for changes with Hexagonal Architecture* (2020) — https://netflixtechblog.com/ready-for-changes-with-hexagonal-architecture-b315ec967749 | Caso real a escala |
| Michael Nygard, *Documenting Architecture Decisions* (2011) — https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions | ADRs |

### Herramientas para tener a mano

- ArchUnit — https://www.archunit.org/userguide/html/000_Index.html
- Spring Modulith — https://docs.spring.io/spring-modulith/reference/
- jMolecules (anotar conceptos arquitectónicos y DDD en el código) — https://github.com/xmolecules/jmolecules
- Testcontainers — https://java.testcontainers.org/ · WireMock — https://wiremock.org/docs/

---

## 7. Plan para el lunes

No intentéis "hexagonalizar" vuestro sistema entero la semana que viene. Un
plan realista de cuatro semanas:

| Semana | Acción                                                                      | Resultado visible                          |
|--------|-----------------------------------------------------------------------------|--------------------------------------------|
| 1      | Añadir ArchUnit con `FreezingArchRule` sobre el código actual               | La deuda no puede crecer                   |
| 2      | Golden master del endpoint más doloroso                                     | Red de seguridad                           |
| 3      | Extraer el dominio y un puerto de salida de ese endpoint                    | Primer test de dominio en milisegundos     |
| 4      | Escribir el ADR-0001 y presentarlo al equipo con los números                | Decisión compartida, no impuesta           |

---

## 8. Encuesta de calidad

Vuestra opinión mejora la siguiente edición del curso. Son cinco minutos y
se agradecen mucho:

**Encuesta:** https://diga.idexaformacion.com/test/2188

Para cualquier duda posterior sobre el curso, materiales o certificado:
**tutoria@idexaformacion.com**

---

## Ejercicio (el último)

Escribe en una tarjeta (o en un comentario del PR del proyecto final):

1. **Una cosa** que vas a aplicar en tu proyecto real en las próximas dos
   semanas.
2. **Una cosa** del curso con la que no estás de acuerdo o que no aplicarías
   en tu contexto, y por qué.

Pista: la segunda es la más valiosa. Si todo te parece bien, aún no lo has
contrastado con tu realidad.

<details><summary>Solución</summary>

No hay solución única. Ejemplos de respuestas que demuestran que el curso ha
calado:

1. "Voy a añadir `ArquitecturaHexagonalTest` con `FreezingArchRule` a nuestro
   servicio de facturación y escribir el golden master del endpoint de
   emisión."
2. "No usaría `BeanConfiguration` en nuestro equipo: somos 3 personas y el
   coste de mantenerla no compensa. Usaremos `@UseCase` y lo dejamos en un
   ADR, con la regla de ArchUnit ajustada para permitir solo esa anotación."

Ambas respuestas tienen lo mismo: **una decisión concreta, justificada y
verificable.** Eso es pensar como arquitecto.

</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture* (2005): https://alistair.cockburn.us/hexagonal-architecture/
- Cockburn & Garrido de Paz, *Hexagonal Architecture Explained* (2024).
- Robert C. Martin, *The Clean Architecture* (2012): https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- Martin Fowler, *Anemic Domain Model*: https://martinfowler.com/bliki/AnemicDomainModel.html
- Martin Fowler, *The Practical Test Pyramid*: https://martinfowler.com/articles/practical-test-pyramid.html
- Martin Fowler, *Who Needs an Architect?* (IEEE Software, 2003).
- Michael Feathers, *Working Effectively with Legacy Code* (2004).
- Neal Ford, Rebecca Parsons, Patrick Kua, *Building Evolutionary Architectures* (O'Reilly, 2017; 2ª ed. 2022).
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023): https://github.com/thombergs/buckpal

## Mantra final del curso

> *"El negocio en el centro, la tecnología en los bordes, las reglas en el
> pipeline y las decisiones por escrito. Lo demás son detalles — y los
> detalles se cambian con un adaptador."*

---

Gracias por estas ocho sesiones. Habéis trabajado mucho y se ha notado. Nos
vemos en vuestros commits.
