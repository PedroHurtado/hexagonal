# Plantillas del núcleo común

Se generan **una sola vez por proyecto**. Si ya existen, no se tocan. Sustituye los
marcadores de paquete con la tabla de `architecture-profiles.md`. Un fichero por tipo.

---

## Puertos (application) — iguales en las variantes A y B

```java
package {{pkg.portRepository}};

/** Añadir. Añadir algo nuevo no requiere leer nada: por eso NO extiende IGet. */
public interface IAdd<T> {
    void add(T entity);
}
```

```java
package {{pkg.portRepository}};

/** Leer por id. Devuelve T o lanza NotFoundException. Nunca null ni Optional. */
public interface IGet<T, ID> {
    T get(ID id);
}
```

```java
package {{pkg.portRepository}};

/** Actualizar. Extiende IGet: para actualizar algo, antes tiene que existir. */
public interface IUpdate<T, ID> extends IGet<T, ID> {
    void update(T entity);
}
```

```java
package {{pkg.portRepository}};

/** Borrar. Extiende IGet: para borrar algo, antes tiene que existir. */
public interface IRemove<T, ID> extends IGet<T, ID> {
    void remove(ID id);
}
```

## Excepción de dominio

```java
package {{pkg.domainException}};

/** Se lanza cuando un agregado no existe. El adaptador web la traduce a 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(Class<?> type, Object id) {
        super(type.getSimpleName() + " con id '" + id + "' no encontrado.");
    }
}
```

Si el dominio ya tiene excepciones "no encontrado" por agregado (`PedidoNoEncontradoException`),
pregunta si se sustituyen por esta o si conviven. No borres las existentes.

---

## Infraestructura — variante A (el id del dominio es el id JPA)

```java
package {{pkg.persistenceCommon}};

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

```java
package {{pkg.persistenceCommon}};

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
    Class<T> domainType();          // para el mensaje de NotFoundException (type erasure)
    ID idOf(T entity);              // cómo sacar el id de un objeto de dominio
}
```

```java
package {{pkg.persistenceCommon}};

import {{pkg.portRepository}}.IAdd;

/** IAdd implementado sobre JPA. */
public interface IAddJpa<T, ID, J> extends IRepositoryJpa<T, ID, J>, IAdd<T> {

    @Override
    default void add(T entity) {
        jpa().save(adapter().toJpa(entity));
    }
}
```

```java
package {{pkg.persistenceCommon}};

import {{pkg.portRepository}}.IGet;
import {{pkg.domainException}}.NotFoundException;

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
package {{pkg.persistenceCommon}};

import {{pkg.portRepository}}.IUpdate;

/** IUpdate sobre JPA. Reutiliza get para garantizar que existe. */
public interface IUpdateJpa<T, ID, J> extends IGetJpa<T, ID, J>, IUpdate<T, ID> {

    @Override
    default void update(T entity) {
        get(idOf(entity));                       // lanza NotFoundException si no existe
        jpa().save(adapter().toJpa(entity));
    }
}
```

```java
package {{pkg.persistenceCommon}};

import {{pkg.portRepository}}.IRemove;

/** IRemove sobre JPA. Reutiliza get para garantizar que existe. */
public interface IRemoveJpa<T, ID, J> extends IGetJpa<T, ID, J>, IRemove<T, ID> {

    @Override
    default void remove(ID id) {
        get(id);                                 // lanza NotFoundException si no existe
        jpa().deleteById(id);
    }
}
```

---

## Infraestructura — variante B (id value object en el dominio)

Úsala cuando los agregados tienen ids como `record PedidoId(UUID value)`. Los puertos no
cambian (hablan de `PedidoId`); la infraestructura añade un cuarto genérico `K` (id JPA)
y una conversión. `AdapterJpa` es idéntico a la variante A.

```java
package {{pkg.persistenceCommon}};

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * @param <T>  tipo de dominio
 * @param <ID> id de dominio (value object)
 * @param <J>  tipo de persistencia (@Entity)
 * @param <K>  id de persistencia (UUID, Long...)
 */
public interface IRepositoryJpa<T, ID, J, K> {

    JpaRepository<J, K> jpa();
    AdapterJpa<T, J> adapter();
    Class<T> domainType();
    ID idOf(T entity);
    K toJpaId(ID id);               // PedidoId::value
}
```

```java
package {{pkg.persistenceCommon}};

import {{pkg.portRepository}}.IAdd;

public interface IAddJpa<T, ID, J, K> extends IRepositoryJpa<T, ID, J, K>, IAdd<T> {

    @Override
    default void add(T entity) {
        jpa().save(adapter().toJpa(entity));
    }
}
```

```java
package {{pkg.persistenceCommon}};

import {{pkg.portRepository}}.IGet;
import {{pkg.domainException}}.NotFoundException;

public interface IGetJpa<T, ID, J, K> extends IRepositoryJpa<T, ID, J, K>, IGet<T, ID> {

    @Override
    default T get(ID id) {
        J found = jpa().findById(toJpaId(id))
                .orElseThrow(() -> new NotFoundException(domainType(), id));
        return adapter().toEntity(found);
    }
}
```

```java
package {{pkg.persistenceCommon}};

import {{pkg.portRepository}}.IUpdate;

public interface IUpdateJpa<T, ID, J, K> extends IGetJpa<T, ID, J, K>, IUpdate<T, ID> {

    @Override
    default void update(T entity) {
        get(idOf(entity));
        jpa().save(adapter().toJpa(entity));
    }
}
```

```java
package {{pkg.persistenceCommon}};

import {{pkg.portRepository}}.IRemove;

public interface IRemoveJpa<T, ID, J, K> extends IGetJpa<T, ID, J, K>, IRemove<T, ID> {

    @Override
    default void remove(ID id) {
        get(id);
        jpa().deleteById(toJpaId(id));
    }
}
```

En variante B, un agregado con id `UUID` simple implementa `toJpaId` como
`return id;`.

---

## Locator (solo si se eligió en el paso 3)

```java
package {{pkg.portLookup}};

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Resuelve referencias (ids) a un agregado de dominio. Misma semántica que IGet:
 * find devuelve T o lanza NotFoundException. Nunca null, nunca Optional.
 */
public interface ILookup<T> {

    T find(UUID id);

    /**
     * Si falta un id, find lanza y la operación entera falla: nunca hay resultados
     * parciales. Un adaptador puede sobrescribirlo para resolver en una sola consulta.
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

Si los ids de dominio son value objects (variante B), sustituye `UUID` por el tipo de id
crudo que lleguen en los comandos (normalmente sigue siendo `UUID`); el lookup convierte
a `PedidoId` antes de llamar al puerto.

```java
package {{pkg.locator}};

import {{pkg.portLookup}}.ILookup;

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
 * Punto único para resolver referencias entre agregados por tipo. Indexa todos los
 * ILookup por el T que resuelven (deducido del genérico). Añadir un tipo nuevo NO obliga
 * a tocar esta clase: basta con registrar otro ILookup<T> (Open/Closed).
 *
 * Java plano: sin Spring. Se cablea en el composition root.
 */
public final class Locator {

    private final Map<Class<?>, ILookup<?>> lookups;

    public Locator(List<ILookup<?>> lookups) {
        this.lookups = lookups.stream().collect(Collectors.toMap(
                Locator::targetTypeOf,
                Function.identity(),
                (a, b) -> {
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
        return (ILookup<T>) lookup;   // seguro: el índice se construyó con ese mismo tipo
    }

    /** Extrae el T de ILookup<T>. Sube por superclases para soportar proxies CGLIB. */
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
package {{pkg.locator}};

/** Se pide un tipo para el que no hay ILookup registrado: es un fallo de cableado. */
public class NoLookupRegisteredException extends RuntimeException {

    public NoLookupRegisteredException(Class<?> type) {
        super("No hay ILookup registrado para " + type.getSimpleName() + ".");
    }
}
```

Bean en el *composition root* (`{{pkg.config}}.BeanConfiguration`):

```java
/** Spring inyecta TODOS los beans que implementan ILookup, sean del tipo que sean. */
@Bean
Locator locator(List<ILookup<?>> lookups) {
    return new Locator(lookups);
}
```
