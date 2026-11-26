# Tests y verificación

Los tests son parte del entregable, no un extra: demuestran que el código generado cumple
las reglas del patrón. Paquetes espejo de `src/main` en `src/test`.

---

## 1. Test del adaptador (`@DataJpaTest`)

Uno por agregado. `@Import` el adaptador, su mapper y **todos los mappers de los que
dependa** (y los adaptadores de los agregados referenciados si el test necesita darlos de
alta). `@DataJpaTest` ejecuta Flyway contra la H2 embebida.

```java
package {{pkg.persistenceAgg}};

import {{pkg.domainException}}.NotFoundException;
import {{pkg.domainModel}}.{{Agg}};
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({ {{Agg}}RepositoryJpa.class, {{Agg}}JpaAdapter.class /* , mappers de los que depende */ })
class {{Agg}}RepositoryJpaTest {

    @Autowired {{Agg}}RepositoryJpa {{aggs}};
    @Autowired TestEntityManager em;

    @Test
    void guarda_y_reconstruye_sin_emitir_eventos() {
        {{Agg}} original = /* fábrica de creación del dominio, p. ej. {{Agg}}.create(...) */;

        {{aggs}}.add(original);
        em.flush();
        em.clear();                                       // obliga a leer de la BD

        {{Agg}} leido = {{aggs}}.get({{idOfOriginal}});
        assertEquals(original, leido);
        // compara también los campos/colecciones relevantes, no solo equals por id
        assertTrue(leido.pullDomainEvents().isEmpty());   // solo si el agregado emite eventos
    }

    @Test
    void get_de_id_inexistente_lanza_not_found() {
        assertThrows(NotFoundException.class, () -> {{aggs}}.get({{randomId}}));
    }

    // Si compone IUpdateJpa:
    @Test
    void update_de_inexistente_lanza_not_found() {
        {{Agg}} nunca_guardado = /* fábrica */;
        assertThrows(NotFoundException.class, () -> {{aggs}}.update(nunca_guardado));
    }

    // Si compone IRemoveJpa:
    @Test
    void remove_borra_y_luego_get_lanza() {
        {{Agg}} original = /* fábrica */;
        {{aggs}}.add(original);
        em.flush();

        {{aggs}}.remove({{idOfOriginal}});
        em.flush();
        em.clear();

        assertThrows(NotFoundException.class, () -> {{aggs}}.get({{idOfOriginal}}));
    }
}
```

Si el agregado referencia a otro (`Pizza` → `Ingredient`), da de alta los referenciados
con su propio adaptador antes de guardar el agregado (la FK lo exige).

---

## 2. `LocatorTest` (solo si hay Locator)

Unitario, sin Spring. Usa un *fake* con nombre (no lambda).

```java
package {{pkg.locator}};

import {{pkg.portLookup}}.ILookup;
import {{pkg.domainException}}.NotFoundException;
import {{pkg.domainModel}}.{{Other}};
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocatorTest {

    static final {{Other}} CONOCIDO = /* instancia válida con id fijo */;

    static class Fake{{Other}}Lookup implements ILookup<{{Other}}> {
        @Override public {{Other}} find(UUID id) {
            if ({{idOfConocido}}.equals(id)) return CONOCIDO;
            throw new NotFoundException({{Other}}.class, id);
        }
    }

    Locator locator = new Locator(List.of(new Fake{{Other}}Lookup()));

    @Test
    void resuelve_por_tipo() {
        assertEquals(CONOCIDO, locator.find({{Other}}.class, {{idOfConocido}}));
    }

    @Test
    void falla_entero_si_falta_un_id() {
        var ids = List.of({{idOfConocido}}, UUID.randomUUID());
        assertThrows(NotFoundException.class, () -> locator.findAll({{Other}}.class, ids));
    }

    @Test
    void falla_si_no_hay_lookup_para_el_tipo() {
        assertThrows(NoLookupRegisteredException.class,
                () -> locator.find(String.class, UUID.randomUUID()));
    }

    @Test
    void rechaza_lookups_duplicados() {
        assertThrows(IllegalStateException.class, () -> new Locator(
                List.of(new Fake{{Other}}Lookup(), new Fake{{Other}}Lookup())));
    }
}
```

---

## 3. Regla ArchUnit

Si el proyecto ya tiene un test de arquitectura (p. ej. `ArquitecturaHexagonalTest`),
comprueba que cubre esto y, si no, **añade** la regla allí en vez de crear otro fichero.
Ajusta los paquetes al perfil.

```java
package {{base}}.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "{{base}}", importOptions = ImportOption.DoNotIncludeTests.class)
class PersistenciaArquitecturaTest {

    @ArchTest
    static final ArchRule dominio_y_aplicacion_sin_spring_ni_jpa = noClasses()
            .that().resideInAnyPackage("{{base}}.domain..", "{{base}}.application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..");

    @ArchTest
    static final ArchRule ningun_adaptador_lanza_unsupported = noClasses()
            .that().resideInAPackage("{{base}}.infrastructure..")
            .should().dependOnClassesThat().areAssignableTo(UnsupportedOperationException.class);
}
```

---

## Checklist final

Revisa cada punto antes de entregar. Si alguno no se cumple, corrígelo o explícalo.

- [ ] `mvn verify` (o equivalente) en verde, ejecutado de verdad.
- [ ] El dominio no se ha modificado (`git diff -- '**/domain/**'` vacío), salvo permiso
      explícito del usuario.
- [ ] Ni un `import` de Spring ni de JPA en `domain` ni en `application` (la regla ArchUnit
      lo verifica).
- [ ] Ningún adaptador lanza `UnsupportedOperationException` ni declara operaciones que no
      se eligieron.
- [ ] Cada `{{Agg}}RepositoryJpa` solo contiene constructor, métodos de `IRepositoryJpa`
      y, como mucho, `get`.
- [ ] Ningún mapper usa la fábrica de creación del dominio.
- [ ] Ninguna relación JPA hace `cascade` hacia otro agregado.
- [ ] Los lookups son clases con nombre y ningún servicio los conoce: solo `Locator`.
- [ ] La migración es nueva (no se editó ninguna existente) y su número de versión es el
      siguiente libre.
