# Día 8 — Bloque 4: Evaluación del sistema

> *"An architectural fitness function provides an objective integrity
> assessment of some architectural characteristic(s)."*
> — Neal Ford, Rebecca Parsons, Patrick Kua, *Building Evolutionary Architectures*

---

## 1. El problema en una frase

"Hemos migrado a hexagonal" no es una afirmación; es una **hipótesis**. Hay
que poder demostrarla con criterios objetivos hoy… y **seguir demostrándola**
dentro de seis meses, cuando alguien con prisa meta un `@Autowired` en el
dominio.

En cristiano: **lo que no se mide en el pipeline, se degrada.**

---

## 2. Cuatro niveles de evaluación

```
            ┌───────────────────────────────────────────────┐
  humano    │ 4. Revisión por pares / ATAM ligero / ADRs    │  ¿Es la decisión correcta?
            ├───────────────────────────────────────────────┤
            │ 3. Métricas: tiempo de suite, cobertura,      │  ¿Es sano?
            │    mutation score, acoplamiento               │
            ├───────────────────────────────────────────────┤
            │ 2. Fitness functions (ArchUnit) en el CI      │  ¿Se respetan las reglas?
            ├───────────────────────────────────────────────┤
  máquina   │ 1. Checklist de arquitectura                  │  ¿Está todo en su sitio?
            └───────────────────────────────────────────────┘
```

Los niveles 1–3 se automatizan. El 4 no, y es el más importante: las métricas
dicen si cumples las reglas, no si las reglas son las adecuadas.

---

## 3. Checklist de arquitectura

Para la revisión del proyecto final. Cada "no" es una conversación.

### Dominio

- [ ] `domain` no importa nada de `org.springframework`, `jakarta.persistence`,
      `com.fasterxml.jackson`, `lombok`, `org.slf4j`, `io.micrometer`.
- [ ] `Pedido` protege **todas** sus invariantes (no añadir líneas a confirmado,
      no confirmar vacío, no cancelar enviado, solo pagar confirmado).
- [ ] No hay setters públicos en el agregado. Los cambios de estado son
      métodos con nombre de negocio.
- [ ] Los value objects (`Dinero`, `Cantidad`, `PedidoId`…) se validan en el
      constructor y son inmutables (`record`).
- [ ] El dominio no llama a `Instant.now()` / `LocalDate.now()`.
- [ ] Las excepciones son de dominio (`PedidoException` y derivadas), no
      `RuntimeException` genéricas.

### Aplicación

- [ ] Cada caso de uso tiene un puerto de entrada (`XxxUseCase` / `XxxQuery`).
- [ ] Los puertos de salida hablan en lenguaje de negocio (`PasarelaDePago`,
      no `StripeClient`; `PedidoRepository`, no `PedidoJpaDao`).
- [ ] Ningún tipo de infraestructura (entidades JPA, `ResponseEntity`, DTOs de
      Jackson) aparece en firmas de puertos.
- [ ] Ninguna llamada HTTP/SMTP/Kafka ocurre dentro de una transacción de BD.
- [ ] Los servicios de aplicación orquestan; no contienen reglas de negocio
      que pertenezcan a `Pedido`.

### Infraestructura

- [ ] El controlador no tiene lógica: traduce, llama, traduce.
- [ ] Ningún controlador devuelve entidades JPA.
- [ ] Los errores se mapean en un único `@RestControllerAdvice`
      (`ProblemDetail`).
- [ ] Los adaptadores no se llaman entre sí (web no usa persistencia).
- [ ] La configuración (URLs, API keys) vive en `@ConfigurationProperties` del
      adaptador, no en servicios.
- [ ] Inyección por constructor en todas partes.

### Proceso

- [ ] El golden master pasa (casos felices sin modificar).
- [ ] El código legacy migrado está **borrado**, no comentado.
- [ ] Hay ADR de la decisión y de los cambios de comportamiento del API.

---

## 4. Fitness functions con ArchUnit

### 4.1 Dependencia

```xml
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>1.3.0</version>
    <scope>test</scope>
</dependency>
```

### 4.2 La suite completa (lista para copiar)

```java
package com.curso.pedidos.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.curso.pedidos", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaHexagonalTest {

    private static final String DOMAIN = "com.curso.pedidos.domain..";
    private static final String APPLICATION = "com.curso.pedidos.application..";
    private static final String INFRASTRUCTURE = "com.curso.pedidos.infrastructure..";

    private static final String[] FRAMEWORKS = {
            "org.springframework..", "jakarta.persistence..", "jakarta.transaction..",
            "org.hibernate..", "com.fasterxml.jackson..", "lombok..",
            "org.slf4j..", "io.micrometer..", "org.apache.kafka.."
    };

    // ---------------------------------------------------------------- capas

    @ArchTest
    static final ArchRule regla_de_dependencias_hacia_dentro = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy(DOMAIN)
            .layer("Application").definedBy(APPLICATION)
            .layer("Infrastructure").definedBy(INFRASTRUCTURE)
            .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Infrastructure")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure")
            .because("las dependencias apuntan siempre hacia el dominio (Cockburn, Martin)");

    // ---------------------------------------------------------------- pureza

    @ArchTest
    static final ArchRule dominio_sin_frameworks = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
            .because("el dominio es Java puro: se compila y prueba sin Spring");

    @ArchTest
    static final ArchRule aplicacion_sin_frameworks = noClasses()
            .that().resideInAPackage(APPLICATION)
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
            .because("los servicios de aplicación se cablean en BeanConfiguration");

    @ArchTest
    static final ArchRule dominio_solo_depende_de_java_y_de_si_mismo = classes()
            .that().resideInAPackage(DOMAIN)
            .should().onlyDependOnClassesThat().resideInAnyPackage(DOMAIN, "java..")
            .because("ni siquiera la capa de aplicación puede colarse en el dominio");

    @ArchTest
    static final ArchRule el_dominio_no_lee_el_reloj = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .because("el tiempo entra como parámetro para que el dominio sea determinista");

    // ---------------------------------------------------------------- puertos

    @ArchTest
    static final ArchRule los_puertos_son_interfaces = classes()
            .that().resideInAnyPackage("..application.port.in..", "..application.port.out..")
            .and().areTopLevelClasses()
            .should().beInterfaces()
            .because("un puerto es un contrato; comandos y resultados van anidados como records");

    @ArchTest
    static final ArchRule puertos_de_salida_con_lenguaje_de_negocio = noClasses()
            .that().resideInAPackage("..application.port.out..")
            .should().haveSimpleNameContaining("Jpa")
            .orShould().haveSimpleNameContaining("Rest")
            .orShould().haveSimpleNameContaining("Kafka")
            .orShould().haveSimpleNameContaining("Http")
            .because("el puerto nombra la necesidad, no la tecnología");

    @ArchTest
    static final ArchRule puertos_de_entrada_bien_nombrados = classes()
            .that().resideInAPackage("..application.port.in..")
            .and().areTopLevelClasses()
            .should().haveSimpleNameEndingWith("UseCase")
            .orShould().haveSimpleNameEndingWith("Query");

    @ArchTest
    static final ArchRule servicios_implementan_puertos_de_entrada = classes()
            .that().resideInAPackage("..application.service..")
            .and().areTopLevelClasses()
            .should().implement(resideInAPackage("..application.port.in.."))
            .andShould().haveSimpleNameEndingWith("Service");

    // ---------------------------------------------------------------- adaptadores

    @ArchTest
    static final ArchRule adaptadores_de_salida_con_sufijo_adapter = classes()
            .that().resideInAnyPackage("..adapter.out.persistence..", "..adapter.out.payment..",
                                       "..adapter.out.notification..")
            .and().implement(resideInAPackage("..application.port.out.."))
            .should().haveSimpleNameEndingWith("Adapter");

    @ArchTest
    static final ArchRule los_adaptadores_no_se_conocen_entre_si = slices()
            .matching("com.curso.pedidos.infrastructure.adapter.*.(*)..")
            .should().notDependOnEachOther()
            .because("web no llama a persistencia: todo pasa por los puertos");

    @ArchTest
    static final ArchRule controladores_solo_en_adaptador_web = classes()
            .that().areAnnotatedWith(RestController.class)
            .or().areAnnotatedWith(RestControllerAdvice.class)
            .should().resideInAPackage("..infrastructure.adapter.in.web..");

    @ArchTest
    static final ArchRule controladores_hablan_con_puertos_no_con_servicios = noClasses()
            .that().resideInAPackage("..adapter.in..")
            .should().dependOnClassesThat().resideInAPackage("..application.service..")
            .because("el adaptador de entrada depende del puerto, no de su implementación");

    @ArchTest
    static final ArchRule entidades_jpa_solo_en_persistencia = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..infrastructure.adapter.out.persistence..")
            .andShould().haveSimpleNameEndingWith("JpaEntity");

    @ArchTest
    static final ArchRule las_entidades_jpa_no_salen_de_persistencia = noClasses()
            .that().resideOutsideOfPackage("..infrastructure.adapter.out.persistence..")
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class);

    // ---------------------------------------------------------------- higiene

    @ArchTest
    static final ArchRule sin_ciclos_entre_paquetes = slices()
            .matching("com.curso.pedidos.(**)")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule sin_inyeccion_por_campo = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule sin_excepciones_genericas = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

    @ArchTest
    static final ArchRule sin_java_util_logging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule sin_system_out = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule configuracion_solo_en_config = classes()
            .that().areAnnotatedWith(org.springframework.context.annotation.Configuration.class)
            .should().resideInAPackage("..infrastructure.config..")
            .because("el cableado vive en infrastructure.config");
}
```

Notas sobre la suite:

- `consideringOnlyDependenciesInLayers()` hace que las dependencias a
  `java..` o a librerías no cuenten como violación de capas (ya las vigilan las
  reglas de pureza).
- `sin_ciclos_entre_paquetes` con `(**)` compara **todos** los paquetes; si es
  demasiado estricto al principio, empieza por `com.curso.pedidos.(*)..`.
- `configuracion_solo_en_config` usa `areAnnotatedWith`, que mira la anotación
  **directa**: la clase `@SpringBootApplication` (solo meta-anotada con
  `@Configuration`) no cuenta y puede quedarse en el paquete raíz.
- Mientras el código legacy siga vivo (reembolsos, informes), las reglas de
  higiene (`sin_inyeccion_por_campo`, `sin_excepciones_genericas`,
  `las_entidades_jpa_no_salen_de_persistencia`) fallarán por él. Congélalas
  (ver la trampa siguiente) en vez de desactivarlas.

> Trampa: activar la suite completa **durante** la migración y que el CI quede
> rojo una semana. Usa `FreezingArchRule` para congelar las violaciones
> existentes del legacy y que solo fallen las **nuevas**:

```java
@ArchTest
static final ArchRule sin_inyeccion_por_campo_congelada =
        com.tngtech.archunit.library.freeze.FreezingArchRule.freeze(NO_CLASSES_SHOULD_USE_FIELD_INJECTION);
```

ArchUnit guarda las violaciones actuales en `archunit_store/` (haz commit de
esa carpeta); cada vez que arreglas una, desaparece del almacén. Es la versión
ArchUnit del Strangler Fig: la deuda solo puede **bajar**.

```properties
# src/test/resources/archunit.properties
freeze.store.default.allowStoreCreation=true
freeze.store.default.path=archunit_store
```

### 4.3 ¿Cómo sé que la regla funciona? Rómpela a propósito

```java
// Añade temporalmente en Pedido.java:
import org.springframework.util.Assert;
...
Assert.notNull(id, "id");
```

```
Architecture Violation [Priority: MEDIUM] - Rule 'no classes that reside in a package
'com.curso.pedidos.domain..' should depend on classes that reside in any package
['org.springframework..', ...], because el dominio es Java puro...' was violated (1 times):
Method <com.curso.pedidos.domain.model.Pedido.<init>(...)> calls method
<org.springframework.util.Assert.notNull(java.lang.Object, java.lang.String)> in (Pedido.java:31)
```

Un test que nunca has visto fallar no es un test: es decoración.

---

## 5. Métricas

### 5.1 Cuadro de mando del proyecto final

| Métrica                                    | Cómo se mide                                       | Legacy        | Objetivo tras migrar      |
|--------------------------------------------|----------------------------------------------------|---------------|---------------------------|
| Imports de Spring/JPA/Jackson en `domain`  | ArchUnit + `grep`                                  | n/a           | **0**                     |
| Tiempo de la suite completa                | `mvn verify` (Surefire + Failsafe)                 | 0 s (sin tests) | < 60 s                  |
| Tiempo de tests de dominio + aplicación    | Surefire, solo `domain`/`application`              | n/a           | < 2 s                     |
| Cobertura de ramas de `domain`             | JaCoCo                                             | 0 %           | ≥ 90 %                    |
| Mutation score de `domain` + `application` | PIT                                                | 0 %           | ≥ 80 %                    |
| Ciclos entre paquetes                      | ArchUnit `beFreeOfCycles`                          | varios        | 0                         |
| Inestabilidad de `domain` (I = Ce/(Ca+Ce)) | ArchUnit metrics                                   | n/a           | ≈ 0 (muy estable)         |
| Duración p95 de confirmar pedido           | Micrometer (`pedidos.confirmacion.duracion`)       | ~20 s con SMTP lento | < 1 s              |
| Ficheros tocados para cambiar de pasarela  | Revisión de PR                                     | ~12           | 1 adaptador + 1 test      |

> La última fila es la métrica que de verdad le importa al negocio. Las demás
> son **indicadores adelantados** de ella.

### 5.2 Tiempo de la suite

La pirámide del día 5 se ve en el reloj:

```bash
mvn -q test -Dtest='com.curso.pedidos.domain.**.*Test,com.curso.pedidos.application.**.*Test'
# Tests run: 41, Failures: 0  — Time elapsed: 0.9 s       ← sin Spring

mvn -q verify
# Unit + slice + integración (Testcontainers) + ArchUnit + golden master — ~45 s
```

Si los tests de dominio tardan más de un par de segundos, algo arranca un
contexto de Spring donde no debe.

### 5.3 Cobertura del dominio con JaCoCo (con umbral que rompe el build)

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution>
            <id>prepare-agent</id>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>verify</phase>
            <goals><goal>report</goal></goals>
        </execution>
        <execution>
            <id>check-domain</id>
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

> Trampa: exigir 90 % de cobertura **global**. Te obliga a testear getters de
> DTOs y configuración. Exige alto donde está el valor (dominio, aplicación) y
> deja los adaptadores a los tests de integración.

### 5.4 Mutation testing con PIT

La cobertura dice qué líneas **se ejecutan**; PIT dice qué líneas **se
comprueban**. Muta el código (cambia `>` por `>=`, elimina llamadas, devuelve
`null`…) y mira si algún test falla.

```xml
<plugin>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-maven</artifactId>
    <version>1.16.1</version>
    <dependencies>
        <dependency>
            <groupId>org.pitest</groupId>
            <artifactId>pitest-junit5-plugin</artifactId>
            <version>1.2.1</version>
        </dependency>
    </dependencies>
    <configuration>
        <targetClasses>
            <param>com.curso.pedidos.domain.*</param>
            <param>com.curso.pedidos.application.*</param>
        </targetClasses>
        <targetTests>
            <param>com.curso.pedidos.domain.*</param>
            <param>com.curso.pedidos.application.*</param>
        </targetTests>
        <mutationThreshold>80</mutationThreshold>
        <timestampedReports>false</timestampedReports>
    </configuration>
</plugin>
```

```bash
mvn test-compile org.pitest:pitest-maven:mutationCoverage
# >> Generated 112 mutations Killed 97 (87%)
# informe en target/pit-reports/index.html
```

Ejemplo de mutante superviviente típico:

```java
// Original en Pedido.cancelar
if (estado == EstadoPedido.ENVIADO || estado == EstadoPedido.CANCELADO)
// Mutante: "removed conditional - replaced equality check with false" en CANCELADO
```

Si sobrevive, falta el test "no se cancela dos veces". PIT encuentra **los
tests que no escribiste**. Y como el dominio no arranca Spring, PIT tarda
segundos, no horas: otro dividendo del hexágono.

### 5.5 Acoplamiento entre paquetes

Métricas de Robert C. Martin (*Clean Architecture*, cap. 14):

- **Ca** (acoplamiento aferente): quién depende de mí.
- **Ce** (acoplamiento eferente): de quién dependo yo.
- **I = Ce / (Ca + Ce)**: inestabilidad. 0 = muy estable, 1 = muy inestable.

En un hexágono sano: `domain` ≈ 0, `application` bajo, `infrastructure` ≈ 1.
Las dependencias van de inestable a estable (*Stable Dependencies Principle*).

```java
@Test
void informe_de_acoplamiento() {
    JavaClasses clases = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.curso.pedidos");

    Set<JavaPackage> capas = Set.of(
            clases.getPackage("com.curso.pedidos.domain"),
            clases.getPackage("com.curso.pedidos.application"),
            clases.getPackage("com.curso.pedidos.infrastructure"));

    ComponentDependencyMetrics m = ArchitectureMetrics
            .componentDependencyMetrics(MetricsComponents.fromPackages(capas));

    for (String capa : List.of("com.curso.pedidos.domain", "com.curso.pedidos.application",
                               "com.curso.pedidos.infrastructure")) {
        System.out.printf("%-35s Ca=%d Ce=%d I=%.2f%n", capa,
                m.getAfferentCoupling(capa), m.getEfferentCoupling(capa), m.getInstability(capa));
    }
    assertThat(m.getInstability("com.curso.pedidos.domain")).isLessThan(0.1);
}
```

```
com.curso.pedidos.domain            Ca=2 Ce=0 I=0.00
com.curso.pedidos.application       Ca=1 Ce=1 I=0.50
com.curso.pedidos.infrastructure    Ca=0 Ce=2 I=1.00
```

(Las clases `ArchitectureMetrics`, `MetricsComponents` y
`ComponentDependencyMetrics` están en `com.tngtech.archunit.library.metrics`.)

Alternativa sin código: `jdeps -verbose:package target/classes`.

### 5.6 El contador más simple: imports de Spring en `domain`

```bash
grep -rE "import (org\.springframework|jakarta\.persistence|com\.fasterxml)" \
     src/main/java/com/curso/pedidos/domain | wc -l
# 0
```

Barato, visible, y perfecto para el README del repo. ArchUnit lo garantiza en
el CI; el `grep` lo enseña en una diapositiva.

---

## 6. Evaluación humana: ATAM ligero y revisión por pares

### 6.1 ATAM en 30 minutos

ATAM (*Architecture Tradeoff Analysis Method*, SEI) completo dura días.
Para el proyecto final hacemos una versión de bolsillo:

1. **Atributos de calidad prioritarios** (el negocio elige 3): mantenibilidad,
   testabilidad, fiabilidad del cobro.
2. **Escenarios concretos** (estímulo → respuesta medible):

| # | Atributo        | Escenario                                                             | Respuesta esperada                          | ¿Cumple? |
|---|-----------------|-----------------------------------------------------------------------|---------------------------------------------|----------|
| E1 | Modificabilidad | Cambiar de proveedor de pagos                                         | 1 adaptador nuevo, 0 cambios en dominio, < 2 días | ✅ |
| E2 | Fiabilidad      | El SMTP tarda 30 s                                                    | Confirmar responde < 1 s; email se reintenta | ✅ |
| E3 | Fiabilidad      | La BD falla después de cobrar                                         | El cobro se anula; alerta si la anulación falla | ⚠️ (sin reintentos) |
| E4 | Testabilidad    | Nueva regla "máx. 10 unidades por línea"                              | Test unitario de dominio en < 1 s, sin Spring | ✅ |
| E5 | Rendimiento     | 200 confirmaciones/s                                                  | p95 < 500 ms                                | ❓ sin medir |

3. **Puntos de sensibilidad**: `UnidadDeTrabajo` (si alguien envuelve el cobro
   en la transacción, E3 se rompe).
4. **Trade-offs**: más clases y mappers (coste) a cambio de E1/E4 (beneficio).
5. **Riesgos**: E3 sin reintentos; E5 sin medir → acciones con responsable.

### 6.2 Revisión por pares (20 min en clase)

Por parejas, cada uno revisa el repo del otro con el checklist del §3 y
responde tres preguntas por escrito:

1. ¿Dónde pondrías una regla nueva "no se puede confirmar un pedido de más de
   5.000 €"? ¿El código te lo pone fácil?
2. ¿Qué adaptador cambiarías primero si mañana se va el proveedor de email?
   ¿Cuántos ficheros tocarías?
3. ¿Hay algo que **sobre**? (puertos que nadie usa, mappers triviales, capas
   vacías).

> Trampa: revisar el estilo (nombres, formato) en vez de la arquitectura. Para
> el estilo está el formateador; aquí se revisa **dónde vive cada decisión**.

---

## 7. Documentar la decisión: Architecture Decision Records

### 7.1 Qué es un ADR

Michael Nygard (2011): un documento **corto**, en el repo, que captura **una**
decisión arquitectónica significativa, su contexto y sus consecuencias. Se
numeran, no se borran: si una decisión cambia, se escribe otro ADR que
*supersede* al anterior.

Por qué importa: dentro de un año alguien preguntará "¿por qué tenemos
`UnidadDeTrabajo` en vez de `@Transactional`?". La respuesta tiene que estar
en el repo, no en la cabeza de alguien que se fue.

### 7.2 Plantilla (formato Nygard)

```markdown
# ADR-NNNN: Título corto en imperativo

## Estado
Propuesto | Aceptado | Rechazado | Obsoleto | Sustituido por ADR-XXXX

## Contexto
Las fuerzas en juego: técnicas, de negocio, de equipo, de plazo. Hechos, no
opiniones. Lenguaje neutral.

## Decisión
"Vamos a…". Voz activa, frases completas.

## Consecuencias
Qué pasa después de aplicar la decisión: lo bueno, lo malo y lo neutro.
Todas las consecuencias, no solo las favorables.
```

Ubicación: `docs/adr/0001-adoptar-arquitectura-hexagonal.md`. Herramienta
opcional: `adr-tools` (Nat Pryce) para generar y enlazar.

### 7.3 ADR de ejemplo

```markdown
# ADR-0001: Adoptar arquitectura hexagonal para el módulo de pedidos

## Estado
Aceptado (2026-11-23). Revisión prevista tras 3 meses en producción.

## Contexto
- El backend de pedidos es un monolito Spring Boot en tres capas con
  `PedidoService` de ~300 líneas y sin tests automatizados.
- Incidentes: cobros a clientes cuyo pedido hizo rollback (llamada HTTP de
  pago dentro de `@Transactional`); confirmaciones de hasta 20 s por SMTP
  lento dentro de la transacción.
- La entidad JPA `PedidoEntity` se usa como DTO JSON; un campo sensible
  (`tokenTarjeta`) estuvo expuesto hasta que se añadió `@JsonIgnore`.
- Cambiar de proveedor de pagos ha costado 3 semanas las dos últimas veces.
- El negocio quiere publicar eventos en Kafka y estudiar la extracción de
  pagos a un servicio propio en 2027.
- El equipo (5 personas) ha recibido formación en arquitectura hexagonal.

## Decisión
Vamos a reestructurar el módulo de pedidos siguiendo la arquitectura
hexagonal (Cockburn, 2005):

- Dominio puro en `com.curso.pedidos.domain`, sin dependencias de
  frameworks, con `Pedido` como agregado que protege sus invariantes.
- Puertos de entrada (`CrearPedidoUseCase`, `ConfirmarPedidoUseCase`,
  `CancelarPedidoUseCase`, `ConsultarPedidoQuery`) y de salida
  (`PedidoRepository`, `CatalogoProductos`, `PasarelaDePago`,
  `NotificadorPedidos`, `PublicadorDeEventos`, `UnidadDeTrabajo`).
- Adaptadores en `infrastructure`; servicios cableados en `BeanConfiguration`.
- Migración incremental (Strangler Fig por endpoint + Branch by Abstraction
  con el flag `pedidos.confirmacion.motor`), protegida por tests de
  caracterización (golden master) del API REST.
- Las reglas se verifican en CI con ArchUnit (`ArquitecturaHexagonalTest`).
- Cambios deliberados del contrato REST, solo en errores: `GET` inexistente
  pasa de 200 vacío a 404; pago rechazado de 500 a 402; errores en formato
  `ProblemDetail` (RFC 9457). El id de línea deja de exponerse (no se usa).
- Reembolsos, informes y exportación CSV quedan fuera de esta migración.

## Consecuencias
Positivas:
- El cobro se hace fuera de la transacción y se compensa si la persistencia
  falla; la transacción de BD dura milisegundos.
- El dominio se prueba sin Spring (suite de dominio < 1 s); mutation testing
  viable en el pipeline.
- Cambiar de pasarela o añadir Kafka se reduce a escribir un adaptador.
- El corte para extraer pagos a un microservicio ya existe (`PasarelaDePago`).

Negativas:
- Más clases: modelo de dominio y entidad JPA separados, mappers, DTOs.
  Curva de entrada para personas nuevas en el equipo.
- Convivencia temporal de código legacy y hexagonal; riesgo de que el legacy
  no se retire (mitigación: fecha límite en el backlog y `FreezingArchRule`).
- La anulación de cobros fallida requiere intervención manual hasta
  implementar outbox con reintentos (ADR-0003 pendiente).
- Los clientes del API deben adaptarse a los nuevos códigos de error
  (comunicado a front y app móvil el 2026-11-20).

Alternativas consideradas:
- Reescritura completa en microservicios: descartada por riesgo y plazo.
- Mantener capas y solo mover HTTP/SMTP fuera de la transacción: arregla los
  incidentes, pero no la testabilidad ni el coste de cambiar de proveedor.
- Vertical Slice: atractiva para endpoints CRUD; descartada para pedidos por
  la riqueza de reglas del agregado (ver comparativa del día 7).
```

### 7.4 Testing de un ADR

Un ADR no se prueba con JUnit, pero **sí** se puede comprobar que se respeta:
cada decisión verificable del ADR debería tener su fitness function. Si el ADR
dice "dominio sin frameworks", existe `dominio_sin_frameworks`. Si dice
"errores como `ProblemDetail`", hay un test del `@RestControllerAdvice`. Las
decisiones sin test se erosionan.

---

## 8. Resumen

| Herramienta       | Qué evalúa                          | Cuándo           | Rompe el build |
|-------------------|-------------------------------------|------------------|----------------|
| Checklist         | Que cada cosa esté en su sitio      | Revisión de PR   | No             |
| ArchUnit          | Reglas de dependencias y nombres    | Cada build       | **Sí**         |
| Golden master     | Que el comportamiento no cambió     | Cada build       | **Sí**         |
| JaCoCo            | Qué código ejecutan los tests       | Cada build       | Sí (dominio)   |
| PIT               | Qué código comprueban los tests     | Nightly / PR     | Sí (umbral)    |
| Métricas Ca/Ce/I  | Dirección y fuerza del acoplamiento | Periódico        | Opcional       |
| ATAM ligero       | Si la arquitectura sirve al negocio | Hitos            | No             |
| ADR               | Por qué se decidió así              | Cada decisión    | No             |

---

## Ejercicio

Escribe **dos fitness functions** nuevas para tu proyecto:

1. Ninguna clase de `application.service` puede depender de otra clase de
   `application.service` (los casos de uso no se llaman entre sí).
2. Los records de `infrastructure.adapter.in.web` que acaban en `Request` o
   `Response` no pueden ser usados fuera de `adapter.in.web`.

Y escribe el **ADR-0002** de una decisión que hayas tomado hoy (por ejemplo,
`UnidadDeTrabajo` frente a `@Transactional`).

<details><summary>Solución</summary>

La versión ingenua (`noClasses().that().resideInAPackage("..application.service..")
.should().dependOnClassesThat().resideInAPackage("..application.service..")`)
se dispara también por las referencias de una clase a sí misma y a sus clases
anidadas. Versión robusta con condición personalizada (si dos casos de uso
comparten lógica, esa lógica va al dominio o a un servicio de dominio):

```java
@ArchTest
static final ArchRule casos_de_uso_independientes = classes()
        .that().resideInAPackage("..application.service..")
        .should(new ArchCondition<JavaClass>("no depender de otros casos de uso") {
            @Override
            public void check(JavaClass clase, ConditionEvents eventos) {
                clase.getDirectDependenciesFromSelf().stream()
                        .map(Dependency::getTargetClass)
                        .filter(t -> t.getPackageName().endsWith("application.service"))
                        .filter(t -> !t.getName().startsWith(clase.getName()))
                        .forEach(t -> eventos.add(SimpleConditionEvent.violated(clase,
                                clase.getSimpleName() + " depende de " + t.getSimpleName())));
            }
        });

@ArchTest
static final ArchRule dtos_web_no_salen_del_adaptador = noClasses()
        .that().resideOutsideOfPackage("..adapter.in.web..")
        .should().dependOnClassesThat(resideInAPackage("..adapter.in.web..")
                .and(com.tngtech.archunit.core.domain.properties.HasName.Predicates
                        .nameMatching(".*(Request|Response)$")));
```

ADR-0002 (resumen): *Estado* aceptado. *Contexto*: la regla del curso prohíbe
Spring en `application`; confirmar requiere dos tramos con una llamada HTTP en
medio. *Decisión*: puerto `UnidadDeTrabajo` implementado con
`TransactionTemplate`. *Consecuencias*: límites transaccionales explícitos y
testeables con un fake; a cambio, un puerto más y la responsabilidad de no
anidar llamadas externas dentro de `ejecutar`.

</details>

---

## Referencias

- Neal Ford, Rebecca Parsons, Patrick Kua (y Pramod Sadalage, 2ª ed.), *Building Evolutionary Architectures* (O'Reilly, 2017; 2ª ed. 2022) — *fitness functions*.
- ArchUnit User Guide: https://www.archunit.org/userguide/html/000_Index.html
- Michael Nygard, *Documenting Architecture Decisions* (2011): https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions
- Robert C. Martin, *Clean Architecture* (2017) — métricas de estabilidad y principios de componentes.
- Martin Fowler, *The Practical Test Pyramid*: https://martinfowler.com/articles/practical-test-pyramid.html
- JUnit 5 User Guide: https://junit.org/junit5/docs/current/user-guide/
- Michael Feathers, *Working Effectively with Legacy Code* (2004).

## Mantra

> *"Una regla de arquitectura que no está en el pipeline es una sugerencia.
> Y las sugerencias no sobreviven a un viernes con prisa."*
