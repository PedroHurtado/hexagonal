---
name: scaffold-persistence
description: Genera la capa de acceso a datos (persistencia JPA) de un proyecto Java/Spring Boot a partir de un dominio ya aislado, en arquitectura hexagonal, Clean Architecture o vertical slice. Crea puertos ISP (IAdd/IGet/IUpdate/IRemove), adaptadores reutilizables con default methods (IAddJpa/IGetJpa...), entidades JPA, mappers dominio↔JPA con rehidratación sin eventos (Hydrate), repositorios Spring Data, Locator para resolver ids entre agregados, migración Flyway, cableado y tests. Úsalo cuando el usuario pida "genera la persistencia de X", "crea el repositorio/adaptador JPA de este agregado", "scaffold persistence", "añade el acceso a datos para Pedido", o quiera aplicar el patrón del ejercicio Pizza ISP a otro dominio.
---

# Scaffold de persistencia para un dominio aislado

Premisa: **la lógica vive en el dominio y el dominio no sabe nada de JPA**. Con esa premisa,
la persistencia es casi mecánica: se deriva de las clases del dominio y de unas pocas
decisiones de diseño. Este skill automatiza la parte mecánica con plantillas fijas y
**pregunta** la parte que no lo es.

La especificación de referencia es `docs/ejercicio-pizza-isp/` (documentos 01–03). Si existe
en el repositorio, es la fuente de verdad del estilo; las plantillas de este skill la
reproducen.

## Reglas que no se negocian

1. **No modificar el dominio** sin permiso explícito. Si el dominio no permite rehidratar
   sin efectos (ver paso 2), se para y se pregunta.
2. **Ni un `import` de `org.springframework..` ni de `jakarta.persistence..`** en `domain`
   ni en `application`. Las interfaces `*Jpa` y los `@Component` van en `infrastructure`.
3. **Ningún adaptador lanza `UnsupportedOperationException`.** Si una operación no se
   necesita, el adaptador no compone su interfaz.
4. **`get` devuelve T o lanza `NotFoundException`.** Nunca `null` ni `Optional`.
5. **Rehidratar nunca emite eventos ni genera ids nuevos.** Prohibido usar la fábrica de
   creación (`create`, `nuevo`...) en el mapper.
6. **Las plantillas se copian, no se reinterpretan.** Solo se sustituyen los marcadores
   `{{...}}`. La consistencia entre agregados es el objetivo.
7. Los servicios de aplicación son Java plano; la transacción la pone el *composition root*
   (`TransactionTemplate`), no el repositorio.

## Flujo de trabajo

Sigue los pasos en orden. Lleva una lista de tareas visible para el usuario.

### Paso 1 — Reconocer el proyecto

Averigua, leyendo el código (no preguntes lo que puedes leer):

- **Paquete base** (`com.curso.pizza`) y **perfil de arquitectura**: hexagonal, clean o
  vertical slice. Lee [references/architecture-profiles.md](references/architecture-profiles.md)
  para la tabla de paquetes de cada perfil y cómo detectarlo.
- Versión de Java (≥ 17 requerida: las plantillas usan *pattern matching* de `instanceof`
  y `record`) y de Spring Boot (3.x: `jakarta.*`).
- Dependencias del `pom.xml`/`build.gradle`: `spring-boot-starter-data-jpa`, Flyway,
  ArchUnit, H2. Si falta alguna necesaria, propón añadirla.
- Migraciones: carpeta `db/migration` y **siguiente número de versión** libre; si no hay
  Flyway, `schema.sql`.
- Si **el núcleo común ya existe** (`IAdd`, `IRepositoryJpa`, `Locator`...). Si existe, no
  se regenera: se reutiliza tal cual y se adapta el resto a él.
- Si existe `BeanConfiguration` (o equivalente) y cómo cablea los servicios.

Si el proyecto es vertical slice **sin modelo de dominio** (handlers con SQL directo, como
`pedidos-vertical-slice`), este skill no aplica: díselo al usuario y para.

### Paso 2 — Analizar los agregados

Para cada clase que el usuario indique (o, si no indica, cada raíz de agregado en
`domain/model`), rellena la ficha de análisis de
[references/domain-analysis.md](references/domain-analysis.md):

- Cómo se obtiene el id (`getId()`, `id()`) y **de qué tipo** es (`UUID` o value object
  como `PedidoId`). Esto decide la variante del núcleo (A o B).
- **Vía de rehidratación**: constructor `protected` → `Hydrate`; `record` → constructor
  canónico; fábrica de reconstrucción (`reconstitute`, `rehidratar`) → esa fábrica;
  solo fábrica que emite eventos → **parar y preguntar**.
- Cada campo con su mapeo (tabla de tipos de la referencia): columnas, value objects,
  enums, colecciones internas, referencias a otros agregados.
- Si emite eventos de dominio y cómo se leen (`pullDomainEvents()`...), para el test.

Muestra la ficha al usuario de forma resumida antes de generar.

### Paso 3 — Decisiones que no son mecánicas

Pregunta con `AskUserQuestion` **solo** lo que no se deduce del código. Agrupa en una
llamada (máximo 4 preguntas) y propón la opción recomendada primero:

1. **Operaciones por agregado**: qué puertos necesita cada uno (`Add`, `Get`, `Update`,
   `Remove`). Si hay casos de uso ya escritos, dedúcelo de ellos y pide solo confirmación.
2. **Referencias a otros agregados por objeto** (p. ej. `Pizza` → `Set<Ingredient>`):
   `@ManyToMany` con tabla intermedia (lo que hace el ejercicio) o columna de id sin
   asociación JPA (recomendación DDD si el referenciado tiene ciclo de vida propio).
3. **Locator**: si hay agregados referenciados desde otros, ¿generar `ILookup` + `Locator`
   (recomendado con ≥ 2 tipos referenciados o muchos servicios) o inyectar el `IGet...`
   directamente (opción B del documento 03)?
4. **Value objects con varios campos**: columnas aplanadas en la entidad JPA (recomendado)
   o `@Embeddable` en infraestructura.

No preguntes por cosas con una respuesta convencional (enums → `STRING`, `BigDecimal` →
`DECIMAL(19,2)`...): aplícalas y menciónalas en el resumen final.

### Paso 4 — Generar el núcleo común (una vez por proyecto)

Si no existe, genera desde [references/core-templates.md](references/core-templates.md):

- Puertos: `IAdd`, `IGet`, `IUpdate`, `IRemove` (jerarquía: `IUpdate`/`IRemove` extienden
  `IGet`; `IAdd` no).
- `NotFoundException` en `domain/exception` (no `EntityNotFoundException`: choca con JPA).
- Infraestructura: `AdapterJpa`, `IRepositoryJpa`, `IAddJpa`, `IGetJpa`, `IUpdateJpa`,
  `IRemoveJpa`.
- Si se eligió Locator: `ILookup`, `Locator`, `NoLookupRegisteredException`.

**Variante A** (ids `UUID` u otro tipo simple en el dominio) o **variante B** (ids value
object, p. ej. `PedidoId`): la elección afecta solo a `IRepositoryJpa` y a dos *default
methods*; está en la misma referencia. Un proyecto usa **una sola** variante.

### Paso 5 — Generar cada agregado

Para cada agregado, desde [references/aggregate-templates.md](references/aggregate-templates.md):

1. Entidad JPA `{{Agg}}Jpa` (+ entidades hijas si tiene colecciones internas).
2. Mapper `{{Agg}}JpaAdapter implements AdapterJpa` con la vía de rehidratación del paso 2.
   Si referencia a otro agregado, recibe el mapper de ese agregado **por constructor**.
3. Puertos por agregado: uno por operación elegida (`IAdd{{Agg}}`, `IGet{{Agg}}`...).
4. `{{Agg}}SpringDataRepository`, con consulta `JOIN FETCH` si el agregado tiene
   colecciones.
5. Adaptador `{{Agg}}RepositoryJpa`: implementa los puertos y **compone** solo las `*Jpa`
   de esas operaciones. Sobrescribe `get` únicamente si hay `JOIN FETCH`.
6. Si es referenciado y se eligió Locator: `{{Agg}}Lookup implements ILookup<{{Agg}}>`
   (clase con nombre, nunca lambda).

### Paso 6 — Migración y cableado

- Migración `V{{n}}__crear_tablas_{{agg}}.sql` con las tablas, la tabla intermedia y las
  FK (ver tabla de tipos SQL en `domain-analysis.md`). No edites migraciones ya existentes.
- En `BeanConfiguration`: añade el bean `Locator locator(List<ILookup<?>> lookups)` si
  procede. Los adaptadores son `@Component` y no necesitan bean manual.
- **No generes casos de uso ni controladores**: son lógica de negocio/API, fuera del
  alcance. Si el usuario los quiere, muestra cómo inyectar el puerto mínimo y para.

### Paso 7 — Tests

Desde [references/tests-templates.md](references/tests-templates.md), por agregado:

- `{{Agg}}RepositoryJpaTest` (`@DataJpaTest`): guardar → `flush` → `clear` → releer →
  igual al original **y sin eventos**; `get` de id inexistente → `NotFoundException`;
  `update`/`remove` si existen.
- Si hay Locator: `LocatorTest` (unitario, sin Spring).
- Regla ArchUnit de capas (si el proyecto ya tiene una, compruébala en vez de duplicarla).

### Paso 8 — Verificar y entregar

1. Ejecuta `mvn -q verify` (o `./mvnw`, o `gradle check`). Si falla, corrige y repite.
   No declares terminado con la build en rojo; si no puedes ejecutarla, dilo.
2. Comprueba la lista de [references/tests-templates.md#checklist-final](references/tests-templates.md#checklist-final).
3. Resume al usuario: ficheros creados por capa, decisiones tomadas (las preguntadas y las
   convencionales), y lo que queda fuera (casos de uso, web, ampliaciones como
   `IGetMany`/N+1 o `Persistable`).
