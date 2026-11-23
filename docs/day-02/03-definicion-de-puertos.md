# Día 2 — Bloque 3: Definición de puertos

> *"The application communicates over 'ports' to external agencies. The word 'port' is supposed to
> evoke thoughts of 'ports' in an operating system, where any device that adheres to the protocols
> of a port can be plugged into it."*
> — Alistair Cockburn, *Hexagonal Architecture* (2005)

---

## 1. El problema en una frase

Si el núcleo de la aplicación llama directamente a `JpaRepository`, a `RestTemplate` o recibe un
`HttpServletRequest`, **el núcleo queda soldado a esas tecnologías** y no hay forma de probarlo,
sustituirlas ni razonar sobre qué hace la aplicación sin leer el framework.

En cristiano: un puerto es **un enchufe con forma de negocio**. Lo que se enchufe detrás (MVC, JPA,
Kafka, un test) da igual mientras respete la forma.

---

## 2. Definición

Un **puerto** es la especificación de una conversación entre el hexágono y el mundo exterior,
expresada **en el lenguaje del dominio**. En Java, un puerto es casi siempre:

- una **`interface`**,
- con **métodos con nombre de negocio**,
- que recibe y devuelve **tipos del hexágono** (value objects, comandos, resultados),
- y que vive en el paquete `application.port.in` o `application.port.out`.

Cockburn insiste en que el puerto es un **propósito de conversación** ("for placing orders",
"for getting product data"), no una tecnología. Una forma práctica de nombrarlos que propone
*Hexagonal Architecture Explained* (Cockburn & Garrido de Paz, 2024) es "**For** + verbo-ing":
*ForPlacingOrders*, *ForStoringOrders*. Nosotros usamos los nombres del curso (`CrearPedidoUseCase`,
`PedidoRepository`), pero la idea es la misma: **el nombre cuenta para qué sirve, no cómo se hace**.

```
                 PUERTO = contrato                         ADAPTADOR = implementación concreta
          ┌───────────────────────────────┐
          │ «interface»                   │                ┌──────────────────────────────┐
          │ PedidoRepository              │◄───implements──│ PedidoPersistenceAdapter     │ JPA (día 4)
          ├───────────────────────────────┤                └──────────────────────────────┘
          │ buscarPorId(PedidoId)         │                ┌──────────────────────────────┐
          │ guardar(Pedido)               │◄───implements──│ PedidoRepositoryEnMemoria    │ tests (hoy)
          └───────────────────────────────┘                └──────────────────────────────┘
              vive DENTRO del hexágono                          viven FUERA del hexágono
```

---

## 3. El puerto como contrato del hexágono

Un puerto es **la frontera**. Todo lo que cruza esa línea tiene que ser comprensible para el negocio.

El contrato no es solo la firma Java; incluye la **semántica**:

- ¿`buscarPorId` devuelve `Optional.empty()` o lanza excepción si no existe?
- ¿`guardar` inserta y actualiza, o solo inserta?
- ¿`cobrar` es idempotente si se reintenta?

Documentadlo con Javadoc en el puerto y, mejor aún, **con un test de contrato** que toda
implementación deba pasar (lo vemos en el [bloque 4](04-clasificacion-de-puertos.md)).

---

## 4. Bad — puertos que filtran tecnología

```java
// ❌ Puerto de entrada que conoce HTTP
public interface PedidoUseCase {
    ResponseEntity<PedidoResponse> crear(HttpServletRequest request, @RequestBody PedidoRequest body);
}

// ❌ Puerto de salida que expone JPA
public interface PedidoRepository extends JpaRepository<PedidoJpaEntity, Long> {
    @Query("select p from PedidoJpaEntity p where p.estado = 'CONFIRMADO'")
    List<PedidoJpaEntity> confirmados();
}

// ❌ "Puerto" gordo: todo lo que se hace con pedidos en una sola interfaz
public interface PedidoService {
    PedidoDto crear(PedidoDto dto);
    PedidoDto confirmar(Long id);
    PedidoDto cancelar(Long id, String motivo);
    void pagar(Long id, String tarjeta);
    PedidoDto buscar(Long id);
    Page<PedidoDto> listar(Pageable pageable);
    void reenviarEmail(Long id);
    byte[] generarFacturaPdf(Long id);
}
```

### ¿Qué duele?

- **El hexágono importa Spring Web y Spring Data.** El paquete `application` ya no compila sin ellos.
- **`PedidoJpaEntity` cruza la frontera**: el servicio de aplicación trabaja con un objeto que existe
  para satisfacer a Hibernate (constructor vacío, setters, lazy loading...).
- **`PedidoDto` sirve para todo**: entrada, salida, web, cola... Un campo que necesita la web obliga a
  tocar el caso de uso.
- **El puerto gordo** obliga a quien lo implementa (o lo dobla en un test) a conocer 8 operaciones
  para usar 1. Cada cambio en `generarFacturaPdf` recompila y re-testea quien solo crea pedidos.
- **`Long id`, `String tarjeta`**: primitivos donde el dominio tiene `PedidoId` y tipos con reglas.

---

## 5. Good — puertos de entrada con comandos y resultados

### Un puerto por caso de uso

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;

public interface CrearPedidoUseCase {

    /**
     * Crea un pedido en BORRADOR con las líneas indicadas, tomando los precios del catálogo.
     *
     * @return el id del pedido creado
     * @throws com.curso.pedidos.domain.exception.PedidoException si alguna regla de negocio se incumple
     */
    PedidoId ejecutar(CrearPedidoCommand comando);
}
```

```java
package com.curso.pedidos.application.port.in;

public interface ConfirmarPedidoUseCase {

    /** Confirma un pedido en BORRADOR. Falla si no existe, está vacío o no hay stock. */
    void ejecutar(ConfirmarPedidoCommand comando);
}
```

### Comandos como `record`, con validación en el constructor compacto

Un **comando** es la intención del actor ("quiero crear un pedido con estas líneas"). Se valida
**al construirlo**: si el objeto existe, es sintácticamente válido. La validación *de negocio*
(stock, estados) sigue en el dominio.

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.ProductoId;

import java.util.List;
import java.util.Objects;

public record CrearPedidoCommand(ClienteId clienteId, List<Linea> lineas) {

    public CrearPedidoCommand {
        Objects.requireNonNull(clienteId, "El cliente es obligatorio");
        Objects.requireNonNull(lineas, "Las líneas son obligatorias");
        if (lineas.isEmpty()) {
            throw new IllegalArgumentException("Un pedido debe crearse con al menos una línea");
        }
        lineas = List.copyOf(lineas);   // copia defensiva e inmodificable (y rechaza elementos null)
    }

    public record Linea(ProductoId productoId, Cantidad cantidad) {
        public Linea {
            Objects.requireNonNull(productoId, "El producto es obligatorio");
            Objects.requireNonNull(cantidad, "La cantidad es obligatoria");
        }
    }
}
```

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;

import java.util.Objects;

public record ConfirmarPedidoCommand(PedidoId pedidoId) {
    public ConfirmarPedidoCommand {
        Objects.requireNonNull(pedidoId, "El id del pedido es obligatorio");
    }
}
```

Dos detalles que no son menores:

- **`List.copyOf`** en el constructor compacto: si quien construyó el comando modifica luego su
  lista, el comando no cambia. Y como `List.copyOf` lanza `NullPointerException` con elementos
  `null`, de paso validamos eso.
- **Los campos son value objects** (`ClienteId`, `Cantidad`): la validación de formato ya la hacen
  ellos. El comando solo añade las reglas *del comando* (al menos una línea).

> **Variante:** comandos con primitivos (`UUID clienteId`, `int cantidad`) y conversión a VOs dentro
> del servicio. Hay equipos que lo prefieren para no exponer el dominio a los adaptadores. Hombergs
> (BuckPal) usa VOs del dominio en los comandos; nosotros también. Lo que **nunca** cruza es un DTO
> web ni una entidad JPA.

> **Variante:** Bean Validation (`@NotNull`, `@Valid`) en comandos. Es cómodo, pero mete
> `jakarta.validation` en `application`. Aceptable para algunos equipos (es una especificación, no
> Spring); en este curso validamos en el constructor y dejamos Bean Validation para los DTOs web
> (día 3).

### Resultados como `record`

Para las consultas, el puerto devuelve un **modelo de lectura** pensado para quien pregunta, no el
agregado (así nadie fuera del hexágono puede llamar a `pedido.cancelar()` por accidente):

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;

public interface ConsultarPedidoQuery {

    /** @throws com.curso.pedidos.domain.exception.PedidoNoEncontradoException si no existe */
    PedidoDetalle ejecutar(PedidoId pedidoId);
}
```

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.*;

import java.time.Instant;
import java.util.List;

public record PedidoDetalle(PedidoId id,
                            ClienteId clienteId,
                            EstadoPedido estado,
                            List<Linea> lineas,
                            Dinero total,
                            Instant creadoEn) {

    public PedidoDetalle {
        lineas = List.copyOf(lineas);
    }

    public record Linea(ProductoId productoId, int cantidad, Dinero precioUnitario, Dinero subtotal) { }

    public static PedidoDetalle desde(Pedido pedido) {
        List<Linea> lineas = pedido.lineas().stream()
                .map(l -> new Linea(l.productoId(), l.cantidad().valor(), l.precioUnitario(), l.subtotal()))
                .toList();
        return new PedidoDetalle(pedido.id(), pedido.clienteId(), pedido.estado(),
                lineas, pedido.total(), pedido.creadoEn());
    }
}
```

> **Resultados con `sealed`:** cuando un caso de uso tiene varios desenlaces de negocio esperables
> (no excepcionales), un `sealed interface` con un `record` por desenlace es muy expresivo. Lo veremos
> con `ResultadoPago` en el [bloque 4](04-clasificacion-de-puertos.md).

---

## 6. Good — puertos de salida en lenguaje de negocio

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;

import java.util.Optional;

public interface PedidoRepository {

    Optional<Pedido> buscarPorId(PedidoId id);

    /** Inserta o actualiza el pedido completo (el agregado entero, con sus líneas). */
    void guardar(Pedido pedido);
}
```

Fijaos: **ni `JpaRepository`, ni `Long`, ni `PedidoJpaEntity`**. Recibe y devuelve el agregado del
dominio. Cómo se traduce a tablas es problema del adaptador (día 4).

> **Trampa:** "Repository" no significa Spring Data. Es el patrón *Repository* de Fowler/Evans: "una
> colección de agregados en memoria". Nombre de negocio, interfaz pequeña, **un repositorio por
> agregado raíz** (no hay `LineaPedidoRepository`).

---

## 7. Granularidad: un puerto por caso de uso vs puerto gordo

> *"Clients should not be forced to depend upon interfaces that they do not use."*
> — Robert C. Martin, Interface Segregation Principle

| Criterio                               | Un puerto por caso de uso             | Puerto gordo (`PedidoService`)         |
|----------------------------------------|---------------------------------------|----------------------------------------|
| ISP                                    | ✔ cada cliente ve solo lo que usa     | ✘ todos ven todo                       |
| Dobles en tests                        | Triviales (una lambda vale)           | Mocks con 8 métodos                    |
| Leer la carpeta `port/in`              | **Es el índice de lo que hace la app**| Hay que abrir el fichero               |
| Seguridad / transacciones por caso     | Fácil (un bean por caso de uso)       | Anotaciones método a método            |
| Nº de ficheros                         | Más                                   | Menos                                  |
| Riesgo                                 | Explosión de interfaces triviales     | Clase dios, acoplamiento               |

**Regla práctica del curso:**

- **Entrada:** un puerto por caso de uso (`CrearPedidoUseCase`, `ConfirmarPedidoUseCase`...).
  `ls application/port/in` debería leerse como el menú de la aplicación ("screaming architecture").
- **Salida:** un puerto por **capacidad cohesiva** que necesita el núcleo. `PedidoRepository` con
  `buscarPorId` + `guardar` es cohesivo; no lo partimos en `CargarPedidoPort` y `GuardarPedidoPort`
  salvo que haya un motivo real (Hombergs sí lo hace en BuckPal; es una opción válida y muy ISP).

Como un puerto de entrada tiene un solo método, se puede usar como **interfaz funcional** en tests:

```java
ConfirmarPedidoUseCase falso = comando -> { /* no hace nada */ };
```

---

## 8. Qué tipos cruzan un puerto (y cuáles nunca)

```
               ┌──────────────────────── HEXÁGONO ────────────────────────┐
  Adaptador    │   Puerto de entrada                Puerto de salida      │   Adaptador
  web          │                                                          │   JPA
 ┌─────────┐   │  ✔ Comandos (record)               ✔ Agregados (Pedido)  │  ┌──────────────┐
 │PedidoReq│─X─┤  ✔ Value objects                   ✔ Value objects      ├─X│PedidoJpaEnt. │
 │  (DTO)  │   │  ✔ Resultados/lecturas (record)    ✔ Eventos de dominio │  │ (@Entity)    │
 └─────────┘   │  ✔ Excepciones de dominio          ✔ Resultados (record)│  └──────────────┘
               │                                                          │
               │  ✘ DTOs web  ✘ HttpServletRequest  ✘ Entidades JPA      │
               │  ✘ ResponseEntity  ✘ Pageable  ✘ JsonNode  ✘ ConsumerRecord│
               └──────────────────────────────────────────────────────────┘
```

| Tipo                                  | ¿Cruza un puerto? | Comentario                                             |
|---------------------------------------|-------------------|--------------------------------------------------------|
| `record` comando / resultado          | ✔                 | Diseñado para eso                                      |
| Value objects (`PedidoId`, `Dinero`)  | ✔                 | Lenguaje del dominio                                   |
| Agregado `Pedido`                     | ✔ en puertos de **salida** | El repositorio guarda agregados                |
| Agregado `Pedido` en puertos de entrada | Evitar          | Mejor un modelo de lectura (`PedidoDetalle`)           |
| Excepciones de dominio                | ✔                 | El adaptador las traduce (HTTP, mensajes...)           |
| DTO web (`PedidoRequest`)             | ✘                 | Vive en `infrastructure.adapter.in.web`                |
| `@Entity` (`PedidoJpaEntity`)         | ✘                 | Vive en `infrastructure.adapter.out.persistence`       |
| `Pageable`, `Page`, `ResponseEntity`  | ✘                 | Son Spring. Si necesitas paginar, define tu `record Pagina<T>` |
| `java.time`, `java.util`, `BigDecimal`| ✔                 | JDK estándar: sin problema                             |

---

## 9. Trampas comunes

- **Trampa: el puerto espejo de la tecnología.** `PedidoRepository` con `findAll`, `findByEstadoAndClienteIdOrderByFechaDesc`...
  copiado de Spring Data. El puerto debe tener **lo que el núcleo necesita**, no lo que JPA ofrece.
- **Trampa: interfaces "por si acaso".** Si un servicio de dominio puro no tiene alternativas ni I/O,
  no necesita interfaz. Los puertos existen en la **frontera** con el exterior.
- **Trampa: validar dos veces lo mismo, distinto.** Si el DTO web dice "cantidad ≥ 0" y `Cantidad` dice
  "> 0", alguien acabará con un 500. La fuente de verdad es el dominio; el DTO valida formato.
- **Trampa: devolver `Optional` en un puerto de entrada** para "no lanzar excepciones". Puede valer en
  consultas, pero entonces el "no existe" pasa a ser un `if` en cada adaptador. Elegid una
  convención y mantenedla (en el curso: excepción `PedidoNoEncontradoException`).

---

## 10. Testing de lo visto

Los puertos son interfaces: no se prueban *ellos*, se prueban sus **implementaciones** (servicios
de aplicación en el bloque 5, adaptadores en el día 4). Lo que sí tiene lógica hoy son los **comandos**
y el **mapeo a resultados**:

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrearPedidoCommandTest {

    private final CrearPedidoCommand.Linea linea =
            new CrearPedidoCommand.Linea(new ProductoId("TEC-001"), Cantidad.de(1));

    @Test
    void no_admite_un_pedido_sin_lineas() {
        assertThatThrownBy(() -> new CrearPedidoCommand(ClienteId.nuevo(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("al menos una línea");
    }

    @Test
    void no_admite_cliente_nulo() {
        assertThatThrownBy(() -> new CrearPedidoCommand(null, List.of(linea)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void hace_copia_defensiva_de_las_lineas() {
        List<CrearPedidoCommand.Linea> original = new ArrayList<>(List.of(linea));
        CrearPedidoCommand comando = new CrearPedidoCommand(ClienteId.nuevo(), original);

        original.clear();

        assertThat(comando.lineas()).hasSize(1);
        assertThatThrownBy(() -> comando.lineas().add(linea))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PedidoDetalleTest {

    @Test
    void se_construye_a_partir_del_agregado() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(new ProductoId("TEC-001"), Cantidad.de(2), Dinero.euros("25.00"));

        PedidoDetalle detalle = PedidoDetalle.desde(pedido);

        assertThat(detalle.id()).isEqualTo(pedido.id());
        assertThat(detalle.estado()).isEqualTo(EstadoPedido.BORRADOR);
        assertThat(detalle.total()).isEqualTo(Dinero.euros("50.00"));
        assertThat(detalle.lineas()).singleElement()
                .satisfies(l -> {
                    assertThat(l.cantidad()).isEqualTo(2);
                    assertThat(l.subtotal()).isEqualTo(Dinero.euros("50.00"));
                });
    }
}
```

---

## 11. Resumen

| Concepto            | En una línea                                                               |
|---------------------|----------------------------------------------------------------------------|
| Puerto              | Interfaz Java en lenguaje de negocio que define una conversación           |
| Contrato            | Firma **+ semántica** (Javadoc y test de contrato)                         |
| Granularidad entrada| Un puerto por caso de uso (ISP)                                            |
| Granularidad salida | Un puerto por capacidad cohesiva (un repositorio por agregado)             |
| Comando             | `record` inmutable que se auto-valida en el constructor compacto           |
| Resultado           | `record` de lectura o `sealed interface` de desenlaces                     |
| Lo que nunca cruza  | DTOs web, entidades JPA, tipos de Spring                                   |
| SOLID               | DIP (el núcleo define la abstracción) + ISP (interfaces pequeñas)          |

---

## Ejercicio

Diseña el puerto de entrada para **añadir una línea a un pedido existente** (en `BORRADOR`):

1. Nombre del puerto y firma.
2. El comando como `record` con su validación.
3. Una prueba del comando.

**Pista:** ¿qué necesita el caso de uso para trabajar? Id del pedido, producto y cantidad. El precio
**no** debería venir del cliente (¿por qué?).

<details><summary>Solución</summary>

```java
package com.curso.pedidos.application.port.in;

public interface AnadirLineaAPedidoUseCase {
    void ejecutar(AnadirLineaCommand comando);
}
```

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.ProductoId;

import java.util.Objects;

public record AnadirLineaCommand(PedidoId pedidoId, ProductoId productoId, Cantidad cantidad) {
    public AnadirLineaCommand {
        Objects.requireNonNull(pedidoId, "pedidoId");
        Objects.requireNonNull(productoId, "productoId");
        Objects.requireNonNull(cantidad, "cantidad");
    }
}
```

```java
@Test
void exige_producto() {
    assertThatThrownBy(() -> new AnadirLineaCommand(PedidoId.nuevo(), null, Cantidad.de(1)))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("productoId");
}
```

El precio no viene en el comando porque **el cliente no decide el precio**: lo consulta el servicio
en `CatalogoProductos` (puerto de salida). Si viniera del exterior, cualquiera podría comprar un
portátil a 1 €.

</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture*: https://alistair.cockburn.us/hexagonal-architecture/
- Alistair Cockburn & Juan Manuel Garrido de Paz, *Hexagonal Architecture Explained* (2024).
- Robert C. Martin, *Clean Architecture* (2017), caps. sobre ISP, DIP y límites; *The Clean Architecture*: https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023) y https://github.com/thombergs/buckpal
- Martin Fowler, *Catalog of Patterns of Enterprise Application Architecture* (Repository): https://martinfowler.com/eaaCatalog/

## Mantra

> *"Un puerto dice QUÉ necesita el negocio. El CÓMO es asunto del adaptador."*
