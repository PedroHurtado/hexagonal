# Día 4 — Bloque 6: Verificar la arquitectura con ArchUnit

> *"Architecture is about the important stuff. Whatever that is."*
> — Ralph Johnson, citado por Martin Fowler

---

## 1. El problema en una frase

La arquitectura que dibujamos en la pizarra **se degrada en silencio**: un
`import` aquí, un atajo allá, y en seis meses el dominio depende de JPA y los
adaptadores se llaman entre sí. Necesitamos que **el build falle** cuando eso
ocurra.

---

## 2. Qué es ArchUnit

**ArchUnit** es una librería Java que analiza el **bytecode** de tus clases y
permite escribir **reglas de arquitectura como tests unitarios** (JUnit 4/5).
Si una regla se incumple, el test falla con un mensaje que dice **qué clase**,
**qué línea** y **qué dependencia** la rompe.

En cristiano: es un *linter* de arquitectura que se ejecuta con `mvn test`.

| Mecanismo                      | Qué garantiza                         | Cuándo falla        |
|--------------------------------|---------------------------------------|---------------------|
| Paquetes + package-private     | Visibilidad de clases                 | Compilación         |
| Módulos Maven                  | Dependencias entre artefactos         | Compilación         |
| **ArchUnit**                   | **Cualquier regla expresable**        | **Tests**           |
| Revisión de código             | Lo que el revisor recuerde mirar      | Cuando hay suerte   |

ArchUnit **complementa** a los módulos Maven: los módulos impiden que
`application` vea JPA, pero no impiden que el adaptador de pagos use el
repositorio JPA (ambos viven en `infrastructure`). ArchUnit sí.

---

## 3. Bad: la arquitectura como documento

```
docs/ARQUITECTURA.md
  "El dominio NO debe depender de Spring."
  "Los adaptadores NO deben llamarse entre sí."
  (Última modificación: hace 14 meses)
```

```java
package com.curso.pedidos.domain.model;

import org.springframework.util.Assert;   // ❌ "solo es una utilidad..."

public record Cantidad(int valor) {
    public Cantidad {
        Assert.isTrue(valor > 0, "La cantidad debe ser positiva");
    }
}
```

```java
package com.curso.pedidos.infrastructure.adapter.out.payment;

// ❌ el adaptador de pagos tira del de persistencia "para ir más rápido"
import com.curso.pedidos.infrastructure.adapter.out.persistence.PedidoJpaRepository;
```

### ¿Qué duele?

- **Nadie lee el documento.** Y si lo lee, no lo recuerda en el momento del
  `import`.
- Las violaciones **se acumulan**: cada una hace la siguiente más "aceptable".
- Cuando se detectan, arreglarlas cuesta **semanas**.

---

## 4. Good: las reglas como tests

### 4.1 Dependencia Maven

```xml
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>1.3.0</version>
    <scope>test</scope>
</dependency>
```

> En un proyecto multi-módulo, pon los tests ArchUnit en `pedidos-bootstrap`
> (el único módulo que ve **todas** las clases).

### 4.2 Reglas de dependencia del hexágono

```java
package com.curso.pedidos.arquitectura;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.curso.pedidos",
                importOptions = ImportOption.DoNotIncludeTests.class)
class ReglasDeDependenciaTest {

    @ArchTest
    static final ArchRule el_dominio_no_depende_de_frameworks =
            noClasses().that().resideInAPackage("com.curso.pedidos.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "org.hibernate..",
                            "com.fasterxml.jackson..")
                    .because("el dominio es Java puro (regla de dependencia)");

    @ArchTest
    static final ArchRule el_dominio_no_depende_de_aplicacion_ni_infraestructura =
            noClasses().that().resideInAPackage("com.curso.pedidos.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.curso.pedidos.application..",
                            "com.curso.pedidos.infrastructure..");

    @ArchTest
    static final ArchRule la_aplicacion_no_depende_de_infraestructura =
            noClasses().that().resideInAPackage("com.curso.pedidos.application..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.curso.pedidos.infrastructure..")
                    .because("los casos de uso solo conocen puertos");

    @ArchTest
    static final ArchRule la_aplicacion_no_depende_de_spring =
            noClasses().that().resideInAPackage("com.curso.pedidos.application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..")
                    .because("los servicios se cablean en infrastructure.config");
}
```

> Si tu equipo eligió la **opción B** del [bloque 5](05-gestion-de-dependencias.md)
> (`@Service` en aplicación), la última regla no aplica. Mejor dejarla y
> escribir explícitamente la excepción (`.and().haveNameNotMatching(...)`) que
> borrarla: así la decisión queda documentada en el código.

### 4.3 Los adaptadores no se llaman entre sí

```java
package com.curso.pedidos.arquitectura;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.repository.Repository;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.curso.pedidos",
                importOptions = ImportOption.DoNotIncludeTests.class)
class ReglasDeAdaptadoresTest {

    @ArchTest
    static final ArchRule los_adaptadores_de_salida_son_independientes =
            slices().matching("com.curso.pedidos.infrastructure.adapter.out.(*)..")
                    .should().notDependOnEachOther()
                    .because("cada adaptador habla solo con su puerto");

    @ArchTest
    static final ArchRule los_adaptadores_de_entrada_no_usan_adaptadores_de_salida =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("..infrastructure.adapter.in..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..infrastructure.adapter.out..")
                    .because("un controlador pasa siempre por un caso de uso");

    @ArchTest
    static final ArchRule los_repositorios_spring_data_solo_se_usan_en_persistencia =
            classes().that().areAssignableTo(Repository.class)
                    .should().onlyBeAccessed().byClassesThat()
                    .resideInAPackage("..infrastructure.adapter.out.persistence..");

    @ArchTest
    static final ArchRule los_puertos_de_salida_son_interfaces =
            classes().that().resideInAPackage("..application.port.out..")
                    .and().haveSimpleNameNotEndingWith("Exception")
                    .and().areTopLevelClasses()
                    .and().areNotAssignableTo(Record.class)
                    .should().beInterfaces();
}
```

> El filtro deja fuera las excepciones del puerto y los records (todo
> `record` hereda de `java.lang.Record`); todo lo demás en `port.out` debe ser
> interfaz. `ResultadoPago` es un `sealed interface`, así que cumple.

### 4.4 La regla prefabricada: `onionArchitecture()`

La *ArchUnit Library* trae reglas listas para arquitecturas conocidas.
`onionArchitecture()` (arquitectura cebolla de Palermo) encaja casi 1:1 con
la hexagonal:

```java
package com.curso.pedidos.arquitectura;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.onionArchitecture;

@AnalyzeClasses(packages = "com.curso.pedidos",
                importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaHexagonalTest {

    @ArchTest
    static final ArchRule arquitectura_hexagonal = onionArchitecture()
            .domainModels("com.curso.pedidos.domain.model..",
                          "com.curso.pedidos.domain.event..",
                          "com.curso.pedidos.domain.exception..")
            .domainServices("com.curso.pedidos.domain.service..")
            .applicationServices("com.curso.pedidos.application..")
            .adapter("web",           "com.curso.pedidos.infrastructure.adapter.in.web..")
            .adapter("persistence",   "com.curso.pedidos.infrastructure.adapter.out.persistence..")
            .adapter("payment",       "com.curso.pedidos.infrastructure.adapter.out.payment..")
            .adapter("notification",  "com.curso.pedidos.infrastructure.adapter.out.notification..")
            .adapter("messaging-out", "com.curso.pedidos.infrastructure.adapter.out.messaging..");
}
```

Qué comprueba:

```
            ┌──────────────────────────────────────────────────┐
            │  ADAPTERS  (web, persistence, payment, ...)       │
            │   · pueden depender de todo lo de dentro          │
            │   · NO pueden depender unos de otros              │
            │   ┌──────────────────────────────────────────┐    │
            │   │ APPLICATION SERVICES  (application..)     │    │
            │   │   ┌──────────────────────────────────┐    │    │
            │   │   │ DOMAIN SERVICES (domain.service) │    │    │
            │   │   │   ┌──────────────────────────┐   │    │    │
            │   │   │   │ DOMAIN MODELS            │   │    │    │
            │   │   │   │ (model, event, exception)│   │    │    │
            │   │   │   └──────────────────────────┘   │    │    │
            │   │   └──────────────────────────────────┘    │    │
            │   └──────────────────────────────────────────┘    │
            └──────────────────────────────────────────────────┘
                 Las dependencias solo apuntan HACIA DENTRO
```

- Las capas interiores no conocen las exteriores.
- Los adaptadores no dependen entre sí.
- `infrastructure.config` no está en ninguna capa: puede conocer a todos (es
  la composition root). Si aparece ahí lógica, es otra señal de alarma.

> Alternativa: `layeredArchitecture().consideringOnlyDependenciesInLayers()`
> con `.layer(...).definedBy(...)` y
> `.whereLayer("Domain").mayOnlyBeAccessedByLayers(...)`. Más verboso pero
> totalmente configurable.

---

## 5. Cómo se ve un fallo

```text
java.lang.AssertionError: Architecture Violation [Priority: MEDIUM] -
Rule 'no classes that reside in a package 'com.curso.pedidos.domain..' should
depend on classes that reside in any package ['org.springframework..', ...],
because el dominio es Java puro (regla de dependencia)' was violated (1 times):
Method <com.curso.pedidos.domain.model.Cantidad.<init>(int)> calls method
<org.springframework.util.Assert.isTrue(boolean, java.lang.String)>
in (Cantidad.java:7)
```

Clase, método, línea y motivo (`because`). El `because` es la **documentación
viva** de la decisión: escríbelo siempre.

---

## 6. Proyectos legados: `FreezingArchRule`

Introducir ArchUnit en un proyecto con 300 violaciones no puede bloquear el
build mañana. `FreezingArchRule` registra las violaciones **existentes** y
solo falla con las **nuevas**:

```java
@ArchTest
static final ArchRule dominio_sin_spring = FreezingArchRule.freeze(
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.."));
```

```properties
# src/test/resources/archunit.properties
freeze.store.default.allowStoreCreation=true
```

Las violaciones conocidas se guardan en un *store* (por defecto, ficheros de
texto en `archunit_store/`) que se versiona con Git. Cada vez que alguien
arregla una, desaparece del store. Encaja perfecto con el **Strangler Fig**
del día 8.

---

## 7. Más allá de ArchUnit: Spring Modulith y jMolecules

### Spring Modulith

Proyecto oficial de Spring para **monolitos modulares**. Considera cada
subpaquete directo del paquete principal un **módulo de aplicación** y
verifica que los módulos solo accedan a la API pública de los demás
(internamente usa ArchUnit).

```java
@Test
void verifica_los_modulos() {
    ApplicationModules.of(PedidosApplication.class).verify();
}
```

Organiza por **contexto de negocio** (`pedidos`, `catalogo`, `pagos`), no por
capa técnica. Es ortogonal al hexágono: cada módulo puede ser un pequeño
hexágono. Lo retomamos en el día 7.

### jMolecules

Librería de **anotaciones y tipos** para expresar conceptos arquitectónicos
en el código: `@AggregateRoot`, `@ValueObject` (DDD), `@Port`, `@Adapter`,
`@PrimaryPort`, `@SecondaryPort` (hexagonal)… sin depender de ningún
framework. Su módulo de integración con ArchUnit
(`org.jmolecules.integrations:jmolecules-archunit`) ofrece reglas ya hechas,
por ejemplo `JMoleculesArchitectureRules.ensureHexagonal()`, que verifica
las dependencias a partir de esas anotaciones en lugar de nombres de paquete.

| Herramienta       | Enfoque                                   | Encaja cuando…                               |
|-------------------|-------------------------------------------|----------------------------------------------|
| ArchUnit a mano   | Reglas por paquete/nombre                 | Siempre; máximo control                      |
| `onionArchitecture()` | Regla prefabricada por capas          | Estructura hexagonal por paquetes            |
| Spring Modulith   | Módulos de negocio + eventos              | Monolito modular con Spring Boot             |
| jMolecules        | Arquitectura explícita con anotaciones    | Quieres que el código "diga" su rol          |

---

## 8. Trampas

- **Trampa: analizar también los tests.** Sin
  `ImportOption.DoNotIncludeTests`, un test del dominio que usa Mockito o
  Spring rompe la regla. Los tests tienen otras reglas.
- **Trampa: paquetes mal escritos.** `"..domian.."` no coincide con nada y la
  regla pasaría siempre en verde. Las versiones actuales de ArchUnit hacen
  fallar las reglas que no encuentran clases que comprobar
  (`archRule.failOnEmptyShould`, activo por defecto): no lo desactives.
- **Trampa: reglas sin `because`.** Dentro de un año nadie sabrá por qué
  existen y alguien las borrará.
- **Trampa: demasiadas reglas.** Empieza con 4–5 reglas que importan (las de
  este bloque). Cada regla es código que mantener.

---

## 9. Resumen

| Regla                                              | API ArchUnit                                    |
|----------------------------------------------------|-------------------------------------------------|
| Dominio sin Spring/JPA/Jackson                     | `noClasses()...dependOnClassesThat()`           |
| Aplicación sin infraestructura                     | `noClasses()...resideInAPackage(infra)`         |
| Adaptadores no se llaman entre sí                  | `slices().matching(...).notDependOnEachOther()` |
| Spring Data solo en persistencia                   | `classes()...onlyBeAccessed()`                  |
| Hexágono completo                                  | `Architectures.onionArchitecture()`             |
| Legado                                             | `FreezingArchRule.freeze(...)`                  |

---

## Ejercicio

Escribe una regla que garantice que **los controladores web solo dependen de
puertos de entrada** de la aplicación (nunca de `application.service` ni de
`application.port.out`).

Pista: `noClasses().that().resideInAPackage(...)` y
`.dependOnClassesThat().resideInAnyPackage(...)`.

<details><summary>Solución</summary>

```java
@ArchTest
static final ArchRule los_controladores_solo_usan_puertos_de_entrada =
        noClasses().that().resideInAPackage("..infrastructure.adapter.in.web..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.curso.pedidos.application.service..",
                        "com.curso.pedidos.application.port.out..")
                .because("el controlador conoce el caso de uso por su interfaz "
                       + "(puerto de entrada), no su implementación");
```

Para comprobar que funciona, haz que `PedidoController` reciba
`ConfirmarPedidoService` en lugar de `ConfirmarPedidoUseCase` y ejecuta
`mvn test`: debe fallar indicando el constructor del controlador.

</details>

---

## Referencias

- ArchUnit User Guide: https://www.archunit.org/userguide/html/000_Index.html
- Spring Modulith — Reference: https://docs.spring.io/spring-modulith/reference/
- jMolecules: https://github.com/xmolecules/jmolecules
- Jeffrey Palermo, *The Onion Architecture* (2008):
  https://jeffreypalermo.com/2008/07/the-onion-architecture-part-1/
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023) —
  *Enforcing Architecture Boundaries*; https://github.com/thombergs/buckpal
- Martin Fowler, *Strangler Fig Application*:
  https://martinfowler.com/bliki/StranglerFigApplication.html

---

## Mantra

> *"Si la arquitectura no está en un test, es solo una opinión."*
