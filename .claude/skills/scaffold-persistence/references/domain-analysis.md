# Análisis del dominio

Antes de generar nada, rellena esta ficha por cada agregado. Todo lo que contiene se lee
del código; lo que no se pueda leer se convierte en pregunta (paso 3 del flujo).

## Ficha de análisis

```
Agregado:             Pizza                         (clase | record)
Paquete:              com.curso.pizza.domain.model
Id:                   getId() : UUID                → idOf = Pizza::getId
Variante del núcleo:  A (UUID)                      | B (value object: PedidoId → UUID)
Rehidratación:        constructor protected(...)    → Hydrate
Eventos:              sí, pullDomainEvents()        → test "rehidratada sin eventos"
Campos:
  name         String              → columna name VARCHAR(100) NOT NULL
  description  String              → columna description VARCHAR(500)
  url          String (url())      → columna url VARCHAR(500)
  ingredients  Set<Ingredient>     → REFERENCIA POR OBJETO a otro agregado → pregunta 2
Colecciones:          sí → JOIN FETCH en get
Referenciado desde:   —                              (Ingredient: desde Pizza → Lookup)
Operaciones:          Add, Get, Update, Remove       (deducidas de casos de uso | pregunta 1)
```

## 1. El id

| Lo que ves | `idOf` | Variante |
|---|---|---|
| `UUID getId()` / `UUID id()` | `Agg::getId` / `Agg::id` | A |
| `Long`/`String` como id | igual | A (cambia el tipo `ID` y la columna) |
| `PedidoId getId()` con `record PedidoId(UUID value)` | `Agg::getId` | **B**: `toJpaId(id) = id.value()` |

No añadas una clase base `EntityBase` al dominio para obtener el id: para eso existe
`idOf(T)`. El dominio no cambia para dar gusto a la persistencia.

## 2. Vía de rehidratación (decide la plantilla del mapper)

Revisa constructores y métodos estáticos del agregado, **en este orden**:

| # | Lo que ves | Plantilla del mapper | Notas |
|---|---|---|---|
| 1 | `record` | constructor canónico | Valida pero no emite eventos. Si el constructor compacto emitiera algo, trátalo como el caso 5. |
| 2 | Fábrica estática de reconstrucción: `reconstitute`, `rehidratar`, `restore`, `fromPersistence`... | llamar a esa fábrica | Compruébalo leyendo su cuerpo: no debe registrar eventos ni generar id. |
| 3 | Constructor `protected` que recibe todo el estado (incluido id) | `Hydrate` (subclase privada) | Es el caso del ejercicio. La clase **no** puede ser `final`. |
| 4 | Constructor `public` que recibe todo el estado (incluido id) y no emite eventos | llamar al constructor | Léelo para confirmar que no emite eventos. |
| 5 | Solo una fábrica que genera id y/o emite eventos, o clase `final` con constructor privado | **Parar** | Explica el problema y propón al usuario añadir un constructor `protected` o una fábrica `reconstitute(...)`. No lo hagas sin permiso (regla 1). |

Coste de `Hydrate` a mencionar: el objeto devuelto es una subclase; `equals` debe usar
`instanceof` (no `getClass()`). Si `equals` usa `getClass() ==`, avisa: los objetos
rehidratados no serán iguales a los originales.

Si el agregado guarda estado que su constructor no recibe (p. ej. `estado` que solo cambia
con `confirmar()`), la vía 3/4 no basta: hace falta un constructor de reconstrucción que
lo reciba. Esto es el caso 5.

## 3. Tabla de mapeo de campos

| Tipo en el dominio | En `{{Agg}}Jpa` | SQL (H2/PostgreSQL) | En el mapper |
|---|---|---|---|
| `String` | `String` + `@Column(nullable=…, length=…)` | `VARCHAR(n)` (100 nombres, 500 textos) | directo |
| `UUID` | `UUID` | `UUID` | directo |
| `BigDecimal` | `BigDecimal` + `precision=19, scale=2` | `DECIMAL(19, 2)` | directo |
| `int`/`long`/`boolean` | igual | `INT`/`BIGINT`/`BOOLEAN` | directo |
| `Instant` | `Instant` | `TIMESTAMP WITH TIME ZONE` | directo |
| `LocalDate`/`LocalDateTime` | igual | `DATE`/`TIMESTAMP` | directo |
| `enum` | mismo enum + `@Enumerated(EnumType.STRING)` | `VARCHAR(50)` | directo (el enum es de dominio, sin anotaciones: se puede usar en la entidad) |
| VO de un campo (`record Email(String value)`) | tipo primitivo (`String email`) | columna simple | `new Email(jpa.getEmail())` / `e.email().value()` |
| VO de varios campos (`Dinero(amount, currency)`) | columnas aplanadas con prefijo (`precioAmount`, `precioCurrency`) **o** `@Embeddable DineroJpa` (pregunta 4) | columnas `precio_amount`, `precio_currency` | construir el VO |
| Id de otro agregado (`ClienteId clienteId`) | `UUID clienteId` (sin asociación JPA) | `UUID NOT NULL` (FK opcional) | `new ClienteId(...)` / `.value()` |
| Colección de entidades **internas** (`List<LineaPedido>`) | `@OneToMany(mappedBy, cascade = ALL, orphanRemoval = true)` a `LineaPedidoJpa` con `@ManyToOne` al padre | tabla hija con FK `ON DELETE CASCADE` | mapear cada hija; el padre fija la relación inversa |
| Colección de VOs (`Set<Etiqueta>`) | `@ElementCollection` + `@CollectionTable` con `@Embeddable` | tabla `{{agg}}_{{campo}}` | mapear cada VO |
| **Otro agregado por objeto** (`Set<Ingredient>`) | `@ManyToMany` **sin cascade** + `@JoinTable` (o ids, pregunta 2) | tabla intermedia con PK compuesta y FK | usar el mapper del otro agregado (inyectado) |

Reglas:
- **Nunca** `cascade` hacia otro agregado: cada agregado se persiste por su propio
  repositorio.
- Las colecciones en la entidad JPA son `Set`/`List` **mutables** (`HashSet`,
  `ArrayList`); el dominio sigue exponiendo las suyas inmodificables.
- Más de una colección `List` con `JOIN FETCH` provoca `MultipleBagFetchException`: usa
  `Set`, o una consulta por colección.
- Tablas en plural y `snake_case` (`pizzas`, `pizza_ingredients`); si el proyecto ya
  tiene otra convención en sus migraciones, síguela.

## 4. Qué operaciones necesita (pregunta 1)

Si hay servicios/handlers que ya usan un repositorio del agregado, deduce:

| Uso en el servicio | Puerto |
|---|---|
| guarda algo nuevo | `IAdd{{Agg}}` |
| solo lee | `IGet{{Agg}}` |
| lee, modifica y guarda | `IUpdate{{Agg}}` (ya trae `get`) |
| borra | `IRemove{{Agg}}` (ya trae `get`) |

Sin casos de uso, propón por defecto: **catálogos / agregados referenciados** → `Add` +
`Get`; **agregados con ciclo de vida** → los cuatro.
