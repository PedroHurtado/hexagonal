# Día 2 — Bloque 1: Componentes internos del dominio

> *"The heart of software is its ability to solve domain-related problems for its user."*
> — Eric Evans, *Domain-Driven Design* (2003)

---

## 1. El problema en una frase

Si las reglas del negocio ("no se confirma un pedido vacío") viven repartidas por controladores,
servicios y triggers de base de datos, **nadie sabe dónde están ni puede garantizarlas**. El dominio
es el sitio donde esas reglas viven, juntas, protegidas y comprobables sin arrancar nada.

En cristiano: el centro del hexágono es **código Java normal** que expresa el negocio. Nada más y
nada menos.

---

## 2. El vocabulario: piezas de un dominio

Estos términos vienen del **Domain-Driven Design** táctico (Evans, 2003; Vernon, 2013). No hace
falta "hacer DDD" para usar hexagonal, pero estas piezas son la mejor forma conocida de rellenar el
centro del hexágono.

| Pieza                 | Qué es                                                        | En nuestro dominio                                  |
|-----------------------|---------------------------------------------------------------|-----------------------------------------------------|
| **Entidad**           | Objeto con **identidad** que perdura aunque cambien sus datos | `Pedido`, `LineaPedido`, `Producto`                 |
| **Value object**      | Objeto definido **solo por sus valores**, inmutable           | `Dinero`, `Cantidad`, `PedidoId`, `ProductoId`, `Email` |
| **Agregado**          | Grupo de objetos que se modifica como una unidad consistente  | `Pedido` + sus `LineaPedido`                        |
| **Agregado raíz**     | Única puerta de entrada al agregado                           | `Pedido`                                            |
| **Servicio de dominio** | Lógica de negocio que no pertenece a una sola entidad       | `CalculadoraDePrecios`                              |
| **Evento de dominio** | Algo relevante que **ya ha pasado** en el negocio             | `PedidoConfirmado`, `PedidoPagado`, `PedidoCancelado` |
| **Excepción de dominio** | Violación de una regla de negocio, con nombre propio       | `TransicionDeEstadoInvalidaException`, ...          |
| **Fábrica**           | Encapsula la creación de objetos complejos y válidos          | `Pedido.crear(...)`, `Pedido.reconstituir(...)`     |

---

## 3. Bad — el modelo anémico

Así es como empieza (y se queda) la mayoría de proyectos Spring "de capas":

```java
// ❌ Una bolsa de getters/setters con anotaciones de infraestructura
@Entity
@Data                       // Lombok: getters, setters, equals, hashCode... de todo
public class Pedido {
    @Id @GeneratedValue
    private Long id;
    private Long clienteId;
    @OneToMany(cascade = CascadeType.ALL)
    private List<LineaPedido> lineas = new ArrayList<>();
    private String estado;  // "BORRADOR", "CONFIRMADO"... a mano
    private BigDecimal total;
}

@Service
public class PedidoService {
    @Autowired private PedidoRepository repo;

    @Transactional
    public void confirmar(Long id) {
        Pedido p = repo.findById(id).orElseThrow();
        if (p.getLineas().isEmpty()) {
            throw new RuntimeException("vacío");
        }
        if (!"BORRADOR".equals(p.getEstado())) {
            throw new RuntimeException("estado inválido");
        }
        p.setEstado("CONFIRMADO");
    }
}
```

### ¿Qué duele?

- **Las reglas no viven con los datos.** Cualquiera puede hacer `pedido.setEstado("ENVIADO")` desde
  cualquier sitio y saltarse todas las reglas. Fowler lo llama *Anemic Domain Model* y lo describe
  como "lo contrario a la idea básica del diseño orientado a objetos".
- **`total` es un campo** que alguien tiene que acordarse de recalcular. Se desincroniza seguro.
- **`String estado`** y **`BigDecimal total`** sin moneda: obsesión por los primitivos.
- **El dominio depende de JPA y Lombok.** Para probar `confirmar()` hay que montar Spring o mockear
  el repositorio; no hay forma de probar la regla "sin nada".
- **`RuntimeException("vacío")`**: el adaptador web no puede distinguir un error de negocio de un
  `NullPointerException`.
- **`@Data` en una entidad** genera `equals/hashCode` sobre todos los campos (incluida la colección
  perezosa): bugs sutiles con `Set`s y proxies de Hibernate.

---

## 4. Good — value objects con `record`

> *"A small simple object, like money or a date range, whose equality isn't based on identity."*
> — Martin Fowler, *ValueObject*

Un value object:

- Es **inmutable** (operar devuelve uno nuevo).
- Se **auto-valida** en el constructor: si existe, es válido.
- Se compara **por valor** (`equals` de todos sus campos).

Los `record` de Java 16+ nos dan inmutabilidad, `equals`, `hashCode` y `toString` gratis. El
**constructor compacto** es el sitio para validar y normalizar.

### `Dinero`

```java
package com.curso.pedidos.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Dinero(BigDecimal importe, Currency moneda) {

    public static final Currency EUR = Currency.getInstance("EUR");

    public Dinero {
        Objects.requireNonNull(importe, "El importe es obligatorio");
        Objects.requireNonNull(moneda, "La moneda es obligatoria");
        if (importe.signum() < 0) {
            throw new IllegalArgumentException("El importe no puede ser negativo: " + importe);
        }
        // Normalizamos la escala: así 10, 10.0 y 10.00 son el MISMO Dinero
        importe = importe.setScale(moneda.getDefaultFractionDigits(), RoundingMode.HALF_EVEN);
    }

    public static Dinero euros(String importe) {
        return new Dinero(new BigDecimal(importe), EUR);
    }

    public static Dinero cero(Currency moneda) {
        return new Dinero(BigDecimal.ZERO, moneda);
    }

    public Dinero sumar(Dinero otro) {
        exigirMismaMoneda(otro);
        return new Dinero(importe.add(otro.importe), moneda);
    }

    public Dinero multiplicar(int factor) {
        return new Dinero(importe.multiply(BigDecimal.valueOf(factor)), moneda);
    }

    public Dinero multiplicar(BigDecimal factor) {
        return new Dinero(importe.multiply(factor), moneda);
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

> **Trampa:** `BigDecimal.equals` compara también la escala: `new BigDecimal("10.0").equals(new
> BigDecimal("10.00"))` es `false`. Como el `equals` del record usa el `equals` de cada campo, sin la
> normalización del constructor dos `Dinero` "iguales" no lo serían.

### `Cantidad` e identificadores

```java
package com.curso.pedidos.domain.model;

public record Cantidad(int valor) {

    public Cantidad {
        if (valor <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor que cero: " + valor);
        }
    }

    public static Cantidad de(int valor) {
        return new Cantidad(valor);
    }

    public Cantidad sumar(Cantidad otra) {
        return new Cantidad(valor + otra.valor);
    }
}
```

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

    public static PedidoId de(String texto) {
        return new PedidoId(UUID.fromString(texto));
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

import java.util.Objects;
import java.util.regex.Pattern;

public record ProductoId(String sku) {

    private static final Pattern FORMATO = Pattern.compile("[A-Z0-9-]{3,20}");

    public ProductoId {
        Objects.requireNonNull(sku, "El SKU es obligatorio");
        sku = sku.trim().toUpperCase();
        if (!FORMATO.matcher(sku).matches()) {
            throw new IllegalArgumentException("SKU con formato inválido: " + sku);
        }
    }
}
```

¿Por qué un `PedidoId` en vez de un `UUID` a pelo? Porque así **el compilador** impide pasar un
`ClienteId` donde se espera un `PedidoId`. Un método `buscar(UUID cliente, UUID pedido)` es una
invitación a cruzar los argumentos; `buscar(ClienteId, PedidoId)` no.

### `EstadoPedido`

```java
package com.curso.pedidos.domain.model;

public enum EstadoPedido {
    BORRADOR, CONFIRMADO, PAGADO, ENVIADO, CANCELADO
}
```

---

## 5. Good — entidades y el agregado `Pedido`

### Entidad vs value object

| Pregunta                                         | Entidad             | Value object          |
|--------------------------------------------------|---------------------|-----------------------|
| ¿Tiene identidad propia que perdura?             | Sí (`PedidoId`)     | No                    |
| ¿Cómo se compara?                                | Por id              | Por todos sus valores |
| ¿Es mutable?                                     | Sí, con métodos de negocio | No, nunca      |
| ¿En Java 17?                                     | `class`             | `record`              |
| Ejemplo                                          | `Pedido`            | `Dinero`              |

### Agregado y agregado raíz

> *"An AGGREGATE is a cluster of associated objects that we treat as a unit for the purpose of data
> changes. [...] The root is the only member of the AGGREGATE that outside objects are allowed to
> hold references to."* — Eric Evans

Reglas prácticas (Vernon, *Implementing DDD*):

1. **Protege invariantes de verdad** dentro de la frontera del agregado.
2. **Diseña agregados pequeños.**
3. **Referencia a otros agregados por id** (`ClienteId`, `ProductoId`), nunca por objeto.
4. **Una transacción modifica un solo agregado**; el resto se entera por eventos.

```
┌───────────────────────── Agregado Pedido ─────────────────────────┐
│                                                                   │
│   ┌──────────────────────┐        ┌───────────────────────────┐   │
│   │ Pedido  «raíz»       │ 1    * │ LineaPedido «entidad int.»│   │
│   ├──────────────────────┤───────►├───────────────────────────┤   │
│   │ id: PedidoId         │        │ productoId: ProductoId ───┼───┼──► Producto
│   │ clienteId: ClienteId─┼──┐     │ cantidad: Cantidad        │   │    (otro agregado,
│   │ estado: EstadoPedido │  │     │ precioUnitario: Dinero    │   │     solo por id)
│   │ creadoEn: Instant    │  │     │ subtotal(): Dinero        │   │
│   ├──────────────────────┤  │     └───────────────────────────┘   │
│   │ anadirLinea(...)     │  │                                     │
│   │ confirmar()          │  └─────────────────────────────────────┼──► Cliente
│   │ cancelar()           │                                        │    (otro agregado)
│   │ marcarComoPagado()   │                                        │
│   │ total(): Dinero      │                                        │
│   └──────────────────────┘                                        │
│   Desde fuera SOLO se habla con Pedido                            │
└───────────────────────────────────────────────────────────────────┘
```

### `LineaPedido` — entidad interna

```java
package com.curso.pedidos.domain.model;

import java.util.Objects;

public class LineaPedido {

    private final ProductoId productoId;
    private Cantidad cantidad;
    private final Dinero precioUnitario;

    public LineaPedido(ProductoId productoId, Cantidad cantidad, Dinero precioUnitario) {
        this.productoId = Objects.requireNonNull(productoId, "productoId");
        this.cantidad = Objects.requireNonNull(cantidad, "cantidad");
        this.precioUnitario = Objects.requireNonNull(precioUnitario, "precioUnitario");
    }

    // package-private: solo Pedido (mismo paquete) puede modificar una línea
    void incrementar(Cantidad extra) {
        this.cantidad = this.cantidad.sumar(extra);
    }

    public Dinero subtotal() {
        return precioUnitario.multiplicar(cantidad.valor());
    }

    public ProductoId productoId()  { return productoId; }
    public Cantidad cantidad()      { return cantidad; }
    public Dinero precioUnitario()  { return precioUnitario; }

    // Identidad LOCAL al agregado: dentro de un pedido, una línea por producto
    @Override
    public boolean equals(Object o) {
        return o instanceof LineaPedido otra && productoId.equals(otra.productoId);
    }

    @Override
    public int hashCode() {
        return productoId.hashCode();
    }
}
```

### `Pedido` — agregado raíz con invariantes

Invariantes que **el propio `Pedido` garantiza** (nadie más):

1. No se pueden añadir líneas a un pedido que no esté en `BORRADOR`.
2. No se confirma un pedido vacío.
3. No se cancela un pedido enviado (ni uno ya cancelado).
4. Las transiciones de estado siguen el ciclo de vida: `BORRADOR → CONFIRMADO → PAGADO → ENVIADO`,
   con `CANCELADO` posible desde los tres primeros.
5. El total **se calcula**, nunca se almacena a mano.

```java
package com.curso.pedidos.domain.model;

import com.curso.pedidos.domain.event.EventoDeDominio;
import com.curso.pedidos.domain.event.PedidoCancelado;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.event.PedidoPagado;
import com.curso.pedidos.domain.exception.PedidoNoModificableException;
import com.curso.pedidos.domain.exception.PedidoVacioException;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class Pedido {

    private final PedidoId id;
    private final ClienteId clienteId;
    private final List<LineaPedido> lineas;
    private EstadoPedido estado;
    private final Instant creadoEn;
    private final List<EventoDeDominio> eventos = new ArrayList<>();

    private Pedido(PedidoId id, ClienteId clienteId, List<LineaPedido> lineas,
                   EstadoPedido estado, Instant creadoEn) {
        this.id = Objects.requireNonNull(id, "id");
        this.clienteId = Objects.requireNonNull(clienteId, "clienteId");
        this.lineas = new ArrayList<>(Objects.requireNonNull(lineas, "lineas"));
        this.estado = Objects.requireNonNull(estado, "estado");
        this.creadoEn = Objects.requireNonNull(creadoEn, "creadoEn");
    }

    // ---------- Fábricas ----------

    /** Crea un pedido NUEVO: siempre en BORRADOR, sin líneas, con id nuevo. */
    public static Pedido crear(ClienteId clienteId) {
        return new Pedido(PedidoId.nuevo(), clienteId, List.of(), EstadoPedido.BORRADOR, Instant.now());
    }

    /** Reconstruye un pedido EXISTENTE (p. ej. desde persistencia). No genera eventos. */
    public static Pedido reconstituir(PedidoId id, ClienteId clienteId, List<LineaPedido> lineas,
                                      EstadoPedido estado, Instant creadoEn) {
        return new Pedido(id, clienteId, lineas, estado, creadoEn);
    }

    // ---------- Comportamiento ----------

    public void anadirLinea(ProductoId productoId, Cantidad cantidad, Dinero precioUnitario) {
        if (estado != EstadoPedido.BORRADOR) {
            throw new PedidoNoModificableException(id, estado);
        }
        if (!lineas.isEmpty()
                && !lineas.get(0).precioUnitario().moneda().equals(precioUnitario.moneda())) {
            throw new IllegalArgumentException("Todas las líneas de un pedido deben usar la misma moneda");
        }
        buscarLinea(productoId).ifPresentOrElse(
                linea -> linea.incrementar(cantidad),
                () -> lineas.add(new LineaPedido(productoId, cantidad, precioUnitario)));
    }

    public void confirmar() {
        exigirEstado(EstadoPedido.BORRADOR, EstadoPedido.CONFIRMADO);
        if (lineas.isEmpty()) {
            throw new PedidoVacioException(id);
        }
        estado = EstadoPedido.CONFIRMADO;
        eventos.add(new PedidoConfirmado(id, Instant.now()));
    }

    public void marcarComoPagado() {
        exigirEstado(EstadoPedido.CONFIRMADO, EstadoPedido.PAGADO);
        estado = EstadoPedido.PAGADO;
        eventos.add(new PedidoPagado(id, Instant.now()));
    }

    public void marcarComoEnviado() {
        exigirEstado(EstadoPedido.PAGADO, EstadoPedido.ENVIADO);
        estado = EstadoPedido.ENVIADO;
    }

    public void cancelar() {
        if (estado == EstadoPedido.ENVIADO || estado == EstadoPedido.CANCELADO) {
            throw new TransicionDeEstadoInvalidaException(id, estado, EstadoPedido.CANCELADO);
        }
        estado = EstadoPedido.CANCELADO;
        eventos.add(new PedidoCancelado(id, Instant.now()));
    }

    public Dinero total() {
        return lineas.stream()
                .map(LineaPedido::subtotal)
                .reduce(Dinero::sumar)
                .orElse(Dinero.cero(Dinero.EUR));
    }

    /** Devuelve los eventos pendientes y los vacía (los publica la capa de aplicación). */
    public List<EventoDeDominio> extraerEventos() {
        List<EventoDeDominio> pendientes = List.copyOf(eventos);
        eventos.clear();
        return pendientes;
    }

    // ---------- Consultas ----------

    public PedidoId id()               { return id; }
    public ClienteId clienteId()       { return clienteId; }
    public EstadoPedido estado()       { return estado; }
    public Instant creadoEn()          { return creadoEn; }
    public List<LineaPedido> lineas()  { return Collections.unmodifiableList(lineas); }

    // ---------- Internos ----------

    private Optional<LineaPedido> buscarLinea(ProductoId productoId) {
        return lineas.stream().filter(l -> l.productoId().equals(productoId)).findFirst();
    }

    private void exigirEstado(EstadoPedido esperado, EstadoPedido destino) {
        if (estado != esperado) {
            throw new TransicionDeEstadoInvalidaException(id, estado, destino);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Pedido otro && id.equals(otro.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
```

Fijaos en lo que **no** hay:

- **Ni un setter.** El estado cambia solo a través de verbos del negocio (`confirmar`, `cancelar`).
- **Ni una anotación.** Ni `@Entity`, ni `@Data`, ni `@Component`.
- **`lineas()` devuelve una vista inmodificable** y `LineaPedido.incrementar` es package-private:
  desde fuera del paquete no hay forma de romper el agregado.

### Diagrama de estados (lo que el agregado protege)

```
                  confirmar()          marcarComoPagado()      marcarComoEnviado()
   ┌──────────┐  ──────────►  ┌────────────┐ ──────────► ┌────────┐ ──────────► ┌─────────┐
   │ BORRADOR │               │ CONFIRMADO │             │ PAGADO │             │ ENVIADO │
   └────┬─────┘               └─────┬──────┘             └───┬────┘             └─────────┘
        │ cancelar()                │ cancelar()             │ cancelar()          ✗ cancelar()
        ▼                           ▼                        ▼
   ┌──────────────────────────────────────────────────────────────┐
   │                         CANCELADO                            │
   └──────────────────────────────────────────────────────────────┘
```

---

## 6. Fábricas

Las fábricas existen porque **crear un objeto válido** puede ser tan importante como usarlo.

- **Método de fábrica estático** (`Pedido.crear`): nombre con intención, garantiza estado inicial
  correcto (`BORRADOR`, sin líneas, id nuevo). El constructor es `private`.
- **`reconstituir`**: separa "nacer" de "volver de la base de datos". Al reconstituir **no** se
  generan eventos ni se aplican reglas de creación (el pedido ya existía y era válido).
- **Clase fábrica** (`PedidoFactory`): solo cuando crear requiere colaboradores o mucha lógica
  (p. ej. construir un pedido a partir de un carrito con promociones). No la creéis "por si acaso".

> **Trampa:** usar `reconstituir` desde la capa de aplicación para "saltarse" las reglas. Es para
> adaptadores de persistencia (día 4), y conviene dejarlo documentado.

---

## 7. Servicios de dominio

Lógica de negocio **que no encaja de forma natural en una entidad o value object**: involucra varios
objetos o es una política que cambia por su cuenta.

```java
package com.curso.pedidos.domain.service;

import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.Pedido;

import java.math.BigDecimal;

/** Política de precios: 10 % de descuento en pedidos de 100 € o más. */
public class CalculadoraDePrecios {

    private static final Dinero UMBRAL_DESCUENTO = Dinero.euros("100.00");
    private static final BigDecimal FACTOR_DESCUENTO = new BigDecimal("0.90");

    public Dinero totalConDescuento(Pedido pedido) {
        Dinero total = pedido.total();
        return total.esMayorOIgualQue(UMBRAL_DESCUENTO)
                ? total.multiplicar(FACTOR_DESCUENTO)
                : total;
    }
}
```

Servicio de dominio vs servicio de aplicación (lo desarrollamos en el
[bloque 5](05-servicios-de-aplicacion.md)):

| Servicio de dominio                      | Servicio de aplicación                          |
|------------------------------------------|-------------------------------------------------|
| Contiene **reglas de negocio**           | **Orquesta**: carga, invoca al dominio, guarda  |
| Sin estado, sin puertos, sin I/O         | Usa puertos de salida (repositorios, pasarelas) |
| Vive en `domain.service`                 | Vive en `application.service`                   |
| Ej.: `CalculadoraDePrecios`              | Ej.: `ConfirmarPedidoService`                   |

> **Trampa:** abusar de los servicios de dominio y vaciar las entidades. Si toda la lógica acaba en
> `XxxDomainService`, has reinventado el modelo anémico con otro nombre.

---

## 8. Eventos de dominio

Un evento de dominio es **un hecho del pasado** relevante para el negocio. Se nombra en pasado
(`PedidoConfirmado`, no `ConfirmarPedido`) y es inmutable: un `record` perfecto.

```java
package com.curso.pedidos.domain.event;

import com.curso.pedidos.domain.model.PedidoId;

import java.time.Instant;

public sealed interface EventoDeDominio permits PedidoConfirmado, PedidoPagado, PedidoCancelado {
    PedidoId pedidoId();
    Instant ocurridoEn();
}
```

```java
package com.curso.pedidos.domain.event;

import com.curso.pedidos.domain.model.PedidoId;
import java.time.Instant;

public record PedidoConfirmado(PedidoId pedidoId, Instant ocurridoEn) implements EventoDeDominio { }
```

```java
package com.curso.pedidos.domain.event;

import com.curso.pedidos.domain.model.PedidoId;
import java.time.Instant;

public record PedidoPagado(PedidoId pedidoId, Instant ocurridoEn) implements EventoDeDominio { }
```

```java
package com.curso.pedidos.domain.event;

import com.curso.pedidos.domain.model.PedidoId;
import java.time.Instant;

public record PedidoCancelado(PedidoId pedidoId, Instant ocurridoEn) implements EventoDeDominio { }
```

El agregado **registra** los eventos (lista interna) y la capa de aplicación los **extrae y publica**
a través de un puerto de salida (`PublicadorDeEventos`). El dominio no sabe si detrás hay Kafka,
RabbitMQ o una lista en memoria.

> **Variante:** Spring Data ofrece `AbstractAggregateRoot` con `registerEvent(...)`. Funciona, pero
> mete Spring en el dominio. Nuestra lista interna hace lo mismo con cero dependencias.

> **Trampa (tiempo):** `Instant.now()` dentro del dominio hace que la hora no sea controlable en tests.
> Para el curso lo aceptamos (los tests comprueban `isNotNull()` o rangos). Si la hora es regla de
> negocio (caducidades, plazos), pasad un `java.time.Clock` o el `Instant` como parámetro.

---

## 9. Excepciones de dominio

Una regla rota merece **un nombre propio**, no un `RuntimeException("error")`. Así el adaptador web
(día 3) podrá traducir cada una a su código HTTP (404, 409, 422...) sin mirar mensajes.

```java
package com.curso.pedidos.domain.exception;

public abstract class PedidoException extends RuntimeException {
    protected PedidoException(String mensaje) {
        super(mensaje);
    }
}
```

```java
package com.curso.pedidos.domain.exception;

import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.PedidoId;

public class TransicionDeEstadoInvalidaException extends PedidoException {

    private final EstadoPedido origen;
    private final EstadoPedido destino;

    public TransicionDeEstadoInvalidaException(PedidoId id, EstadoPedido origen, EstadoPedido destino) {
        super("El pedido %s no puede pasar de %s a %s".formatted(id.valor(), origen, destino));
        this.origen = origen;
        this.destino = destino;
    }

    public EstadoPedido origen()  { return origen; }
    public EstadoPedido destino() { return destino; }
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

import com.curso.pedidos.domain.model.PedidoId;

public class PedidoVacioException extends PedidoException {
    public PedidoVacioException(PedidoId id) {
        super("No se puede confirmar el pedido " + id.valor() + " porque no tiene líneas");
    }
}
```

```java
package com.curso.pedidos.domain.exception;

import com.curso.pedidos.domain.model.EstadoPedido;
import com.curso.pedidos.domain.model.PedidoId;

public class PedidoNoModificableException extends PedidoException {
    public PedidoNoModificableException(PedidoId id, EstadoPedido estado) {
        super("El pedido %s está en %s y ya no admite cambios".formatted(id.valor(), estado));
    }
}
```

```java
package com.curso.pedidos.domain.exception;

import com.curso.pedidos.domain.model.ProductoId;

public class StockInsuficienteException extends PedidoException {

    private final ProductoId productoId;
    private final int solicitado;
    private final int disponible;

    public StockInsuficienteException(ProductoId productoId, int solicitado, int disponible) {
        super("Stock insuficiente de %s: solicitado %d, disponible %d"
                .formatted(productoId.sku(), solicitado, disponible));
        this.productoId = productoId;
        this.solicitado = solicitado;
        this.disponible = disponible;
    }

    public ProductoId productoId() { return productoId; }
    public int solicitado()        { return solicitado; }
    public int disponible()        { return disponible; }
}
```

**¿Y `IllegalArgumentException` en los value objects?** Es un criterio razonable y muy extendido:
un `Cantidad(-1)` es un **error de programación o de entrada** que debería haberse filtrado antes
(validación del comando, día 3); una transición inválida es una **regla de negocio**. Si vuestro
equipo prefiere excepciones de dominio también para los VOs, adelante: lo importante es ser coherente.

---

## 10. `Producto` (otro agregado)

`Producto` pertenece al catálogo; `Pedido` solo lo referencia por `ProductoId`. Versión mínima (la
ampliaréis en el [ejercicio 1](06-ejercicios-dia-02.md)):

```java
package com.curso.pedidos.domain.model;

import java.util.Objects;

public class Producto {

    private final ProductoId id;
    private final String nombre;
    private final Dinero precio;
    private int stock;

    public Producto(ProductoId id, String nombre, Dinero precio, int stock) {
        this.id = Objects.requireNonNull(id, "id");
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("El nombre del producto es obligatorio");
        }
        if (stock < 0) {
            throw new IllegalArgumentException("El stock no puede ser negativo: " + stock);
        }
        this.nombre = nombre;
        this.precio = Objects.requireNonNull(precio, "precio");
        this.stock = stock;
    }

    public boolean hayStockPara(Cantidad cantidad) {
        return stock >= cantidad.valor();
    }

    public ProductoId id()  { return id; }
    public String nombre()  { return nombre; }
    public Dinero precio()  { return precio; }
    public int stock()      { return stock; }

    @Override
    public boolean equals(Object o) {
        return o instanceof Producto otro && id.equals(otro.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
```

---

## 11. ¿Por qué el dominio sin Spring, JPA ni Lombok?

| Tentación                     | Por qué no en el dominio                                                       | Dónde sí                         |
|-------------------------------|--------------------------------------------------------------------------------|----------------------------------|
| `@Entity`, `@Id`, `@OneToMany`| JPA exige constructor sin argumentos, campos mutables y define tu modelo por tu esquema de tablas | `PedidoJpaEntity` (día 4)   |
| `@Component`, `@Autowired`    | El dominio no necesita inyección; y te ata al contenedor                       | `BeanConfiguration` (día 4)      |
| `@JsonProperty`               | El formato del JSON es cosa del adaptador web                                  | DTOs web (día 3)                 |
| Lombok `@Data`/`@Setter`      | Genera setters (rompe invariantes) y `equals` sobre todo (rompe identidad); `record` ya cubre los VOs | Adaptadores, si el equipo quiere |
| `@Transactional`              | La transacción es una preocupación técnica de la frontera                      | Aplicación/infraestructura (día 6) |

Ventajas medibles:

- **Tests en milisegundos**: `new Pedido...`, sin contexto, sin base de datos.
- **Cambiar de framework o de versión** no toca el corazón del negocio.
- **El modelo se diseña por el negocio**, no por cómo mapea Hibernate.

El precio: **mapear** entre dominio y entidades JPA/DTOs. Es código aburrido, pero explícito; lo
veremos (y valoraremos alternativas pragmáticas) en los días 3, 4 y 6.

---

## 12. Modelo rico vs anémico — resumen

| Aspecto                    | Anémico                                  | Rico                                         |
|----------------------------|------------------------------------------|----------------------------------------------|
| Dónde están las reglas     | En servicios, repartidas                 | En el agregado, junto a los datos            |
| Cambiar estado             | `setEstado("CONFIRMADO")`                | `pedido.confirmar()`                         |
| Total                      | Campo que hay que recalcular             | `total()` derivado                           |
| Tipos                      | `String`, `Long`, `BigDecimal`           | `EstadoPedido`, `PedidoId`, `Dinero`         |
| Errores                    | `RuntimeException("vacío")`              | `PedidoVacioException`                       |
| Testear una regla          | Montar servicio + mocks                  | `new` + `assertThat`                         |
| Riesgo                     | Estados imposibles en producción         | Más clases, hay que mapear en los bordes     |

---

## 13. Testing (avance)

Todo lo anterior se prueba sin Spring. Un aperitivo (el bloque completo está en
[02-testing-del-modelo-de-dominio.md](02-testing-del-modelo-de-dominio.md)):

```java
@Test
void no_se_puede_confirmar_un_pedido_vacio() {
    Pedido pedido = Pedido.crear(ClienteId.nuevo());

    assertThatThrownBy(pedido::confirmar)
            .isInstanceOf(PedidoVacioException.class);
    assertThat(pedido.estado()).isEqualTo(EstadoPedido.BORRADOR);
}
```

---

## Ejercicio

Añade el value object `Email(String valor)` en `domain.model`:

- No admite `null` ni cadenas en blanco.
- Normaliza a minúsculas y sin espacios alrededor.
- Debe contener exactamente una `@` con texto a ambos lados y un punto en el dominio.
- `new Email("Ana@Tienda.es").equals(new Email("ana@tienda.es"))` debe ser `true`.

**Pista:** constructor compacto + reasignación del parámetro + `Pattern`.

<details><summary>Solución</summary>

```java
package com.curso.pedidos.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

public record Email(String valor) {

    private static final Pattern FORMATO = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public Email {
        Objects.requireNonNull(valor, "El email es obligatorio");
        valor = valor.trim().toLowerCase();
        if (!FORMATO.matcher(valor).matches()) {
            throw new IllegalArgumentException("Email inválido: " + valor);
        }
    }
}
```

`"   "` tras `trim()` queda vacío y no casa con el patrón, así que también se rechaza. En el
[bloque 2](02-testing-del-modelo-de-dominio.md) lo probamos con `@ParameterizedTest`.

</details>

---

## Referencias

- Eric Evans, *Domain-Driven Design: Tackling Complexity in the Heart of Software* (2003). Caps. 5 y 6.
- Vaughn Vernon, *Implementing Domain-Driven Design* (2013). Caps. 5 (Entities), 6 (Value Objects), 10 (Aggregates).
- Martin Fowler, *ValueObject*: https://martinfowler.com/bliki/ValueObject.html
- Martin Fowler, *AnemicDomainModel*: https://martinfowler.com/bliki/AnemicDomainModel.html
- Alistair Cockburn, *Hexagonal Architecture*: https://alistair.cockburn.us/hexagonal-architecture/
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023) y https://github.com/thombergs/buckpal
- jMolecules (anotaciones de DDD sin acoplar a frameworks): https://github.com/xmolecules/jmolecules

## Mantra

> *"Si un objeto puede estar en un estado inválido, algún día lo estará. Que el agregado no lo permita."*
