# Día 1 — Bloque 2: Principios de diseño clave

> *"Depend upon abstractions. Do not depend upon concretions."*
> — Robert C. Martin, *Agile Software Development* (2002)

---

## 1. El problema en una frase

Las arquitecturas se rompen por **dependencias mal puestas**: cuando la clase
que contiene las reglas de negocio conoce detalles (JPA, HTTP, JSON), cualquier
cambio en esos detalles se propaga hasta el corazón del sistema.

En cristiano: la arquitectura hexagonal no es más que **un puñado de principios
clásicos aplicados con disciplina a escala de sistema**. Si dominas este
bloque, el resto del curso es consecuencia.

---

## 2. Acoplamiento y cohesión

Dos métricas que vienen de Larry Constantine y Ed Yourdon (*Structured Design*,
1979) y siguen siendo las más útiles:

| Concepto       | Definición                                             | Queremos  |
|----------------|--------------------------------------------------------|-----------|
| **Acoplamiento** | Cuánto sabe un módulo de otro / cuánto le afecta que cambie | **Bajo**  |
| **Cohesión**     | Cuánto tienen que ver entre sí las cosas de un módulo       | **Alta**  |

Tipos de acoplamiento, de peor a menos malo:

```
Contenido  ──►  Común (estado global)  ──►  Control (flags)  ──►
Estampado (paso objetos enteros)  ──►  Datos (solo lo necesario)  ──►  Mensaje
   PEOR ◄──────────────────────────────────────────────────────────► MEJOR
```

Ejemplo de **acoplamiento de control** (un flag que decide el comportamiento):

```java
// Bad: quien llama tiene que saber cómo funciona por dentro
public Dinero calcularTotal(Pedido pedido, boolean aplicarIva, boolean esVip) { ... }
```

Ejemplo de **baja cohesión**: un `PedidoUtils` con `formatearFecha`,
`calcularIva`, `enviarEmail` y `parsearCsv`. Nada que ver entre sí.

Regla práctica: **lo que cambia junto, vive junto; lo que cambia por motivos
distintos, vive separado**.

---

## 3. Separación de responsabilidades (Separation of Concerns)

Edsger Dijkstra (1974) acuñó *separation of concerns*: estudiar un aspecto del
problema aislado de los demás. En una aplicación de pedidos hay, como mínimo,
estas preocupaciones:

| Preocupación      | Ejemplo                                       | Cambia cuando…                       |
|-------------------|-----------------------------------------------|--------------------------------------|
| Reglas de negocio | "No se confirma un pedido vacío"              | Cambia el negocio                    |
| Orquestación      | Cargar pedido, confirmar, guardar, notificar  | Cambia el caso de uso                |
| Persistencia      | Tablas, JPA, SQL                              | Cambia el esquema o el motor         |
| Exposición        | REST, JSON, códigos HTTP                      | Cambia el contrato de API            |
| Integraciones     | Pasarela de pago, email                       | Cambia el proveedor                  |

La arquitectura hexagonal es SoC con una regla añadida: **las reglas de
negocio no conocen a las demás preocupaciones**.

---

## 4. SOLID aplicado al dominio Pedidos

### 4.1 S — Single Responsibility Principle

> *"Un módulo debe ser responsable ante uno, y solo un, actor."* — R. C. Martin

**Bad**

```java
public class PedidoManager {

    public void confirmar(UUID pedidoId) {
        // 1. Regla de negocio
        // 2. SQL a mano
        // 3. Construir HTML del email
        // 4. Llamar al SMTP
    }
}
```

¿Qué duele? Negocio, DBA y marketing piden cambios sobre la **misma clase**.

**Good**: `Pedido.confirmar()` (regla), `PedidoRepository` (persistencia),
`NotificadorPedidos` (aviso), `ConfirmarPedidoService` (orquestación). Una
razón de cambio cada una.

### 4.2 O — Open/Closed Principle

> *"Abierto a extensión, cerrado a modificación."* — Bertrand Meyer (1988)

**Bad**

```java
public Dinero calcularGastosEnvio(Pedido pedido, String tipo) {
    return switch (tipo) {
        case "ESTANDAR" -> Dinero.euros("4.95");
        case "URGENTE"  -> Dinero.euros("9.95");
        // cada tipo nuevo = tocar esta clase
        default -> throw new IllegalArgumentException(tipo);
    };
}
```

**Good**

```java
public interface PoliticaDeEnvio {
    Dinero gastosPara(Pedido pedido);
}

public final class EnvioEstandar implements PoliticaDeEnvio {
    @Override
    public Dinero gastosPara(Pedido pedido) {
        return Dinero.euros("4.95");
    }
}

public final class EnvioGratisDesde implements PoliticaDeEnvio {
    private final Dinero umbral;

    public EnvioGratisDesde(Dinero umbral) { this.umbral = umbral; }

    @Override
    public Dinero gastosPara(Pedido pedido) {
        return pedido.total().esMayorOIgualQue(umbral)
                ? Dinero.cero()
                : Dinero.euros("4.95");
    }
}
```

Nueva política = **nueva clase**. Nadie toca las existentes.

### 4.3 L — Liskov Substitution Principle

> *"Los subtipos deben poder sustituir a sus tipos base sin alterar la
> corrección del programa."* — Barbara Liskov (1987)

**Bad**: un repositorio "de solo lectura" que implementa la interfaz completa y
revienta al guardar.

```java
public class PedidoRepositoryCache implements PedidoRepository {
    @Override
    public void guardar(Pedido pedido) {
        throw new UnsupportedOperationException("solo lectura"); // rompe LSP
    }
    // ...
}
```

Quien usa `PedidoRepository` espera poder guardar. Si el sustituto no cumple el
contrato, no es un subtipo de verdad.

**Good**: separar contratos (esto enlaza con ISP) o cumplir el contrato
completo. En hexagonal, LSP es **vital**: el adaptador en memoria de los tests
y el adaptador JPA de producción **deben comportarse igual** frente al caso de
uso. En el día 5 escribiremos *contract tests* que lo verifican.

### 4.4 I — Interface Segregation Principle

> *"Los clientes no deben depender de métodos que no usan."*

**Bad**

```java
public interface PedidoDao {
    void guardar(Pedido p);
    Optional<Pedido> buscarPorId(PedidoId id);
    List<Pedido> buscarPorCliente(ClienteId id);
    void borrar(PedidoId id);
    byte[] exportarCsv();
    void reindexarBusqueda();
}
```

**Good**: puertos pequeños, pensados desde quien los usa.

```java
public interface PedidoRepository {
    void guardar(Pedido pedido);
    Optional<Pedido> buscarPorId(PedidoId id);
}

public interface ConsultaPedidosPorCliente {
    List<Pedido> deCliente(ClienteId clienteId);
}
```

Mañana veremos que los **puertos** del hexágono son ISP en estado puro.

### 4.5 D — Dependency Inversion Principle (el importante)

> *"A. Los módulos de alto nivel no deben depender de módulos de bajo nivel.
> Ambos deben depender de abstracciones.
> B. Las abstracciones no deben depender de los detalles. Los detalles deben
> depender de las abstracciones."* — Robert C. Martin

Hay dos ideas, y la segunda es la que casi todo el mundo se salta:

1. **Depender de interfaces** (fácil, todos lo hacemos).
2. **La interfaz pertenece al módulo de alto nivel** (la mayoría no lo hace).

**Bad: dependencia directa del detalle**

```java
package com.curso.pedidos.service;

import com.curso.pedidos.jpa.PedidoJpaRepository;   // detalle
import com.curso.pedidos.jpa.PedidoJpaEntity;       // detalle

public class ConfirmarPedidoService {

    private final PedidoJpaRepository jpa = new PedidoJpaRepository(); // new del detalle

    public void confirmar(UUID id) {
        PedidoJpaEntity e = jpa.findById(id).orElseThrow();
        if (e.getLineas().isEmpty()) throw new IllegalStateException("vacío");
        e.setEstado("CONFIRMADO");
        jpa.save(e);
    }
}
```

```
┌────────────────────────┐        ┌────────────────────────┐
│ ConfirmarPedidoService │ ─────► │  PedidoJpaRepository   │
│     (alto nivel)       │        │     (bajo nivel)       │
└────────────────────────┘        └────────────────────────┘
        negocio  ──── depende de ────►  tecnología
```

**"Bad" camuflado: interfaz, pero en el sitio equivocado**

```java
package com.curso.pedidos.persistence;          // la interfaz vive con JPA

public interface PedidoDao extends JpaRepository<PedidoJpaEntity, UUID> { }
```

El servicio "depende de una interfaz"… que está definida **en términos de JPA**
y vive en el paquete de persistencia. La flecha sigue apuntando a la
tecnología. Esto **no** es DIP.

**Good: la abstracción la define y la posee el negocio**

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import java.util.Optional;

public interface PedidoRepository {             // lenguaje de negocio
    void guardar(Pedido pedido);
    Optional<Pedido> buscarPorId(PedidoId id);
}
```

```java
package com.curso.pedidos.application.service;

public class ConfirmarPedidoService {

    private final PedidoRepository pedidos;     // abstracción propia

    public ConfirmarPedidoService(PedidoRepository pedidos) {  // inyección por constructor
        this.pedidos = pedidos;
    }

    public void confirmar(PedidoId id) {
        Pedido pedido = pedidos.buscarPorId(id)
                .orElseThrow(() -> new PedidoNoEncontradoException(id));
        pedido.confirmar();                     // la regla vive en el dominio
        pedidos.guardar(pedido);
    }
}
```

```java
package com.curso.pedidos.infrastructure.adapter.out.persistence;

public class PedidoPersistenceAdapter implements PedidoRepository {  // el detalle depende
    // usa Spring Data JPA por dentro (día 4)
}
```

```
     paquete application                 paquete infrastructure
┌──────────────────────────────┐      ┌──────────────────────────────┐
│ ConfirmarPedidoService       │      │                              │
│          │                   │      │                              │
│          ▼                   │      │                              │
│ «interface» PedidoRepository │ ◄─── │ PedidoPersistenceAdapter     │
└──────────────────────────────┘      └──────────────────────────────┘
          la flecha de código fuente apunta HACIA el negocio
```

**Eso es la inversión**: el flujo de control va de negocio a BD, pero la
dependencia de código fuente va de BD a negocio. Este diagrama, repetido para
cada sistema externo, **es** la arquitectura hexagonal.

Trampa: confundir **DIP** (principio de diseño) con **inyección de
dependencias** (técnica: pasar colaboradores por constructor) y con
**contenedor IoC** (herramienta: Spring). Puedes tener DI con Spring y seguir
violando DIP si las interfaces viven en el paquete equivocado.

| Concepto                  | Qué es               | Ejemplo                                  |
|---------------------------|----------------------|------------------------------------------|
| DIP                       | Principio            | `PedidoRepository` en `application`      |
| Inyección de dependencias | Técnica              | Constructor `ConfirmarPedidoService(...)`|
| Contenedor IoC            | Herramienta          | Spring `ApplicationContext`              |

---

## 5. Ley de Demeter ("no hables con extraños")

Ian Holland (1987): un método solo debería llamar a métodos de sí mismo, de
sus parámetros, de los objetos que crea y de sus atributos directos.

**Bad: choque de trenes**

```java
BigDecimal importe = pedido.getLineas().get(0).getPrecioUnitario().getImporte();
if (pedido.getCliente().getDireccion().getPais().getCodigo().equals("ES")) { ... }
```

¿Qué duele? El llamante conoce **toda la estructura interna**. Si `LineaPedido`
cambia, rompes código en sitios que no deberían saber que existe.

**Good: "Tell, don't ask"**

```java
Dinero total = pedido.total();
boolean nacional = pedido.esEnvioNacional();
```

Demeter empuja la lógica **hacia dentro del objeto que tiene los datos**, que
es justo lo que necesita un modelo de dominio rico.

Trampa: los *fluent builders* y los `Stream` encadenados **no** violan
Demeter: devuelven el mismo tipo o tipos de la librería estándar, no navegan
la estructura interna de tu dominio.

---

## 6. Composición sobre herencia

**Bad: herencia para reutilizar código**

```java
public class PedidoService extends JdbcDaoSupport { ... }   // ¿es-un DAO? No.

public class PedidoVip extends Pedido { ... }               // ¿y si deja de ser VIP?
```

La herencia es el acoplamiento **más fuerte** que existe: la subclase depende
de detalles internos del padre y no se puede cambiar en tiempo de ejecución.

**Good: componer colaboradores**

```java
public class CalculadoraDePrecios {

    private final PoliticaDeDescuento descuento;
    private final PoliticaDeEnvio envio;

    public CalculadoraDePrecios(PoliticaDeDescuento descuento, PoliticaDeEnvio envio) {
        this.descuento = descuento;
        this.envio = envio;
    }

    public Dinero precioFinal(Pedido pedido) {
        Dinero base = pedido.total();
        return descuento.aplicarA(base).sumar(envio.gastosPara(pedido));
    }
}
```

Cliente VIP = otra `PoliticaDeDescuento` inyectada. Sin jerarquías.

---

## 7. Programar contra interfaces

GoF: *"Program to an interface, not an implementation."*

```java
// Bad
ArrayList<Pedido> pendientes = new ArrayList<>();
StripeClient pasarela = new StripeClient(apiKey);

// Good
List<Pedido> pendientes = new ArrayList<>();
PasarelaDePago pasarela;   // inyectada; da igual si es Stripe, Redsys o un fake
```

Ojo: **no** significa "una interfaz por cada clase". Un `PedidoMapper` o un
value object `Dinero` no necesitan interfaz. Interfaz **cuando hay un borde**:
algo que puede variar, algo externo, algo que quieres sustituir en tests.

---

## 8. Testing: los principios se notan en los tests

Gracias a DIP podemos probar `ConfirmarPedidoService` con un doble **escrito a
mano**, sin Mockito, sin Spring y sin BD:

```java
package com.curso.pedidos.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.exception.TransicionDeEstadoInvalidaException;
import com.curso.pedidos.domain.model.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConfirmarPedidoServiceTest {

    static class PedidoRepositoryEnMemoria implements PedidoRepository {
        final Map<PedidoId, Pedido> datos = new HashMap<>();

        @Override public void guardar(Pedido pedido) { datos.put(pedido.id(), pedido); }
        @Override public Optional<Pedido> buscarPorId(PedidoId id) {
            return Optional.ofNullable(datos.get(id));
        }
    }

    private final PedidoRepositoryEnMemoria repo = new PedidoRepositoryEnMemoria();
    private final ConfirmarPedidoService service = new ConfirmarPedidoService(repo);

    @Test
    void confirma_un_pedido_con_lineas() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(new ProductoId("SKU-1"), new Cantidad(2),
                Dinero.euros("10.00"));
        repo.guardar(pedido);

        service.confirmar(pedido.id());

        assertThat(repo.datos.get(pedido.id()).estado())
                .isEqualTo(EstadoPedido.CONFIRMADO);
    }

    @Test
    void no_confirma_un_pedido_vacio() {
        Pedido vacio = Pedido.crear(ClienteId.nuevo());
        repo.guardar(vacio);

        assertThatThrownBy(() -> service.confirmar(vacio.id()))
                .isInstanceOf(TransicionDeEstadoInvalidaException.class);
    }
}
```

Y la política de envío (OCP) se prueba en aislamiento total:

```java
class EnvioGratisDesdeTest {

    @Test
    void envio_gratis_si_supera_el_umbral() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(new ProductoId("SKU-1"), new Cantidad(1), Dinero.euros("60.00"));

        PoliticaDeEnvio politica = new EnvioGratisDesde(Dinero.euros("50.00"));

        assertThat(politica.gastosPara(pedido)).isEqualTo(Dinero.cero());
    }
}
```

> Los helpers `Dinero.euros(...)`, `Dinero.cero()`, `esMayorOIgualQue(...)` y
> `ClienteId.nuevo()` se definen en
> [05-primer-ejemplo-completo.md](05-primer-ejemplo-completo.md).

Si un test necesita 40 líneas de `when(...).thenReturn(...)`, **el test no es
el problema: el diseño lo es**.

---

## 9. Tabla resumen: principios y hexágono

| Principio                  | En una frase                                 | Dónde aparece en el hexágono                              |
|----------------------------|----------------------------------------------|-----------------------------------------------------------|
| Bajo acoplamiento          | Saber lo mínimo del otro                     | Dominio no conoce adaptadores                             |
| Alta cohesión              | Lo que cambia junto, vive junto              | Paquetes `domain`, `application`, `infrastructure`        |
| SoC                        | Una preocupación cada vez                    | Negocio / orquestación / adaptadores                      |
| SRP                        | Un actor por módulo                          | Un caso de uso por clase de servicio                      |
| OCP                        | Extender sin modificar                       | Nuevo adaptador sin tocar el núcleo                       |
| LSP                        | Sustitutos que cumplen el contrato           | Adaptador en memoria ≡ adaptador JPA                      |
| ISP                        | Interfaces pequeñas, desde el cliente        | Puertos finos y con nombre de negocio                     |
| **DIP**                    | **Abstracción propiedad del alto nivel**     | **Puertos de salida definidos en `application`**          |
| Demeter                    | No hables con extraños                       | `pedido.total()`, no `getLineas().get(0)...`              |
| Composición > herencia     | Ensamblar, no heredar                        | Servicios reciben puertos por constructor                 |
| Programar contra interfaces| Depender de contratos                        | Casos de uso dependen de puertos, no de clases concretas  |

---

## Ejercicio

Este método viola **al menos cuatro** principios. Identifícalos y propón un
diseño mejor (solo firmas, sin implementar todo).

```java
public class PedidoHelper extends RestTemplate {

    public void procesar(UUID id, boolean urgente) {
        PedidoJpaEntity e = new PedidoJpaRepositoryImpl().findById(id).get();
        String pais = e.getCliente().getDireccion().getPais().getCodigo();
        if (urgente && pais.equals("ES")) {
            postForObject("https://envios.ejemplo/urgente", e, String.class);
        }
        e.setEstado("ENVIADO");
    }
}
```

*Pista*: mira la herencia, el `new`, el `boolean`, y la cadena de `get`.

<details><summary>Solución</summary>

Violaciones:

- **Composición sobre herencia**: hereda de `RestTemplate` para reutilizar
  `postForObject`. No "es un" cliente HTTP.
- **DIP**: `new PedidoJpaRepositoryImpl()` y uso de `PedidoJpaEntity` en la
  lógica.
- **Demeter**: `getCliente().getDireccion().getPais().getCodigo()`.
- **Acoplamiento de control**: flag `boolean urgente`.
- **SRP**: persistencia, regla de negocio y HTTP en el mismo método.
- Además, cambia el estado con un `setEstado("ENVIADO")` sin invariantes
  (modelo anémico).

Diseño propuesto:

```java
// application.port.out
public interface ServicioDeEnvios {
    void solicitarEnvio(Pedido pedido, ModalidadEnvio modalidad);
}

// domain.model
public enum ModalidadEnvio { ESTANDAR, URGENTE }

// domain.model.Pedido
public boolean esEnvioNacional() { ... }
public void marcarComoEnviado() { ... }  // valida que esté PAGADO

// application.service
public class EnviarPedidoService {
    private final PedidoRepository pedidos;
    private final ServicioDeEnvios envios;

    public EnviarPedidoService(PedidoRepository pedidos, ServicioDeEnvios envios) { ... }

    public void enviar(PedidoId id, ModalidadEnvio modalidad) {
        Pedido pedido = pedidos.buscarPorId(id)
                .orElseThrow(() -> new PedidoNoEncontradoException(id));
        envios.solicitarEnvio(pedido, modalidad);
        pedido.marcarComoEnviado();
        pedidos.guardar(pedido);
    }
}

// infrastructure.adapter.out.shipping
public class HttpServicioDeEnviosAdapter implements ServicioDeEnvios { /* RestClient dentro */ }
```

</details>

---

## Referencias

- Robert C. Martin, *Agile Software Development: Principles, Patterns, and Practices* (2002) y *Clean Architecture* (2017).
- Robert C. Martin, *The Clean Architecture* (2012): https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- Gamma et al., *Design Patterns* (GoF, 1994).
- Martin Fowler, *AnemicDomainModel*: https://martinfowler.com/bliki/AnemicDomainModel.html
- Martin Fowler, *ValueObject*: https://martinfowler.com/bliki/ValueObject.html
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023): https://github.com/thombergs/buckpal
- JUnit 5: https://junit.org/junit5/docs/current/user-guide/ ; AssertJ: https://assertj.github.io/doc/

---

## Mantra

> *"Inyectar dependencias es técnica; invertirlas es diseño. La interfaz es de quien la necesita, no de quien la implementa."*
