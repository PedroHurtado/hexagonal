# 1 — Repositorios con ISP y adaptadores con *default methods*

> *"Clients should not be forced to depend upon interfaces that they do not use."*
> — Robert C. Martin, *Interface Segregation Principle*

---

## 1. El problema en una frase

Un puerto `Repository<T, ID>` genérico con `add`, `get`, `update`, `remove`, `getAll`,
`exists`… obliga a **todos** los casos de uso a depender de **todas** las operaciones, y a
**todos** los adaptadores a implementarlas, aunque no las usen.

---

## 2. Bad: el *God repository*

```java
// ❌ Todos lo heredan, casi nadie lo usa entero
public interface Repository<T, ID> {
    void add(T entity);
    T get(ID id);
    void update(T entity);
    void remove(ID id);
    List<T> getAll();
    boolean exists(ID id);
    // ... y sigue creciendo
}
```

### ¿Qué duele?

- Un repositorio de **solo lectura** tiene que implementar `add`, `update` y `remove`,
  normalmente con `throw new UnsupportedOperationException()`. Eso es un olor: la firma
  dice que sabe hacer algo que no sabe hacer.
- El servicio de "consultar pizza" **depende** de toda la superficie de escritura. Leyendo
  su constructor no sabes si escribe o no.
- En los tests, el *fake* en memoria tiene que implementar seis métodos para probar un caso
  de uso que usa uno.
- Cada método nuevo que se añade a la interfaz **rompe a todos los implementadores**.

---

## 3. Good: cuatro operaciones atómicas (los puertos)

Una interfaz por operación. Son **puertos de salida**: solo hablan de dominio (`T`, `ID`),
no saben nada de JPA.

```java
package com.curso.pizza.application.port.out.repository;

/** Añadir. Añadir algo nuevo no requiere leer nada: por eso NO extiende IGet. */
public interface IAdd<T> {
    void add(T entity);
}
```

```java
package com.curso.pizza.application.port.out.repository;

/**
 * Leer por id. Devuelve T o lanza NotFoundException.
 * Nunca devuelve null ni Optional.
 */
public interface IGet<T, ID> {
    T get(ID id);
}
```

```java
package com.curso.pizza.application.port.out.repository;

/** Actualizar. Extiende IGet: para actualizar algo, antes tiene que existir. */
public interface IUpdate<T, ID> extends IGet<T, ID> {
    void update(T entity);
}
```

```java
package com.curso.pizza.application.port.out.repository;

/** Borrar. Extiende IGet: para borrar algo, antes tiene que existir. */
public interface IRemove<T, ID> extends IGet<T, ID> {
    void remove(ID id);
}
```

### La herencia entre interfaces expresa precondiciones

Fíjate en **quién extiende a quién**. Es una decisión de diseño, no un capricho:

```
        IAdd<T>            añadir no necesita leer  →  independiente

        IGet<T,ID>         leer es la operación base
          ▲      ▲
          │      │
   IUpdate│      │IRemove  actualizar y borrar EXIGEN que la entidad exista:
          │      │         el tipo ya lo dice
```

Un caso de uso que inyecta `IUpdatePizza` puede hacer `get` y `update`, que es justo lo
que necesita (leer el agregado, cambiarlo y guardarlo). No tiene que pedir dos puertos.

### La excepción de "no encontrado" vive en el dominio

```java
package com.curso.pizza.domain.exception;

/** Se lanza cuando un agregado no existe. El adaptador web la traduce a 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(Class<?> type, Object id) {
        super(type.getSimpleName() + " con id '" + id + "' no encontrado.");
    }
}
```

> **¿Por qué no `EntityNotFoundException`?** Porque ya existe
> `jakarta.persistence.EntityNotFoundException`. Con el mismo nombre, un *auto-import*
> despistado mete JPA en el dominio. Es el mismo criterio del día 3: **la capa de datos
> lanza y la capa web traduce** (`@RestControllerAdvice` → `ProblemDetail` 404).

---

## 4. El contrato de mapeo: `AdapterJpa`

Es el que ya tenéis en el PDF. Traduce en los dos sentidos entre el modelo de dominio y el
modelo de persistencia:

```java
package com.curso.pizza.infrastructure.adapter.out.persistence.common;

/**
 * Mapper dominio ↔ JPA.
 * @param <E> tipo de dominio
 * @param <J> tipo de persistencia (@Entity)
 */
public interface AdapterJpa<E, J> {
    J toJpa(E entity);
    E toEntity(J jpa);
}
```

> **Ojo con la palabra "adaptador".** En el PDF el mapper se llama `AdapterJpa` /
> `PizzaJpaAdapter`, y mantenemos ese nombre. Pero en hexagonal, el **adaptador de
> salida** es la clase que **implementa el puerto** (aquí `PizzaRepositoryJpa`, en el
> documento 2). `PizzaJpaAdapter` es una pieza **interna** de ese adaptador: un mapper.

---

## 5. El punto variable: `IRepositoryJpa`

Para implementar las cuatro operaciones sobre Spring Data, cada adaptador tiene que
aportar **cuatro cosas** que cambian de un agregado a otro. Las declaramos en una
interfaz:

```java
package com.curso.pizza.infrastructure.adapter.out.persistence.common;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lo que cada adaptador concreto tiene que cablear. Todo lo demás (add, get, update,
 * remove) se implementa UNA vez en las interfaces *Jpa con default methods.
 *
 * @param <T>  tipo de dominio
 * @param <ID> tipo del id
 * @param <J>  tipo de persistencia (@Entity)
 */
public interface IRepositoryJpa<T, ID, J> {

    JpaRepository<J, ID> jpa();     // save / findById / deleteById
    AdapterJpa<T, J> adapter();     // dominio ↔ JPA
    Class<T> domainType();          // para el mensaje de NotFoundException
    ID idOf(T entity);              // cómo sacar el id de un objeto de dominio
}
```

Dos detalles de Java que conviene entender:

- **`domainType()` existe por el *type erasure*.** Los genéricos se borran en tiempo de
  ejecución: dentro de un método genérico no puedes escribir `T.class`. El adaptador
  concreto lo aporta devolviendo `Pizza.class`.
- **`idOf(T)` en vez de una clase base `EntityBase`.** El `Pizza` del PDF **no hereda de
  nada** (tiene su `final UUID id` y su `getId()`), e `Ingredient` es un `record` con
  `id()`. En lugar de obligar al dominio a heredar de una clase técnica, cada adaptador
  dice cómo se obtiene el id: `Pizza::getId`, `Ingredient::id`. **El dominio no cambia
  para dar gusto a la persistencia.**

---

## 6. La pieza clave de Java: *default methods*

Desde **Java 8** una interfaz puede traer implementación (`default`). Eso nos permite
**escribir la lógica de cada operación una sola vez** y reutilizarla **por composición**,
sin una clase base `AbstractJpaRepository` de la que todo el mundo herede.

Una interfaz `*Jpa` por operación. Cada una junta `IRepositoryJpa` con el puerto
correspondiente y lo implementa como `default`:

```java
package com.curso.pizza.infrastructure.adapter.out.persistence.common;

import com.curso.pizza.application.port.out.repository.IAdd;

/** IAdd implementado sobre JPA. */
public interface IAddJpa<T, ID, J> extends IRepositoryJpa<T, ID, J>, IAdd<T> {

    @Override
    default void add(T entity) {
        jpa().save(adapter().toJpa(entity));
    }
}
```

```java
package com.curso.pizza.infrastructure.adapter.out.persistence.common;

import com.curso.pizza.application.port.out.repository.IGet;
import com.curso.pizza.domain.exception.NotFoundException;

/** IGet implementado sobre JPA: mapea a dominio o lanza. */
public interface IGetJpa<T, ID, J> extends IRepositoryJpa<T, ID, J>, IGet<T, ID> {

    @Override
    default T get(ID id) {
        J found = jpa().findById(id)
                .orElseThrow(() -> new NotFoundException(domainType(), id));
        return adapter().toEntity(found);
    }
}
```

```java
package com.curso.pizza.infrastructure.adapter.out.persistence.common;

import com.curso.pizza.application.port.out.repository.IUpdate;

/** IUpdate sobre JPA. Reutiliza el get de IGetJpa para garantizar que existe. */
public interface IUpdateJpa<T, ID, J> extends IGetJpa<T, ID, J>, IUpdate<T, ID> {

    @Override
    default void update(T entity) {
        get(idOf(entity));                       // lanza NotFoundException si no existe
        jpa().save(adapter().toJpa(entity));
    }
}
```

```java
package com.curso.pizza.infrastructure.adapter.out.persistence.common;

import com.curso.pizza.application.port.out.repository.IRemove;

/** IRemove sobre JPA. Reutiliza el get de IGetJpa para garantizar que existe. */
public interface IRemoveJpa<T, ID, J> extends IGetJpa<T, ID, J>, IRemove<T, ID> {

    @Override
    default void remove(ID id) {
        get(id);                                 // lanza NotFoundException si no existe
        jpa().deleteById(id);
    }
}
```

### Cómo encaja todo

```
   PUERTOS (application)                 ADAPTADORES (infrastructure)

   IAdd<T> ◀──────────────────────────── IAddJpa<T,ID,J>    ─┐
   IGet<T,ID> ◀───────────────────────── IGetJpa<T,ID,J>     │  todas extienden
     ▲      ▲                              ▲      ▲          ├─ IRepositoryJpa<T,ID,J>
   IUpdate  IRemove ◀──────────────── IUpdateJpa  IRemoveJpa ─┘  (jpa, adapter,
                                                                  domainType, idOf)
```

> **Detalle de Java.** Un `default` puede implementar un método declarado en **otra**
> interfaz heredada (el `add` de `IAdd`, el `get` de `IGet`…). La lógica vive en la
> interfaz, no en una clase: por eso un adaptador puede **componer** las operaciones que
> necesite (`implements IAddJpa<...>, IGetJpa<...>`) sin heredar de nadie. Java no deja
> heredar de dos clases, pero sí implementar todas las interfaces que quieras.

---

## 7. Lo que hemos ganado

```
   Adaptador de solo lectura:   implements IGetJpa<...>
   Adaptador de solo alta:      implements IAddJpa<...>
   Adaptador CRUD completo:     implements IAddJpa<...>, IUpdateJpa<...>, IRemoveJpa<...>
```

- Cada adaptador **declara en su firma** lo que sabe hacer. No hay
  `UnsupportedOperationException`.
- La implementación JPA de `add`/`get`/`update`/`remove` está escrita **una vez**.
- Un adaptador nuevo solo implementa los cuatro métodos de `IRepositoryJpa`, cada uno de
  **una línea**.

---

## 8. Trampas

- **Poner las `*Jpa` en `application`.** Importan `JpaRepository`, así que son
  infraestructura. Los puertos (`IAdd`…) **no** pueden importar nada de Spring Data.
  ArchUnit lo comprueba (día 4).
- **Devolver `Optional` en `get`.** Rompe el contrato: cada llamante acabaría haciendo su
  propio `orElseThrow` con su propio mensaje. Si un caso de uso necesita "puede que no
  exista", eso es **otra operación** (por ejemplo `IFind<T, ID>` con `Optional<T> find(ID)`).
  ISP también sirve para añadir cosas.
- **`update` y `remove` hacen un `SELECT` previo.** Es el precio de la precondición
  "debe existir". Si un agregado es muy pesado, el adaptador concreto puede sobrescribir el
  `default` con un `existsById`. Sobrescribir un `default` es igual que sobrescribir un
  método heredado.
- **Olvidar `@Transactional`.** Los *default methods* no abren transacción. La transacción
  la pone el caso de uso (el `TransactionTemplate` de `BeanConfiguration`, día 6 bloque 3),
  no el repositorio.

---

## Mantra

> *"Una interfaz por operación, una implementación por operación, y cada adaptador
> compone las que sabe hacer."*
