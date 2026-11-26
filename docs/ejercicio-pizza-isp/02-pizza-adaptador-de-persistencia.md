# 2 — Pizza: puertos por agregado, mapper con `Hydrate` y adaptador de persistencia

> *"The domain model is a model of the business, the persistence model is a model of
> the tables. Two responsibilities, two classes."*

---

## 1. Dónde estamos

En el documento 1 montamos el **núcleo reutilizable**: los puertos `IAdd`/`IGet`/
`IUpdate`/`IRemove` y las interfaces `*Jpa` con la implementación por defecto. Ahora lo
**aplicamos** al agregado `Pizza` del PDF, con sus `Ingredient` en relación N:M.

```
   1. Dominio:          Pizza, Ingredient, PizzaCreated            (PDF, sin tocar)
   2. Persistencia:     PizzaJpa, IngredientJpa                    (PDF, sin tocar)
   3. Mapper:           PizzaJpaAdapter con Hydrate                (PDF + @Component)
   4. Puertos:          IAddPizza, IGetPizza, IUpdatePizza, IRemovePizza
   5. Adaptador:        PizzaRepositoryJpa                         (compone las *Jpa)
```

---

## 2. Recordatorio del dominio (del PDF)

Lo que importa para este ejercicio es que `Pizza` tiene **dos vías de construcción**:

| Vía | Visibilidad | ¿Emite `PizzaCreated`? | Para qué |
|---|---|---|---|
| `Pizza.create(...)` | `public static` (fábrica) | **Sí** | Crear una pizza nueva desde un caso de uso |
| `protected Pizza(...)` | `protected` | **No** | Reconstruir desde la base de datos (mapper) |

Si el mapper reconstruyera con `create(...)`, **cada `SELECT` dispararía un
`PizzaCreated` falso** y además generaría un id nuevo. El constructor `protected` lo evita
de raíz.

Y por qué `PizzaJpa` es una clase **separada** de `Pizza` (día 6, bloque 1):

- `Pizza` protege sus invariantes: `final` en `id` e `ingredients`, sin setters, validación
  en el constructor, `Set` inmodificable hacia fuera. Configurar Hibernate para reconstruir
  **eso** es pelearse con el ORM.
- `PizzaJpa` es un contenedor de datos pensado para JPA: constructor vacío, `HashSet`
  mutable, anotaciones `@ManyToMany` / `@JoinTable`.
- Resultado: **ni un `import jakarta.persistence` en `domain`.**

---

## 3. El mapper: `PizzaJpaAdapter` con `Hydrate`

Es el del PDF con dos cambios: lo registramos como `@Component` y recibimos el
`IngredientJpaAdapter` por constructor en lugar de hacer `new` (así se puede sustituir en un
test).

```java
package com.curso.pizza.infrastructure.adapter.out.persistence.ingredient;

import com.curso.pizza.domain.model.Ingredient;
import com.curso.pizza.infrastructure.adapter.out.persistence.common.AdapterJpa;
import org.springframework.stereotype.Component;

@Component
public class IngredientJpaAdapter implements AdapterJpa<Ingredient, IngredientJpa> {

    @Override
    public IngredientJpa toJpa(Ingredient entity) {
        return new IngredientJpa(entity.id(), entity.name(), entity.cost());
    }

    @Override
    public Ingredient toEntity(IngredientJpa jpa) {
        // Ingredient es un record: su constructor canónico valida, pero no emite eventos
        return new Ingredient(jpa.getId(), jpa.getName(), jpa.getCost());
    }
}
```

```java
package com.curso.pizza.infrastructure.adapter.out.persistence.pizza;

import com.curso.pizza.domain.model.Ingredient;
import com.curso.pizza.domain.model.Pizza;
import com.curso.pizza.infrastructure.adapter.out.persistence.common.AdapterJpa;
import com.curso.pizza.infrastructure.adapter.out.persistence.ingredient.IngredientJpa;
import com.curso.pizza.infrastructure.adapter.out.persistence.ingredient.IngredientJpaAdapter;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class PizzaJpaAdapter implements AdapterJpa<Pizza, PizzaJpa> {

    private final IngredientJpaAdapter ingredientAdapter;

    public PizzaJpaAdapter(IngredientJpaAdapter ingredientAdapter) {
        this.ingredientAdapter = ingredientAdapter;
    }

    @Override
    public PizzaJpa toJpa(Pizza entity) {
        Set<IngredientJpa> ingredients = entity.getIngredients().stream()
                .map(ingredientAdapter::toJpa)
                .collect(Collectors.toSet());
        return new PizzaJpa(entity.getId(), entity.getName(),
                entity.getDescription(), entity.url(), ingredients);
    }

    @Override
    public Pizza toEntity(PizzaJpa jpa) {
        Set<Ingredient> ingredients = jpa.getIngredients().stream()
                .map(ingredientAdapter::toEntity)
                .collect(Collectors.toSet());
        return new Hydrate(jpa.getId(), jpa.getName(), jpa.getDescription(),
                jpa.getUrl(), ingredients);
    }

    /**
     * Rehidratación: subclase privada que abre el constructor protected de Pizza.
     * No pasa por create(), así que NO emite PizzaCreated.
     */
    private static final class Hydrate extends Pizza {
        Hydrate(UUID id, String name, String description, String url,
                Set<Ingredient> ingredients) {
            super(id, name, description, url, ingredients);
        }
    }
}
```

> **¿Por qué funciona `Hydrate` desde otro paquete?** `protected` en Java da acceso a las
> **subclases**, estén donde estén. `Hydrate` está en `infrastructure`, `Pizza` en `domain`,
> y aun así puede llamar a `super(...)`. Es el mismo truco que `TestPizza` en los tests del
> PDF.
>
> **Coste a conocer:** el objeto que devuelve el repositorio es un `Hydrate`, no un `Pizza`
> "puro". Como `equals` de `Pizza` usa `instanceof` y compara por `id`, a efectos de
> dominio son iguales. Pero `pizza.getClass() == Pizza.class` da `false`: no comparéis
> clases.

---

## 4. Los puertos por agregado (las "vistas")

Fijan los genéricos para `Pizza`. Solo hablan de dominio. Son **lo que inyectan los casos
de uso**:

```java
package com.curso.pizza.application.port.out.pizza;

import com.curso.pizza.application.port.out.repository.IAdd;
import com.curso.pizza.domain.model.Pizza;

public interface IAddPizza extends IAdd<Pizza> { }
```

```java
package com.curso.pizza.application.port.out.pizza;

import com.curso.pizza.application.port.out.repository.IGet;
import com.curso.pizza.domain.model.Pizza;
import java.util.UUID;

public interface IGetPizza extends IGet<Pizza, UUID> { }
```

```java
package com.curso.pizza.application.port.out.pizza;

import com.curso.pizza.application.port.out.repository.IUpdate;
import com.curso.pizza.domain.model.Pizza;
import java.util.UUID;

public interface IUpdatePizza extends IUpdate<Pizza, UUID> { }
```

```java
package com.curso.pizza.application.port.out.pizza;

import com.curso.pizza.application.port.out.repository.IRemove;
import com.curso.pizza.domain.model.Pizza;
import java.util.UUID;

public interface IRemovePizza extends IRemove<Pizza, UUID> { }
```

> **¿Por qué no inyectar `IAdd<Pizza>` directamente?** Por dos motivos. Con un nombre
> propio, el constructor del servicio se lee solo (`IAddPizza pizzas`). Y Spring resuelve
> mejor una interfaz concreta que un genérico cuando hay varios agregados con
> `IAdd<Algo>`.

---

## 5. El adaptador de salida: `PizzaRepositoryJpa`

Primero, el repositorio de Spring Data con una consulta que carga el agregado **completo**
(la pizza con sus ingredientes):

```java
package com.curso.pizza.infrastructure.adapter.out.persistence.pizza;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PizzaSpringDataRepository extends JpaRepository<PizzaJpa, UUID> {

    @Query("select p from PizzaJpa p left join fetch p.ingredients where p.id = :id")
    Optional<PizzaJpa> findByIdWithIngredients(@Param("id") UUID id);
}
```

Y el adaptador. **Compone** los cuatro puertos de Pizza con las `*Jpa` del núcleo, cablea
los cuatro puntos variables y **solo sobrescribe `get`** para usar el `JOIN FETCH`:

```java
package com.curso.pizza.infrastructure.adapter.out.persistence.pizza;

import com.curso.pizza.application.port.out.pizza.IAddPizza;
import com.curso.pizza.application.port.out.pizza.IGetPizza;
import com.curso.pizza.application.port.out.pizza.IRemovePizza;
import com.curso.pizza.application.port.out.pizza.IUpdatePizza;
import com.curso.pizza.domain.exception.NotFoundException;
import com.curso.pizza.domain.model.Pizza;
import com.curso.pizza.infrastructure.adapter.out.persistence.common.AdapterJpa;
import com.curso.pizza.infrastructure.adapter.out.persistence.common.IAddJpa;
import com.curso.pizza.infrastructure.adapter.out.persistence.common.IRemoveJpa;
import com.curso.pizza.infrastructure.adapter.out.persistence.common.IUpdateJpa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Adaptador de salida de Pizza. add/update/remove vienen de los default methods de las
 * interfaces *Jpa. Solo se sobrescribe get, para cargar los ingredientes con JOIN FETCH.
 */
@Component
public class PizzaRepositoryJpa implements
        IAddPizza, IGetPizza, IUpdatePizza, IRemovePizza,       // ← puertos (lo que ve la aplicación)
        IAddJpa<Pizza, UUID, PizzaJpa>,                         // ← implementación reutilizada
        IUpdateJpa<Pizza, UUID, PizzaJpa>,
        IRemoveJpa<Pizza, UUID, PizzaJpa> {

    private final PizzaSpringDataRepository jpa;
    private final PizzaJpaAdapter adapter;

    public PizzaRepositoryJpa(PizzaSpringDataRepository jpa, PizzaJpaAdapter adapter) {
        this.jpa = jpa;
        this.adapter = adapter;
    }

    // --- los cuatro puntos variables de IRepositoryJpa ---
    @Override public JpaRepository<PizzaJpa, UUID> jpa()  { return jpa; }
    @Override public AdapterJpa<Pizza, PizzaJpa> adapter() { return adapter; }
    @Override public Class<Pizza> domainType()             { return Pizza.class; }
    @Override public UUID idOf(Pizza pizza)                { return pizza.getId(); }

    // --- único método sobrescrito: carga el agregado completo ---
    @Override
    public Pizza get(UUID id) {
        PizzaJpa found = jpa.findByIdWithIngredients(id)
                .orElseThrow(() -> new NotFoundException(Pizza.class, id));
        return adapter.toEntity(found);
    }
}
```

`add`, `update` y `remove` **no aparecen**: vienen de los *default methods*. Como `update` y
`remove` llaman a `get(...)` y `get` está sobrescrito, **también usan el `JOIN FETCH`**. Es
polimorfismo normal: el `default` llama a `this.get(...)`, que resuelve a la versión de la
clase.

> **¿Por qué `IGetJpa` no aparece en el `implements`?** Porque ya llega a través de
> `IUpdateJpa` e `IRemoveJpa`, que lo extienden. Si mañana el adaptador solo supiera leer,
> pondríais `IGetPizza, IGetJpa<Pizza, UUID, PizzaJpa>` y nada más.

### Ingredient: el mismo patrón, menos operaciones

El catálogo de ingredientes en este ejercicio **solo se da de alta y se lee**. Así que su
adaptador solo compone eso:

```java
package com.curso.pizza.application.port.out.ingredient;

public interface IAddIngredient extends IAdd<Ingredient> { }
public interface IGetIngredient extends IGet<Ingredient, UUID> { }
```

```java
package com.curso.pizza.infrastructure.adapter.out.persistence.ingredient;

@Component
public class IngredientRepositoryJpa implements
        IAddIngredient, IGetIngredient,
        IAddJpa<Ingredient, UUID, IngredientJpa>,
        IGetJpa<Ingredient, UUID, IngredientJpa> {

    private final IngredientSpringDataRepository jpa;
    private final IngredientJpaAdapter adapter;

    public IngredientRepositoryJpa(IngredientSpringDataRepository jpa, IngredientJpaAdapter adapter) {
        this.jpa = jpa;
        this.adapter = adapter;
    }

    @Override public JpaRepository<IngredientJpa, UUID> jpa()        { return jpa; }
    @Override public AdapterJpa<Ingredient, IngredientJpa> adapter() { return adapter; }
    @Override public Class<Ingredient> domainType()                  { return Ingredient.class; }
    @Override public UUID idOf(Ingredient ingredient)                { return ingredient.id(); }
    // ni un método más: add y get vienen de los default methods
}
```

Es la prueba de que ISP funciona: **no hay `update` ni `remove` que lanzar con
`UnsupportedOperationException`**. Simplemente no existen.

---

## 6. Los casos de uso inyectan solo lo que usan

```java
package com.curso.pizza.application.service;

/** Consultar: solo lee. */
public class ConsultarPizzaService implements ConsultarPizzaQuery {

    private final IGetPizza pizzas;

    public ConsultarPizzaService(IGetPizza pizzas) { this.pizzas = pizzas; }

    @Override
    public Pizza consultar(UUID id) {
        return pizzas.get(id);              // lanza NotFoundException → 404
    }
}
```

```java
/** Eliminar: IRemovePizza ya trae el get (precondición "debe existir"). */
public class EliminarPizzaService implements EliminarPizzaUseCase {

    private final IRemovePizza pizzas;

    public EliminarPizzaService(IRemovePizza pizzas) { this.pizzas = pizzas; }

    @Override
    public void ejecutar(UUID id) {
        pizzas.remove(id);
    }
}
```

Y el cableado, en el *composition root*, igual que en `pedidos-hexagonal`:

```java
@Configuration
public class BeanConfiguration {

    @Bean
    ConsultarPizzaQuery consultarPizzaQuery(IGetPizza pizzas,
                                            TransactionTemplate readOnlyTransactionTemplate) {
        ConsultarPizzaQuery service = new ConsultarPizzaService(pizzas);
        return id -> readOnlyTransactionTemplate.execute(status -> service.consultar(id));
    }

    @Bean
    EliminarPizzaUseCase eliminarPizzaUseCase(IRemovePizza pizzas,
                                              TransactionTemplate transactionTemplate) {
        EliminarPizzaUseCase service = new EliminarPizzaService(pizzas);
        return id -> transactionTemplate.executeWithoutResult(status -> service.ejecutar(id));
    }
}
```

`PizzaRepositoryJpa` es **un único bean** que implementa los cuatro puertos: pedir
`IGetPizza` o `IRemovePizza` resuelve a ese mismo bean sin registrar nada más.

---

## 7. Testing: los *fakes* se vuelven diminutos

Con ISP, el *fake* en memoria implementa solo lo que el caso de uso necesita:

```java
/** Fake para ConsultarPizzaService: solo IGetPizza. */
class PizzasEnMemoria implements IGetPizza {

    private final Map<UUID, Pizza> datos = new HashMap<>();

    void guardar(Pizza pizza) { datos.put(pizza.getId(), pizza); }

    @Override
    public Pizza get(UUID id) {
        Pizza pizza = datos.get(id);
        if (pizza == null) throw new NotFoundException(Pizza.class, id);
        return pizza;
    }
}
```

```java
class ConsultarPizzaServiceTest {

    @Test
    void lanza_not_found_si_no_existe() {
        var service = new ConsultarPizzaService(new PizzasEnMemoria());
        assertThrows(NotFoundException.class, () -> service.consultar(UUID.randomUUID()));
    }
}
```

Y el adaptador se prueba de verdad contra la base de datos con `@DataJpaTest` (día 5),
importando `PizzaRepositoryJpa`, `PizzaJpaAdapter` e `IngredientJpaAdapter`:

```java
@DataJpaTest
@Import({PizzaRepositoryJpa.class, PizzaJpaAdapter.class,
         IngredientRepositoryJpa.class, IngredientJpaAdapter.class})
class PizzaRepositoryJpaTest {

    @Autowired PizzaRepositoryJpa pizzas;
    @Autowired IngredientRepositoryJpa ingredients;
    @Autowired TestEntityManager em;

    @Test
    void guarda_y_reconstruye_sin_emitir_eventos() {
        var mozzarella = new Ingredient(UUID.randomUUID(), "Mozzarella", new BigDecimal("1.20"));
        ingredients.add(mozzarella);
        var pizza = Pizza.create("Margarita", "Clásica", "/img/m.png", Set.of(mozzarella));

        pizzas.add(pizza);
        em.flush();
        em.clear();                                   // obliga a leer de la BD

        Pizza leida = pizzas.get(pizza.getId());
        assertEquals(pizza, leida);
        assertEquals(Set.of(mozzarella), leida.getIngredients());
        assertTrue(leida.pullDomainEvents().isEmpty()); // ← Hydrate no emite PizzaCreated
    }
}
```

---

## 8. Trampas

- **Reconstruir con `Pizza.create(...)` en el mapper.** Genera un id nuevo y emite un
  `PizzaCreated` por cada lectura. Usad siempre `Hydrate`.
- **Olvidar el `JOIN FETCH`.** Con `findById` a secas y `open-in-view=false`, los
  ingredientes no están cargados cuando el mapper los recorre fuera de la transacción:
  `LazyInitializationException`.
- **`save` con id asignado hace un `SELECT` antes del `INSERT`.** Spring Data ve un id no
  nulo, cree que la entidad ya existe y hace `merge`. Funciona, pero cuesta una consulta.
  Si molesta, `PizzaJpa` puede implementar `Persistable<UUID>` (día 6, bloque 1).
- **Meter `@Component` en el dominio o en los puertos.** Solo los adaptadores
  (`infrastructure`) llevan anotaciones de Spring. Los servicios se cablean en
  `BeanConfiguration`.

---

## Mantra

> *"El dominio modela el negocio, `PizzaJpa` modela la tabla, el `AdapterJpa` traduce y
> `Hydrate` devuelve la pizza a la vida sin que nadie se entere."*
