# 4 — Enunciado del ejercicio

> **Duración orientativa:** 2–3 horas.
> **Material:** documentos [01](01-repositorios-isp-y-default-methods.md),
> [02](02-pizza-adaptador-de-persistencia.md) y [03](03-locator-referencias-entre-agregados.md),
> y el PDF [`Pizza-dominio-jpa.md.pdf`](../../Pizza-dominio-jpa.md.pdf).

---

## Objetivo

Construir un servicio **`pizza-hexagonal`** (Java 17, Spring Boot 3.3, H2) que gestione un
catálogo de pizzas e ingredientes aplicando:

- **ISP** en los puertos de salida (`IAdd`, `IGet`, `IUpdate`, `IRemove`).
- **Adaptadores JPA reutilizables** con *default methods* (`IAddJpa`, `IGetJpa`,
  `IUpdateJpa`, `IRemoveJpa`).
- **Mappers** dominio ↔ JPA (`AdapterJpa`) con **rehidratación sin eventos** (`Hydrate`).
- Un **Locator** para resolver ids de ingredientes a objetos `Ingredient`.

Partís del dominio y de las entidades JPA del PDF, **sin modificarlos**.

---

## Punto de partida

Cread el proyecto en `code/pizza-hexagonal` con la misma estructura de paquetes que
`pedidos-hexagonal` (podéis copiar su `pom.xml` y cambiar el `artifactId`):

```
com.curso.pizza
├── domain/model, domain/exception
├── application/port/in, application/port/out/{repository,pizza,ingredient,lookup},
│   application/lookup, application/service
└── infrastructure/adapter/in/web, infrastructure/adapter/out/persistence/{common,pizza,ingredient},
    infrastructure/config
```

Migración Flyway de partida (`V1__crear_tablas_pizza.sql`):

```sql
CREATE TABLE ingredients (
    id   UUID           PRIMARY KEY,
    name VARCHAR(100)   NOT NULL,
    cost DECIMAL(10, 2) NOT NULL
);

CREATE TABLE pizzas (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    url         VARCHAR(500)
);

CREATE TABLE pizza_ingredients (
    pizza_id      UUID NOT NULL REFERENCES pizzas(id) ON DELETE CASCADE,
    ingredient_id UUID NOT NULL REFERENCES ingredients(id),
    PRIMARY KEY (pizza_id, ingredient_id)
);
```

---

## Parte 1 — El núcleo ISP (documento 1)

1. Crea los puertos genéricos `IAdd<T>`, `IGet<T, ID>`, `IUpdate<T, ID>` e
   `IRemove<T, ID>` en `application/port/out/repository`. Respeta la jerarquía:
   `IUpdate` e `IRemove` extienden `IGet`; `IAdd` no.
2. Crea `NotFoundException` en `domain/exception`.
3. Crea `AdapterJpa<E, J>` e `IRepositoryJpa<T, ID, J>` (con `jpa()`, `adapter()`,
   `domainType()` e `idOf(T)`) en `infrastructure/adapter/out/persistence/common`.
4. Implementa `IAddJpa`, `IGetJpa`, `IUpdateJpa` e `IRemoveJpa` con **default methods**.

## Parte 2 — Adaptadores de persistencia (documento 2)

1. Copia `Ingredient`, `Pizza`, `PizzaCreated`, `IngredientJpa`, `PizzaJpa`,
   `IngredientJpaAdapter` y `PizzaJpaAdapter` del PDF a sus paquetes. Haz los dos mappers
   `@Component` e inyecta `IngredientJpaAdapter` en `PizzaJpaAdapter`.
2. Crea los puertos por agregado:
   - `IAddIngredient`, `IGetIngredient`.
   - `IAddPizza`, `IGetPizza`, `IUpdatePizza`, `IRemovePizza`.
3. Crea `IngredientSpringDataRepository` y `PizzaSpringDataRepository` (con el
   `JOIN FETCH`).
4. Crea los adaptadores:
   - `IngredientRepositoryJpa`: **solo** alta y lectura.
   - `PizzaRepositoryJpa`: CRUD completo, sobrescribiendo **solo** `get`.

## Parte 3 — El Locator (documento 3)

1. Crea `ILookup<T>` (con `find` y `findAll` por defecto) en `application/port/out/lookup`.
2. Crea `Locator` y `NoLookupRegisteredException` en `application/lookup`, **sin ninguna
   dependencia de Spring**.
3. Crea `IngredientLookup` en la infraestructura, delegando en `IGetIngredient`.
4. Registra el `Locator` en `BeanConfiguration` recibiendo `List<ILookup<?>>`.

## Parte 4 — Casos de uso y API REST

Cada servicio inyecta **solo** el puerto que usa:

| Caso de uso | Puerto(s) de salida | Endpoint |
|---|---|---|
| `CrearIngredienteUseCase` | `IAddIngredient` | `POST /ingredients` → `201` + `Location` |
| `CrearPizzaUseCase` | `IAddPizza`, `Locator` | `POST /pizzas` → `201` + `Location` |
| `ConsultarPizzaQuery` | `IGetPizza` | `GET /pizzas/{id}` → `200` / `404` |
| `AnadirIngredienteUseCase` | `IUpdatePizza`, `Locator` | `POST /pizzas/{id}/ingredients/{ingredientId}` → `204` / `404` / `409` |
| `QuitarIngredienteUseCase` | `IUpdatePizza`, `Locator` | `DELETE /pizzas/{id}/ingredients/{ingredientId}` → `204` / `404` / `409` |
| `EliminarPizzaUseCase` | `IRemovePizza` | `DELETE /pizzas/{id}` → `204` / `404` |

- Los servicios son **Java plano** y se cablean en `BeanConfiguration` envueltos en un
  `TransactionTemplate` (igual que en `pedidos-hexagonal`).
- `NotFoundException` → `404` y `IllegalStateException` del dominio (ingrediente
  duplicado o ausente) → `409`, ambas en un `@RestControllerAdvice` con `ProblemDetail`.

## Parte 5 — Tests

Como mínimo:

1. **`LocatorTest`** (unitario, sin Spring): resuelve por tipo, falla entero si falta un id
   y lanza `NoLookupRegisteredException` si no hay lookup.
2. **`CrearPizzaServiceTest`** (unitario, con *fakes*): el *fake* del repositorio
   implementa **solo** `IAddPizza`. Comprueba que la pizza creada emite `PizzaCreated`.
3. **`PizzaRepositoryJpaTest`** (`@DataJpaTest`): guarda, limpia el `EntityManager`,
   relee y comprueba que **la pizza rehidratada no tiene eventos**.
4. **`IngredientRepositoryJpaTest`** (`@DataJpaTest`): `get` de un id inexistente lanza
   `NotFoundException`.
5. **ArchUnit**: ninguna clase de `domain` ni de `application` depende de
   `org.springframework..` ni de `jakarta.persistence..`.

---

## Criterios de aceptación

- [ ] `mvn verify` pasa en verde.
- [ ] En `domain` no hay ni un `import` de Spring ni de JPA. El dominio del PDF **no se ha
      modificado**.
- [ ] Ningún adaptador lanza `UnsupportedOperationException`.
- [ ] `IngredientRepositoryJpa` **no** tiene métodos `update` ni `remove`.
- [ ] `PizzaRepositoryJpa` solo sobrescribe `get` y los cuatro métodos de `IRepositoryJpa`.
- [ ] Ningún servicio inyecta un puerto con operaciones que no usa.
- [ ] Ningún servicio conoce `IngredientLookup`: solo `Locator`.
- [ ] Leer una pizza de la base de datos **no** emite `PizzaCreated`.
- [ ] Crear una pizza con un id de ingrediente inexistente devuelve `404` y **no** guarda
      nada.

---

## Ampliaciones (opcionales)

1. **Sin N+1.** Sobrescribe `findAll` en `IngredientLookup` para resolver todos los ids en
   una sola consulta. Pista: necesitarás un puerto nuevo (`IGetManyIngredient` con
   `Set<Ingredient> getAll(Collection<UUID> ids)`) y su `IGetManyJpa` con *default method*
   que use `findAllById` y lance si `ids.size()` no coincide con lo encontrado.
2. **Un segundo tipo referenciable.** Añade un agregado `Salsa` (una por pizza) y su
   `SalsaLookup`. Comprueba con `git diff` que **no has tocado** el `Locator`.
3. **Publicar el evento.** Tras `pizzas.add(pizza)`, el servicio hace
   `pizza.pullDomainEvents()` y los envía por un puerto `PublicadorDeEventos` (día 6,
   bloque 4).
4. **`IFind<T, ID>`.** Un puerto con `Optional<T> find(ID id)` para el caso "puede que no
   exista", con su `IFindJpa`. ¿En qué caso de uso tendría sentido?

---

## Preguntas para entregar (respuesta corta)

1. ¿Por qué `IUpdate` e `IRemove` extienden `IGet` y `IAdd` no?
2. ¿Por qué las interfaces `*Jpa` están en `infrastructure` y `IAdd`/`IGet` en
   `application`? ¿Qué regla rompería moverlas?
3. ¿Qué ganamos con *default methods* frente a una clase abstracta
   `AbstractJpaRepository<T, ID, J>`? Pista: ¿podría `IngredientRepositoryJpa` "no tener"
   `update` si heredara de esa clase?
4. ¿Qué pasaría si `PizzaJpaAdapter.toEntity` usara `Pizza.create(...)` en lugar de
   `Hydrate`? Di **dos** problemas.
5. ¿Por qué `IRepositoryJpa` necesita `domainType()` e `idOf(T)`? ¿Qué alternativa hay a
   `idOf` y por qué no la usamos?
6. ¿En qué se diferencia nuestro `Locator` del *Service Locator* que Mark Seemann considera
   un antipatrón? ¿Qué inconveniente comparten?
7. ¿Cuándo **no** usarías el Locator e inyectarías directamente `IGetIngredient`?
8. ¿Por qué un lookup no puede registrarse como lambda?
9. DDD (Vaughn Vernon) recomienda que un agregado **referencie a otros por id**, no por
   objeto. `Pizza` guarda `Set<Ingredient>`. ¿Es un problema aquí? ¿Qué cambiaría si
   `Ingredient` tuviera su propio ciclo de vida (precio que cambia, stock)?

---

## Referencias

- Robert C. Martin, *The Interface Segregation Principle* (1996) y *Clean Architecture*
  (2017), cap. 10.
- Bertrand Meyer, *Object-Oriented Software Construction* (1988): Open/Closed.
- Mark Seemann, Steven van Deursen, *Dependency Injection Principles, Practices, and
  Patterns* (2019), cap. 5: *Service Locator*.
- Vaughn Vernon, *Effective Aggregate Design*, parte II: referenciar agregados por identidad.
- Java Language Specification, *default methods* (JLS §9.4).
- Spring Data JPA, *Persisting entities* y `Persistable`:
  https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html

---

## Mantra

> *"Pide solo lo que usas, escribe cada cosa una vez y deja que el sistema crezca
> añadiendo, no modificando."*
