# Día 2 — Bloque 4: Clasificación de puertos

> *"As events arrive from the outside world at a port, a technology-specific adapter converts it into
> a usable procedure call or message and passes it to the application."*
> — Alistair Cockburn, *Hexagonal Architecture* (2005)

---

## 1. El problema en una frase

No todos los puertos son iguales: unos los **usa el mundo para pedirle cosas a la aplicación** y
otros los **usa la aplicación para pedirle cosas al mundo**. Si no distinguimos ambos lados, acabamos
con dependencias apuntando en la dirección equivocada.

En cristiano: **¿quién llama a quién?** Esa pregunta clasifica cualquier puerto.

---

## 2. Tres nombres para lo mismo

La literatura usa tres vocabularios. Conviene conocerlos todos porque los vais a leer mezclados:

| Lado                         | Nombre 1 (Cockburn)      | Nombre 2 (posición)  | Nombre 3 (dirección) | Quién inicia la conversación |
|------------------------------|--------------------------|----------------------|----------------------|------------------------------|
| Izquierda del hexágono       | **Primario**             | **Driving**          | **De entrada** (in)  | El actor externo             |
| Derecha del hexágono         | **Secundario**           | **Driven**           | **De salida** (out)  | La aplicación                |

- **Primario / driving / de entrada:** el actor (un usuario vía REST, un test, un cron, un mensaje
  Kafka) **conduce** (*drives*) la aplicación. El puerto expone **casos de uso**.
- **Secundario / driven / de salida:** la aplicación **conduce** a un actor secundario (base de datos,
  pasarela de pago, SMTP, broker). El puerto expone **lo que la aplicación necesita**.

**A partir de aquí, en el curso diremos "de entrada" y "de salida"** (y los paquetes son
`port.in` y `port.out`).

---

## 3. El hexágono completo del dominio Pedidos

```
   ACTORES PRIMARIOS                                                   ACTORES SECUNDARIOS
   (conducen)                                                          (son conducidos)

                     ┌──────────────────── HEXÁGONO ─────────────────────┐
 ┌──────────────┐    │ PUERTOS DE ENTRADA             PUERTOS DE SALIDA  │    ┌──────────────┐
 │ Cliente web  │───►│ ● CrearPedidoUseCase       ● PedidoRepository     │───►│ PostgreSQL   │
 │ (REST, d.3)  │    │                                                   │    └──────────────┘
 └──────────────┘    │ ● ConfirmarPedidoUseCase   ● CatalogoProductos    │───►┌──────────────┐
 ┌──────────────┐    │                                                   │    │ Catálogo     │
 │ Listener     │───►│ ● ConsultarPedidoQuery     ● PasarelaDePago       │    └──────────────┘
 │ Kafka (d.3)  │    │                                                   │───►┌──────────────┐
 └──────────────┘    │        ┌───────────────────────────┐              │    │ Pasarela     │
 ┌──────────────┐    │        │ Servicios de aplicación   │ ● Notificador│    └──────────────┘
 │ Test JUnit   │───►│        │   ┌───────────────────┐   │   Pedidos    │───►┌──────────────┐
 │ (hoy mismo)  │    │        │   │      DOMINIO      │   │              │    │ SMTP         │
 └──────────────┘    │        │   │ Pedido, Dinero... │   │ ● Publicador │    └──────────────┘
 ┌──────────────┐    │        │   └───────────────────┘   │   DeEventos  │───►┌──────────────┐
 │ Cron / CLI   │───►│        └───────────────────────────┘              │    │ Kafka        │
 └──────────────┘    └───────────────────────────────────────────────────┘    └──────────────┘

   el actor LLAMA al puerto de entrada          la aplicación LLAMA al puerto de salida
   (primario / driving / in)                    (secundario / driven / out)
```

---

## 4. Quién define y quién implementa cada puerto

Esta es la parte que más confunde y la más importante. **Los dos tipos de puerto los define el
hexágono.** Lo que cambia es quién implementa y quién llama:

| Puerto                    | Lo **define**           | Lo **implementa**                              | Lo **llama**                       |
|---------------------------|-------------------------|------------------------------------------------|------------------------------------|
| De entrada (`port.in`)    | Hexágono (`application`)| Servicio de aplicación (`application.service`) | Adaptador de entrada (controller)  |
| De salida (`port.out`)    | Hexágono (`application`)| Adaptador de salida (`infrastructure`)         | Servicio de aplicación             |

```
 Adaptador de entrada                 HEXÁGONO                          Adaptador de salida
┌──────────────────┐   ┌─────────────────────────────────────────┐   ┌──────────────────────┐
│ PedidoController │──►│ «in» ConfirmarPedidoUseCase             │   │ PedidoPersistence-   │
└──────────────────┘   │            ▲ implements                 │   │ Adapter              │
      (usa)            │ ConfirmarPedidoService                  │   └──────────┬───────────┘
                       │            │ usa                        │              │ implements
                       │            ▼                            │              │
                       │ «out» PedidoRepository ◄────────────────┼──────────────┘
                       └─────────────────────────────────────────┘
  Las dependencias de código (usa / implements) que cruzan la frontera apuntan HACIA el hexágono.
```

Fijaos en la última línea: **el código de infraestructura depende del hexágono; el hexágono no
depende de nadie.** En el puerto de salida, el flujo de *control* va de dentro a fuera (el servicio
llama al repositorio) pero la *dependencia de código* va de fuera a dentro (el adaptador implementa
la interfaz del hexágono). Eso es exactamente el **Principio de Inversión de Dependencias** del día 1.

> **Trampa:** poner `PedidoRepository` en el paquete de infraestructura "porque es de persistencia".
> Entonces `application` importa `infrastructure` y la inversión se rompe. El puerto vive **donde
> se necesita**, no donde se implementa.

---

## 5. Puertos de entrada del dominio Pedidos

### Comandos vs consultas en los puertos de entrada

Separamos los casos de uso que **cambian estado** (comandos) de los que **solo leen** (consultas).
Es la idea de *Command-Query Separation* (Bertrand Meyer) llevada al nivel de puertos, el primer
peldaño de lo que Fowler describe como CQRS (sin necesidad de dos bases de datos).

| Puerto                    | Tipo     | Firma                                          | Cambia estado | Devuelve           |
|---------------------------|----------|------------------------------------------------|---------------|--------------------|
| `CrearPedidoUseCase`      | Comando  | `PedidoId ejecutar(CrearPedidoCommand)`        | Sí            | Solo el id         |
| `ConfirmarPedidoUseCase`  | Comando  | `void ejecutar(ConfirmarPedidoCommand)`        | Sí            | Nada               |
| `ConsultarPedidoQuery`    | Consulta | `PedidoDetalle ejecutar(PedidoId)`             | No            | Modelo de lectura  |

Convenciones del curso:

- Comandos: sufijo `UseCase`, parámetro `XxxCommand`, devuelven `void` o un identificador.
- Consultas: sufijo `Query`, devuelven un `record` de lectura, **nunca el agregado**.
- Ventaja práctica: a las consultas se les puede dar luego un camino optimizado (proyecciones, una
  vista SQL, caché) sin tocar el modelo de escritura. Lo exploramos en el día 6.

(Las definiciones completas están en el [bloque 3](03-definicion-de-puertos.md).)

---

## 6. Puertos de salida del dominio Pedidos

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;

import java.util.Optional;

public interface PedidoRepository {
    Optional<Pedido> buscarPorId(PedidoId id);
    void guardar(Pedido pedido);
}
```

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.Producto;
import com.curso.pedidos.domain.model.ProductoId;

import java.util.Optional;

public interface CatalogoProductos {
    Optional<Producto> buscarPorId(ProductoId id);
}
```

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.PedidoId;

public interface PasarelaDePago {

    /**
     * Intenta cobrar el importe. Un rechazo (tarjeta sin fondos) es un desenlace de negocio
     * esperado y se devuelve como resultado; un fallo técnico (timeout) se lanza como excepción.
     */
    ResultadoPago cobrar(PedidoId pedidoId, Dinero importe);
}
```

```java
package com.curso.pedidos.application.port.out;

public sealed interface ResultadoPago {

    record Aprobado(String referencia) implements ResultadoPago { }

    record Rechazado(String motivo) implements ResultadoPago { }
}
```

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.PedidoId;

public interface NotificadorPedidos {
    void notificarConfirmacion(PedidoId pedidoId, ClienteId clienteId);
}
```

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.event.EventoDeDominio;

import java.util.List;

public interface PublicadorDeEventos {
    void publicar(List<EventoDeDominio> eventos);
}
```

Observaciones:

- **`ResultadoPago` es `sealed`**: el compilador sabe que solo hay `Aprobado` y `Rechazado`. En Java 17
  se consume con `instanceof` con patrón (`if (r instanceof ResultadoPago.Rechazado rechazado)`); en
  Java 21 con `switch` de patrones exhaustivo, que avisa si añadís un tercer caso. Los `record`
  anidados de una interfaz `sealed` en el mismo fichero no necesitan cláusula `permits`.
- **`NotificadorPedidos` no dice "email"**: mañana puede ser SMS o push. El adaptador elige.
- **`PublicadorDeEventos` no dice "Kafka"**: en tests será una lista; en el día 4, Kafka; en el día 6,
  una tabla *outbox*.
- **`CatalogoProductos` no dice "REST" ni "repositorio"**: puede ser otra tabla, otro microservicio o
  un fichero. Es lo que el núcleo necesita saber del catálogo.

| Puerto de salida       | Actor secundario típico   | Adaptador previsto (día 4)                   |
|------------------------|---------------------------|----------------------------------------------|
| `PedidoRepository`     | PostgreSQL                | `PedidoPersistenceAdapter` (Spring Data JPA) |
| `CatalogoProductos`    | Servicio de catálogo      | Adaptador JPA o REST (`RestClient`)          |
| `PasarelaDePago`       | Proveedor de pagos        | `StripeLikePasarelaAdapter` (`RestClient`)   |
| `NotificadorPedidos`   | Servidor SMTP             | `EmailNotificadorAdapter`                    |
| `PublicadorDeEventos`  | Broker de mensajería      | `KafkaPublicadorDeEventos`                   |

---

## 7. Variantes y trampas

- **Trampa: confundir "de entrada" con "recibe datos".** `PedidoRepository.buscarPorId` *devuelve*
  datos al hexágono, pero es de **salida**: lo inicia la aplicación. El criterio es **quién inicia**.
- **Trampa: un adaptador de entrada que llama a un puerto de salida** (el controller usando el
  repositorio "para una consulta rápida"). Se salta la aplicación y sus reglas. Toda conversación
  entra por un puerto de entrada.
- **Trampa: puertos de salida con nombres de tecnología** (`KafkaPort`, `JpaPedidoPort`). Si el nombre
  cambia al cambiar de tecnología, el nombre está mal.
- **Variante: el test como actor primario.** Cockburn lo dice en la primera frase de su artículo: la
  aplicación debe poder ser conducida "igual por usuarios, programas, tests o scripts". Un test JUnit
  que llama a `ConfirmarPedidoUseCase` es un adaptador de entrada más.
- **Variante: puertos de salida "de configuración"** (p. ej. un `Clock` o un `GeneradorDeIds`).
  Son puertos de salida legítimos cuando el dominio necesita algo no determinista.

---

## 8. Testing: test de contrato de un puerto de salida

Un puerto de salida tendrá varias implementaciones: un **fake en memoria** (para tests de aplicación)
y el **adaptador real** (JPA, día 4). ¿Cómo garantizamos que ambas cumplen el mismo contrato? Con un
**test de contrato abstracto** que cada implementación hereda:

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Cualquier implementación de PedidoRepository debe pasar estos tests. */
public abstract class PedidoRepositoryContractTest {

    protected abstract PedidoRepository crearRepositorio();

    private PedidoRepository repositorio;

    @BeforeEach
    void setUp() {
        repositorio = crearRepositorio();
    }

    @Test
    void devuelve_vacio_si_el_pedido_no_existe() {
        assertThat(repositorio.buscarPorId(PedidoId.nuevo())).isEmpty();
    }

    @Test
    void guarda_y_recupera_un_pedido_con_sus_lineas() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(new ProductoId("TEC-001"), Cantidad.de(2), Dinero.euros("25.00"));

        repositorio.guardar(pedido);

        assertThat(repositorio.buscarPorId(pedido.id())).hasValueSatisfying(recuperado -> {
            assertThat(recuperado.clienteId()).isEqualTo(pedido.clienteId());
            assertThat(recuperado.estado()).isEqualTo(EstadoPedido.BORRADOR);
            assertThat(recuperado.total()).isEqualTo(Dinero.euros("50.00"));
        });
    }

    @Test
    void guardar_de_nuevo_actualiza_el_estado() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(new ProductoId("TEC-001"), Cantidad.de(1), Dinero.euros("10.00"));
        repositorio.guardar(pedido);

        pedido.confirmar();
        repositorio.guardar(pedido);

        assertThat(repositorio.buscarPorId(pedido.id()))
                .map(Pedido::estado)
                .hasValue(EstadoPedido.CONFIRMADO);
    }
}
```

El fake en memoria y su test (en `src/test/java`, paquete `com.curso.pedidos.application.fakes`):

```java
package com.curso.pedidos.application.fakes;

import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class PedidoRepositoryEnMemoria implements PedidoRepository {

    private final Map<PedidoId, Pedido> pedidos = new HashMap<>();

    @Override
    public Optional<Pedido> buscarPorId(PedidoId id) {
        return Optional.ofNullable(pedidos.get(id));
    }

    @Override
    public void guardar(Pedido pedido) {
        pedidos.put(pedido.id(), pedido);
    }

    public int cantidad() {
        return pedidos.size();
    }
}
```

```java
package com.curso.pedidos.application.fakes;

import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PedidoRepositoryContractTest;

class PedidoRepositoryEnMemoriaTest extends PedidoRepositoryContractTest {

    @Override
    protected PedidoRepository crearRepositorio() {
        return new PedidoRepositoryEnMemoria();
    }
}
```

En el día 4, `PedidoPersistenceAdapterTest extends PedidoRepositoryContractTest` con Testcontainers.
Si los dos pasan el mismo contrato, **podemos fiarnos del fake** en los tests de aplicación.

> **Trampa del fake:** guarda la **misma referencia** del agregado. Un repositorio real devuelve una
> copia nueva cada vez. Si un test modifica el pedido y olvida llamar a `guardar`, con el fake
> "funciona" y en producción no. El tercer test de contrato (`guardar_de_nuevo_actualiza_el_estado`)
> no lo detecta; para hacerlo el fake podría guardar una copia (vía `Pedido.reconstituir`). Lo
> discutimos en el día 5.

---

## 9. Resumen

| Pregunta                          | Puerto de entrada                    | Puerto de salida                         |
|-----------------------------------|--------------------------------------|------------------------------------------|
| Otros nombres                     | Primario, driving                    | Secundario, driven                       |
| ¿Quién inicia?                    | Actor externo                        | La aplicación                            |
| ¿Quién lo define?                 | El hexágono                          | El hexágono                              |
| ¿Quién lo implementa?             | Servicio de aplicación               | Adaptador de infraestructura             |
| ¿Quién lo llama?                  | Adaptador de entrada                 | Servicio de aplicación                   |
| Paquete                           | `application.port.in`                | `application.port.out`                   |
| Ejemplos                          | `CrearPedidoUseCase`, `ConsultarPedidoQuery` | `PedidoRepository`, `PasarelaDePago` |
| Cómo se testea                    | Llamándolo desde un test (actor)     | Test de contrato + fakes                 |

---

## Ejercicio

Clasifica cada pieza y di **en qué paquete** vive y **quién la implementa**:

1. `EnviarFacturaPorEmail` (la app manda la factura al confirmar el pago).
2. Un job nocturno que cancela pedidos en `BORRADOR` de hace más de 7 días.
3. `ListarPedidosDeCliente`.
4. Un servicio externo de cálculo de gastos de envío.
5. Un webhook al que la pasarela de pago avisa de que un cobro se ha completado.

**Pista:** pregúntate siempre "¿quién inicia la conversación?".

<details><summary>Solución</summary>

| # | Pieza                         | Clasificación                                                               | Paquete del puerto | Implementa               |
|---|-------------------------------|-----------------------------------------------------------------------------|--------------------|--------------------------|
| 1 | Envío de factura              | Salida (la app inicia). Puerto p. ej. `EnviadorDeFacturas`                  | `port.out`         | Adaptador de email       |
| 2 | Job nocturno                  | El job es un **adaptador de entrada** (scheduler) que llama a un puerto de entrada `CancelarPedidosCaducadosUseCase` | `port.in` | Servicio de aplicación |
| 3 | Listar pedidos de cliente     | Entrada, consulta: `ListarPedidosDeClienteQuery` → `List<PedidoResumen>`    | `port.in`          | Servicio de aplicación   |
| 4 | Gastos de envío externos      | Salida: `CalculadoraDeGastosDeEnvio` (nombre de negocio, no `EnvioRestClient`) | `port.out`      | Adaptador REST           |
| 5 | Webhook de la pasarela        | El webhook es un **adaptador de entrada** (REST) que llama a `RegistrarPagoUseCase`. Ojo: la pasarela es actor secundario cuando cobramos y **primario** cuando nos avisa | `port.in` | Servicio de aplicación |

El caso 5 es el interesante: **un mismo sistema externo puede ser actor primario y secundario**,
según quién inicie cada conversación.

</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture*: https://alistair.cockburn.us/hexagonal-architecture/
- Alistair Cockburn & Juan Manuel Garrido de Paz, *Hexagonal Architecture Explained* (2024).
- Martin Fowler, *CQRS*: https://martinfowler.com/bliki/CQRS.html
- Robert C. Martin, *The Clean Architecture*: https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- Netflix Tech Blog, *Ready for changes with Hexagonal Architecture* (2020): https://netflixtechblog.com/ready-for-changes-with-hexagonal-architecture-b315ec967749
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023) y https://github.com/thombergs/buckpal

## Mantra

> *"Los puertos de entrada dicen lo que la aplicación sabe hacer; los de salida, lo que necesita.
> Los dos los define el hexágono."*
