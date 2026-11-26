# Plantillas por agregado

Marcadores: `{{Agg}}` (PascalCase, `Pizza`), `{{agg}}` (minúsculas, `pizza`), `{{aggs}}`
(tabla en plural, `pizzas`), `{{ID}}` (tipo de id de dominio), `{{K}}` (id JPA, solo
variante B), `{{idOf}}` (`pizza.getId()`). Los ejemplos usan variante A; en variante B
añade `{{K}}` como cuarto genérico de cada `*Jpa` y el método `toJpaId`.

---

## 1. Entidad JPA `{{Agg}}Jpa`

Contenedor de datos para Hibernate: constructor vacío `protected`, constructor completo,
getters, colecciones mutables. **Sin lógica, sin setters salvo los que pida una relación
bidireccional.** Campos según la tabla de `domain-analysis.md`.

```java
package {{pkg.persistenceAgg}};

import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "{{aggs}}")
public class {{Agg}}Jpa {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    // ... resto de columnas

    /* Solo si referencia a otro agregado POR OBJETO y se eligió @ManyToMany (pregunta 2).
       Sin cascade: el otro agregado se persiste por su propio repositorio. */
    @ManyToMany
    @JoinTable(name = "{{agg}}_{{others}}",
            joinColumns = @JoinColumn(name = "{{agg}}_id"),
            inverseJoinColumns = @JoinColumn(name = "{{other}}_id"))
    private Set<{{Other}}Jpa> {{others}} = new HashSet<>();

    protected {{Agg}}Jpa() { }                      // exigido por JPA

    public {{Agg}}Jpa(UUID id, String name, /* ... */ Set<{{Other}}Jpa> {{others}}) {
        this.id = id;
        this.name = name;
        // ...
        this.{{others}} = new HashSet<>({{others}});
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    // ...
    public Set<{{Other}}Jpa> get{{Others}}() { return {{others}}; }
}
```

### Colección de entidades internas (`@OneToMany`)

Para hijas que pertenecen al agregado (p. ej. `LineaPedido` en `Pedido`). Si la hija no
tiene id en el dominio, usa una clave sustituta generada.

```java
// En el padre
@OneToMany(mappedBy = "{{agg}}", cascade = CascadeType.ALL, orphanRemoval = true)
private Set<{{Child}}Jpa> {{children}} = new HashSet<>();

public void add{{Child}}({{Child}}Jpa child) {    // mantiene los dos lados
    child.set{{Agg}}(this);
    {{children}}.add(child);
}
```

```java
@Entity
@Table(name = "{{agg}}_{{children}}")
public class {{Child}}Jpa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                                 // clave sustituta: el dominio no la ve

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "{{agg}}_id")
    private {{Agg}}Jpa {{agg}};

    // ... columnas de la hija

    protected {{Child}}Jpa() { }
    public {{Child}}Jpa(/* columnas */) { /* ... */ }

    void set{{Agg}}({{Agg}}Jpa {{agg}}) { this.{{agg}} = {{agg}}; }
    // getters
}
```

Al hacer `update`, el `merge` de un padre nuevo con hijas sin id borra las hijas antiguas
(`orphanRemoval`) e inserta las nuevas. Es correcto y simple; si el volumen lo hace caro,
coméntalo al usuario como mejora, no lo optimices por tu cuenta.

---

## 2. Mapper `{{Agg}}JpaAdapter`

Siempre `@Component`. Las dependencias (mappers de otros agregados o de hijas) **por
constructor**, nunca con `new`.

```java
package {{pkg.persistenceAgg}};

import {{pkg.domainModel}}.{{Agg}};
import {{pkg.persistenceCommon}}.AdapterJpa;
import org.springframework.stereotype.Component;

@Component
public class {{Agg}}JpaAdapter implements AdapterJpa<{{Agg}}, {{Agg}}Jpa> {

    private final {{Other}}JpaAdapter {{other}}Adapter;     // solo si referencia a otro agregado

    public {{Agg}}JpaAdapter({{Other}}JpaAdapter {{other}}Adapter) {
        this.{{other}}Adapter = {{other}}Adapter;
    }

    @Override
    public {{Agg}}Jpa toJpa({{Agg}} entity) {
        Set<{{Other}}Jpa> {{others}} = entity.get{{Others}}().stream()
                .map({{other}}Adapter::toJpa)
                .collect(Collectors.toSet());
        return new {{Agg}}Jpa(entity.getId(), entity.getName(), /* ... */ {{others}});
    }

    @Override
    public {{Agg}} toEntity({{Agg}}Jpa jpa) {
        Set<{{Other}}> {{others}} = jpa.get{{Others}}().stream()
                .map({{other}}Adapter::toEntity)
                .collect(Collectors.toSet());
        return /* VÍA DE REHIDRATACIÓN: ver variantes */;
    }
}
```

### Variantes de `toEntity` según la vía de rehidratación

**(1) record** — constructor canónico:
```java
return new {{Agg}}(jpa.getId(), jpa.getName(), jpa.getCost());
```

**(2) fábrica de reconstrucción**:
```java
return {{Agg}}.reconstitute(jpa.getId(), jpa.getName(), /* ... */);
```

**(3) constructor `protected` → `Hydrate`** (dentro del mapper):
```java
return new Hydrate(jpa.getId(), jpa.getName(), /* ... */ {{others}});

/**
 * Rehidratación: subclase privada que abre el constructor protected de {{Agg}}.
 * No pasa por la fábrica de creación, así que NO emite eventos ni genera id.
 */
private static final class Hydrate extends {{Agg}} {
    Hydrate(UUID id, String name, /* mismos parámetros que el constructor protected */) {
        super(id, name, /* ... */);
    }
}
```

**(4) constructor `public` sin efectos**:
```java
return new {{Agg}}(jpa.getId(), jpa.getName(), /* ... */);
```

Value objects: se construyen aquí (`new Email(jpa.getEmail())`) y se descomponen en
`toJpa` (`entity.getEmail().value()`).

---

## 3. Puertos por agregado

Uno por operación elegida. En `{{pkg.portAgg}}`. Sin cuerpo: solo fijan los genéricos.

```java
package {{pkg.portAgg}};

import {{pkg.portRepository}}.IAdd;
import {{pkg.domainModel}}.{{Agg}};

public interface IAdd{{Agg}} extends IAdd<{{Agg}}> { }
```

```java
package {{pkg.portAgg}};

import {{pkg.portRepository}}.IGet;
import {{pkg.domainModel}}.{{Agg}};
import java.util.UUID;                       // o el VO de id

public interface IGet{{Agg}} extends IGet<{{Agg}}, {{ID}}> { }
```

```java
public interface IUpdate{{Agg}} extends IUpdate<{{Agg}}, {{ID}}> { }   // mismo patrón
public interface IRemove{{Agg}} extends IRemove<{{Agg}}, {{ID}}> { }
```

---

## 4. Repositorio Spring Data

```java
package {{pkg.persistenceAgg}};

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface {{Agg}}SpringDataRepository extends JpaRepository<{{Agg}}Jpa, UUID> {

    /* Solo si el agregado tiene colecciones: carga el agregado COMPLETO en una consulta.
       Una colección por JOIN FETCH si son List (MultipleBagFetchException). */
    @Query("select a from {{Agg}}Jpa a left join fetch a.{{collection}} where a.id = :id")
    Optional<{{Agg}}Jpa> findByIdWithDetails(@Param("id") UUID id);
}
```

---

## 5. Adaptador de salida `{{Agg}}RepositoryJpa`

Implementa los puertos elegidos y compone las `*Jpa` correspondientes:

| Operaciones elegidas | `implements` (además de los puertos) |
|---|---|
| Get | `IGetJpa<...>` |
| Add | `IAddJpa<...>` |
| Add + Get | `IAddJpa<...>, IGetJpa<...>` |
| Get + Update | `IUpdateJpa<...>` (trae `IGetJpa`) |
| Add + Get + Update + Remove | `IAddJpa<...>, IUpdateJpa<...>, IRemoveJpa<...>` |

Regla: no repitas `IGetJpa` si ya llega por `IUpdateJpa`/`IRemoveJpa`. Si solo se eligió
`Remove` o `Update`, igualmente se expone `get` (lo exige la precondición).

```java
package {{pkg.persistenceAgg}};

import {{pkg.portAgg}}.IAdd{{Agg}};
import {{pkg.portAgg}}.IGet{{Agg}};
import {{pkg.portAgg}}.IRemove{{Agg}};
import {{pkg.portAgg}}.IUpdate{{Agg}};
import {{pkg.domainException}}.NotFoundException;
import {{pkg.domainModel}}.{{Agg}};
import {{pkg.persistenceCommon}}.AdapterJpa;
import {{pkg.persistenceCommon}}.IAddJpa;
import {{pkg.persistenceCommon}}.IRemoveJpa;
import {{pkg.persistenceCommon}}.IUpdateJpa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Adaptador de salida de {{Agg}}. Las operaciones vienen de los default methods de las
 * interfaces *Jpa. Solo se sobrescribe get, para cargar el agregado con JOIN FETCH.
 */
@Component
public class {{Agg}}RepositoryJpa implements
        IAdd{{Agg}}, IGet{{Agg}}, IUpdate{{Agg}}, IRemove{{Agg}},   // ← puertos
        IAddJpa<{{Agg}}, UUID, {{Agg}}Jpa>,                          // ← implementación reutilizada
        IUpdateJpa<{{Agg}}, UUID, {{Agg}}Jpa>,
        IRemoveJpa<{{Agg}}, UUID, {{Agg}}Jpa> {

    private final {{Agg}}SpringDataRepository jpa;
    private final {{Agg}}JpaAdapter adapter;

    public {{Agg}}RepositoryJpa({{Agg}}SpringDataRepository jpa, {{Agg}}JpaAdapter adapter) {
        this.jpa = jpa;
        this.adapter = adapter;
    }

    // --- los puntos variables de IRepositoryJpa ---
    @Override public JpaRepository<{{Agg}}Jpa, UUID> jpa()   { return jpa; }
    @Override public AdapterJpa<{{Agg}}, {{Agg}}Jpa> adapter() { return adapter; }
    @Override public Class<{{Agg}}> domainType()              { return {{Agg}}.class; }
    @Override public UUID idOf({{Agg}} {{agg}})               { return {{idOf}}; }
    // variante B: @Override public UUID toJpaId({{ID}} id)   { return id.value(); }

    // --- SOLO si hay JOIN FETCH; si no, este método no existe ---
    @Override
    public {{Agg}} get(UUID id) {
        {{Agg}}Jpa found = jpa.findByIdWithDetails(id)
                .orElseThrow(() -> new NotFoundException({{Agg}}.class, id));
        return adapter.toEntity(found);
    }
}
```

`update` y `remove` llaman a `this.get(...)`, así que también usan el `JOIN FETCH`.

Criterio de aceptación: el adaptador **solo** contiene constructor, los métodos de
`IRepositoryJpa` y, como mucho, `get`. Cualquier otro método es una señal de que algo no
encaja con el patrón: coméntalo al usuario en vez de añadirlo en silencio.

---

## 6. Lookup (si el agregado es referenciado y se eligió Locator)

```java
package {{pkg.persistenceAgg}};

import {{pkg.portAgg}}.IGet{{Agg}};
import {{pkg.portLookup}}.ILookup;
import {{pkg.domainModel}}.{{Agg}};
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Clase con nombre (nunca lambda): el Locator lee el genérico por reflexión. */
@Component
public class {{Agg}}Lookup implements ILookup<{{Agg}}> {

    private final IGet{{Agg}} {{aggs}};

    public {{Agg}}Lookup(IGet{{Agg}} {{aggs}}) {
        this.{{aggs}} = {{aggs}};
    }

    @Override
    public {{Agg}} find(UUID id) {
        return {{aggs}}.get(id);           // "leer o lanzar" ya vive en IGetJpa
        // variante B: return {{aggs}}.get(new {{ID}}(id));
    }
}
```

---

## 7. Migración

`V{{n}}__crear_tablas_{{agg}}.sql`. Primero las tablas referenciadas, luego las que
referencian, luego las intermedias.

```sql
CREATE TABLE {{aggs}} (
    id   UUID         PRIMARY KEY,
    name VARCHAR(100) NOT NULL
    -- ... resto de columnas según la tabla de tipos
);

-- @ManyToMany a otro agregado
CREATE TABLE {{agg}}_{{others}} (
    {{agg}}_id   UUID NOT NULL REFERENCES {{aggs}}(id) ON DELETE CASCADE,
    {{other}}_id UUID NOT NULL REFERENCES {{others}}(id),
    PRIMARY KEY ({{agg}}_id, {{other}}_id)
);

-- @OneToMany a hijas internas
CREATE TABLE {{agg}}_{{children}} (
    id        BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    {{agg}}_id UUID NOT NULL REFERENCES {{aggs}}(id) ON DELETE CASCADE
    -- ... columnas de la hija
);
```

Si el proyecto usa `spring.jpa.hibernate.ddl-auto=validate`, los tipos y longitudes de la
migración deben coincidir con las anotaciones `@Column`.
