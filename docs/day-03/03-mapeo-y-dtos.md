# Día 3 — Bloque 3: Mapeo y DTOs

> *"A Data Transfer Object is one of those objects our mothers told us never to write."*
> — Martin Fowler, *Patterns of Enterprise Application Architecture* (2002)

---

## 1. El problema en una frase

Si devolvemos el agregado `Pedido` tal cual en el JSON, **el contrato público de la API queda soldado al modelo de dominio**: cada refactor interno rompe a los clientes, y cada necesidad de los clientes deforma el dominio.

---

## 2. Por qué no exponer el dominio

### Bad: el agregado como respuesta

```java
@GetMapping("/{id}")
Pedido consultar(@PathVariable UUID id) {
    return consultarPedido.consultar(new PedidoId(id));
}
```

JSON resultante (aproximado):

```json
{
  "id": { "value": "3f2a1c9e-..." },
  "clienteId": { "value": "1111..." },
  "lineas": [ { "productoId": { "sku": "LIB-001" }, "cantidad": { "valor": 2 }, ... } ],
  "estado": "BORRADOR",
  "creadoEn": "2026-11-23T10:00:00Z",
  "eventosPendientes": [ ... ]
}
```

### ¿Qué duele?

| Dolor                                | Ejemplo                                                                                   |
|--------------------------------------|-------------------------------------------------------------------------------------------|
| **Forma fea y accidental**           | `"id": {"value": ...}` porque `PedidoId` es un record. El cliente no debería saberlo.     |
| **Fuga de detalles internos**        | `eventosPendientes`, campos técnicos o calculados que nadie debería ver.                  |
| **Acoplamiento de versiones**        | Renombrar `cantidad` → `unidades` en el dominio rompe todos los clientes móviles.         |
| **Jackson invade el dominio**        | Para arreglar lo anterior acabas poniendo `@JsonProperty`, `@JsonIgnore` en `Pedido`.     |
| **Entrada peor todavía**             | Deserializar `Pedido` exige constructor vacío/setters ⇒ adiós invariantes.                |
| **Seguridad (mass assignment)**      | Un `@RequestBody Pedido` permite al cliente mandar `"estado": "PAGADO"`.                  |
| **Recursión / lazy loading**         | Si un día el dominio se mezcla con JPA: `LazyInitializationException` o ciclos infinitos. |

En cristiano: **el dominio es para pensar, el DTO es para hablar**. Son dos modelos con dos razones distintas para cambiar (SRP aplicado a modelos).

### Los modelos que conviven en el hexágono

```
   ┌────────────────────┐      ┌──────────────────────┐      ┌──────────────────┐
   │  Modelo de API     │      │   Modelo de dominio  │      │ Modelo persist.  │
   │  (adapter.in.web)  │      │   (domain.model)     │      │ (adapter.out.    │
   │                    │      │                      │      │  persistence)    │
   │ CrearPedidoRequest │ ───► │ Command ─► Pedido    │ ───► │ PedidoJpaEntity  │
   │ PedidoResponse     │ ◄─── │ LineaPedido, Dinero  │ ◄─── │ LineaJpaEntity   │
   └────────────────────┘      └──────────────────────┘      └──────────────────┘
        cambia cuando              cambia cuando               cambia cuando
        cambia la API              cambia el negocio           cambia el esquema
        (versionado)               (reglas)                    (BD, rendimiento)
            hoy                       día 2                       día 4
```

Hombergs llama a esto **"full mapping strategy"** (mapeo completo entre capas) y lo compara con alternativas:

| Estrategia (Hombergs)     | Descripción                                                     | Cuándo                                         |
|---------------------------|-----------------------------------------------------------------|------------------------------------------------|
| No mapping                | Todas las capas usan el mismo modelo                            | CRUD trivial, prototipo                        |
| Two-way mapping           | Cada capa su modelo; el puerto usa el modelo de dominio         | **Lo que hacemos hoy** (por defecto)           |
| Full mapping              | Además, comando de entrada específico por caso de uso           | Casos de uso con entradas distintas (también hoy: `XxxCommand`) |
| One-way mapping           | Interfaces comunes de "estado" implementadas por cada capa      | Raro; muchos modelos parecidos                 |

En nuestro curso: **DTO web → `Command` (puerto) → dominio** en la entrada, y **dominio → DTO** en la salida.

---

## 3. Estrategias de mapeo

Tres formas de escribir la traducción, de más explícita a más automática.

### 3.1 Mapper manual (clase dedicada)

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.application.port.in.AnadirLineaPedidoCommand;
import com.curso.pedidos.application.port.in.CrearPedidoCommand;
import com.curso.pedidos.domain.model.*;

import java.util.UUID;

final class PedidoWebMapper {

    private PedidoWebMapper() { }

    // ── Entrada: DTO ─► Command ─────────────────────────────────────────
    static CrearPedidoCommand toCommand(CrearPedidoRequest request) {
        return new CrearPedidoCommand(new ClienteId(request.clienteId()));
    }

    static AnadirLineaPedidoCommand toCommand(UUID pedidoId, AnadirLineaRequest request) {
        return new AnadirLineaPedidoCommand(
                new PedidoId(pedidoId),
                new ProductoId(request.sku()),
                new Cantidad(request.cantidad()));
    }

    // ── Salida: dominio ─► DTO ─────────────────────────────────────────
    static PedidoResponse toResponse(Pedido pedido) {
        return new PedidoResponse(
                pedido.id().valor(),
                pedido.clienteId().valor(),
                pedido.estado().name(),
                pedido.creadoEn(),
                pedido.lineas().stream().map(PedidoWebMapper::toResponse).toList(),
                toResponse(pedido.total()));
    }

    static LineaPedidoResponse toResponse(LineaPedido linea) {
        return new LineaPedidoResponse(
                linea.productoId().sku(),
                linea.cantidad().valor(),
                toResponse(linea.precioUnitario()),
                toResponse(linea.subtotal()));
    }

    static ImporteResponse toResponse(Dinero dinero) {
        return new ImporteResponse(dinero.importe(), dinero.moneda().getCurrencyCode());
    }
}
```

Uso en el controlador:

```java
@PostMapping("/{id}/lineas")
ResponseEntity<Void> anadirLinea(@PathVariable UUID id, @Valid @RequestBody AnadirLineaRequest request) {
    anadirLinea.ejecutar(PedidoWebMapper.toCommand(id, request));
    return ResponseEntity.noContent().build();
}
```

**A favor**: explícito, depurable, sin magia, el compilador detecta todo. Cero dependencias.
**En contra**: más líneas; con modelos de 30 campos se vuelve tedioso.

> ¿Clase con métodos estáticos o `@Component` inyectable? Estáticos son lo más simple y no necesitan Spring. Un `@Component` es útil si el mapper necesita dependencias (p. ej. un `Clock` o un servicio de i18n). Ojo: `@WebMvcTest` **no** carga `@Component`s normales; habría que añadir `@Import(PedidoWebMapper.class)` al test.

### 3.2 Factorías estáticas en el propio DTO

Es lo que usamos en el bloque 2:

```java
public record PedidoResponse(UUID id, UUID clienteId, String estado, Instant creadoEn,
                             List<LineaPedidoResponse> lineas, ImporteResponse total) {
    static PedidoResponse desde(Pedido pedido) { /* ... */ }
}

public record AnadirLineaRequest(@NotBlank String sku, @NotNull @Positive Integer cantidad) {
    AnadirLineaPedidoCommand toCommand(UUID pedidoId) {
        return new AnadirLineaPedidoCommand(new PedidoId(pedidoId),
                                            new ProductoId(sku), new Cantidad(cantidad));
    }
}
```

**A favor**: el mapeo vive junto a los datos que mapea; muy cohesivo; fácil de encontrar.
**En contra**: el DTO conoce el dominio (aceptable: la flecha va hacia dentro). Si el mismo DTO se reutiliza en varios contextos con mapeos distintos, se complica.

> **Regla de dirección**: `PedidoResponse.desde(Pedido)` ✅ (adaptador → dominio). `Pedido.toResponse()` ❌ (dominio → adaptador, viola la regla de dependencias).

### 3.3 MapStruct (generación en compilación)

MapStruct genera **en tiempo de compilación** la implementación del mapper a partir de una interfaz. No usa reflexión en tiempo de ejecución: el código generado es tan rápido como el manual y se puede leer en `target/generated-sources`.

#### `pom.xml`

```xml
<properties>
    <java.version>17</java.version>
    <mapstruct.version>1.6.3</mapstruct.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
        <version>${mapstruct.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.mapstruct</groupId>
                        <artifactId>mapstruct-processor</artifactId>
                        <version>${mapstruct.version}</version>
                    </path>
                    <!-- Si usas Lombok en adaptadores, Lombok va ANTES y añade
                         lombok-mapstruct-binding -->
                </annotationProcessorPaths>
                <compilerArgs>
                    <arg>-Amapstruct.defaultComponentModel=spring</arg>
                    <arg>-Amapstruct.unmappedTargetPolicy=ERROR</arg>
                </compilerArgs>
            </configuration>
        </plugin>
    </plugins>
</build>
```

`unmappedTargetPolicy=ERROR` es **imprescindible**: si añades un campo al DTO y olvidas mapearlo, la compilación falla en vez de devolver `null` en silencio.

#### El mapper

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.domain.model.*;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper   // componentModel=spring por configuración global ⇒ es un bean
interface PedidoWebMapStructMapper {

    @Mapping(target = "id",        expression = "java(pedido.id().valor())")
    @Mapping(target = "clienteId", expression = "java(pedido.clienteId().valor())")
    @Mapping(target = "estado",    expression = "java(pedido.estado().name())")
    @Mapping(target = "creadoEn",  expression = "java(pedido.creadoEn())")
    @Mapping(target = "lineas",    expression = "java(toLineas(pedido.lineas()))")
    @Mapping(target = "total",     expression = "java(toResponse(pedido.total()))")
    PedidoResponse toResponse(Pedido pedido);

    @Mapping(target = "sku",            expression = "java(linea.productoId().sku())")
    @Mapping(target = "cantidad",       expression = "java(linea.cantidad().valor())")
    @Mapping(target = "precioUnitario", expression = "java(toResponse(linea.precioUnitario()))")
    @Mapping(target = "subtotal",       expression = "java(toResponse(linea.subtotal()))")
    LineaPedidoResponse toResponse(LineaPedido linea);

    List<LineaPedidoResponse> toLineas(List<LineaPedido> lineas);   // MapStruct itera por nosotros

    default ImporteResponse toResponse(Dinero dinero) {          // Dinero es record: también valdría
        return new ImporteResponse(dinero.importe(), dinero.moneda().getCurrencyCode());
    }
}
```

¿Por qué tanta `expression`? **Trampa importante**: MapStruct detecta como propiedades los **getters JavaBean** (`getId()`) y los **componentes de un `record`**. `Pedido` y `LineaPedido` son **clases** (entidades, no records) con accesores al estilo record (`id()`, `lineas()`), que MapStruct **no reconoce** por defecto. Opciones:

1. `expression = "java(...)"` como arriba (explícito, pero ya casi es mapeo manual).
2. Un `AccessorNamingStrategy` propio registrado vía SPI que trate `xxx()` como getter.
3. Añadir `getXxx()` al dominio **solo para MapStruct** ❌ (el adaptador condiciona al dominio).

Lo que sí funciona de serie: un origen que sea `record` (p. ej. `Dinero`, los VOs) se mapea por nombre de componente, y rutas anidadas como `source = "precioUnitario.importe"` funcionan cuando cada eslabón es record o JavaBean.

Moraleja: **con un dominio rico de accesores fluidos, MapStruct pierde gran parte de su gracia**. Brilla con DTOs y entidades JPA "planas" con getters (lo veremos en el día 4 con `PedidoJpaEntity`).

Notas importantes:

- MapStruct soporta **records como destino** (usa el constructor canónico) desde la versión 1.5.
- Para tipos "raros" (`Dinero` → `ImporteResponse`) un método `default` es más claro que una ristra de `@Mapping`.
- La **entrada** (DTO → Command con VOs que validan en el constructor) suele quedar más clara a mano; muchos equipos usan MapStruct **solo para la salida**.
- MapStruct es infraestructura: **nunca** en `domain` ni `application`.

Para usarlo en `@WebMvcTest`, como es un bean de Spring generado, hay que importarlo:

```java
@WebMvcTest(PedidoController.class)
@Import(PedidoWebMapStructMapperImpl.class)
class PedidoControllerTest { /* ... */ }
```

---

## 4. Trade-offs

| Criterio                         | Manual (clase)        | Estático en DTO       | MapStruct                          |
|----------------------------------|-----------------------|-----------------------|------------------------------------|
| Dependencias extra               | Ninguna               | Ninguna               | `mapstruct` + annotation processor |
| Líneas de código                 | Muchas                | Muchas                | Pocas                              |
| Errores detectados en compilación| Todos                 | Todos                 | Con `unmappedTargetPolicy=ERROR`   |
| Legibilidad del mapeo            | Total                 | Total                 | Media (anotaciones + generado)     |
| Depuración                       | Trivial               | Trivial               | Hay que mirar el código generado   |
| VOs con validación en constructor| Natural               | Natural               | Requiere métodos `default` o `@ObjectFactory` |
| Curva de aprendizaje             | Nula                  | Nula                  | Baja-media                         |
| Encaja con…                      | Modelos medianos      | Pocos DTOs, cohesión  | Muchos DTOs grandes y planos       |

Recomendación del curso:

1. **Empieza manual** (estático en el DTO o clase `XxxWebMapper`). En un hexágono bien cortado, cada adaptador mapea pocos tipos.
2. Pasa a **MapStruct** cuando el mapeo sea mayoritariamente "campo a campo con el mismo nombre" y empiece a doler.
3. **Evita** mapeadores por reflexión en runtime (ModelMapper, `BeanUtils.copyProperties`): fallan en silencio, son lentos y se llevan mal con records y VOs inmutables.

### Trampa: el mapper que toma decisiones

```java
static PedidoResponse toResponse(Pedido p) {
    String estado = p.lineas().isEmpty() ? "VACIO" : p.estado().name();   // ¡regla inventada!
    BigDecimal total = p.total().importe().multiply(new BigDecimal("1.21")); // ¡IVA en el mapper!
    ...
}
```

El IVA es negocio ⇒ dominio (`CalculadoraDePrecios` del día 2). El mapper **copia y formatea**; no calcula ni interpreta.

### Trampa: un DTO "universal"

`PedidoDto` usado para crear, actualizar y responder, con la mitad de campos `null` según el caso. Mejor **un DTO por intención**: `CrearPedidoRequest`, `AnadirLineaRequest`, `PedidoResponse`, `PedidoResumenResponse`. Los records hacen que sean baratos.

### Trampa: exponer enums del dominio

`EstadoPedido` en el DTO funciona, pero acopla el contrato a los nombres del enum. Mapear a `String` (`estado().name()`) o a un enum propio de la API te deja renombrar internamente sin romper clientes.

---

## 5. Testing del mapper

Los mappers son funciones puras: test unitario sin Spring, rapidísimo. Es especialmente valioso cuando hay **aplanamientos** o **conversiones de formato**.

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.application.port.in.AnadirLineaPedidoCommand;
import com.curso.pedidos.domain.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.curso.pedidos.infrastructure.adapter.in.web.PedidoMother.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PedidoWebMapperTest {

    @Test
    void toResponse_aplanaValueObjectsYCalculaSubtotales() {
        PedidoResponse dto = PedidoWebMapper.toResponse(pedidoConUnaLinea());

        assertThat(dto.id()).isEqualTo(PEDIDO_ID);
        assertThat(dto.clienteId()).isEqualTo(CLIENTE_ID);
        assertThat(dto.estado()).isEqualTo("BORRADOR");
        assertThat(dto.creadoEn()).isEqualTo(AHORA);
        assertThat(dto.lineas()).singleElement().satisfies(linea -> {
            assertThat(linea.sku()).isEqualTo("LIB-001");
            assertThat(linea.cantidad()).isEqualTo(2);
            assertThat(linea.precioUnitario().importe()).isEqualByComparingTo("15.50");
            assertThat(linea.subtotal().importe()).isEqualByComparingTo("31.00");
        });
        assertThat(dto.total()).isEqualTo(new ImporteResponse(new BigDecimal("31.00"), "EUR"));
    }

    @Test
    void toCommand_construyeValueObjects() {
        var request = new AnadirLineaRequest("LIB-001", 3);

        AnadirLineaPedidoCommand cmd = PedidoWebMapper.toCommand(PEDIDO_ID, request);

        assertThat(cmd).isEqualTo(new AnadirLineaPedidoCommand(
                new PedidoId(PEDIDO_ID), new ProductoId("LIB-001"), new Cantidad(3)));
    }

    @Test
    void toCommand_conCantidadInvalida_elDominioSeProtegeAunqueFalleLaValidacionDelBorde() {
        var request = new AnadirLineaRequest("LIB-001", -1);   // nadie llamó a @Valid

        assertThatThrownBy(() -> PedidoWebMapper.toCommand(PEDIDO_ID, request))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

> `isEqualByComparingTo("31.00")` compara `BigDecimal` ignorando la escala (`31.0` vs `31.00`). Con `isEqualTo` fallaría si la escala difiere: trampa típica con dinero. En el último `assertThat(dto.total()).isEqualTo(...)` sí usamos `equals` del record porque controlamos la escala en el fixture; si os da guerra, comparad campo a campo.

El tercer test es didácticamente importante: **demuestra que el dominio es la red de seguridad** aunque el borde falle.

### Test de MapStruct

Idéntico, instanciando la implementación generada:

```java
class PedidoWebMapStructMapperTest {
    private final PedidoWebMapStructMapper mapper = new PedidoWebMapStructMapperImpl();

    @Test
    void mapeaIgualQueElManual() {
        var pedido = pedidoConUnaLinea();
        assertThat(mapper.toResponse(pedido)).isEqualTo(PedidoWebMapper.toResponse(pedido));
    }
}
```

Un test que compara ambos mappers es un truco útil **durante una migración** de manual a MapStruct.

---

## 6. Resumen

| Idea                                    | En una línea                                                       |
|-----------------------------------------|--------------------------------------------------------------------|
| No exponer el dominio                   | El contrato de API y el modelo de negocio cambian por motivos distintos |
| Un DTO por intención                    | Request de crear ≠ request de añadir línea ≠ response              |
| Dirección del mapeo                     | Siempre en el adaptador; el dominio no conoce DTOs                 |
| Entrada                                 | DTO → `Command` con value objects (validan en constructor)         |
| Salida                                  | Dominio → DTO plano (sin VOs anidados, sin enums internos)         |
| Estrategia por defecto                  | Manual; MapStruct cuando el volumen lo justifique                  |
| Testing                                 | Unitario, sin Spring, con fixture de dominio                       |

---

## Ejercicio

El equipo de móvil pide que `PedidoResponse` incluya `numeroDeLineas` y que los importes vengan formateados como texto `"31,00 €"` además del número.

1. ¿Dónde implementas cada cambio? ¿Toca el dominio?
2. Implementa el cambio en `PedidoWebMapper` y su test.

**Pista**: `NumberFormat.getCurrencyInstance(Locale.of("es", "ES"))` (Java 19+) o `new Locale("es", "ES")` en Java 17.

<details>
<summary>Solución</summary>

1. **Ninguno toca el dominio**. Son necesidades de presentación de un cliente concreto ⇒ solo DTO + mapper. `numeroDeLineas` es derivable (`lineas().size()`); el formato monetario es localización, cosa del borde.

```java
public record ImporteResponse(BigDecimal importe, String moneda, String formateado) { }

public record PedidoResponse(UUID id, UUID clienteId, String estado, Instant creadoEn,
                             int numeroDeLineas, List<LineaPedidoResponse> lineas,
                             ImporteResponse total) { }
```

```java
private static final Locale ES = new Locale("es", "ES");

static ImporteResponse toResponse(Dinero dinero) {
    NumberFormat formato = NumberFormat.getCurrencyInstance(ES);
    formato.setCurrency(dinero.moneda());
    return new ImporteResponse(dinero.importe(), dinero.moneda().getCurrencyCode(),
                               formato.format(dinero.importe()));
}

static PedidoResponse toResponse(Pedido pedido) {
    return new PedidoResponse(pedido.id().valor(), pedido.clienteId().valor(),
            pedido.estado().name(), pedido.creadoEn(), pedido.lineas().size(),
            pedido.lineas().stream().map(PedidoWebMapper::toResponse).toList(),
            toResponse(pedido.total()));
}
```

```java
@Test
void toResponse_incluyeNumeroDeLineasEImporteFormateado() {
    PedidoResponse dto = PedidoWebMapper.toResponse(pedidoConUnaLinea());

    assertThat(dto.numeroDeLineas()).isEqualTo(1);
    assertThat(dto.total().formateado())
            .isEqualToIgnoringWhitespace("31,00€");   // el JDK usa espacio duro antes de €
}
```

`NumberFormat` no es thread-safe: por eso se crea en cada llamada (o se usa un `ThreadLocal`).
</details>

---

## Referencias

- Martin Fowler, *Data Transfer Object* (P of EAA): https://martinfowler.com/eaaCatalog/dataTransferObject.html
- Martin Fowler, *Catalog of Patterns of Enterprise Application Architecture*: https://martinfowler.com/eaaCatalog/
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023), capítulo "Mapping between Boundaries"; https://github.com/thombergs/buckpal
- MapStruct Reference Guide: https://mapstruct.org/documentation/stable/reference/html/
- Spring Framework, *Spring Web MVC*: https://docs.spring.io/spring-framework/reference/web/webmvc.html
- AssertJ: https://assertj.github.io/doc/

---

## Mantra

> *"El dominio es para pensar, el DTO es para hablar. Quien los mezcla acaba pensando en JSON."*
