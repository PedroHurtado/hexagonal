# Ejercicio — Pizza: repositorios con ISP, adaptadores con *default methods* y Locator

> Material complementario del **día 6** (persistencia y patrones de acceso a datos).
> **Requisitos:** días 2 (puertos), 4 (adaptadores de salida y mappers) y 6 (bloques 1–2).
> **Dominio de partida:** el de [`Pizza-dominio-jpa.md.pdf`](../../Pizza-dominio-jpa.md.pdf)
> (`Pizza`, `Ingredient`, `PizzaCreated`, `PizzaJpa`, `IngredientJpa`, `AdapterJpa`,
> `PizzaJpaAdapter`, `IngredientJpaAdapter`).

---

## Qué vamos a construir

Hasta ahora cada agregado tenía **su** puerto de repositorio hecho a mano
(`PedidoRepository` con `guardar` y `buscarPorId`) y **su** adaptador JPA escrito de
arriba abajo. Funciona, pero en cuanto hay cinco agregados se repite el mismo código cinco
veces y los puertos tienden a engordar.

En este ejercicio montamos la capa de persistencia de `Pizza` e `Ingredient` con tres ideas:

1. **ISP en los puertos de salida.** Una interfaz por operación (`IAdd`, `IGet`, `IUpdate`,
   `IRemove`). Cada caso de uso inyecta **solo** la operación que usa.
2. **Adaptadores reutilizables con *default methods*.** La implementación JPA de cada
   operación se escribe **una vez** en una interfaz (`IAddJpa`, `IGetJpa`…) y cada
   adaptador concreto la **compone**, sin herencia de clases.
3. **Locator.** Un único punto que traduce **ids → agregados de dominio** cuando un
   comando trae referencias a otros agregados (crear una pizza con una lista de ids de
   ingredientes). Abierto a extensión y cerrado a modificación.

---

## Mapa de lectura

| # | Documento | Qué aprendes |
|---|---|---|
| 1 | [01-repositorios-isp-y-default-methods.md](01-repositorios-isp-y-default-methods.md) | `IAdd`/`IGet`/`IUpdate`/`IRemove`, `AdapterJpa` y las interfaces `*Jpa` con *default methods* |
| 2 | [02-pizza-adaptador-de-persistencia.md](02-pizza-adaptador-de-persistencia.md) | Puertos por agregado (`IAddPizza`…), el mapper con `Hydrate` y el adaptador `PizzaRepositoryJpa` |
| 3 | [03-locator-referencias-entre-agregados.md](03-locator-referencias-entre-agregados.md) | ★ **El Locator**: qué problema resuelve, por qué así y en qué se diferencia del *Service Locator* antipatrón |
| 4 | [04-enunciado-del-ejercicio.md](04-enunciado-del-ejercicio.md) | Lo que tenéis que entregar, criterios de aceptación y preguntas |

---

## Dónde vive cada pieza en el hexágono

```
com.curso.pizza
├── domain
│   ├── model          Pizza, Ingredient, PizzaCreated            (del PDF, sin cambios)
│   └── exception      NotFoundException
├── application
│   ├── port/out
│   │   ├── repository IAdd, IGet, IUpdate, IRemove               (genéricos, sin JPA)
│   │   ├── pizza      IAddPizza, IGetPizza, IUpdatePizza, IRemovePizza
│   │   ├── ingredient IAddIngredient, IGetIngredient
│   │   └── lookup     ILookup<T>
│   ├── lookup         Locator                                    (Java plano)
│   └── service        CrearPizzaService, AnadirIngredienteService, ...
└── infrastructure
    ├── adapter/out/persistence
    │   ├── common     AdapterJpa, IRepositoryJpa, IAddJpa, IGetJpa, IUpdateJpa, IRemoveJpa
    │   ├── pizza      PizzaJpa, PizzaJpaAdapter, PizzaSpringDataRepository, PizzaRepositoryJpa
    │   └── ingredient IngredientJpa, IngredientJpaAdapter, IngredientSpringDataRepository,
    │                  IngredientRepositoryJpa, IngredientLookup
    └── config         BeanConfiguration
```

La regla de dependencias de siempre:

```
   infrastructure ──────▶ application ──────▶ domain
   (JPA, Spring)          (puertos ISP,        (Pizza, Ingredient,
                           Locator, servicios)  sin Spring ni JPA)
```

- Las interfaces **genéricas** `IAdd`/`IGet`… son **puertos**: hablan de dominio y viven
  en `application`.
- Las interfaces `*Jpa` con *default methods* conocen `JpaRepository`, así que son
  **infraestructura**.
- El `Locator` es Java plano (sin Spring) y lo usan los servicios, así que vive en
  `application`.

---

## Mantra

> *"Un puerto que declara exactamente lo que sabe hacer no miente. Un adaptador que
> escribe cada operación una sola vez no se repite. Un Locator que crece sin tocarse
> no se rompe."*
