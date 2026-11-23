# Día 5 — Bloque 5: Validación completa

> *"Architecture is the decisions that you wish you could get right early in a project."* — Ralph Johnson (citado por Martin Fowler, *Who Needs an Architect?*, 2003)

---

## 1. El problema en una frase

Con tests de dominio, de aplicación y de cada adaptador por separado, aún quedan dos huecos: **¿encajan las piezas?** (¿está bien cableado `BeanConfiguration`, coinciden las transacciones, llega el JSON hasta PostgreSQL y vuelve?) y **¿seguirá siendo un hexágono dentro de un año?** (¿alguien habrá metido un `@Entity` en el dominio "porque era más rápido"?).

Este bloque cierra ambos huecos: **tests end-to-end** (pocos y buenos) y **tests de arquitectura** con ArchUnit. Y añade dos herramientas de higiene: cobertura con **JaCoCo** y velocidad de la suite.

---

## 2. Bad: el E2E que lo prueba todo (y nada)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")                                   // ✘ BD distinta a producción
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)   // ✘ nuevo contexto por test
class PedidoE2EBadTest {

    @Autowired TestRestTemplate rest;

    @Test
    void flujo_completo() throws Exception {
        // 120 líneas: crear, añadir 5 líneas, confirmar, pagar, cancelar,
        // comprobar 404, comprobar 409, comprobar validación...
        Thread.sleep(2000);                             // ✘ "por si Kafka tarda"
    }
}
```

### ¿Qué duele?

- **Un solo test con 15 comportamientos**: cuando falla, el diagnóstico es arqueología.
- **`@DirtiesContext` por método**: cada test arranca Spring de cero. 40 tests × 8 s = 5 minutos.
- **H2**: el E2E es justo donde más importa la BD real.
- **`Thread.sleep`**: lento cuando sobra, insuficiente cuando falta. Flaky garantizado.
- **Duplica** reglas ya probadas en dominio (409, 422...). El E2E no está para eso.

---

## 3. Good: tests end-to-end mínimos y reales

### 3.1 Qué debe probar un E2E en hexagonal

Solo lo que **ningún otro test puede probar**:

1. Que el **cableado** (`BeanConfiguration`) monta los casos de uso con los adaptadores correctos.
2. Que un **camino feliz** por caso de uso atraviesa HTTP → aplicación → dominio → PostgreSQL → HTTP.
3. Que las **transacciones** y la configuración (Flyway, Jackson, seguridad) funcionan juntas.

Las variantes y errores ya están cubiertos más abajo en la pirámide.

```
   TestRestTemplate / RestClient
            │ HTTP real (puerto aleatorio)
            ▼
   ┌─────────────────────────────────────────────────────────────┐
   │ Tomcat embebido                                             │
   │  PedidoController ─► ConfirmarPedidoService ─► Pedido       │
   │                              │                              │
   │                              ▼                              │
   │                   PedidoPersistenceAdapter ─────────────────┼──► PostgreSQL (Testcontainers)
   │                              │                              │
   │                   NotificadorPedidos / PublicadorDeEventos ─┼──► dobles (terceros)
   └─────────────────────────────────────────────────────────────┘
```

### 3.2 Clase base reutilizable

Todo lo que comparten los tests de integración vive en **una** clase base. Así todos usan **la misma configuración** y Spring reutiliza el contexto (ver sección 6).

```java
package com.curso.pedidos.support;

import com.curso.pedidos.application.port.out.NotificadorPedidos;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainersConfig.class)      // del bloque 4: contenedor como bean @ServiceConnection
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // Terceros que no son "nuestro sistema": se doblan AQUÍ, una sola vez, para todos.
    // (Kafka y la pasarela tienen sus propios *IT con Testcontainers / WireMock.)
    @MockBean protected NotificadorPedidos notificador;
    @MockBean protected PublicadorDeEventos publicadorDeEventos;
}
```

> Spring Boot 3.4+: `@MockitoBean` en lugar de `@MockBean` (mismo significado).

### 3.3 El test E2E con `TestRestTemplate`

```java
package com.curso.pedidos;

import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.infrastructure.adapter.in.web.PedidoResponse;
import com.curso.pedidos.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static com.curso.pedidos.domain.model.PedidoBuilder.unPedido;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.BDDMockito.then;

class PedidoApiIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;          // ya apunta a http://localhost:{puerto aleatorio}
    @Autowired PedidoRepository pedidoRepository;

    @Test
    void crear_un_pedido_y_consultarlo() {
        UUID clienteId = UUID.randomUUID();

        ResponseEntity<Void> creado = rest.postForEntity(
                "/api/pedidos", Map.of("clienteId", clienteId), Void.class);

        assertThat(creado.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        URI location = creado.getHeaders().getLocation();
        assertThat(location).isNotNull();

        ResponseEntity<PedidoResponse> consultado = rest.getForEntity(location, PedidoResponse.class);

        assertThat(consultado.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(consultado.getBody())
                .isNotNull()
                .satisfies(p -> {
                    assertThat(p.clienteId()).isEqualTo(clienteId);
                    assertThat(p.estado()).isEqualTo("BORRADOR");
                });
    }

    @Test
    void confirmar_un_pedido_lo_persiste_y_publica_el_evento() {
        // given: preparamos el estado por el PUERTO (más rápido y estable que encadenar 4 llamadas HTTP)
        Pedido pedido = unPedido().conIdAleatorio()
                .conLinea("SKU-TECLADO", 1, "49.90")
                .build();
        pedidoRepository.guardar(pedido);

        // when: actuamos por HTTP, como un cliente real
        ResponseEntity<Void> respuesta = rest.postForEntity(
                "/api/pedidos/{id}/confirmacion", null, Void.class, pedido.id().valor());

        // then: el efecto es visible por HTTP y en la BD
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(pedidoRepository.buscarPorId(pedido.id()))
                .get().extracting(Pedido::estado).isEqualTo(EstadoPedido.CONFIRMADO);
        then(publicadorDeEventos).should().publicar(isA(PedidoConfirmado.class));
        then(notificador).should().notificarConfirmacion(any());
    }

    @Test
    void un_pedido_inexistente_devuelve_404_problem_detail() {
        ResponseEntity<String> respuesta = rest.postForEntity(
                "/api/pedidos/{id}/confirmacion", null, String.class, UUID.randomUUID());

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(respuesta.getHeaders().getContentType()).hasToString("application/problem+json");
    }
}
```

Puntos clave:

- **Arrange por el puerto, act por HTTP, assert por ambos.** No hace falta que cada E2E construya el mundo a base de peticiones.
- **Ids aleatorios** (`conIdAleatorio()`): los E2E **no** hacen *rollback* (la petición corre en otro hilo, en el servidor real), así que los datos persisten entre tests. Con ids únicos, los tests no se pisan. Alternativa: `TRUNCATE` en un `@BeforeEach` con `JdbcClient`.
- **Un E2E de error** (404) basta para comprobar que el `ProblemDetail` llega bien serializado de punta a punta. Los demás errores ya los cubre `@WebMvcTest`.

> Trampa: poner `@Transactional` en un test `RANDOM_PORT` "para que haga rollback". No funciona: el servidor procesa la petición en **otro hilo** con **otra transacción**. Solo da falsa sensación de limpieza (y puede ocultar problemas de lazy loading).

### 3.4 Variante con `RestClient` (Spring Framework 6.1+)

`TestRestTemplate` sigue siendo perfectamente válido en Spring Boot 3.3. Si tu equipo prefiere la API fluida de `RestClient`:

```java
class PedidoApiRestClientIT extends AbstractIntegrationTest {

    @LocalServerPort int puerto;
    RestClient cliente;

    @BeforeEach
    void setUp() {
        cliente = RestClient.builder().baseUrl("http://localhost:" + puerto).build();
    }

    @Test
    void crear_pedido() {
        ResponseEntity<Void> respuesta = cliente.post()
                .uri("/api/pedidos")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("clienteId", UUID.randomUUID()))
                .retrieve()
                .toBodilessEntity();

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
```

> `WebTestClient` también sirve contra un servidor MVC en `RANDOM_PORT`, pero requiere la dependencia `spring-boot-starter-webflux` en test. Si no la tienes ya, `TestRestTemplate` o `RestClient` son la opción natural.

---

## 4. Tests de arquitectura con ArchUnit

### 4.1 El problema

Las reglas del hexágono (el dominio no depende de nada, los puertos de salida no mencionan JPA...) viven en la cabeza del equipo y en un README. Basta un *pull request* con prisa para romperlas. **ArchUnit convierte esas reglas en tests** que fallan el build.

```
   ¿Quién puede depender de quién?

   infrastructure ──────► application ──────► domain
        │                                       ▲
        └───────────────────────────────────────┘

   domain       ──X──► application, infrastructure, Spring, JPA, Jackson
   application  ──X──► infrastructure, Spring, JPA, Jackson
   adapter.in   ──X──► adapter.out   (y viceversa)
```

### 4.2 Las reglas

```java
package com.curso.pedidos.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.onionArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.curso.pedidos", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaHexagonalTest {

    @ArchTest
    static final ArchRule el_dominio_no_depende_de_capas_exteriores =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..application..", "..infrastructure..")
                    .because("el dominio es el centro del hexágono");

    @ArchTest
    static final ArchRule dominio_y_aplicacion_libres_de_frameworks =
            noClasses().that().resideInAnyPackage("..domain..", "..application..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "org.hibernate..",
                            "com.fasterxml.jackson..")
                    .because("dominio y aplicación deben poder probarse y reutilizarse sin infraestructura");

    @ArchTest
    static final ArchRule la_aplicacion_no_conoce_la_infraestructura =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule los_casos_de_uso_son_interfaces_en_port_in =
            classes().that().haveSimpleNameEndingWith("UseCase")
                    .should().beInterfaces()
                    .andShould().resideInAPackage("..application.port.in..");

    @ArchTest
    static final ArchRule los_adaptadores_no_se_conocen_entre_si =
            slices().matching("com.curso.pedidos.infrastructure.adapter.(*).(*)..")
                    .should().notDependOnEachOther()
                    .because("un controller no debe llamar a un repositorio JPA saltándose el caso de uso");

    @ArchTest
    static final ArchRule forma_de_cebolla =
            onionArchitecture()
                    .domainModels("..domain.model..", "..domain.event..", "..domain.exception..")
                    .domainServices("..domain.service..")
                    .applicationServices("..application..")
                    .adapter("web", "..infrastructure.adapter.in.web..")
                    .adapter("persistence", "..infrastructure.adapter.out.persistence..")
                    .adapter("payment", "..infrastructure.adapter.out.payment..")
                    .adapter("messaging", "..infrastructure.adapter.out.messaging..")
                    .withOptionalLayers(true);
}
```

Cuando alguien rompe una regla, el mensaje es explícito:

```
Architecture Violation [Priority: MEDIUM] - Rule 'no classes that reside in any package
['..domain..', '..application..'] should depend on classes that reside in any package
['org.springframework..', 'jakarta.persistence..', ...], because dominio y aplicación deben
poder probarse y reutilizarse sin infraestructura' was violated (1 times):
Class <com.curso.pedidos.domain.model.Pedido> is annotated with <jakarta.persistence.Entity>
in (Pedido.java:0)
```

> **¿Y si adoptamos el pragmatismo de `@Service` en aplicación?** (opción comentada en el día 4). Entonces relajas la regla para `org.springframework.stereotype..` y `org.springframework.transaction.annotation..` **de forma explícita** en el test. La regla documenta la decisión.

> **Código legado:** `FreezingArchRule.freeze(regla)` congela las violaciones existentes y solo falla con las **nuevas**. Ideal para el día 8 (migración del monolito).

> Alternativas/complementos: **jMolecules** (anotaciones `@DomainLayer`, `@Port`, `@Adapter` + reglas ArchUnit ya hechas) y **Spring Modulith** (`ApplicationModules.of(...).verify()`) para verificar módulos.

---

## 5. Contract testing entre servicios (mención)

Los tests de contrato **de puerto** del bloque 3 garantizan que dos implementaciones de un puerto se comportan igual *dentro* de nuestro servicio. Entre **servicios distintos** el problema es otro: ¿el proveedor sigue respetando lo que el consumidor espera?

| Herramienta              | Enfoque                    | Cómo funciona                                                                 |
|--------------------------|----------------------------|-------------------------------------------------------------------------------|
| **Spring Cloud Contract**| *Producer-driven* (o CDC)  | El contrato (Groovy/YAML) vive en el proveedor; genera tests del proveedor y **stubs WireMock** que usa el consumidor |
| **Pact**                 | *Consumer-driven*          | El consumidor genera el *pact* en sus tests; el proveedor lo verifica (Pact Broker para compartirlos) |

Encaje hexagonal: el **consumidor** usaría los stubs generados en el test de su adaptador de salida (en lugar de un WireMock escrito a mano como en el bloque 4); el **proveedor** verifica el contrato contra su adaptador de entrada (`@WebMvcTest` o `RANDOM_PORT`).

---

## 6. Velocidad de la suite: reutilizar el contexto Spring

### 6.1 La caché de contextos

Spring TestContext **cachea** los `ApplicationContext` entre clases de test. Dos clases reutilizan el mismo contexto si su **configuración es idéntica**. La clave de caché incluye, entre otros:

- Clases de configuración (`@SpringBootTest(classes=...)`, `@Import`, `@ContextConfiguration`).
- Perfiles activos (`@ActiveProfiles`).
- Propiedades (`properties = ...`, `@TestPropertySource`, `@DynamicPropertySource`).
- El conjunto de `@MockBean`/`@SpyBean` (o `@MockitoBean`).
- El tipo de slice y su configuración (`@WebMvcTest(PedidoController.class)` ≠ `@WebMvcTest(OtroController.class)`).

```
   Clase A: @SpringBootTest + @MockBean Notificador          ─┐
   Clase B: @SpringBootTest + @MockBean Notificador          ─┼─► 1 contexto (cacheado) ✔
   Clase C: @SpringBootTest + @MockBean Notificador          ─┘

   Clase D: @SpringBootTest + @MockBean Notificador + @MockBean Pasarela ──► contexto nuevo ✘
   Clase E: @SpringBootTest + properties="x=1"                         ──► contexto nuevo ✘
   Clase F: @DirtiesContext                                            ──► destruye y recrea ✘
```

Reglas prácticas:

1. **Una clase base** (`AbstractIntegrationTest`) con todos los `@MockBean` y configuración comunes. Nada de mocks "a la carta" en cada subclase.
2. **Evita `@DirtiesContext`.** Si lo necesitas, suele ser síntoma de estado compartido mal gestionado.
3. El contenedor como **bean `@ServiceConnection`** (bloque 4) vive mientras viva el contexto: un único PostgreSQL para toda la suite.
4. Comprueba qué pasa con `logging.level.org.springframework.test.context.cache=DEBUG`: verás *hits* y *misses* de la caché.
5. La caché guarda hasta **32 contextos** por defecto (`spring.test.context.cache.maxSize`).

### 6.2 Otras palancas

| Palanca                                | Efecto                                         | Cuidado                                          |
|----------------------------------------|------------------------------------------------|--------------------------------------------------|
| Más tests abajo en la pirámide         | El mayor ahorro con diferencia                 | —                                                |
| Slices en vez de `@SpringBootTest`     | Contextos más pequeños                         | Cada slice distinto es otro contexto             |
| Contenedor *singleton* / bean          | Un PostgreSQL por suite, no por clase          | Datos compartidos: ids aleatorios o limpieza     |
| Testcontainers *reuse* (`.withReuse(true)` + `testcontainers.reuse.enable=true` en `~/.testcontainers.properties`) | El contenedor sobrevive entre ejecuciones en local | **Solo en local**, nunca en CI |
| Ejecución paralela JUnit (`junit.jupiter.execution.parallel.enabled=true`) | Aprovecha núcleos en unitarios | Estado estático compartido, fakes `static` |
| Surefire/Failsafe separados            | `mvn test` en segundos en local                | Que CI ejecute `verify`                          |

Presupuesto orientativo para el proyecto del curso:

| Tipo                       | Nº aprox. | Tiempo total   |
|----------------------------|-----------|----------------|
| Dominio                    | 80–150    | < 1 s          |
| Aplicación (fakes/Mockito) | 40–80     | 1–2 s          |
| `@WebMvcTest`              | 20–40     | 3–5 s          |
| Persistencia (TC)          | 10–20     | 5–10 s (+ arranque contenedor) |
| WireMock                   | 5–15      | 2–4 s          |
| E2E                        | 5–10      | 10–15 s        |
| ArchUnit                   | 5–10      | 1–2 s          |

---

## 7. Cobertura con JaCoCo

JaCoCo instrumenta el bytecode con un agente Java y mide líneas, ramas, instrucciones y métodos ejecutados.

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <!-- Inyecta el agente en ${argLine}, que usan Surefire y Failsafe -->
        <execution>
            <id>prepare-agent</id>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <!-- Genera target/site/jacoco/index.html -->
        <execution>
            <id>report</id>
            <phase>verify</phase>
            <goals><goal>report</goal></goals>
        </execution>
        <!-- Umbral SOLO para el dominio: es donde tiene sentido exigir -->
        <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
            <configuration>
                <rules>
                    <rule>
                        <element>PACKAGE</element>
                        <includes>
                            <include>com.curso.pedidos.domain*</include>
                        </includes>
                        <limits>
                            <limit>
                                <counter>BRANCH</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.90</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

> Si configuras `<argLine>` a mano en Surefire/Failsafe, usa `@{argLine} -Xmx1g ...` para no machacar el agente de JaCoCo.

Cómo leer la cobertura **en un hexágono**:

| Capa            | Objetivo razonable | Por qué                                                         |
|-----------------|--------------------|-----------------------------------------------------------------|
| `domain`        | Muy alto (≥ 90 % ramas) | Barato de probar y es donde vive el negocio               |
| `application`   | Alto               | Orquestación con fakes: barato                                  |
| `infrastructure`| Sin umbral fijo    | Lo cubren ITs; perseguir el 100 % aquí es caro y poco rentable  |
| `config`, DTOs  | Excluir            | Sin lógica                                                      |

> **La cobertura es un detector de código no probado, no una medida de calidad.** 100 % de líneas con aserciones vacías es 0 % de protección. Para la calidad: PIT (bloque 2).

---

## 8. Tabla final: test → herramienta → velocidad → qué protege

| Test                              | Herramienta                                     | Velocidad        | Qué protege                                                       |
|-----------------------------------|-------------------------------------------------|------------------|-------------------------------------------------------------------|
| Dominio (`PedidoTest`)            | JUnit 5 + AssertJ                               | ms               | Invariantes, cálculos, transiciones de estado                     |
| Value objects (`DineroTest`)      | JUnit 5 + AssertJ + `@ParameterizedTest`        | ms               | Validación, igualdad, aritmética                                  |
| Calidad de los tests de dominio   | PIT                                             | minutos (puntual)| Que los tests detectan cambios en la lógica                       |
| Caso de uso (`*ServiceTest`)      | JUnit + fakes / Mockito                         | ms               | Orquestación: carga, guarda, publica, notifica; caminos de error  |
| Contrato de puerto (fake)         | Clase abstracta + `InMemory*`                   | ms               | Que el fake se comporta como dicta el contrato                    |
| Contrato de puerto (real)         | Clase abstracta + `@DataJpaTest` + Testcontainers | s              | Que el adaptador real cumple el mismo contrato                    |
| Adaptador web                     | `@WebMvcTest` + MockMvc + `@MockBean`           | ~1 s/contexto    | Rutas, JSON, validación, códigos HTTP, `ProblemDetail`            |
| Adaptador persistencia            | `@DataJpaTest` + Testcontainers                 | s                | Mapeo, esquema Flyway, consultas, tipos PostgreSQL                |
| Adaptador HTTP saliente           | WireMock                                        | 100s de ms       | Petición exacta, traducción de errores, timeouts                  |
| Adaptador mensajería              | Testcontainers Kafka + Awaitility               | s                | Serialización, topic, clave                                       |
| End-to-end                        | `@SpringBootTest(RANDOM_PORT)` + Testcontainers | s                | Cableado, transacciones, configuración, camino feliz completo     |
| Arquitectura                      | ArchUnit                                        | ~1 s             | Reglas de dependencia del hexágono a lo largo del tiempo          |
| Contrato entre servicios          | Spring Cloud Contract / Pact                    | s                | Compatibilidad consumidor-proveedor                               |
| Cobertura                         | JaCoCo                                          | (sobre la suite) | Detectar zonas sin probar                                         |

---

## Ejercicio

1. Añade una regla ArchUnit: **"las clases del paquete `..infrastructure.adapter.out.persistence..` que terminan en `JpaEntity` solo pueden ser usadas desde ese mismo paquete"**.
2. Rompe la regla a propósito (usa `PedidoJpaEntity` desde el controller) y comprueba el mensaje.
3. Ejecuta `./mvnw verify` con `logging.level.org.springframework.test.context.cache=DEBUG` y cuenta cuántos contextos se crean. Añade un `@MockBean` distinto en un IT y vuelve a contar.

**Pista:** `classes().that().haveSimpleNameEndingWith("JpaEntity").should().onlyBeAccessed().byAnyPackage(...)`.

<details><summary>Solución</summary>

```java
@ArchTest
static final ArchRule las_entidades_jpa_no_salen_del_adaptador =
        classes().that().haveSimpleNameEndingWith("JpaEntity")
                .should().onlyBeAccessed()
                .byAnyPackage("..infrastructure.adapter.out.persistence..")
                .because("las entidades JPA son un detalle del adaptador; fuera se usa el modelo de dominio");
```

Paso 3: con la configuración común en `AbstractIntegrationTest`, todos los ITs `RANDOM_PORT` comparten **un** contexto (más uno por cada slice distinto: `@WebMvcTest`, `@DataJpaTest`...). Al añadir un `@MockBean` extra en una sola subclase, aparece un *cache miss* y un contexto nuevo (y, si el contenedor es un bean, ¡un segundo PostgreSQL!). Moraleja: los mocks comunes, en la base.

</details>

---

## Referencias

- Spring Boot, Testing: https://docs.spring.io/spring-boot/index.html (sección *Testing Spring Boot Applications*)
- Spring Framework, Testing (TestContext, *context caching*): https://docs.spring.io/spring-framework/reference/testing.html
- Testcontainers for Java: https://java.testcontainers.org/
- ArchUnit User Guide: https://www.archunit.org/userguide/html/000_Index.html
- jMolecules: https://github.com/xmolecules/jmolecules
- Spring Modulith: https://docs.spring.io/spring-modulith/reference/
- JaCoCo: https://www.jacoco.org/jacoco/
- PIT: https://pitest.org/
- Martin Fowler, *Contract Test*: https://martinfowler.com/bliki/ContractTest.html
- Ham Vocke, *The Practical Test Pyramid*: https://martinfowler.com/articles/practical-test-pyramid.html
- Netflix Tech Blog, *Ready for changes with Hexagonal Architecture* (2020): https://netflixtechblog.com/ready-for-changes-with-hexagonal-architecture-b315ec967749

---

## Mantra

> *"Pocos E2E que prueben que las piezas encajan, y ArchUnit para que nadie cambie la forma de las piezas."*
