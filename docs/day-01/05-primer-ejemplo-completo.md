# Día 1 — Bloque 5: Primer ejemplo completo — `CrearPedido` de punta a punta

> *"Make it work, make it right, make it fast."* — Kent Beck

---

## 1. El problema en una frase

Hasta ahora hemos visto trozos. Ahora necesitamos ver **un caso de uso
completo**, desde la petición HTTP hasta el almacenamiento, con todas las
piezas del hexágono en su sitio, cableado con Spring Boot y **probado sin
Mockito ni base de datos** en el núcleo.

En cristiano: hoy montamos el esqueleto que vamos a ir engordando durante las
siete sesiones siguientes.

---

## 2. Qué vamos a construir

Un endpoint `POST /api/pedidos` que crea un pedido en estado `BORRADOR` con sus
líneas y devuelve `201 Created` con el id.

```
 POST /api/pedidos                                        (memoria)
      │                                                       ▲
      ▼                                                       │
┌──────────────────┐   ┌──────────────────────┐   ┌───────────┴──────────────┐
│ PedidoController │──►│ «CrearPedidoUseCase» │   │ InMemoryPedidoRepository │
│ (adapter in/web) │   └──────────▲───────────┘   │ Adapter (adapter out)    │
└──────────────────┘              │ implements    └───────────┬──────────────┘
                       ┌──────────┴───────────┐               │ implements
                       │  CrearPedidoService  │──►┌───────────▼──────────┐
                       └──────────┬───────────┘   │ «PedidoRepository»   │
                                  │ usa           └──────────────────────┘
                                  ▼
                       ┌──────────────────────┐
                       │ Pedido, LineaPedido, │
                       │ Dinero, Cantidad...  │
                       └──────────────────────┘
```

¿Por qué un adaptador **en memoria** y no JPA? Porque el hexágono nos permite
**aplazar la decisión técnica**. El caso de uso no cambia cuando en el día 4
lo sustituyamos por `PedidoPersistenceAdapter` con JPA y PostgreSQL. Esa es la
demostración.

---

## 3. Estructura de paquetes

```
src/main/java/com/curso/pedidos
├── PedidosApplication.java
├── domain
│   ├── model
│   │   ├── Pedido.java
│   │   ├── LineaPedido.java
│   │   ├── EstadoPedido.java
│   │   ├── PedidoId.java
│   │   ├── ClienteId.java
│   │   ├── ProductoId.java
│   │   ├── Cantidad.java
│   │   └── Dinero.java
│   └── exception
│       ├── PedidoException.java
│       ├── PedidoNoEncontradoException.java
│       └── TransicionDeEstadoInvalidaException.java
├── application
│   ├── port
│   │   ├── in
│   │   │   ├── CrearPedidoUseCase.java
│   │   │   └── CrearPedidoCommand.java
│   │   └── out
│   │       └── PedidoRepository.java
│   └── service
│       └── CrearPedidoService.java
└── infrastructure
    ├── adapter
    │   ├── in
    │   │   └── web
    │   │       ├── PedidoController.java
    │   │       ├── CrearPedidoRequest.java
    │   │       ├── PedidoCreadoResponse.java
    │   │       └── ManejadorDeErrores.java
    │   └── out
    │       └── persistence
    │           └── InMemoryPedidoRepositoryAdapter.java
    └── config
        └── BeanConfiguration.java
```

### `pom.xml` (lo esencial)

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
</parent>

<groupId>com.curso</groupId>
<artifactId>pedidos</artifactId>
<version>0.1.0-SNAPSHOT</version>

<properties>
    <java.version>17</java.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>   <!-- JUnit 5, AssertJ, Mockito -->
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## 4. Dominio (Java puro)

### 4.1 Value objects

```java
package com.curso.pedidos.domain.model;

import java.util.Objects;
import java.util.UUID;

public record PedidoId(UUID valor) {
    public PedidoId {
        Objects.requireNonNull(valor, "El id del pedido es obligatorio");
    }

    public static PedidoId nuevo() {
        return new PedidoId(UUID.randomUUID());
    }
}
```

```java
package com.curso.pedidos.domain.model;

import java.util.Objects;
import java.util.UUID;

public record ClienteId(UUID valor) {
    public ClienteId {
        Objects.requireNonNull(valor, "El id del cliente es obligatorio");
    }

    public static ClienteId nuevo() {
        return new ClienteId(UUID.randomUUID());
    }
}
```

```java
package com.curso.pedidos.domain.model;

public record ProductoId(String sku) {
    public ProductoId {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("El SKU es obligatorio");
        }
    }
}
```

```java
package com.curso.pedidos.domain.model;

public record Cantidad(int valor) {
    public Cantidad {
        if (valor <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor que 0: " + valor);
        }
    }
}
```

```java
package com.curso.pedidos.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Dinero(BigDecimal importe, Currency moneda) {

    private static final Currency EUR = Currency.getInstance("EUR");

    public Dinero {
        Objects.requireNonNull(importe, "El importe es obligatorio");
        Objects.requireNonNull(moneda, "La moneda es obligatoria");
        if (importe.signum() < 0) {
            throw new IllegalArgumentException("El importe no puede ser negativo: " + importe);
        }
        // Normalizamos la escala: así 10, 10.0 y 10.00 son el mismo Dinero (equals de record)
        importe = importe.setScale(moneda.getDefaultFractionDigits(), RoundingMode.HALF_EVEN);
    }

    public static Dinero euros(String importe) {
        return new Dinero(new BigDecimal(importe), EUR);
    }

    public static Dinero cero() {
        return new Dinero(BigDecimal.ZERO, EUR);
    }

    public Dinero sumar(Dinero otro) {
        exigirMismaMoneda(otro);
        return new Dinero(importe.add(otro.importe), moneda);
    }

    public Dinero multiplicar(int factor) {
        return new Dinero(importe.multiply(BigDecimal.valueOf(factor)), moneda);
    }

    public boolean esMayorOIgualQue(Dinero otro) {
        exigirMismaMoneda(otro);
        return importe.compareTo(otro.importe) >= 0;
    }

    private void exigirMismaMoneda(Dinero otro) {
        if (!moneda.equals(otro.moneda)) {
            throw new IllegalArgumentException(
                    "No se pueden operar monedas distintas: " + moneda + " y " + otro.moneda);
        }
    }
}
```

Trampa: `BigDecimal.equals` compara **también la escala** (`10.0` ≠ `10.00`).
Si no normalizas, dos `Dinero` "iguales" no lo serán para el `equals` del
record. Por eso el `setScale` en el constructor compacto.

### 4.2 Estado y excepciones

```java
package com.curso.pedidos.domain.model;

public enum EstadoPedido {
    BORRADOR, CONFIRMADO, PAGADO, ENVIADO, CANCELADO
}
```

```java
package com.curso.pedidos.domain.exception;

public class PedidoException extends RuntimeException {
    public PedidoException(String mensaje) {
        super(mensaje);
    }
}
```

```java
package com.curso.pedidos.domain.exception;

import com.curso.pedidos.domain.model.PedidoId;

public class PedidoNoEncontradoException extends PedidoException {
    public PedidoNoEncontradoException(PedidoId id) {
        super("No existe el pedido " + id.valor());
    }
}
```

```java
package com.curso.pedidos.domain.exception;

import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.PedidoId;

public class TransicionDeEstadoInvalidaException extends PedidoException {

    public TransicionDeEstadoInvalidaException(PedidoId id, EstadoPedido desde, EstadoPedido hacia) {
        super("El pedido %s no puede pasar de %s a %s".formatted(id.valor(), desde, hacia));
    }

    public TransicionDeEstadoInvalidaException(PedidoId id, EstadoPedido estado, String motivo) {
        super("Operación no permitida en el pedido %s (estado %s): %s"
                .formatted(id.valor(), estado, motivo));
    }
}
```

### 4.3 `LineaPedido` y `Pedido` (agregado raíz)

```java
package com.curso.pedidos.domain.model;

import java.util.Objects;

public final class LineaPedido {

    private final ProductoId productoId;
    private final Cantidad cantidad;
    private final Dinero precioUnitario;

    LineaPedido(ProductoId productoId, Cantidad cantidad, Dinero precioUnitario) {  // solo Pedido la crea
        this.productoId = Objects.requireNonNull(productoId);
        this.cantidad = Objects.requireNonNull(cantidad);
        this.precioUnitario = Objects.requireNonNull(precioUnitario);
    }

    public Dinero subtotal() {
        return precioUnitario.multiplicar(cantidad.valor());
    }

    public ProductoId productoId() { return productoId; }
    public Cantidad cantidad() { return cantidad; }
    public Dinero precioUnitario() { return precioUnitario; }
}
```

```java
package com.curso.pedidos.domain.model;

import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Pedido {

    private final PedidoId id;
    private final ClienteId clienteId;
    private final List<LineaPedido> lineas;
    private EstadoPedido estado;
    private final Instant creadoEn;

    private Pedido(PedidoId id, ClienteId clienteId, EstadoPedido estado, Instant creadoEn) {
        this.id = Objects.requireNonNull(id);
        this.clienteId = Objects.requireNonNull(clienteId);
        this.estado = Objects.requireNonNull(estado);
        this.creadoEn = Objects.requireNonNull(creadoEn);
        this.lineas = new ArrayList<>();
    }

    // ---------- Factorías ----------

    public static Pedido crear(ClienteId clienteId) {
        return crear(clienteId, Clock.systemUTC());
    }

    public static Pedido crear(ClienteId clienteId, Clock reloj) {
        return new Pedido(PedidoId.nuevo(), clienteId, EstadoPedido.BORRADOR, reloj.instant());
    }

    // ---------- Comportamiento ----------

    public void anadirLinea(ProductoId productoId, Cantidad cantidad, Dinero precioUnitario) {
        if (estado != EstadoPedido.BORRADOR) {
            throw new TransicionDeEstadoInvalidaException(id, estado,
                    "solo se pueden añadir líneas a un pedido en borrador");
        }
        lineas.add(new LineaPedido(productoId, cantidad, precioUnitario));
    }

    public void confirmar() {
        if (estado != EstadoPedido.BORRADOR) {
            throw new TransicionDeEstadoInvalidaException(id, estado, EstadoPedido.CONFIRMADO);
        }
        if (lineas.isEmpty()) {
            throw new TransicionDeEstadoInvalidaException(id, estado,
                    "no se puede confirmar un pedido vacío");
        }
        estado = EstadoPedido.CONFIRMADO;
    }

    public void marcarComoPagado() {
        if (estado != EstadoPedido.CONFIRMADO) {
            throw new TransicionDeEstadoInvalidaException(id, estado, EstadoPedido.PAGADO);
        }
        estado = EstadoPedido.PAGADO;
    }

    public void cancelar() {
        if (estado == EstadoPedido.ENVIADO || estado == EstadoPedido.CANCELADO) {
            throw new TransicionDeEstadoInvalidaException(id, estado, EstadoPedido.CANCELADO);
        }
        estado = EstadoPedido.CANCELADO;
    }

    public Dinero total() {
        return lineas.stream()
                .map(LineaPedido::subtotal)
                .reduce(Dinero.cero(), Dinero::sumar);
    }

    // ---------- Consultas (sin setters) ----------

    public PedidoId id() { return id; }
    public ClienteId clienteId() { return clienteId; }
    public EstadoPedido estado() { return estado; }
    public Instant creadoEn() { return creadoEn; }
    public List<LineaPedido> lineas() { return List.copyOf(lineas); }
}
```

Observa lo que **no** hay: ni `@Entity`, ni `@Id`, ni `@JsonProperty`, ni
setters, ni constructor vacío. Y por qué **sin Lombok** en el dominio:
`@Data` genera setters que rompen invariantes y `equals` sobre campos mutables;
en el dominio queremos decidir **a mano** qué se expone.

---

## 5. Aplicación: puertos y caso de uso

### 5.1 Puerto de entrada y comando

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;

public interface CrearPedidoUseCase {
    PedidoId ejecutar(CrearPedidoCommand command);
}
```

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.ProductoId;
import java.util.List;
import java.util.Objects;

public record CrearPedidoCommand(ClienteId clienteId, List<Linea> lineas) {

    public CrearPedidoCommand {
        Objects.requireNonNull(clienteId, "El cliente es obligatorio");
        if (lineas == null || lineas.isEmpty()) {
            throw new IllegalArgumentException("Un pedido necesita al menos una línea");
        }
        lineas = List.copyOf(lineas);   // inmutable
    }

    public record Linea(ProductoId productoId, Cantidad cantidad, Dinero precioUnitario) {
        public Linea {
            Objects.requireNonNull(productoId);
            Objects.requireNonNull(cantidad);
            Objects.requireNonNull(precioUnitario);
        }
    }
}
```

El comando ya viene con **tipos de dominio** (`ClienteId`, `Cantidad`), no con
`String` e `int`: si llega aquí, es sintácticamente válido. La validación de
negocio (estado, invariantes) la hace el dominio.

Trampa: en una tienda real el **precio no lo manda el cliente**; se consulta
al catálogo (puerto `CatalogoProductos`). Lo dejamos así para no complicar el
primer ejemplo; lo arreglamos en el ejercicio 3 de
[06-ejercicios-dia-01.md](06-ejercicios-dia-01.md).

### 5.2 Puerto de salida

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import java.util.Optional;

public interface PedidoRepository {
    void guardar(Pedido pedido);
    Optional<Pedido> buscarPorId(PedidoId id);
}
```

### 5.3 El caso de uso

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.CrearPedidoCommand;
import com.curso.pedidos.application.port.in.CrearPedidoUseCase;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import java.time.Clock;

public class CrearPedidoService implements CrearPedidoUseCase {

    private final PedidoRepository pedidos;
    private final Clock reloj;

    public CrearPedidoService(PedidoRepository pedidos, Clock reloj) {
        this.pedidos = pedidos;
        this.reloj = reloj;
    }

    @Override
    public PedidoId ejecutar(CrearPedidoCommand command) {
        Pedido pedido = Pedido.crear(command.clienteId(), reloj);
        command.lineas().forEach(l ->
                pedido.anadirLinea(l.productoId(), l.cantidad(), l.precioUnitario()));
        pedidos.guardar(pedido);
        return pedido.id();
    }
}
```

Fíjate en los `import`: **solo** `application` y `domain` (y `java.time`).
Ninguna anotación. Este servicio se puede usar desde Spring, desde Quarkus,
desde un `main` o desde un test.

---

## 6. Infraestructura: adaptadores y configuración

### 6.1 Adaptador de salida en memoria

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryPedidoRepositoryAdapter implements PedidoRepository {

    private final Map<PedidoId, Pedido> almacen = new ConcurrentHashMap<>();

    @Override
    public void guardar(Pedido pedido) {
        almacen.put(pedido.id(), pedido);
    }

    @Override
    public Optional<Pedido> buscarPorId(PedidoId id) {
        return Optional.ofNullable(almacen.get(id));
    }

    public int cuantos() {          // utilidad para tests
        return almacen.size();
    }
}
```

Trampa: guardamos **la misma referencia**. Si alguien modifica el `Pedido`
después de `guardar` sin volver a llamar a `guardar`, el cambio "se persiste"
igualmente, cosa que con JPA desacoplado **no** pasaría. En el día 5 haremos
que el adaptador en memoria guarde copias para que se comporte como uno real
(LSP).

### 6.2 Adaptador de entrada web: DTOs

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CrearPedidoRequest(UUID clienteId, List<LineaRequest> lineas) {

    public record LineaRequest(String sku, int cantidad, BigDecimal precioUnitario, String moneda) { }
}
```

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import java.util.UUID;

public record PedidoCreadoResponse(UUID id) { }
```

### 6.3 Adaptador de entrada web: controller

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.application.port.in.CrearPedidoCommand;
import com.curso.pedidos.application.port.in.CrearPedidoUseCase;
import com.curso.pedidos.domain.model.Cantidad;
import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.ProductoId;
import java.net.URI;
import java.util.Currency;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    private final CrearPedidoUseCase crearPedido;

    public PedidoController(CrearPedidoUseCase crearPedido) {
        this.crearPedido = crearPedido;
    }

    @PostMapping
    public ResponseEntity<PedidoCreadoResponse> crear(@RequestBody CrearPedidoRequest request) {
        PedidoId id = crearPedido.ejecutar(aComando(request));
        return ResponseEntity
                .created(URI.create("/api/pedidos/" + id.valor()))
                .body(new PedidoCreadoResponse(id.valor()));
    }

    private static CrearPedidoCommand aComando(CrearPedidoRequest request) {
        if (request.clienteId() == null) {
            throw new IllegalArgumentException("clienteId es obligatorio");
        }
        List<CrearPedidoCommand.Linea> lineas = request.lineas() == null ? List.of()
                : request.lineas().stream()
                        .map(l -> new CrearPedidoCommand.Linea(
                                new ProductoId(l.sku()),
                                new Cantidad(l.cantidad()),
                                new Dinero(l.precioUnitario(), Currency.getInstance(l.moneda()))))
                        .toList();
        return new CrearPedidoCommand(new ClienteId(request.clienteId()), lineas);
    }
}
```

El controller es un **traductor**: JSON → DTO → comando → caso de uso →
respuesta HTTP. No decide nada de negocio.

### 6.4 Traducción de errores a HTTP

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.domain.exception.PedidoException;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ManejadorDeErrores {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail peticionInvalida(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(PedidoNoEncontradoException.class)
    ProblemDetail noEncontrado(PedidoNoEncontradoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PedidoException.class)
    ProblemDetail reglaDeNegocio(PedidoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
```

El dominio lanza excepciones **de negocio**; es el adaptador web quien decide
que eso es un `422`. Si mañana el mismo caso de uso se invoca desde Kafka, el
listener decidirá otra cosa (reintentar, DLQ…).

### 6.5 Configuración: el único sitio que conoce a todos

```java
package com.curso.pedidos.infrastructure.config;

import com.curso.pedidos.application.port.in.CrearPedidoUseCase;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.service.CrearPedidoService;
import com.curso.pedidos.infrastructure.adapter.out.persistence.InMemoryPedidoRepositoryAdapter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfiguration {

    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }

    @Bean
    PedidoRepository pedidoRepository() {
        return new InMemoryPedidoRepositoryAdapter();   // día 4: PedidoPersistenceAdapter (JPA)
    }

    @Bean
    CrearPedidoUseCase crearPedidoUseCase(PedidoRepository pedidoRepository, Clock reloj) {
        return new CrearPedidoService(pedidoRepository, reloj);
    }
}
```

```java
package com.curso.pedidos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PedidosApplication {
    public static void main(String[] args) {
        SpringApplication.run(PedidosApplication.class, args);
    }
}
```

**Alternativa pragmática**: anotar `CrearPedidoService` con `@Service` (o con
una anotación propia `@UseCase` meta-anotada con `@Component`) y olvidarse de
`BeanConfiguration`.

| Opción                       | Ventaja                                  | Inconveniente                                   |
|------------------------------|------------------------------------------|-------------------------------------------------|
| `BeanConfiguration` explícita| `application` 100 % libre de Spring       | Un `@Bean` más por caso de uso                  |
| `@Service` en el servicio    | Cero configuración                        | `application` importa Spring                    |
| `@UseCase` propia            | Semántica clara, un solo punto de acople  | Sigue dependiendo de `@Component` por debajo    |

En el curso usamos `BeanConfiguration`; en vuestros proyectos, decidid como
equipo y **sed consistentes**.

### 6.6 Probarlo a mano

```bash
curl -i -X POST http://localhost:8080/api/pedidos \
  -H "Content-Type: application/json" \
  -d '{
        "clienteId": "3f1c6a2e-8b7d-4c1e-9a55-2b0f7f1d9e10",
        "lineas": [
          { "sku": "LIBRO-DDD", "cantidad": 1, "precioUnitario": 49.90, "moneda": "EUR" },
          { "sku": "TAZA-HEX",  "cantidad": 2, "precioUnitario": 9.95,  "moneda": "EUR" }
        ]
      }'
```

```
HTTP/1.1 201
Location: /api/pedidos/7d0b0c5e-...
Content-Type: application/json

{"id":"7d0b0c5e-..."}
```

---

## 7. Testing

### 7.1 Dominio: JUnit puro

```java
package com.curso.pedidos.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import org.junit.jupiter.api.Test;

class PedidoTest {

    @Test
    void calcula_el_total_sumando_subtotales() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(new ProductoId("A"), new Cantidad(2), Dinero.euros("10.00"));
        pedido.anadirLinea(new ProductoId("B"), new Cantidad(1), Dinero.euros("5.50"));

        assertThat(pedido.total()).isEqualTo(Dinero.euros("25.50"));
    }

    @Test
    void no_admite_lineas_si_ya_esta_confirmado() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(new ProductoId("A"), new Cantidad(1), Dinero.euros("10"));
        pedido.confirmar();

        assertThatThrownBy(() ->
                pedido.anadirLinea(new ProductoId("B"), new Cantidad(1), Dinero.euros("1")))
                .isInstanceOf(TransicionDeEstadoInvalidaException.class);
    }

    @Test
    void no_se_confirma_un_pedido_vacio() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());

        assertThatThrownBy(pedido::confirmar)
                .isInstanceOf(TransicionDeEstadoInvalidaException.class)
                .hasMessageContaining("vacío");
    }
}
```

### 7.2 Caso de uso: con el adaptador en memoria (sin Mockito)

```java
package com.curso.pedidos.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.curso.pedidos.application.port.in.CrearPedidoCommand;
import com.curso.pedidos.domain.model.*;
import com.curso.pedidos.infrastructure.adapter.out.persistence.InMemoryPedidoRepositoryAdapter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrearPedidoServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-23T14:00:00Z");

    private final InMemoryPedidoRepositoryAdapter repo = new InMemoryPedidoRepositoryAdapter();
    private final Clock relojFijo = Clock.fixed(AHORA, ZoneOffset.UTC);
    private final CrearPedidoService service = new CrearPedidoService(repo, relojFijo);

    @Test
    void crea_un_pedido_en_borrador_con_sus_lineas_y_lo_guarda() {
        ClienteId cliente = ClienteId.nuevo();
        var command = new CrearPedidoCommand(cliente, List.of(
                new CrearPedidoCommand.Linea(new ProductoId("LIBRO-DDD"), new Cantidad(1), Dinero.euros("49.90")),
                new CrearPedidoCommand.Linea(new ProductoId("TAZA-HEX"), new Cantidad(2), Dinero.euros("9.95"))));

        PedidoId id = service.ejecutar(command);

        Pedido guardado = repo.buscarPorId(id).orElseThrow();
        assertThat(guardado.clienteId()).isEqualTo(cliente);
        assertThat(guardado.estado()).isEqualTo(EstadoPedido.BORRADOR);
        assertThat(guardado.lineas()).hasSize(2);
        assertThat(guardado.total()).isEqualTo(Dinero.euros("69.80"));
        assertThat(guardado.creadoEn()).isEqualTo(AHORA);
        assertThat(repo.cuantos()).isEqualTo(1);
    }
}
```

¿Por qué **sin Mockito** aquí? Porque con un mock verificaríamos *"se llamó a
`guardar` con algo"*; con el adaptador en memoria verificamos *"el pedido
**está guardado** y es correcto"*. Probamos **comportamiento**, no
interacciones, y el test no se rompe si mañana el servicio llama dos veces a
`guardar`. Fíjate también en el `Clock` fijo: el tiempo es otra dependencia
externa.

### 7.3 Adaptador web: `@WebMvcTest`

Aquí **sí** usamos Mockito: queremos probar **solo la traducción HTTP**, no el
caso de uso (ya probado).

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.curso.pedidos.application.port.in.CrearPedidoCommand;
import com.curso.pedidos.application.port.in.CrearPedidoUseCase;
import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.PedidoId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PedidoController.class)
class PedidoControllerTest {

    @Autowired MockMvc mvc;

    @MockBean CrearPedidoUseCase crearPedido;   // Boot 3.4+: @MockitoBean

    @Test
    void devuelve_201_con_location_y_traduce_el_json_a_comando() throws Exception {
        UUID idPedido = UUID.randomUUID();
        when(crearPedido.ejecutar(any())).thenReturn(new PedidoId(idPedido));

        mvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clienteId": "3f1c6a2e-8b7d-4c1e-9a55-2b0f7f1d9e10",
                                  "lineas": [
                                    {"sku": "LIBRO-DDD", "cantidad": 1, "precioUnitario": 49.90, "moneda": "EUR"}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/pedidos/" + idPedido))
                .andExpect(jsonPath("$.id").value(idPedido.toString()));

        ArgumentCaptor<CrearPedidoCommand> captor = ArgumentCaptor.forClass(CrearPedidoCommand.class);
        verify(crearPedido).ejecutar(captor.capture());
        CrearPedidoCommand cmd = captor.getValue();
        assertThat(cmd.lineas()).singleElement()
                .satisfies(l -> {
                    assertThat(l.productoId().sku()).isEqualTo("LIBRO-DDD");
                    assertThat(l.precioUnitario()).isEqualTo(Dinero.euros("49.90"));
                });
    }

    @Test
    void devuelve_400_si_la_cantidad_es_cero() throws Exception {
        mvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clienteId": "3f1c6a2e-8b7d-4c1e-9a55-2b0f7f1d9e10",
                                  "lineas": [{"sku": "X", "cantidad": 0, "precioUnitario": 1, "moneda": "EUR"}]
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(crearPedido);
    }
}
```

`@WebMvcTest` levanta **solo** la capa web (controllers, `@RestControllerAdvice`,
Jackson), no `BeanConfiguration` ni el caso de uso real. Arranca en uno o dos
segundos.

### 7.4 La pirámide que nos queda

```
                ┌────────────┐
                │ @SpringBoot│   pocos: humo de arranque y cableado (día 5)
                │    Test    │
              ┌─┴────────────┴─┐
              │  @WebMvcTest   │   adaptador web: traducción HTTP
            ┌─┴────────────────┴─┐
            │ Caso de uso + repo │   lógica de aplicación, en memoria, ms
            │     en memoria     │
          ┌─┴────────────────────┴─┐
          │   Dominio (JUnit puro) │   reglas de negocio, ms
          └────────────────────────┘
```

---

## 8. Trampas habituales en el primer ejemplo

- **Trampa: devolver `Pedido` desde el controller.** Acoplas el JSON público
  al modelo de dominio. Usa siempre DTOs en el adaptador web.
- **Trampa: que el caso de uso reciba el `CrearPedidoRequest`.** El DTO web es
  del adaptador; el caso de uso recibe un **comando** suyo.
- **Trampa: poner `@Transactional` en `CrearPedidoService`.** Mete Spring en
  `application`. Opciones (día 6): decorador transaccional en
  infraestructura, o aceptar el trade-off conscientemente.
- **Trampa: `Instant.now()` dentro del dominio sin control.** Inyecta un
  `Clock` cuando el tiempo importe en los tests.
- **Trampa: "el adaptador en memoria es solo para tests".** También sirve para
  arrancar el servicio en local sin infraestructura y para demos.

---

## 9. Resumen: cada pieza en su sitio

| Pieza                             | Paquete                                         | Depende de                | Spring |
|-----------------------------------|-------------------------------------------------|---------------------------|--------|
| `Pedido`, `Dinero`, …             | `domain.model`                                  | Nada                      | No     |
| `TransicionDeEstadoInvalida…`     | `domain.exception`                              | `domain.model`            | No     |
| `CrearPedidoUseCase`, `…Command`  | `application.port.in`                           | `domain`                  | No     |
| `PedidoRepository`                | `application.port.out`                          | `domain`                  | No     |
| `CrearPedidoService`              | `application.service`                           | puertos + `domain`        | No     |
| `PedidoController` + DTOs         | `infrastructure.adapter.in.web`                 | puerto de entrada         | Sí     |
| `InMemoryPedidoRepositoryAdapter` | `infrastructure.adapter.out.persistence`        | puerto de salida          | No*    |
| `BeanConfiguration`               | `infrastructure.config`                         | todo                      | Sí     |

\* Los adaptadores pueden usar Spring libremente; este simplemente no lo
necesita.

---

## Ejercicio

Añade el caso de uso **`ConfirmarPedido`** siguiendo exactamente el mismo
esquema:

1. Puerto de entrada `ConfirmarPedidoUseCase` con `void ejecutar(PedidoId id)`.
2. Servicio `ConfirmarPedidoService`.
3. Endpoint `POST /api/pedidos/{id}/confirmacion` que devuelva `204`.
4. Bean en `BeanConfiguration`.
5. Test del servicio con el adaptador en memoria (caso feliz y pedido no
   encontrado).

*Pista*: la regla ya está en `Pedido.confirmar()`. El servicio son tres
líneas.

<details><summary>Solución</summary>

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;

public interface ConfirmarPedidoUseCase {
    void ejecutar(PedidoId id);
}
```

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;

public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {

    private final PedidoRepository pedidos;

    public ConfirmarPedidoService(PedidoRepository pedidos) {
        this.pedidos = pedidos;
    }

    @Override
    public void ejecutar(PedidoId id) {
        Pedido pedido = pedidos.buscarPorId(id)
                .orElseThrow(() -> new PedidoNoEncontradoException(id));
        pedido.confirmar();
        pedidos.guardar(pedido);
    }
}
```

```java
// En PedidoController (añadir el puerto al constructor)
@PostMapping("/{id}/confirmacion")
public ResponseEntity<Void> confirmar(@PathVariable UUID id) {
    confirmarPedido.ejecutar(new PedidoId(id));
    return ResponseEntity.noContent().build();
}
```

```java
// En BeanConfiguration
@Bean
ConfirmarPedidoUseCase confirmarPedidoUseCase(PedidoRepository pedidoRepository) {
    return new ConfirmarPedidoService(pedidoRepository);
}
```

```java
class ConfirmarPedidoServiceTest {

    private final InMemoryPedidoRepositoryAdapter repo = new InMemoryPedidoRepositoryAdapter();
    private final ConfirmarPedidoService service = new ConfirmarPedidoService(repo);

    @Test
    void confirma_un_pedido_existente() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(new ProductoId("A"), new Cantidad(1), Dinero.euros("10"));
        repo.guardar(pedido);

        service.ejecutar(pedido.id());

        assertThat(repo.buscarPorId(pedido.id()).orElseThrow().estado())
                .isEqualTo(EstadoPedido.CONFIRMADO);
    }

    @Test
    void falla_si_el_pedido_no_existe() {
        assertThatThrownBy(() -> service.ejecutar(PedidoId.nuevo()))
                .isInstanceOf(PedidoNoEncontradoException.class);
    }
}
```

`ManejadorDeErrores` ya traduce `PedidoNoEncontradoException` a `404` y
`TransicionDeEstadoInvalidaException` a `422`, sin tocar nada.

</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture* (2005): https://alistair.cockburn.us/hexagonal-architecture/
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023) y https://github.com/thombergs/buckpal
- Spring Boot docs: https://docs.spring.io/spring-boot/index.html
- Spring Framework testing (MockMvc): https://docs.spring.io/spring-framework/reference/testing.html
- Martin Fowler, *The Practical Test Pyramid*: https://martinfowler.com/articles/practical-test-pyramid.html
- Martin Fowler, *ValueObject*: https://martinfowler.com/bliki/ValueObject.html
- JUnit 5: https://junit.org/junit5/docs/current/user-guide/ ; AssertJ: https://assertj.github.io/doc/ ; Mockito: https://site.mockito.org/

---

## Mantra

> *"Si puedes cambiar el adaptador sin tocar el caso de uso, has construido un hexágono. Si no, has construido carpetas."*
