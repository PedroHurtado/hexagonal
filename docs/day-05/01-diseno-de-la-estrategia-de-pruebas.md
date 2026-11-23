# Día 5 — Bloque 1: Diseño de la estrategia de pruebas

> *"The test pyramid is a way of thinking about how different kinds of automated tests should be used to create a balanced portfolio."* — Martin Fowler

---

## 1. El problema en una frase

Sin estrategia, los equipos acaban con **una suite lenta, frágil y que no protege lo importante**: 400 tests `@SpringBootTest` que tardan 12 minutos, fallan aleatoriamente y, aun así, dejan pasar el bug de negocio porque nadie probó la regla de "no se cancela un pedido enviado".

La estrategia de pruebas responde a tres preguntas **antes** de escribir el primer test:

1. **¿Qué** pruebo en cada capa?
2. **¿Con qué** lo pruebo (herramienta, dobles, infraestructura)?
3. **¿Cuánto** de cada tipo (proporción y presupuesto de tiempo)?

---

## 2. Bad: la suite "todo es integración"

```java
@SpringBootTest
@AutoConfigureMockMvc
class PedidoTodoEnUnoTest {

    @Autowired MockMvc mockMvc;
    @Autowired PedidoJpaRepository jpaRepository;

    @Test
    void noSePuedeConfirmarUnPedidoVacio() throws Exception {
        // Para probar UNA regla de dominio levantamos: contexto Spring completo,
        // DataSource, Hibernate, Flyway, MVC, Jackson, seguridad, Kafka...
        String id = mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clienteId\":\"" + UUID.randomUUID() + "\"}"))
                .andReturn().getResponse().getHeader("Location")
                .replace("/api/pedidos/", "");

        mockMvc.perform(post("/api/pedidos/{id}/confirmacion", id))
                .andExpect(status().isUnprocessableEntity());
    }
}
```

### ¿Qué duele?

- **Lentitud**: 8–15 segundos de arranque para comprobar un `if` del dominio. Multiplica por 300 reglas.
- **Diagnóstico pobre**: si falla, ¿ha sido el dominio, el mapeo JSON, el `@ControllerAdvice`, Flyway o la transacción?
- **Fragilidad**: cambias el nombre de un campo del DTO y se rompen 80 tests que hablaban de reglas de negocio.
- **Feedback tardío**: nadie ejecuta la suite en local; se descubre todo en CI.
- **Contradicción con el hexágono**: hemos aislado el dominio precisamente para **no** necesitar nada de esto para probarlo.

---

## 3. Los modelos de referencia

### 3.1 La pirámide de tests (Mike Cohn, 2009; popularizada por Martin Fowler)

Mike Cohn la presentó en *Succeeding with Agile* (2009). Ham Vocke la actualizó en el artículo *The Practical Test Pyramid* publicado en martinfowler.com (2018).

```
                 ▲   lento, caro, frágil, alta confianza "de sistema"
                ╱ ╲
               ╱E2E╲            pocos
              ╱─────╲
             ╱ Integ. ╲         algunos
            ╱──────────╲
           ╱  Unitarios  ╲      muchos
          ╱────────────────╲
                 ▼   rápido, barato, estable, feedback preciso
```

Dos ideas que sobreviven a cualquier moda:

1. **Escribe tests de distinta granularidad.**
2. **Cuanto más alto en la pirámide, menos tests deberías tener.**

### 3.2 El panal (*honeycomb*) de Spotify (2018)

André Schaffer, en el blog de ingeniería de Spotify (*Testing of Microservices*, 2018), argumenta que en **microservicios pequeños** la mayor parte del riesgo está en la **integración** (con la BD, con otros servicios), no en una lógica interna que a menudo es trivial.

```
            ┌───────────────────┐
            │ Integrated (pocos)│   tests que dependen de OTROS servicios desplegados
            └───────────────────┘
        ┌───────────────────────────┐
        │                           │
        │   Integration (muchos)    │   el servicio entero contra su BD/colas reales
        │                           │
        └───────────────────────────┘
            ┌───────────────────┐
            │Implementation det.│   unitarios de detalle (pocos)
            └───────────────────┘
```

### 3.3 El trofeo de testing (Kent C. Dodds, 2018)

Nacido en el mundo frontend: base de **análisis estático** (tipos, linters), pocos unitarios, **muchos de integración**, pocos E2E. *"Write tests. Not too many. Mostly integration."* (frase original de Guillermo Rauch).

### 3.4 ¿Y en hexagonal, cuál?

En cristiano: **depende de dónde viva la complejidad**.

| Si tu servicio...                                        | Forma que encaja | Por qué                                         |
|----------------------------------------------------------|------------------|-------------------------------------------------|
| Tiene un **dominio rico** (reglas, estados, cálculos)    | Pirámide         | El riesgo está en el dominio, y se prueba barato|
| Es un **CRUD fino** o un "pegamento" entre sistemas      | Panal / trofeo   | El riesgo está en la integración                |
| Es un hexágono "de verdad" con casos de uso no triviales | **Pirámide con cintura gorda**: muchos unitarios de dominio, bastantes de aplicación con fakes, tests de contrato de cada adaptador, pocos E2E | Lo mejor de ambos |

El hexágono tiene una ventaja enorme: como el dominio y la aplicación **no dependen de infraestructura**, la base de la pirámide es **realmente barata**. Y como cada adaptador implementa un puerto, sus tests de integración son **pequeños y focalizados** (un adaptador, una tecnología), no "todo el sistema".

---

## 4. Good: el mapa de tests del hexágono

```
                    ┌────────────────────────────────────────────────────────────┐
  E2E               │  @SpringBootTest(RANDOM_PORT) + Testcontainers + RestClient │  pocos
  (sistema)         │  ArchUnit (reglas de dependencia sobre TODO el código)      │
                    └────────────────────────────────────────────────────────────┘

       ┌─────────────────────┐                              ┌─────────────────────────┐
       │ ADAPTADOR ENTRADA   │                              │ ADAPTADOR SALIDA        │
       │ PedidoController    │                              │ PedidoPersistenceAdapter│
       │                     │                              │ StripeLikePasarela...   │
       │ @WebMvcTest+MockMvc │                              │ @DataJpaTest+Testcont.  │
       │ caso de uso = mock  │                              │ WireMock / Kafka TC     │
       └─────────┬───────────┘                              └────────────▲────────────┘
                 │ puerto entrada                       puerto salida    │
                 ▼                                                       │
       ┌──────────────────────────────────────────────────────────────────┴───┐
       │  APLICACIÓN  (ConfirmarPedidoService, CancelarPedidoService...)      │
       │  JUnit + fakes en memoria (InMemoryPedidoRepository) o Mockito       │
       │  ┌────────────────────────────────────────────────────────────────┐  │
       │  │ DOMINIO  (Pedido, LineaPedido, Dinero, Cantidad...)            │  │
       │  │ JUnit 5 + AssertJ puro. Sin dobles. Sin Spring. Milisegundos.  │  │
       │  └────────────────────────────────────────────────────────────────┘  │
       └──────────────────────────────────────────────────────────────────────┘

       ════════ Tests de CONTRATO de puerto: la misma batería contra ════════
                 InMemoryPedidoRepository  y  PedidoPersistenceAdapter
```

Regla de oro: **el doble de test se pone en el puerto**, nunca dentro del dominio ni a mitad de un adaptador.

| Qué pruebo                    | Qué es real                  | Qué se dobla                      | Herramienta                          |
|-------------------------------|------------------------------|-----------------------------------|--------------------------------------|
| `Pedido` (agregado)           | Todo                         | Nada                              | JUnit 5 + AssertJ                    |
| `ConfirmarPedidoService`      | Servicio + dominio           | Puertos de salida                 | Fakes en memoria / Mockito           |
| `PedidoController`            | Controller, Jackson, validación, advice | Puerto de entrada (caso de uso) | `@WebMvcTest` + `@MockBean`      |
| `PedidoPersistenceAdapter`    | Adaptador, mapper, JPA, PostgreSQL | Nada                        | `@DataJpaTest` + Testcontainers      |
| `StripeLikePasarelaAdapter`   | Adaptador, `RestClient`, JSON | El proveedor HTTP externo        | WireMock                             |
| Flujo completo                | Todo nuestro sistema         | Solo terceros externos            | `@SpringBootTest` + Testcontainers   |
| Reglas de arquitectura        | El bytecode                  | —                                 | ArchUnit                             |

---

## 5. Tests sociables vs solitarios

Terminología de Jay Fields (*Working Effectively with Unit Tests*), recogida por Fowler en su bliki (*UnitTest*):

- **Test solitario** (*solitary*): la unidad bajo prueba se aísla de **todos** sus colaboradores con dobles. Estilo "mockista" o "escuela de Londres".
- **Test sociable** (*sociable*): la unidad usa sus colaboradores **reales** siempre que sean rápidos y deterministas. Estilo "clasicista" o "escuela de Detroit/Chicago".

```
  SOLITARIO                                   SOCIABLE
  ┌─────────────────────────┐                 ┌─────────────────────────┐
  │ ConfirmarPedidoService  │                 │ ConfirmarPedidoService  │
  └──┬──────────┬───────────┘                 └──┬──────────┬───────────┘
     │          │                                │          │
     ▼          ▼                                ▼          ▼
  ┌──────┐  ┌────────────┐                    ┌──────┐  ┌──────────────────┐
  │ mock │  │ mock Pedido│  ◄── ¡no!          │Pedido│  │InMemoryPedidoRepo│
  │ repo │  └────────────┘                    │ REAL │  │     (fake)       │
  └──────┘                                    └──────┘  └──────────────────┘
```

**Nuestra postura en el curso:**

1. **Nunca se mockea el dominio.** `Pedido`, `Dinero`, `Cantidad` son objetos de valor y entidades puras: se usan reales. Mockear un value object es una señal de alarma.
2. **Se doblan los puertos de salida**, porque detrás hay I/O (BD, red, reloj).
3. Preferimos **fakes** para los puertos con estado (repositorios) y **Mockito** para verificar interacciones con efectos (notificar, publicar, cobrar).

> Trampa: "unitario = una clase". Falso. *Unit* es una **unidad de comportamiento**. Un test de `ConfirmarPedidoService` con `Pedido` real y un repositorio fake sigue siendo unitario: rápido, aislado del I/O, determinista.

---

## 6. Dobles de test (Gerard Meszaros, *xUnit Test Patterns*, 2007)

Meszaros acuñó **Test Double** como término paraguas (por analogía con el *stunt double* del cine) y definió cinco tipos:

| Tipo      | Qué hace                                                            | Ejemplo en Pedidos                                         |
|-----------|---------------------------------------------------------------------|------------------------------------------------------------|
| **Dummy** | Se pasa para rellenar un parámetro; nunca se usa                    | Un `NotificadorPedidos` que no se invoca en ese camino     |
| **Stub**  | Devuelve respuestas **preprogramadas**                              | `CatalogoProductos` que siempre devuelve stock 10          |
| **Spy**   | Stub que además **registra** cómo se le llamó, para verificar luego | `PublicadorDeEventos` que guarda los eventos en una lista  |
| **Mock**  | Viene **programado con expectativas**; verifica la interacción      | `verify(pasarela).cobrar(id, importe)` con Mockito         |
| **Fake**  | Implementación **funcional pero simplificada**, no apta para prod   | `InMemoryPedidoRepository` con un `HashMap`                |

### 6.1 Los cinco, en código

```java
// DUMMY: está ahí porque el constructor lo pide
NotificadorPedidos dummy = pedido -> { throw new AssertionError("no debería llamarse"); };

// STUB: respuestas enlatadas
class CatalogoConStockInfinito implements CatalogoProductos {
    @Override
    public Optional<Producto> buscar(ProductoId id) {
        return Optional.of(new Producto(id, "Teclado", Dinero.euros("49.90"), 1_000));
    }
}

// SPY: registra lo que le pasa
class PublicadorEspia implements PublicadorDeEventos {
    final List<EventoDeDominio> publicados = new ArrayList<>();
    @Override
    public void publicar(EventoDeDominio evento) { publicados.add(evento); }
}

// MOCK: expectativas y verificación (Mockito)
PasarelaDePago pasarela = mock(PasarelaDePago.class);
// ... ejercitar ...
verify(pasarela).cobrar(pedidoId, Dinero.euros("99.80"));

// FAKE: funciona de verdad, pero en memoria
class InMemoryPedidoRepository implements PedidoRepository {
    private final Map<PedidoId, Pedido> almacen = new HashMap<>();
    // guardar / buscarPorId / buscarPorCliente funcionales
}
```

> Ojo con el lenguaje: Mockito llama `mock` a todo, pero un `mock(...)` con `when(...).thenReturn(...)` y sin `verify` es, en rigor, un **stub**. Y `Mockito.spy(objetoReal)` es un spy *parcial* sobre un objeto real, que no es exactamente el spy de Meszaros. Conviene saber la diferencia para hablar con precisión en las revisiones de código.

### 6.2 Fakes en memoria: ciudadanos de primera

En hexagonal, los fakes de los puertos de salida **no son código de usar y tirar**. Son infraestructura de test de primer nivel:

- **Viven en `src/test/java`** (o en un módulo `test-fixtures` si los comparten varios módulos) en el mismo paquete que el puerto: `com.curso.pedidos.application.port.out`.
- **Tienen sus propios tests**: los tests de contrato del bloque 3 garantizan que el fake se comporta como el adaptador real.
- **Se reutilizan**: en tests de aplicación, en tests de controlador "sociables" y hasta para arrancar la app en local sin BD (perfil `inmemory`).
- **Leen mejor que Mockito**: el test habla de estado ("el pedido guardado está CANCELADO") en vez de interacciones ("se llamó a `save` con un argumento que...").

```
   ¿Qué doble uso para un puerto de salida?

   ¿El puerto tiene ESTADO consultable (repositorio, caché)?
        │
        ├── Sí ──► FAKE en memoria (+ test de contrato)
        │
        └── No, es un EFECTO (cobrar, notificar, publicar)
                 │
                 ├── Me importa QUÉ se envió ──► SPY propio o Mockito + ArgumentCaptor
                 │
                 └── Solo necesito que responda ──► STUB (lambda o when/thenReturn)
```

> Trampa: el fake "demasiado listo". Si tu `InMemoryPedidoRepository` empieza a tener 200 líneas, índices, transacciones simuladas... has escrito una base de datos. Mantenlo tonto y que el test de contrato te diga si se desvía.

---

## 7. Dependencias Maven de test

Con el `spring-boot-starter-parent` 3.3.x la mayoría de versiones están gestionadas por el BOM de Spring Boot (JUnit 5, AssertJ, Mockito, Testcontainers, Hamcrest, JSONPath...). Solo WireMock, ArchUnit y PIT necesitan versión explícita.

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
</parent>

<properties>
    <java.version>17</java.version>
    <archunit.version>1.3.0</archunit.version>
    <wiremock.version>3.9.1</wiremock.version>
</properties>

<dependencies>
    <!-- JUnit 5, AssertJ, Mockito, Hamcrest, JSONPath, Spring Test, MockMvc -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- Integración Spring Boot <-> Testcontainers (@ServiceConnection) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- Doble HTTP para adaptadores de salida REST -->
    <dependency>
        <groupId>org.wiremock</groupId>
        <artifactId>wiremock-standalone</artifactId>
        <version>${wiremock.version}</version>
        <scope>test</scope>
    </dependency>

    <!-- Tests de arquitectura -->
    <dependency>
        <groupId>com.tngtech.archunit</groupId>
        <artifactId>archunit-junit5</artifactId>
        <version>${archunit.version}</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

> Usamos `wiremock-standalone` (y no `wiremock`) para evitar conflictos de versiones de Jetty con Spring Boot: trae sus dependencias reempaquetadas (*shaded*).

---

## 8. Surefire vs Failsafe: `*Test` vs `*IT`

Maven tiene dos plugins para ejecutar tests, con fases y convenciones distintas:

| Plugin                    | Fase         | Patrones por defecto                                | Si falla...                                         | Para qué                     |
|---------------------------|--------------|-----------------------------------------------------|-----------------------------------------------------|------------------------------|
| `maven-surefire-plugin`   | `test`       | `**/Test*.java`, `**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java` | Rompe el build inmediatamente | Unitarios rápidos            |
| `maven-failsafe-plugin`   | `integration-test` + `verify` | `**/IT*.java`, `**/*IT.java`, `**/*ITCase.java` | Deja ejecutar `post-integration-test` (limpieza) y falla en `verify` | Integración, Docker, E2E |

El parent de Spring Boot ya declara la versión de Failsafe, pero **no lo activa**: hay que añadir los *goals*.

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-failsafe-plugin</artifactId>
            <executions>
                <execution>
                    <goals>
                        <goal>integration-test</goal>
                        <goal>verify</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Convención del curso:

| Sufijo  | Ejemplos                                                       | Comando          |
|---------|----------------------------------------------------------------|------------------|
| `*Test` | `PedidoTest`, `ConfirmarPedidoServiceTest`, `PedidoControllerTest`, `InMemoryPedidoRepositoryTest` | `./mvnw test`    |
| `*IT`   | `PedidoPersistenceAdapterIT`, `PedidoApiIT`, `StripeLikePasarelaAdapterIT` | `./mvnw verify`  |

> ¿Y `@WebMvcTest`? Arranca un contexto Spring parcial pero **no necesita Docker** y tarda poco: lo dejamos como `*Test`. La frontera práctica es: **¿necesita Docker o red?** → `*IT`.

> Alternativa: etiquetar con `@Tag("integration")` y filtrar con `<groups>`/`<excludedGroups>` en Surefire. Funciona, pero la convención de nombres es más visible y la entiende cualquiera que llegue al proyecto.

---

## 9. Estructura de carpetas de test

```
src/test/java/com/curso/pedidos
├── domain/model
│   ├── PedidoTest.java
│   ├── DineroTest.java
│   ├── PedidoMother.java            ◄── Object Mother
│   └── PedidoBuilder.java           ◄── Test Data Builder
├── application
│   ├── port/out
│   │   ├── InMemoryPedidoRepository.java       ◄── fake
│   │   ├── InMemoryPedidoRepositoryTest.java   ◄── extends contrato
│   │   └── PedidoRepositoryContractTest.java   ◄── abstracto
│   └── service
│       ├── ConfirmarPedidoServiceTest.java
│       └── CancelarPedidoServiceTest.java
├── infrastructure
│   ├── adapter/in/web/PedidoControllerTest.java          (@WebMvcTest)
│   ├── adapter/out/persistence/PedidoPersistenceAdapterIT.java
│   └── adapter/out/payment/StripeLikePasarelaAdapterIT.java
├── architecture/ArquitecturaHexagonalTest.java           (ArchUnit)
├── support/AbstractIntegrationTest.java                  (Testcontainers)
└── PedidoApiIT.java                                       (E2E)
```

---

## 10. Trampas y errores comunes

- **Trampa: medir éxito por número de tests.** 2.000 tests que comprueban getters no protegen nada. Mide qué **comportamientos** están cubiertos.
- **Trampa: mockear lo que no es tuyo.** *"Don't mock types you don't own"* (Freeman & Pryce, *Growing Object-Oriented Software, Guided by Tests*). No mockees `JpaRepository` ni `RestClient`: mockea **tu** puerto y prueba el adaptador contra la tecnología real.
- **Trampa: un único `@SpringBootTest` por test con configuraciones distintas.** Cada combinación de `@MockBean`/propiedades crea un contexto nuevo y mata la caché de contextos (lo vemos en el bloque 5).
- **Trampa: tests que dependen de la hora.** `Instant.now()` dentro del dominio = test no determinista. Inyecta `Clock`.
- **Trampa: H2 "para ir rápido".** H2 no es PostgreSQL: tipos, `ON CONFLICT`, secuencias, JSONB... Para la persistencia, Testcontainers. H2 solo para demos.

---

## 11. Tabla resumen

| Concepto              | Idea clave                                                         |
|-----------------------|--------------------------------------------------------------------|
| Pirámide              | Muchos tests baratos abajo, pocos caros arriba                     |
| Panal                 | En microservicios finos, el riesgo está en la integración          |
| Trofeo                | Estático + integración como núcleo                                 |
| Hexágono              | Pirámide con cintura gorda; dobles **en los puertos**              |
| Sociable vs solitario | Dominio real siempre; se doblan solo los puertos de salida         |
| Fake                  | Preferido para puertos con estado; validado con test de contrato   |
| Mock                  | Para verificar efectos (cobrar, publicar, notificar)               |
| Surefire / Failsafe   | `*Test` rápido sin Docker / `*IT` con infraestructura real         |

---

## Ejercicio

Coge el caso de uso `ConfirmarPedidoService` del día 2 (carga el pedido, lo confirma, lo guarda, publica `PedidoConfirmado` y notifica al cliente). Rellena esta tabla para **cada colaborador**:

| Colaborador            | ¿Real o doble? | Tipo de doble (Meszaros) | Justificación |
|------------------------|----------------|--------------------------|---------------|
| `Pedido`               |                |                          |               |
| `PedidoRepository`     |                |                          |               |
| `PublicadorDeEventos`  |                |                          |               |
| `NotificadorPedidos`   |                |                          |               |
| `Clock`                |                |                          |               |

**Pista:** pregúntate si el colaborador tiene estado consultable, si es un efecto o si introduce no-determinismo.

<details><summary>Solución</summary>

| Colaborador            | ¿Real o doble? | Tipo                    | Justificación                                                                 |
|------------------------|----------------|-------------------------|-------------------------------------------------------------------------------|
| `Pedido`               | Real           | —                       | Es dominio puro: rápido y determinista. Mockearlo esconde bugs de negocio.    |
| `PedidoRepository`     | Doble          | Fake (`InMemoryPedidoRepository`) | Tiene estado: el test comprueba "lo guardado", no "se llamó a guardar". |
| `PublicadorDeEventos`  | Doble          | Spy (propio) o mock + `ArgumentCaptor` | Es un efecto; interesa **qué** evento se publicó.             |
| `NotificadorPedidos`   | Doble          | Mock (`verify`) o dummy | Efecto. En tests que no lo ejercitan, dummy.                                  |
| `Clock`                | "Real" fijo    | Stub (`Clock.fixed`)    | `Clock.fixed(...)` es una implementación real del JDK que actúa como stub.     |

</details>

---

## Referencias

- Martin Fowler / Ham Vocke, *The Practical Test Pyramid* (2018): https://martinfowler.com/articles/practical-test-pyramid.html
- Martin Fowler, *Unit Test* (sociable vs solitary): https://martinfowler.com/bliki/UnitTest.html
- Martin Fowler, *Test Double*: https://martinfowler.com/bliki/TestDouble.html
- Gerard Meszaros, *xUnit Test Patterns* (2007), Test Double: http://xunitpatterns.com/Test%20Double.html
- André Schaffer, *Testing of Microservices*, Spotify Engineering (2018): https://engineering.atspotify.com/2018/01/testing-of-microservices
- Mike Cohn, *Succeeding with Agile* (2009).
- Steve Freeman & Nat Pryce, *Growing Object-Oriented Software, Guided by Tests* (2009).
- Michael Feathers, *Working Effectively with Legacy Code* (2004).
- Spring Framework, Testing: https://docs.spring.io/spring-framework/reference/testing.html
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023), cap. "Testing Architecture Elements"; repo https://github.com/thombergs/buckpal

---

## Mantra

> *"El doble de test vive en el puerto. Si lo pones en otro sitio, estás probando tu imaginación, no tu sistema."*
