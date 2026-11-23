# Día 5 — Módulo 4: Estrategias de testing en arquitecturas desacopladas

Material de apoyo para la quinta jornada del curso (sesión de **3h45**).

> *"Legacy code is simply code without tests."* — Michael Feathers, *Working Effectively with Legacy Code* (2004)

---

## Objetivos del día

Al terminar la sesión, el alumno será capaz de:

1. **Diseñar una estrategia de pruebas** para una aplicación hexagonal: qué se prueba en cada capa, con qué herramienta y con qué velocidad.
2. Distinguir los **dobles de test** de Meszaros (dummy, stub, spy, mock, fake) y saber cuándo un **fake en memoria** es mejor que Mockito.
3. Escribir tests de **dominio puro** expresivos con JUnit 5 + AssertJ: `@Nested`, `@DisplayName`, `@ParameterizedTest`, builders y Object Mother.
4. Probar **casos de uso** con fakes y con Mockito (`verify`, `ArgumentCaptor`, BDDMockito), y garantizar con **tests de contrato** que el fake y el adaptador JPA se comportan igual.
5. Probar **adaptadores** con los *slices* de Spring Boot: `@WebMvcTest`, `@DataJpaTest` + **Testcontainers** (`@ServiceConnection`), y clientes HTTP con **WireMock**.
6. Cerrar el círculo con tests **end-to-end**, **ArchUnit** como red de seguridad arquitectónica y **JaCoCo** para medir (sin obsesionarse).

---

## Estructura del día

| # | Bloque                                     | Duración orientativa | Fichero                                    |
|---|--------------------------------------------|----------------------|--------------------------------------------|
| 1 | Diseño de la estrategia de pruebas         | ~40 min              | `01-diseno-de-la-estrategia-de-pruebas.md` |
| 2 | Testing del dominio                        | ~40 min              | `02-testing-del-dominio.md`                |
| 3 | Testing de casos de uso                    | ~40 min              | `03-testing-de-casos-de-uso.md`            |
| – | Descanso                                   | ~15 min              | —                                          |
| 4 | Testing de infraestructura                 | ~45 min              | `04-testing-de-infraestructura.md`         |
| 5 | Validación completa (E2E, ArchUnit, JaCoCo)| ~30 min              | `05-validacion-completa.md`                |
| 6 | Taller: suite completa de `CancelarPedido` | ~15 min (+ casa)     | `06-ejercicios-dia-05.md`                  |

Total: **~3h45** con descanso. El taller del bloque 6 se arranca en clase (dominio y caso de uso) y se termina en casa (infraestructura y E2E).

## Decisión de ritmo

Los markdowns son **material extendido**: muchos ejemplos de test completos, con imports, para copiar y pegar en el proyecto. En clase vamos más rápido: se enseña el esqueleto de cada tipo de test, se ejecuta en vivo y se comenta la salida. Lo extenso es para que te lo lleves.

**Hoy es el día con más código del curso.** Y casi todo es código de test. No es casualidad: la principal promesa de la arquitectura hexagonal es la **testabilidad**. Hoy toca cobrarla.

---

## Cómo enfocar el bloque

Llevamos tres días construyendo el hexágono:

```
          ┌──────────────────────────────────────────────┐
          │              INFRAESTRUCTURA                 │
          │   ┌──────────────────────────────────────┐   │
 HTTP ───►│   │            APLICACIÓN                │   │───► PostgreSQL
          │   │   ┌──────────────────────────────┐   │   │
          │   │   │           DOMINIO            │   │   │───► Pasarela de pago
          │   │   │  Pedido, LineaPedido, Dinero │   │   │
          │   │   └──────────────────────────────┘   │   │───► Kafka
          │   │  CasosDeUso  +  Puertos (in/out)     │   │
          │   └──────────────────────────────────────┘   │
          │  Controllers, JPA, RestClient, Kafka...      │
          └──────────────────────────────────────────────┘
```

Hoy la pregunta cambia: **¿cómo demuestro que todo esto funciona, y que seguirá funcionando dentro de seis meses?**

La respuesta tiene una forma muy concreta: **cada anillo del hexágono tiene su tipo de test**, y la frontera entre anillos (los puertos) es exactamente el sitio donde ponemos los dobles de test.

| Anillo          | Tipo de test predominante          | ¿Spring? | ¿Docker? | Velocidad      |
|-----------------|------------------------------------|----------|----------|----------------|
| Dominio         | Unitario puro (JUnit + AssertJ)    | No       | No       | Milisegundos   |
| Aplicación      | Unitario con fakes / Mockito       | No       | No       | Milisegundos   |
| Adaptador entrada | Slice `@WebMvcTest`              | Parcial  | No       | ~1-2 s contexto|
| Adaptador salida| Slice `@DataJpaTest` + Testcontainers | Parcial | Sí     | Segundos       |
| Sistema         | `@SpringBootTest` E2E + ArchUnit   | Completo | Sí       | Segundos       |

El esquema de cada fichero del día es:

1. **El problema** en una frase.
2. **Bad**: el test que todos hemos escrito alguna vez y que duele.
3. **Good**: el test bien hecho sobre el dominio Pedidos.
4. **Diagrama ASCII** de qué se prueba y qué se dobla.
5. **Trampas** habituales.
6. **Tabla resumen**, **ejercicio**, **referencias** y **mantra**.

---

## Decisión de diseño del día

**Todos los tests son sobre el mismo dominio Pedidos que venimos usando.** Nada de calculadoras ni `assertEquals(4, 2 + 2)`. Probamos `Pedido`, `ConfirmarPedidoService`, `PedidoController`, `PedidoPersistenceAdapter` y `StripeLikePasarelaAdapter`: el código que hemos escrito en los días 2, 3 y 4.

Al final del día el alumno tendrá en su proyecto:

- Un `PedidoMother` y un `PedidoBuilder` reutilizables.
- Un `InMemoryPedidoRepository` (fake) como ciudadano de primera.
- Un `PedidoRepositoryContractTest` abstracto ejecutado contra el fake **y** contra PostgreSQL real.
- Un `AbstractIntegrationTest` con Testcontainers reutilizable.
- Reglas ArchUnit que fallan el build si alguien mete Spring en el dominio.

---

## Punto de partida

Recuperamos el proyecto donde lo dejamos ayer (día 4). Lo relevante para hoy:

```java
// domain.model
public class Pedido {
    public static Pedido crear(PedidoId id, ClienteId clienteId, Instant creadoEn) { ... }
    public static Pedido reconstituir(PedidoId id, ClienteId clienteId, List<LineaPedido> lineas,
                                      EstadoPedido estado, Instant creadoEn) { ... }
    public void anadirLinea(ProductoId productoId, Cantidad cantidad, Dinero precioUnitario) { ... }
    public void confirmar() { ... }
    public void cancelar() { ... }
    public void marcarComoPagado() { ... }
    public Dinero total() { ... }
}

// application.port.out
public interface PedidoRepository {
    void guardar(Pedido pedido);
    Optional<Pedido> buscarPorId(PedidoId id);
    List<Pedido> buscarPorCliente(ClienteId clienteId);
}

// application.service
public class ConfirmarPedidoService implements ConfirmarPedidoUseCase { ... }

// infrastructure.adapter.out.persistence
public class PedidoPersistenceAdapter implements PedidoRepository { ... }
```

> Nota: los servicios de aplicación reciben un `java.time.Clock` por constructor. Ese detalle, que el día 2 parecía un capricho, hoy nos va a permitir escribir tests **deterministas** con `Clock.fixed(...)`.

---

## Conexión con días anteriores

- **Día 1** nos dio DIP: *depende de abstracciones*. Hoy vemos su recompensa más tangible: si dependes de una interfaz, en el test puedes enchufar otra implementación.
- **Día 2** nos dio el dominio rico y los **puertos**. Hoy los puertos son **costuras de test** (*seams*, en terminología de Feathers): el sitio exacto donde se corta el sistema para probarlo.
- **Día 3** nos dio los **adaptadores de entrada** (`PedidoController`, DTOs, `@RestControllerAdvice`). Hoy los probamos con `@WebMvcTest` sin levantar el servidor ni la base de datos.
- **Día 4** nos dio los **adaptadores de salida** (JPA, `RestClient`, Kafka) y el cableado en `BeanConfiguration`. Hoy los probamos contra infraestructura **real** en Docker con Testcontainers.

## Conexión con días siguientes

- **Día 6** (persistencia avanzada y transacciones): los tests de contrato y Testcontainers de hoy serán la red que nos permita refactorizar el mapeo JPA, añadir Outbox o cambiar la estrategia transaccional sin miedo.
- **Día 7** (Hexagonal vs Clean vs Vertical Slice): veremos cómo cambia la estrategia de testing cuando cada *slice* es un único fichero (spoiler: más tests sociables, menos mocks).
- **Día 8** (migración del monolito): sin una buena suite de caracterización, no se migra nada. Lo de hoy es requisito.

---

## Comandos útiles del día

```bash
# Solo tests unitarios (surefire, *Test)
./mvnw test

# Unitarios + integración (failsafe, *IT) — requiere Docker arrancado
./mvnw verify

# Un único test
./mvnw test -Dtest=PedidoTest

# Mutation testing con PIT
./mvnw org.pitest:pitest-maven:mutationCoverage

# Informe de cobertura JaCoCo (target/site/jacoco/index.html)
./mvnw verify jacoco:report
```

---

## Mantra del día

> *"Si no puedes probar tu dominio sin arrancar Spring, no tienes un hexágono: tienes un monolito con carpetas bonitas."*
