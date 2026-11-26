# 3 — El Locator: resolver referencias entre agregados

> *"Software entities should be open for extension, but closed for modification."*
> — Bertrand Meyer, *Open/Closed Principle*

---

## 1. El problema en una frase

Cuando un caso de uso crea o modifica un agregado que **referencia a otros**, el comando no
trae objetos de dominio, **trae ids**. Alguien tiene que convertir esos ids en agregados
reales y comprobar que existen **antes** de llamar al dominio.

```
   CrearPizzaCommand                           Agregado de dominio
   ─────────────────                           ───────────────────
   name          = "Cuatro quesos"              Pizza
   ingredientIds = [ g1, g2, g3, g4 ]   ──▶    ingredients = { Mozzarella, Gorgonzola, ... }
                        │                                ▲
                        │      ¿quién traduce            │
                        └────── ids → Ingredient? ───────┘
```

`Pizza.create(...)` recibe un `Set<Ingredient>`, no un `Set<UUID>`. Y eso está bien: el
dominio trabaja con **objetos válidos**. Un `Ingredient` ya ha pasado sus validaciones
(nombre no vacío, coste no negativo). Un `UUID` no garantiza nada, ni siquiera que exista.

---

## 2. Las tres opciones que tenemos

### Opción A — Que el dominio reciba ids y los resuelva él ❌

```java
// ❌ El dominio necesitaría un repositorio para construirse
public static Pizza create(String name, ..., Set<UUID> ingredientIds, IGetIngredient repo) { ... }
```

Rompe la regla principal del hexágono: **el dominio no llama a puertos de salida**. El
agregado dejaría de poder probarse sin un *fake* de repositorio y acabaría haciendo I/O
dentro de una invariante.

### Opción B — Cada servicio inyecta el puerto de lectura de cada agregado referenciado ⚠️

```java
public class CrearPizzaService {
    private final IAddPizza pizzas;
    private final IGetIngredient ingredients;      // ← para resolver los ids

    public UUID ejecutar(CrearPizzaCommand cmd) {
        Set<Ingredient> resolved = new HashSet<>();
        for (UUID id : cmd.ingredientIds()) {
            resolved.add(ingredients.get(id));      // ← el mismo bucle en cada servicio
        }
        ...
    }
}
```

Con **un** agregado referenciado es perfectamente válido (y es lo más simple). El problema
llega cuando el modelo crece:

- `CrearPizzaService` y `AnadirIngredienteService` resuelven `Ingredient`.
- Mañana `CrearMenuService` resuelve `Pizza` **y** `Bebida`.
- Pasado, `CrearPedidoService` resuelve `Pizza`, `Cliente`, `Cupon`...

Cada servicio repite el bucle "ids → entidades", decide por su cuenta qué hacer si falta
uno y su constructor crece con un puerto por cada tipo referenciado.

### Opción C — Un único mecanismo de resolución por tipo: el **Locator** ✅

Un objeto al que le pides "**dame los `Ingredient` con estos ids**" y que sabe a quién
preguntar según el **tipo**:

```java
Set<Ingredient> ingredients = locator.findAll(Ingredient.class, cmd.ingredientIds());
```

El servicio no sabe **quién** resuelve `Ingredient`. Solo sabe que el Locator lo hace, con
una semántica única: **o devuelve todos, o lanza `NotFoundException`**.

---

## 3. Las piezas

```
                 application                                  infrastructure
   ┌────────────────────────────────────────────┐   ┌──────────────────────────────────┐
   │  port/out/lookup                           │   │                                  │
   │    ILookup<T>   ◀──────────────────────────┼───┤  IngredientLookup                │
   │      T find(UUID)                          │   │    implements ILookup<Ingredient>│
   │      default Set<T> findAll(ids)           │   │    (delega en IGetIngredient)    │
   │                                            │   │                                  │
   │  lookup                                    │   │  (mañana) PizzaLookup,           │
   │    Locator                                 │   │           BebidaLookup...        │
   │      índice: Class<?> ──▶ ILookup<?>       │   │                                  │
   │      find(Class<T>, UUID)                  │   └──────────────────────────────────┘
   │      findAll(Class<T>, Collection<UUID>)   │
   │                                            │
   │  service                                   │
   │    CrearPizzaService ──usa──▶ Locator      │
   └────────────────────────────────────────────┘
```

| Pieza | Capa | Responsabilidad |
|---|---|---|
| `ILookup<T>` | `application/port/out/lookup` | Puerto: resolver **un** tipo de agregado por id |
| `Locator` | `application/lookup` | Punto único: indexa todos los `ILookup` **por el tipo que resuelven** y despacha |
| `IngredientLookup` | `infrastructure` | Adaptador: resuelve `Ingredient` apoyándose en `IGetIngredient` |

---

## 4. El puerto `ILookup<T>`

```java
package com.curso.pizza.application.port.out.lookup;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Resuelve referencias (ids) a un agregado de dominio. Cada agregado que pueda ser
 * referenciado por otro aporta su propia implementación.
 *
 * Misma semántica que IGet: find devuelve T o lanza NotFoundException. Nunca null,
 * nunca Optional.
 */
public interface ILookup<T> {

    T find(UUID id);

    /**
     * Resuelve una colección. Si falta un id, find lanza y la operación entera falla:
     * nunca hay resultados parciales.
     *
     * Implementación por defecto: un find por id. Un adaptador puede sobrescribirla
     * para resolver en una sola consulta (findAllById).
     */
    default Set<T> findAll(Collection<UUID> ids) {
        Set<T> result = new HashSet<>();
        for (UUID id : ids) {
            result.add(find(id));
        }
        return result;
    }
}
```

Fíjate en dos cosas:

- La interfaz **no declara qué tipo resuelve** (no hay un `Class<T> type()`). El Locator lo
  **deduce del genérico** por reflexión (sección 5). El implementador escribe
  `implements ILookup<Ingredient>` y no tiene que repetir `Ingredient` en ningún otro sitio.
- `findAll` es otro **default method**, como en el documento 1: la versión simple viene
  gratis y quien necesite rendimiento la sobrescribe.

---

## 5. El `Locator`

```java
package com.curso.pizza.application.lookup;

import com.curso.pizza.application.port.out.lookup.ILookup;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Punto único para resolver referencias entre agregados por tipo.
 *
 * Recibe TODOS los ILookup registrados y los indexa por el T que resuelve cada uno
 * (deducido del genérico). Añadir un tipo nuevo NO obliga a tocar esta clase:
 * basta con registrar otro ILookup<T>. Eso es Open/Closed.
 *
 * Java plano: sin Spring. Se cablea en BeanConfiguration.
 */
public final class Locator {

    private final Map<Class<?>, ILookup<?>> lookups;

    public Locator(List<ILookup<?>> lookups) {
        this.lookups = lookups.stream().collect(Collectors.toMap(
                Locator::targetTypeOf,
                Function.identity(),
                (a, b) -> {                       // dos lookups para el mismo tipo: fallo al arrancar
                    throw new IllegalStateException("Lookup duplicado: "
                            + a.getClass().getName() + " y " + b.getClass().getName());
                }));
    }

    /** Resuelve una referencia. Lanza NotFoundException si el id no existe. */
    public <T> T find(Class<T> type, UUID id) {
        return lookupFor(type).find(id);
    }

    /** Resuelve varias. Si falta alguna, lanza: nunca devuelve resultados parciales. */
    public <T> Set<T> findAll(Class<T> type, Collection<UUID> ids) {
        return lookupFor(type).findAll(ids);
    }

    @SuppressWarnings("unchecked")
    private <T> ILookup<T> lookupFor(Class<T> type) {
        ILookup<?> lookup = lookups.get(type);
        if (lookup == null) {
            throw new NoLookupRegisteredException(type);
        }
        return (ILookup<T>) lookup;   // cast seguro: el índice se construyó con ese mismo tipo
    }

    /**
     * Extrae el T de una clase que implementa ILookup<T>. Sube por las superclases para
     * soportar los proxies de Spring (CGLIB crea una subclase del bean).
     */
    private static Class<?> targetTypeOf(ILookup<?> lookup) {
        for (Class<?> c = lookup.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Type iface : c.getGenericInterfaces()) {
                if (iface instanceof ParameterizedType pt
                        && pt.getRawType() == ILookup.class
                        && pt.getActualTypeArguments()[0] instanceof Class<?> type) {
                    return type;
                }
            }
        }
        throw new IllegalStateException(lookup.getClass().getName()
                + " debe implementar ILookup<T> con un tipo concreto (no una lambda).");
    }
}
```

```java
package com.curso.pizza.application.lookup;

/** Se pide un tipo para el que no hay ILookup registrado: es un fallo de cableado. */
public class NoLookupRegisteredException extends RuntimeException {

    public NoLookupRegisteredException(Class<?> type) {
        super("No hay ILookup registrado para " + type.getSimpleName() + ".");
    }
}
```

### Por qué se pasa `Ingredient.class` y no basta con el genérico

Por el ***type erasure***: en tiempo de ejecución `findAll(ids)` no sabe qué `T` le han
pedido, porque los genéricos se borran. Pasar el `Class<T>` es la forma idiomática de
llevar el tipo hasta el runtime. A cambio, **el llamante nunca ve un cast**:

```java
Set<Ingredient> ingredients = locator.findAll(Ingredient.class, ids);   // tipado fuerte
```

El único cast (`(ILookup<T>) lookup`) está **encerrado dentro del Locator**, y es seguro
porque el índice se construyó con ese mismo tipo como clave.

---

## 6. Un adaptador: `IngredientLookup`

`Ingredient` puede ser referenciado desde `Pizza`, así que registra su lookup. Reutiliza
el puerto de lectura del documento 2, que ya lanza `NotFoundException`:

```java
package com.curso.pizza.infrastructure.adapter.out.persistence.ingredient;

import com.curso.pizza.application.port.out.ingredient.IGetIngredient;
import com.curso.pizza.application.port.out.lookup.ILookup;
import com.curso.pizza.domain.model.Ingredient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class IngredientLookup implements ILookup<Ingredient> {

    private final IGetIngredient ingredients;

    public IngredientLookup(IGetIngredient ingredients) {
        this.ingredients = ingredients;
    }

    @Override
    public Ingredient find(UUID id) {
        return ingredients.get(id);        // "leer o lanzar" ya vive en IGetJpa
    }
}
```

No hay lógica nueva: el lookup **reenvía**. La regla "si no existe, lanza" se escribió una
vez, en `IGetJpa`, y aquí se aprovecha tal cual.

---

## 7. Uso en los casos de uso

### Crear pizza

```java
package com.curso.pizza.application.service;

public class CrearPizzaService implements CrearPizzaUseCase {

    private final IAddPizza pizzas;
    private final Locator locator;

    public CrearPizzaService(IAddPizza pizzas, Locator locator) {
        this.pizzas = pizzas;
        this.locator = locator;
    }

    @Override
    public UUID ejecutar(CrearPizzaCommand cmd) {
        // 1. ids → agregados de dominio (NotFoundException → 404 si falta alguno)
        Set<Ingredient> ingredients = locator.findAll(Ingredient.class, cmd.ingredientIds());

        // 2. el dominio recibe objetos válidos; la FÁBRICA emite PizzaCreated
        Pizza pizza = Pizza.create(cmd.name(), cmd.description(), cmd.url(), ingredients);

        // 3. persistir por el puerto mínimo
        pizzas.add(pizza);
        return pizza.getId();
    }
}
```

```java
public record CrearPizzaCommand(String name, String description, String url,
                                Set<UUID> ingredientIds) { }
```

### Añadir un ingrediente a una pizza existente

```java
public class AnadirIngredienteService implements AnadirIngredienteUseCase {

    private final IUpdatePizza pizzas;     // trae get + update
    private final Locator locator;

    public AnadirIngredienteService(IUpdatePizza pizzas, Locator locator) {
        this.pizzas = pizzas;
        this.locator = locator;
    }

    @Override
    public void ejecutar(UUID pizzaId, UUID ingredientId) {
        Pizza pizza = pizzas.get(pizzaId);
        Ingredient ingredient = locator.find(Ingredient.class, ingredientId);
        pizza.addIngredient(ingredient);   // invariante: no duplicados (lo decide el dominio)
        pizzas.update(pizza);
    }
}
```

Ninguno de los dos servicios conoce `IngredientLookup`. Si mañana `Pizza` referencia
también una `Salsa`, el servicio hará `locator.find(Salsa.class, ...)` y bastará con
**añadir** un `SalsaLookup`.

---

## 8. Cableado en el *composition root*

```java
@Configuration
public class BeanConfiguration {

    /** Spring inyecta TODOS los beans que implementan ILookup, sean del tipo que sean. */
    @Bean
    Locator locator(List<ILookup<?>> lookups) {
        return new Locator(lookups);
    }

    @Bean
    CrearPizzaUseCase crearPizzaUseCase(IAddPizza pizzas, Locator locator,
                                        TransactionTemplate transactionTemplate) {
        CrearPizzaUseCase service = new CrearPizzaService(pizzas, locator);
        return cmd -> transactionTemplate.execute(status -> service.ejecutar(cmd));
    }
}
```

`List<ILookup<?>>` es la **inyección de colección** de Spring: el contenedor recoge todos
los beans compatibles con `ILookup<?>` y los entrega juntos. Por eso añadir un lookup es
**un `@Component` más**, sin tocar esta clase.

> **Importante:** la transacción envuelve **todo** el caso de uso. Las lecturas del Locator
> y el `add` se hacen en la misma transacción. Si entre la lectura y el `INSERT` alguien
> borra un ingrediente, la FK de `pizza_ingredients` lo detecta y el caso de uso falla
> entero.

---

## 9. Por qué esto es Open/Closed

```
   AÑADIR un nuevo tipo referenciable (p. ej. Salsa)
   ─────────────────────────────────────────────────
   1. Crear  SalsaLookup implements ILookup<Salsa>
   2. Anotarlo con @Component

   Lo que NO se toca:
   ✗ Locator                   (cerrado a modificación)
   ✗ BeanConfiguration         (la List<ILookup<?>> lo recoge sola)
   ✗ los demás lookups
   ✗ los servicios que no usan Salsa
```

---

## 10. ★ ¿Por qué un Locator? ¿No era el *Service Locator* un antipatrón?

Buena pregunta, y la tenéis que saber responder. Mark Seemann (*Dependency Injection
Principles, Practices, and Patterns*) describe el **Service Locator** como antipatrón:

```java
// ❌ Service Locator clásico
public class CrearPizzaService {
    public UUID ejecutar(CrearPizzaCommand cmd) {
        IAddPizza pizzas = ServiceLocator.get(IAddPizza.class);   // ← dependencia oculta
        Notificador n    = ServiceLocator.get(Notificador.class); // ← y otra
        ...
    }
}
```

Sus problemas son tres: **esconde las dependencias** (el constructor no dice nada),
**falla en runtime** en lugar de al arrancar, y suele ser **estático y global**, así que no
hay forma limpia de sustituirlo en un test.

Nuestro `Locator` se parece en el nombre pero **no es eso**:

| | Service Locator (antipatrón) | Nuestro `Locator` |
|---|---|---|
| ¿Qué localiza? | **Servicios** (colaboradores: repositorios, clientes HTTP...) | **Datos de dominio**: agregados por id |
| ¿Cómo llega al servicio? | Estático / global, sin declarar | **Inyectado por constructor**, visible en la firma |
| ¿Qué API ofrece? | `get(Class<?>)` de cualquier cosa | Una sola: `find`/`findAll` de agregados por `UUID` |
| ¿Sustituible en tests? | Difícil (estado global) | Trivial: `new Locator(List.of(new FakeIngredientLookup()))` |
| ¿Quién decide las dependencias? | El código, en mitad de un método | El *composition root* (`BeanConfiguration`) |

En el fondo, `Locator` es un **puerto de lectura polimórfico por tipo**: un `IGet` que, en
lugar de servir a un solo agregado, despacha al adecuado según el `Class<T>`.

### Lo que sí cuesta (sed honestos)

- **El constructor ya no dice qué tipos resuelve el servicio.** `CrearPizzaService` recibe
  un `Locator`, no un `IGetIngredient`. Para saber que usa `Ingredient` hay que leer el
  método. Es el mismo reproche que se le hace al Service Locator, en versión pequeña.
- **Si falta un lookup, falla en runtime** (`NoLookupRegisteredException`), no al arrancar.
  Se mitiga con un test por caso de uso que ejecute el servicio contra el contexto de Spring
  real, o con un test que compruebe que existen los lookups de los tipos que se usan.

### Cuándo usarlo y cuándo no

- **Un solo tipo referenciado y uno o dos servicios** → inyectad `IGetIngredient` y listo
  (opción B). El Locator sería sobreingeniería.
- **Varios agregados se referencian entre sí** y muchos servicios repiten el bucle
  "ids → entidades" → el Locator **unifica** la semántica (todo o nada, `NotFoundException`)
  y deja crecer el modelo sin tocar lo existente.

---

## 11. Testing

El `Locator` es Java plano, así que se prueba sin Spring:

```java
class LocatorTest {

    static final Ingredient MOZZARELLA =
            new Ingredient(UUID.randomUUID(), "Mozzarella", new BigDecimal("1.20"));

    /** Clase con nombre (no lambda): el Locator necesita leer el genérico. */
    static class FakeIngredientLookup implements ILookup<Ingredient> {
        @Override public Ingredient find(UUID id) {
            if (MOZZARELLA.id().equals(id)) return MOZZARELLA;
            throw new NotFoundException(Ingredient.class, id);
        }
    }

    Locator locator = new Locator(List.of(new FakeIngredientLookup()));

    @Test
    void resuelve_por_tipo() {
        assertEquals(MOZZARELLA, locator.find(Ingredient.class, MOZZARELLA.id()));
    }

    @Test
    void falla_entero_si_falta_un_id() {
        var ids = List.of(MOZZARELLA.id(), UUID.randomUUID());
        assertThrows(NotFoundException.class, () -> locator.findAll(Ingredient.class, ids));
    }

    @Test
    void falla_si_no_hay_lookup_para_el_tipo() {
        assertThrows(NoLookupRegisteredException.class,
                () -> locator.find(Pizza.class, UUID.randomUUID()));
    }
}
```

---

## 12. Trampas

- **Registrar un lookup como lambda.** `ILookup<Ingredient> l = id -> ...;` compila, pero
  la clase de una lambda **no conserva el genérico**: el Locator no puede saber qué tipo
  resuelve y falla al construirse. Usad siempre una clase con nombre.
- **N+1 consultas.** El `findAll` por defecto hace un `SELECT` por id. Con 4 ingredientes no
  importa; con 400 sí. Sobrescribid `findAll` en el lookup para resolver en una consulta.
  Es la ampliación opcional del ejercicio.
- **Devolver solo los que existen.** Si `findAll` devuelve 3 de 4 sin avisar, se crea una
  pizza sin uno de sus ingredientes y nadie se entera. La regla es **todo o nada**.
- **Usar el Locator dentro del dominio.** El Locator es de `application`. `Pizza` sigue
  recibiendo `Ingredient`, nunca un `Locator`.

---

## Mantra

> *"El comando trae ids, el dominio quiere objetos. El Locator traduce, comprueba que
> existen y crece sin que nadie lo toque."*
