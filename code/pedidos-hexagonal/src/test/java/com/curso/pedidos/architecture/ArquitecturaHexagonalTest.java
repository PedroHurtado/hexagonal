package com.curso.pedidos.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.onionArchitecture;

/** Las reglas de dependencia del hexágono, verificadas en cada build. */
@AnalyzeClasses(packages = "com.curso.pedidos", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaHexagonalTest {

    @ArchTest
    static final ArchRule dominioNoDependeDeFrameworks = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "jakarta.validation..",
                    "com.fasterxml.jackson..", "org.hibernate..")
            .because("el dominio es Java puro");

    @ArchTest
    static final ArchRule aplicacionNoDependeDeFrameworks = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "com.fasterxml.jackson..", "org.hibernate..")
            .because("los casos de uso no conocen el framework; se cablean en BeanConfiguration");

    @ArchTest
    static final ArchRule dominioNoDependeDeAplicacionNiInfraestructura = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..");

    @ArchTest
    static final ArchRule aplicacionNoDependeDeInfraestructura = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule adaptadoresNoSeConocenEntreSi = noClasses()
            .that().resideInAPackage("..adapter.in..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.out..");

    @ArchTest
    static final ArchRule adaptadoresDeSalidaTerminanEnAdapter = classes()
            .that().resideInAPackage("..adapter.out..")
            .and().implement(com.curso.pedidos.application.port.out.PedidoRepository.class)
            .should().haveSimpleNameEndingWith("Adapter");

    @ArchTest
    static final ArchRule cebolla = onionArchitecture()
            .domainModels("..domain.model..", "..domain.event..", "..domain.exception..")
            .domainServices("..domain.service..")
            .applicationServices("..application..")
            .adapter("web", "..adapter.in.web..")
            .adapter("persistence", "..adapter.out.persistence..")
            .adapter("catalogo", "..adapter.out.catalogo..")
            .adapter("events", "..adapter.out.events..")
            // El composition root (config) cablea todas las capas: es la excepción deliberada.
            .ignoreDependency(resideInAPackage("..infrastructure.config.."), alwaysTrue())
            .because("las dependencias apuntan siempre hacia el dominio");
}
