# Día 4 — Bloque 5: Gestión de dependencias

> *"The overriding rule that makes this architecture work is The Dependency
> Rule. This rule says that source code dependencies can only point
> inwards."* — Robert C. Martin, *The Clean Architecture* (2012)

---

## 1. El problema en una frase

Ya tenemos dominio, casos de uso y media docena de adaptadores. Ahora hay que
responder a dos preguntas: **¿quién crea y conecta todas estas piezas?** y
**¿qué impide que mañana alguien importe `PedidoJpaEntity` desde un caso de
uso?**

---

## 2. La *composition root*

La **composition root** es el único lugar de la aplicación donde se conocen
**todas** las implementaciones concretas y se conectan entre sí. En una app
Spring Boot es el contexto de Spring: la clase `@SpringBootApplication` y las
clases `@Configuration`.

```
   ┌──────────────────────────────────────────────────────────────┐
   │            COMPOSITION ROOT (infrastructure.config)          │
   │                                                              │
   │   new ConfirmarPedidoService(                                │
   │          pedidoPersistenceAdapter,   ◄── adaptador JPA       │
   │          emailNotificadorAdapter,    ◄── adaptador SMTP      │
   │          kafkaPublicadorDeEventos)   ◄── adaptador Kafka     │
   └──────────────────────────────────────────────────────────────┘
           conoce a todos          ▲          nadie la conoce
                                   │
          El dominio y la aplicación ignoran que existe Spring.
```

Hay tres formas habituales de registrar los **servicios de aplicación** como
beans. Las tres son válidas; lo que importa es elegir con criterio.

---

## 3. Opción A: `BeanConfiguration` con `@Bean` (la del curso)

```java
package com.curso.pedidos.infrastructure.config;

import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.in.CrearPedidoUseCase;
import com.curso.pedidos.application.port.in.ConsultarPedidoQuery;
import com.curso.pedidos.application.port.out.*;
import com.curso.pedidos.application.service.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfiguration {

    @Bean
    CrearPedidoUseCase crearPedidoUseCase(PedidoRepository pedidoRepository,
                                          CatalogoProductos catalogo) {
        return new CrearPedidoService(pedidoRepository, catalogo);
    }

    @Bean
    ConfirmarPedidoUseCase confirmarPedidoUseCase(PedidoRepository pedidoRepository,
                                                  NotificadorPedidos notificador,
                                                  PublicadorDeEventos publicador) {
        return new ConfirmarPedidoService(pedidoRepository, notificador, publicador);
    }

    @Bean
    ConsultarPedidoQuery consultarPedidoQuery(PedidoRepository pedidoRepository) {
        return new ConsultarPedidoService(pedidoRepository);
    }
}
```

- `application.service.*` son **POJOs** sin una sola anotación.
- El bean se expone por el **puerto de entrada** (`ConfirmarPedidoUseCase`),
  no por la clase concreta: el controlador solo ve la interfaz.
- Todo el cableado es **visible en un fichero**: el diagrama de dependencias
  de la aplicación, en código.

### Transacciones sin anotar la aplicación

Si `ConfirmarPedidoService` no puede llevar `@Transactional`, ¿cómo
envolvemos el caso de uso en una transacción? Con un **decorador** en
infraestructura usando `TransactionTemplate`:

```java
package com.curso.pedidos.infrastructure.config;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import org.springframework.transaction.support.TransactionTemplate;

class TransactionalConfirmarPedidoUseCase implements ConfirmarPedidoUseCase {

    private final ConfirmarPedidoUseCase delegado;
    private final TransactionTemplate tx;

    TransactionalConfirmarPedidoUseCase(ConfirmarPedidoUseCase delegado,
                                        TransactionTemplate tx) {
        this.delegado = delegado;
        this.tx = tx;
    }

    @Override
    public void ejecutar(ConfirmarPedidoCommand cmd) {
        tx.executeWithoutResult(status -> delegado.ejecutar(cmd));
    }
}
```

```java
@Bean
ConfirmarPedidoUseCase confirmarPedidoUseCase(PedidoRepository repo,
                                              NotificadorPedidos notificador,
                                              PublicadorDeEventos publicador,
                                              TransactionTemplate tx) {
    return new TransactionalConfirmarPedidoUseCase(
            new ConfirmarPedidoService(repo, notificador, publicador), tx);
}
```

`TransactionTemplate` lo autoconfigura Spring Boot cuando hay un
`PlatformTransactionManager`. Lo profundizamos en el día 6.

---

## 4. Opción B: `@Service` / `@Component` en la capa de aplicación

```java
package com.curso.pedidos.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service                       // ⚠ import de Spring en application
@Transactional
public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {
    // constructor con los puertos: Spring inyecta automáticamente
}
```

Pragmática y muy extendida. El coste: **la capa de aplicación depende de
Spring** (`spring-context`, `spring-tx`). Las anotaciones no cambian el
comportamiento del código en un test unitario (el `new` sigue funcionando),
pero la regla "aplicación sin frameworks" queda rota y el módulo
`application` ya no compila sin Spring.

---

## 5. Opción C: anotación propia `@UseCase` + `@ComponentScan`

Punto intermedio: la capa de aplicación se anota con **nuestra** anotación
(sin imports de Spring) y la infraestructura le dice a Spring que la
reconozca.

```java
package com.curso.pedidos.application;

import java.lang.annotation.*;

/** Marca un servicio de aplicación. Sin dependencia de ningún framework. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UseCase { }
```

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.UseCase;

@UseCase
public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {
    public ConfirmarPedidoService(PedidoRepository repo,
                                  NotificadorPedidos notificador,
                                  PublicadorDeEventos publicador) { /* ... */ }
}
```

```java
package com.curso.pedidos.infrastructure.config;

import com.curso.pedidos.application.UseCase;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

@Configuration
@ComponentScan(
        basePackages = "com.curso.pedidos.application",
        includeFilters = @ComponentScan.Filter(
                type = FilterType.ANNOTATION, classes = UseCase.class),
        useDefaultFilters = false)
class UseCaseScanConfiguration { }
```

- `useDefaultFilters = false`: solo registra lo marcado con `@UseCase` (no
  busca `@Component`).
- Si la clase tiene **un único constructor**, Spring lo usa para inyectar sin
  `@Autowired` (desde Spring 4.3).
- jMolecules ofrece anotaciones similares ya hechas (`@Service`,
  `@Repository`… de DDD, no de Spring) y la integración con Spring y ArchUnit.

> Alternativa elegante: meta-anotar `@UseCase` con `@Component`. Funciona
> sin `@ComponentScan` extra… pero vuelve a meter un import de Spring en
> `application`. Volvemos a la opción B con otro nombre.

---

## 6. Trade-offs

| Criterio                          | A: `@Bean` en config    | B: `@Service`              | C: `@UseCase` + scan       |
|-----------------------------------|-------------------------|----------------------------|----------------------------|
| Spring en `application`           | No                      | Sí                         | No                         |
| Código de cableado                | Explícito (más líneas)  | Ninguno                    | Mínimo (un `@ComponentScan`)|
| Visibilidad del grafo             | Total, en un fichero    | Implícita (repartida)      | Implícita                  |
| Transacciones                     | Decorador / `TransactionTemplate` | `@Transactional` | Decorador o AOP por anotación |
| Añadir un caso de uso             | Editar `BeanConfiguration` | Nada                    | Nada                       |
| Errores de cableado               | En arranque, localizados| En arranque                | En arranque                |
| Recomendado para                  | Equipos que aprenden hexagonal, dominios críticos | Proyectos pragmáticos pequeños | Equilibrio en proyectos medianos |

En cristiano: **A** es la más pura y didáctica; **B** es la más cómoda;
**C** es el compromiso. Lo que no es aceptable es **mezclar** sin criterio
(la mitad con `@Service`, la otra mitad con `@Bean`).

---

## 7. Intercambiar adaptadores: `@Profile` vs `@ConditionalOnProperty`

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

@Configuration
class PersistenciaConfiguration {

    @Bean
    @ConditionalOnProperty(name = "pedidos.persistencia", havingValue = "jpa",
                           matchIfMissing = true)
    PedidoRepository pedidoRepositoryJpa(PedidoJpaRepository jpa) {
        return new PedidoPersistenceAdapter(jpa);
    }

    @Bean
    @ConditionalOnProperty(name = "pedidos.persistencia", havingValue = "memoria")
    PedidoRepository pedidoRepositoryEnMemoria() {
        return new InMemoryPedidoRepository();
    }
}
```

> Fíjate en el paquete: esta configuración vive **dentro** del adaptador de
> persistencia, así puede ver `PedidoJpaRepository` y el adaptador aunque sean
> package-private, y se cumple la regla ArchUnit "Spring Data solo se usa en
> persistencia" del [bloque 6](06-verificar-la-arquitectura-con-archunit.md).
> Si usas esta configuración, **quita** el `@Component` de
> `PedidoPersistenceAdapter` (bloque 2) o tendrás dos beans del mismo puerto.
> `InMemoryPedidoRepository` debe ser visible desde aquí (público, en
> `src/main` si se usa en el perfil local).

```yaml
# application-local.yml
pedidos:
  persistencia: memoria
```

| Mecanismo                | Granularidad                    | Úsalo para                                  |
|--------------------------|---------------------------------|---------------------------------------------|
| `@Profile("prod")`       | Entorno completo                | "En prod todo real, en local todo fake"     |
| `@ConditionalOnProperty` | Por adaptador                   | "Kafka real pero email fake en staging"     |
| `@ConditionalOnMissingBean` | Valor por defecto sobreescribible | Librerías / starters propios           |
| `@Primary`               | Desempate                       | Composite / decorador sobre varios candidatos |

> Trampa: dos beans del mismo puerto activos a la vez →
> `NoUniqueBeanDefinitionException` en el arranque. Las condiciones deben
> ser **mutuamente excluyentes**.

---

## 8. Que lo imponga el compilador: Maven multi-módulo

Los paquetes son **convención**: nada impide escribir
`import ...persistence.PedidoJpaEntity` en un servicio. Los **módulos** son
**regla**: si `application` no declara la dependencia, **no compila**.

```
pedidos/
├── pom.xml                  (parent, packaging pom)
├── pedidos-domain/          dominio puro           → sin dependencias
├── pedidos-application/     puertos + servicios    → domain
├── pedidos-infrastructure/  adaptadores + config   → application, Spring, JPA...
└── pedidos-bootstrap/       @SpringBootApplication → infrastructure (jar ejecutable)
```

```
   ┌───────────────────┐
   │ pedidos-bootstrap │  (main, empaqueta el jar)
   └─────────┬─────────┘
             ▼
   ┌────────────────────────┐
   │ pedidos-infrastructure │──► spring-boot-starter-web, data-jpa, kafka, mail
   └─────────┬──────────────┘
             ▼
   ┌─────────────────────┐
   │ pedidos-application │──► (nada de Spring)
   └─────────┬───────────┘
             ▼
   ┌────────────────┐
   │ pedidos-domain │──► (nada)
   └────────────────┘
```

### 8.1 POM padre

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.curso</groupId>
    <artifactId>pedidos</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>pedidos-domain</module>
        <module>pedidos-application</module>
        <module>pedidos-infrastructure</module>
        <module>pedidos-bootstrap</module>
    </modules>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.curso</groupId>
                <artifactId>pedidos-domain</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.curso</groupId>
                <artifactId>pedidos-application</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.curso</groupId>
                <artifactId>pedidos-infrastructure</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <!-- Dependencias de test comunes a todos los módulos -->
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

> Usar `spring-boot-starter-parent` como padre da gestión de versiones a
> **todos** los módulos, pero **no** añade dependencias de Spring a ninguno:
> `dependencyManagement` solo fija versiones. El dominio sigue limpio.

### 8.2 `pedidos-domain/pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.curso</groupId>
        <artifactId>pedidos</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>pedidos-domain</artifactId>
    <!-- Sin <dependencies>: Java puro -->
</project>
```

### 8.3 `pedidos-application/pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.curso</groupId>
        <artifactId>pedidos</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>pedidos-application</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.curso</groupId>
            <artifactId>pedidos-domain</artifactId>
        </dependency>
    </dependencies>
</project>
```

### 8.4 `pedidos-infrastructure/pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.curso</groupId>
        <artifactId>pedidos</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>pedidos-infrastructure</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.curso</groupId>
            <artifactId>pedidos-application</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-mail</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <version>3.9.1</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 8.5 `pedidos-bootstrap/pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.curso</groupId>
        <artifactId>pedidos</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>pedidos-bootstrap</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.curso</groupId>
            <artifactId>pedidos-infrastructure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Solo el módulo ejecutable genera el fat jar -->
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

```java
package com.curso.pedidos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication   // escanea com.curso.pedidos.** (incluye infrastructure.config)
public class PedidosApplication {
    public static void main(String[] args) {
        SpringApplication.run(PedidosApplication.class, args);
    }
}
```

> Trampa: poner `spring-boot-maven-plugin` en el padre. Reempaqueta **todos**
> los módulos como jars ejecutables y los demás módulos ya no pueden usarlos
> como dependencia normal.

> Trampa: `<scope>compile</scope>` transitivo. `bootstrap` ve `application`
> y `domain` a través de `infrastructure`. Es correcto (necesita todo para
> arrancar), pero **no** escribas código de negocio en `bootstrap`.

### 8.6 ¿Qué pasa si alguien rompe la regla?

```java
// En pedidos-application
import jakarta.persistence.Entity;   // ❌
```

```bash
$ mvn -q compile
[ERROR] .../ConfirmarPedidoService.java:[3,26] package jakarta.persistence does not exist
```

El error aparece **en el portátil, al compilar**, no en una revisión de
código tres semanas después.

---

## 9. Paquetes vs módulos Maven vs JPMS

| Mecanismo                         | Qué impone                                         | Coste                         | Cuándo                                    |
|-----------------------------------|----------------------------------------------------|-------------------------------|-------------------------------------------|
| **Paquetes** (+ package-private)  | Nada (convención) salvo visibilidad de clase       | Cero                          | Proyectos pequeños; siempre + ArchUnit    |
| **Módulos Maven/Gradle**          | Dependencias entre artefactos en compilación       | Más poms, build más lento     | Equipos medianos/grandes, larga vida      |
| **JPMS** (`module-info.java`)     | Qué paquetes se **exportan**; encapsulación fuerte | Fricción con reflexión (Spring, Hibernate necesitan `opens`) | Librerías, casos muy específicos |
| **ArchUnit** (bloque 6)           | Reglas arbitrarias, en tests                       | Bajo                          | Siempre, complementa a los anteriores     |

Ejemplo JPMS para el dominio:

```java
// pedidos-domain/src/main/java/module-info.java
module com.curso.pedidos.domain {
    exports com.curso.pedidos.domain.model;
    exports com.curso.pedidos.domain.event;
    exports com.curso.pedidos.domain.exception;
    // domain.service NO se exporta si es interno
}
```

En la práctica, en el ecosistema Spring Boot, la combinación más rentable es
**paquetes + ArchUnit** para empezar y **módulos Maven + ArchUnit** cuando el
equipo o el sistema crecen. JPMS rara vez compensa en aplicaciones Spring.

> Alternativa con menos módulos: `domain` + `application` en un único módulo
> `core` y `infrastructure` + `bootstrap` en otro. Ya obtienes la garantía
> clave (el núcleo no ve Spring) con solo dos artefactos.

---

## 10. Testing del cableado

Un test barato que detecta beans duplicados, faltantes o condiciones mal
escritas:

```java
package com.curso.pedidos;

import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.infrastructure.adapter.out.messaging.InMemoryPublicadorDeEventos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "pedidos.persistencia=memoria")
@ActiveProfiles("local")
class CableadoLocalTest {

    @Autowired PublicadorDeEventos publicador;
    @Autowired PedidoRepository pedidoRepository;

    @Test
    void en_local_se_usan_los_fakes() {
        assertThat(publicador).isInstanceOf(InMemoryPublicadorDeEventos.class);
        assertThat(pedidoRepository.getClass().getSimpleName())
                .isEqualTo("InMemoryPedidoRepository");
    }
}
```

Y para probar condiciones **sin** arrancar la aplicación entera,
`ApplicationContextRunner` de Spring Boot:

```java
class PersistenciaConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PersistenciaConfiguration.class);

    @Test
    void con_memoria_registra_el_repositorio_en_memoria() {
        runner.withPropertyValues("pedidos.persistencia=memoria")
              .run(ctx -> assertThat(ctx)
                      .hasSingleBean(PedidoRepository.class)
                      .getBean(PedidoRepository.class)
                      .isInstanceOf(InMemoryPedidoRepository.class));
    }
}
```

---

## 11. Resumen

| Pregunta                                  | Respuesta                                                  |
|-------------------------------------------|------------------------------------------------------------|
| ¿Quién crea los servicios de aplicación?  | La composition root (`BeanConfiguration`)                  |
| ¿Y si quiero menos código?                | `@UseCase` propia + `@ComponentScan(includeFilters)`       |
| ¿Cómo cambio de adaptador?                | `@Profile` (por entorno) o `@ConditionalOnProperty` (fino) |
| ¿Cómo impido imports prohibidos?          | Módulos Maven (compilador) + ArchUnit (tests)              |
| ¿JPMS?                                    | Raramente compensa con Spring                              |

---

## Ejercicio

Convierte el proyecto de un solo módulo en **dos** módulos: `pedidos-core`
(domain + application) y `pedidos-app` (infrastructure + bootstrap). Después
intenta añadir `@Service` a un servicio de `pedidos-core`.

Pista: `pedidos-core` no debe tener ninguna dependencia salvo las de test.

<details><summary>Solución</summary>

- `pedidos-core/pom.xml`: sin `<dependencies>` de compilación (solo JUnit,
  AssertJ y Mockito heredadas en scope test).
- `pedidos-app/pom.xml`: depende de `pedidos-core` + starters de Spring +
  `spring-boot-maven-plugin`.
- Al añadir `import org.springframework.stereotype.Service;` en
  `pedidos-core`, `mvn compile` falla con
  `package org.springframework.stereotype does not exist`.
- Para registrar los servicios: `BeanConfiguration` en `pedidos-app`
  (opción A) o `@UseCase` definida en `pedidos-core` y escaneada desde
  `pedidos-app` (opción C). La opción B (`@Service`) **ya no es posible**: esa
  es exactamente la garantía que buscábamos.

</details>

---

## Referencias

- Robert C. Martin, *The Clean Architecture* (2012):
  https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- Robert C. Martin, *Clean Architecture* (2017) — *The Dependency Rule*,
  *Main component*.
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023) —
  capítulos *Assembling the Application* y *Enforcing Architecture
  Boundaries*; https://github.com/thombergs/buckpal
- Spring Boot docs (profiles, `@Conditional*`, `ApplicationContextRunner`):
  https://docs.spring.io/spring-boot/index.html
- Spring Framework — Transaction Management (`TransactionTemplate`):
  https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- jMolecules: https://github.com/xmolecules/jmolecules

---

## Mantra

> *"Una convención se olvida un viernes por la tarde; un error de compilación,
> no."*
