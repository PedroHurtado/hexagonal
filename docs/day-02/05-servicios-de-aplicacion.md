# Día 2 — Bloque 5: Servicios de aplicación

> *"The application layer is thin. It does not contain business rules or knowledge, but only
> coordinates tasks and delegates work to collaborations of domain objects."*
> — Eric Evans, *Domain-Driven Design* (2003)

---

## 1. El problema en una frase

Alguien tiene que **implementar los puertos de entrada**: cargar el pedido, pedirle que se confirme,
guardarlo, publicar los eventos y avisar al cliente. Si ese "alguien" empieza también a decidir
reglas de negocio, el dominio se vacía y volvemos al modelo anémico.

En cristiano: el servicio de aplicación es el **director de orquesta**. No toca ningún instrumento;
dice quién entra y cuándo.

---

## 2. Orquestación vs lógica de dominio

| El servicio de aplicación **sí** hace…            | El servicio de aplicación **no** hace…                |
|---------------------------------------------------|-------------------------------------------------------|
| Cargar agregados por su puerto de salida          | Decidir si un pedido se puede confirmar               |
| Invocar métodos de negocio del dominio            | `if (pedido.estado() == BORRADOR) ...`                |
| Guardar el resultado                              | Calcular totales o descuentos                         |
| Publicar eventos y lanzar notificaciones          | Cambiar campos del agregado "a mano"                  |
| Traducir comando → llamadas al dominio            | Conocer HTTP, JSON, SQL o Kafka                       |
| Ser la frontera transaccional (día 6)             | Capturar excepciones de dominio para "arreglarlas"    |

**Test rápido:** si el servicio contiene un `if` que habla de reglas del negocio, probablemente ese
`if` pertenece al dominio. Los `if` que quedan en un servicio de aplicación son de **flujo**
("¿existe el pedido?").

---

## 3. Bad — el servicio que se come al dominio

```java
// ❌ Las reglas viven aquí; Pedido es una bolsa de datos
public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {

    private final PedidoRepository repo;
    private final JavaMailSender mailSender;           // ❌ tecnología concreta
    private final KafkaTemplate<String, Object> kafka; // ❌ tecnología concreta

    // ...

    @Override
    public void ejecutar(ConfirmarPedidoCommand comando) {
        Pedido p = repo.buscarPorId(comando.pedidoId()).orElseThrow();
        if (p.getLineas().isEmpty()) throw new IllegalStateException("vacío");              // regla
        if (p.getEstado() != EstadoPedido.BORRADOR) throw new IllegalStateException("estado"); // regla
        BigDecimal total = BigDecimal.ZERO;
        for (var l : p.getLineas()) total = total.add(l.getPrecio().multiply(BigDecimal.valueOf(l.getCantidad()))); // regla
        p.setTotal(total);
        p.setEstado(EstadoPedido.CONFIRMADO);
        repo.guardar(p);
        kafka.send("pedidos", new PedidoConfirmado(p.getId(), Instant.now()));
        var msg = new SimpleMailMessage();
        msg.setTo(p.getEmailCliente());
        msg.setText("Tu pedido está confirmado");
        mailSender.send(msg);
    }
}
```

### ¿Qué duele?

- **Reglas duplicables**: el día que `CancelarPedidoService` necesite saber si un pedido está vacío,
  alguien copiará el `if`. Y el de `marcarComoPagado`. Y el del job nocturno.
- **Dependencias de infraestructura** (`JavaMailSender`, `KafkaTemplate`) dentro de `application`: el
  test necesita Spring Mail y Kafka, o mocks de APIs ajenas.
- **`orElseThrow()` sin excepción de dominio**: el adaptador web recibirá un `NoSuchElementException`
  y devolverá un 500 en vez de un 404.
- **Imposible de leer como un caso de uso**: la intención se pierde entre bucles y `SimpleMailMessage`.

---

## 4. Good — `ConfirmarPedidoService`

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.out.CatalogoProductos;
import com.curso.pedidos.application.port.out.NotificadorPedidos;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.exception.StockInsuficienteException;
import com.curso.pedidos.domain.model.LineaPedido;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.Producto;

import java.util.Objects;

public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final CatalogoProductos catalogoProductos;
    private final PublicadorDeEventos publicadorDeEventos;
    private final NotificadorPedidos notificadorPedidos;

    public ConfirmarPedidoService(PedidoRepository pedidoRepository,
                                  CatalogoProductos catalogoProductos,
                                  PublicadorDeEventos publicadorDeEventos,
                                  NotificadorPedidos notificadorPedidos) {
        this.pedidoRepository = Objects.requireNonNull(pedidoRepository);
        this.catalogoProductos = Objects.requireNonNull(catalogoProductos);
        this.publicadorDeEventos = Objects.requireNonNull(publicadorDeEventos);
        this.notificadorPedidos = Objects.requireNonNull(notificadorPedidos);
    }

    @Override
    public void ejecutar(ConfirmarPedidoCommand comando) {
        // 1. Cargar
        Pedido pedido = pedidoRepository.buscarPorId(comando.pedidoId())
                .orElseThrow(() -> new PedidoNoEncontradoException(comando.pedidoId()));

        // 2. Comprobar precondiciones que requieren datos de fuera del agregado
        verificarStock(pedido);

        // 3. Delegar la regla de negocio en el dominio
        pedido.confirmar();

        // 4. Persistir
        pedidoRepository.guardar(pedido);

        // 5. Efectos posteriores
        publicadorDeEventos.publicar(pedido.extraerEventos());
        notificadorPedidos.notificarConfirmacion(pedido.id(), pedido.clienteId());
    }

    private void verificarStock(Pedido pedido) {
        for (LineaPedido linea : pedido.lineas()) {
            Producto producto = catalogoProductos.buscarPorId(linea.productoId())
                    .orElseThrow(() -> new StockInsuficienteException(
                            linea.productoId(), linea.cantidad().valor(), 0));
            if (!producto.hayStockPara(linea.cantidad())) {          // la decisión la toma Producto
                throw new StockInsuficienteException(
                        linea.productoId(), linea.cantidad().valor(), producto.stock());
            }
        }
    }
}
```

Fijaos en lo que **no** hay:

- **Ni una anotación de Spring.** Ni `@Service` ni `@Transactional`. Se registra como bean desde
  fuera (sección 6).
- **Ni una regla de estado.** "¿Está vacío?", "¿está en borrador?" las decide `pedido.confirmar()`.
- **Ni una tecnología.** Solo puertos con nombres de negocio.

Se lee de arriba abajo como la descripción del caso de uso. Ese es el objetivo.

```
 ConfirmarPedidoCommand
          │
          ▼
 ┌─────────────────────────┐   buscarPorId   ┌──────────────────────┐
 │ ConfirmarPedidoService  │────────────────►│ «out» PedidoRepository│
 │                         │   buscarPorId   ├──────────────────────┤
 │  1 cargar               │────────────────►│ «out» CatalogoProductos│
 │  2 verificar stock      │                 └──────────────────────┘
 │  3 pedido.confirmar() ──┼──► DOMINIO (reglas + evento PedidoConfirmado)
 │  4 guardar              │────────────────► «out» PedidoRepository
 │  5 publicar / notificar │────────────────► «out» PublicadorDeEventos
 └─────────────────────────┘────────────────► «out» NotificadorPedidos
```

### ¿Y la verificación de stock no es lógica de negocio?

Buena pregunta (y saldrá en clase). La **decisión** ("¿hay stock suficiente?") está en
`Producto.hayStockPara`; el servicio solo **recorre y reúne** datos de otro agregado. Si la regla
creciera (reservas, stock en varios almacenes), la moveríamos a un **servicio de dominio**
`VerificadorDeStock` que reciba el pedido y los productos, y el servicio de aplicación quedaría aún
más fino. Regla: cuando dudéis, empujad la lógica hacia el centro.

### Orden de los efectos y consistencia

Guardar y luego publicar tiene un riesgo: si la publicación falla, el pedido queda confirmado sin
evento; si publicamos antes de guardar y el guardado falla, emitimos un evento falso. La solución
seria (patrón **Transactional Outbox**) la vemos en el día 6. Hoy basta con saber que el problema
existe y que **el lugar donde se decide es el servicio de aplicación**, no el dominio.

---

## 5. El `CrearPedidoService` del día 1, revisado

Con los puertos de hoy, el caso de uso de ayer toma los precios del catálogo (nunca del cliente):

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.CrearPedidoCommand;
import com.curso.pedidos.application.port.in.CrearPedidoUseCase;
import com.curso.pedidos.application.port.out.CatalogoProductos;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.exception.StockInsuficienteException;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;
import com.curso.pedidos.domain.model.Producto;

public class CrearPedidoService implements CrearPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final CatalogoProductos catalogoProductos;

    public CrearPedidoService(PedidoRepository pedidoRepository, CatalogoProductos catalogoProductos) {
        this.pedidoRepository = pedidoRepository;
        this.catalogoProductos = catalogoProductos;
    }

    @Override
    public PedidoId ejecutar(CrearPedidoCommand comando) {
        Pedido pedido = Pedido.crear(comando.clienteId());
        for (CrearPedidoCommand.Linea linea : comando.lineas()) {
            Producto producto = catalogoProductos.buscarPorId(linea.productoId())
                    .orElseThrow(() -> new StockInsuficienteException(
                            linea.productoId(), linea.cantidad().valor(), 0));
            pedido.anadirLinea(producto.id(), linea.cantidad(), producto.precio());
        }
        pedidoRepository.guardar(pedido);
        return pedido.id();
    }
}
```

> Usar `StockInsuficienteException` para "producto inexistente" es una simplificación del curso. En
> un sistema real tendríais `ProductoNoEncontradoException`. Buen candidato para refactorizar.

---

## 6. ¿Cómo llega a ser un bean de Spring? (avance del día 4)

Como el servicio no tiene anotaciones, lo cableamos en infraestructura:

```java
package com.curso.pedidos.infrastructure.config;

import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.application.port.out.*;
import com.curso.pedidos.application.service.ConfirmarPedidoService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfiguration {

    @Bean
    ConfirmarPedidoUseCase confirmarPedidoUseCase(PedidoRepository pedidoRepository,
                                                  CatalogoProductos catalogoProductos,
                                                  PublicadorDeEventos publicadorDeEventos,
                                                  NotificadorPedidos notificadorPedidos) {
        return new ConfirmarPedidoService(pedidoRepository, catalogoProductos,
                publicadorDeEventos, notificadorPedidos);
    }
}
```

| Opción                                    | Ventaja                                       | Inconveniente                                  |
|-------------------------------------------|-----------------------------------------------|------------------------------------------------|
| `@Bean` en `BeanConfiguration` (curso)    | `application` 100 % libre de Spring           | Un método por caso de uso                      |
| `@Service` en el servicio                 | Cero configuración                            | `application` importa Spring                   |
| Anotación propia `@UseCase` + escaneo     | Semántica de negocio, un solo punto de config | Algo de "magia" que hay que documentar         |

Lo desarrollamos en el día 4 (gestión de dependencias). La transacción (`@Transactional` o
`TransactionTemplate`), en el día 6.

---

## 7. Testing del servicio de aplicación con dobles

Aquí el servicio sí tiene colaboradores (los puertos de salida). Necesitamos **dobles de prueba**.
Dos estilos:

### 7.1 Con fakes en memoria (estilo *classicist* / "Detroit")

Implementaciones reales pero simples de los puertos. Ya tenemos `PedidoRepositoryEnMemoria`
(bloque 4). Añadimos los demás en `src/test/java/com/curso/pedidos/application/fakes`:

```java
package com.curso.pedidos.application.fakes;

import com.curso.pedidos.application.port.out.CatalogoProductos;
import com.curso.pedidos.domain.model.Producto;
import com.curso.pedidos.domain.model.ProductoId;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class CatalogoProductosEnMemoria implements CatalogoProductos {

    private final Map<ProductoId, Producto> productos = new HashMap<>();

    public CatalogoProductosEnMemoria con(Producto producto) {
        productos.put(producto.id(), producto);
        return this;
    }

    @Override
    public Optional<Producto> buscarPorId(ProductoId id) {
        return Optional.ofNullable(productos.get(id));
    }
}
```

```java
package com.curso.pedidos.application.fakes;

import com.curso.pedidos.application.port.out.PublicadorDeEventos;
import com.curso.pedidos.domain.event.EventoDeDominio;

import java.util.ArrayList;
import java.util.List;

public class PublicadorDeEventosEnMemoria implements PublicadorDeEventos {

    private final List<EventoDeDominio> publicados = new ArrayList<>();

    @Override
    public void publicar(List<EventoDeDominio> eventos) {
        publicados.addAll(eventos);
    }

    public List<EventoDeDominio> publicados() {
        return List.copyOf(publicados);
    }
}
```

```java
package com.curso.pedidos.application.fakes;

import com.curso.pedidos.application.port.out.NotificadorPedidos;
import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.PedidoId;

import java.util.ArrayList;
import java.util.List;

public class NotificadorPedidosEnMemoria implements NotificadorPedidos {

    private final List<PedidoId> confirmacionesEnviadas = new ArrayList<>();

    @Override
    public void notificarConfirmacion(PedidoId pedidoId, ClienteId clienteId) {
        confirmacionesEnviadas.add(pedidoId);
    }

    public List<PedidoId> confirmacionesEnviadas() {
        return List.copyOf(confirmacionesEnviadas);
    }
}
```

El test:

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.fakes.*;
import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.exception.PedidoVacioException;
import com.curso.pedidos.domain.exception.StockInsuficienteException;
import com.curso.pedidos.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfirmarPedidoServiceTest {

    private static final ProductoId TECLADO = new ProductoId("TEC-001");

    private PedidoRepositoryEnMemoria pedidos;
    private CatalogoProductosEnMemoria catalogo;
    private PublicadorDeEventosEnMemoria publicador;
    private NotificadorPedidosEnMemoria notificador;
    private ConfirmarPedidoService servicio;

    @BeforeEach
    void setUp() {
        pedidos = new PedidoRepositoryEnMemoria();
        catalogo = new CatalogoProductosEnMemoria()
                .con(new Producto(TECLADO, "Teclado mecánico", Dinero.euros("25.00"), 10));
        publicador = new PublicadorDeEventosEnMemoria();
        notificador = new NotificadorPedidosEnMemoria();
        servicio = new ConfirmarPedidoService(pedidos, catalogo, publicador, notificador);
    }

    private Pedido pedidoGuardadoConTeclados(int unidades) {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(TECLADO, Cantidad.de(unidades), Dinero.euros("25.00"));
        pedidos.guardar(pedido);
        return pedido;
    }

    @Test
    void confirma_guarda_publica_el_evento_y_notifica() {
        Pedido pedido = pedidoGuardadoConTeclados(2);

        servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id()));

        assertThat(pedidos.buscarPorId(pedido.id()))
                .map(Pedido::estado).hasValue(EstadoPedido.CONFIRMADO);
        assertThat(publicador.publicados())
                .singleElement().isInstanceOf(PedidoConfirmado.class);
        assertThat(notificador.confirmacionesEnviadas()).containsExactly(pedido.id());
    }

    @Test
    void falla_si_el_pedido_no_existe_y_no_tiene_efectos() {
        PedidoId inexistente = PedidoId.nuevo();

        assertThatThrownBy(() -> servicio.ejecutar(new ConfirmarPedidoCommand(inexistente)))
                .isInstanceOf(PedidoNoEncontradoException.class);
        assertThat(publicador.publicados()).isEmpty();
        assertThat(notificador.confirmacionesEnviadas()).isEmpty();
    }

    @Test
    void falla_si_no_hay_stock_suficiente_y_el_pedido_sigue_en_borrador() {
        Pedido pedido = pedidoGuardadoConTeclados(11);   // stock 10

        assertThatThrownBy(() -> servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id())))
                .isInstanceOfSatisfying(StockInsuficienteException.class, ex -> {
                    assertThat(ex.solicitado()).isEqualTo(11);
                    assertThat(ex.disponible()).isEqualTo(10);
                });
        assertThat(pedido.estado()).isEqualTo(EstadoPedido.BORRADOR);
        assertThat(notificador.confirmacionesEnviadas()).isEmpty();
    }

    @Test
    void propaga_las_reglas_del_dominio_pedido_vacio() {
        Pedido vacio = Pedido.crear(ClienteId.nuevo());
        pedidos.guardar(vacio);

        assertThatThrownBy(() -> servicio.ejecutar(new ConfirmarPedidoCommand(vacio.id())))
                .isInstanceOf(PedidoVacioException.class);
        assertThat(publicador.publicados()).isEmpty();
    }
}
```

Fijaos: el test **no sabe cómo** el servicio usa los puertos (cuántas llamadas, en qué orden). Solo
comprueba el **resultado observable**: el pedido está confirmado, se publicó el evento, se notificó.
Si mañana refactorizáis el servicio, el test sigue valiendo.

### 7.2 Con Mockito (estilo *mockist* / "London")

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.out.*;
import com.curso.pedidos.domain.event.EventoDeDominio;
import com.curso.pedidos.domain.event.PedidoConfirmado;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfirmarPedidoServiceMockitoTest {

    private static final ProductoId TECLADO = new ProductoId("TEC-001");

    @Mock PedidoRepository pedidoRepository;
    @Mock CatalogoProductos catalogoProductos;
    @Mock PublicadorDeEventos publicadorDeEventos;
    @Mock NotificadorPedidos notificadorPedidos;
    @InjectMocks ConfirmarPedidoService servicio;
    @Captor ArgumentCaptor<List<EventoDeDominio>> eventosCaptor;

    @Test
    void guarda_antes_de_publicar_y_notificar() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(TECLADO, Cantidad.de(1), Dinero.euros("25.00"));
        when(pedidoRepository.buscarPorId(pedido.id())).thenReturn(Optional.of(pedido));
        when(catalogoProductos.buscarPorId(TECLADO))
                .thenReturn(Optional.of(new Producto(TECLADO, "Teclado", Dinero.euros("25.00"), 5)));

        servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id()));

        InOrder orden = inOrder(pedidoRepository, publicadorDeEventos, notificadorPedidos);
        orden.verify(pedidoRepository).guardar(pedido);
        orden.verify(publicadorDeEventos).publicar(eventosCaptor.capture());
        orden.verify(notificadorPedidos).notificarConfirmacion(pedido.id(), pedido.clienteId());
        assertThat(eventosCaptor.getValue()).singleElement().isInstanceOf(PedidoConfirmado.class);
    }

    @Test
    void si_el_pedido_no_existe_no_toca_ningun_otro_puerto() {
        PedidoId id = PedidoId.nuevo();
        when(pedidoRepository.buscarPorId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.ejecutar(new ConfirmarPedidoCommand(id)))
                .isInstanceOf(PedidoNoEncontradoException.class);

        verify(pedidoRepository, never()).guardar(any());
        verifyNoInteractions(catalogoProductos, publicadorDeEventos, notificadorPedidos);
    }
}
```

Notas sobre Mockito:

- **`MockitoExtension` usa *strict stubs***: si stubeáis algo que el test no usa, falla con
  `UnnecessaryStubbingException`. Es una ayuda, no un estorbo: os avisa de tests con ruido.
- **`InOrder`** permite verificar el orden "guardar antes de publicar", algo que con fakes cuesta más.
  Úsalo solo cuando el orden **sea un requisito** (aquí lo es: no publicar eventos de algo no guardado).
- **Nunca mockeéis `Pedido`**: es dominio, se construye con `new` y es lo que queréis ejercitar de verdad.
- **Mockead solo tipos vuestros** (los puertos). Mockear `JdbcTemplate` o `KafkaTemplate` es la señal
  de que falta un puerto.

### 7.3 ¿Fakes o Mockito? Cuándo cada uno

| Criterio                                    | Fakes en memoria                         | Mockito                                    |
|---------------------------------------------|------------------------------------------|--------------------------------------------|
| Qué verifica                                | **Estado** resultante                    | **Interacciones** (llamadas, orden)        |
| Resistencia a refactorizar el servicio      | Alta                                     | Media/baja (acoplado a la implementación)  |
| Coste inicial                               | Escribir el fake (una vez, se reutiliza) | Ninguno                                    |
| Legibilidad con muchos escenarios           | Alta (datos de prueba naturales)         | Baja (muchos `when(...)`)                  |
| Simular errores raros (timeout, excepción)  | Hay que programarlo en el fake           | Trivial: `thenThrow(...)`                  |
| Verificar "no se llamó a X" / orden         | Posible pero manual                      | Natural: `never()`, `InOrder`              |
| Fiabilidad                                  | Alta si pasa el **test de contrato**     | Depende de que el stub refleje la realidad |

**Recomendación del curso:**

- **Fakes por defecto** para repositorios y catálogos (puertos "de datos"), validados con el test de
  contrato del [bloque 4](04-clasificacion-de-puertos.md).
- **Mockito** para puertos "de efectos" donde importa *que* se llamó (notificador, publicador,
  pasarela) y para **simular fallos** (`when(pasarela.cobrar(...)).thenThrow(...)`).
- Mezclar ambos en el mismo test es perfectamente válido.

---

## 8. Trampas comunes

- **Trampa: servicio de aplicación que llama a otro servicio de aplicación.** `ConfirmarPedidoService`
  invocando `CrearPedidoUseCase`. Crea dependencias en cadena y transacciones confusas. Si dos casos
  de uso comparten lógica, esa lógica suele ser de dominio.
- **Trampa: devolver el agregado desde un comando** para que el controlador "lo pinte". Devolved un id
  y que el controlador use `ConsultarPedidoQuery` si necesita datos.
- **Trampa: capturar `PedidoException` en el servicio** y devolver `null` o `false`. Dejadla subir; el
  adaptador de entrada sabe traducirla (día 3).
- **Trampa: `@Transactional` en el dominio** o en cada método de puerto de salida. La unidad de trabajo
  es el caso de uso (día 6).
- **Trampa: un servicio con 12 dependencias.** Señal de que el caso de uso hace demasiado o de que
  faltan eventos de dominio para desacoplar efectos.

---

## 9. Resumen

| Pieza                     | Responsabilidad                                  | Dónde                      | Cómo se testea                       |
|---------------------------|--------------------------------------------------|----------------------------|--------------------------------------|
| Agregado `Pedido`         | Reglas e invariantes                             | `domain.model`             | Unitario puro (bloque 2)             |
| Servicio de dominio       | Reglas que no caben en una entidad               | `domain.service`           | Unitario puro                        |
| Puerto de entrada         | Contrato del caso de uso                         | `application.port.in`      | A través de su implementación        |
| Servicio de aplicación    | Orquestar: cargar → dominio → guardar → efectos  | `application.service`      | Fakes + Mockito, sin Spring          |
| Puerto de salida          | Lo que el núcleo necesita del exterior           | `application.port.out`     | Test de contrato                     |
| Wiring                    | Crear beans                                      | `infrastructure.config`    | Test de arranque (día 4/5)           |

---

## Ejercicio

Escribe un test con fakes que demuestre que `ConfirmarPedidoService` **no publica ni notifica** cuando
el pedido ya está `CONFIRMADO` (confirmar dos veces).

**Pista:** puedes usar `PedidoMother.confirmado()` del [bloque 2](02-testing-del-modelo-de-dominio.md)
y guardarlo en el repositorio en memoria.

<details><summary>Solución</summary>

```java
@Test
void confirmar_un_pedido_ya_confirmado_no_tiene_efectos() {
    Pedido pedido = PedidoMother.confirmado();
    pedidos.guardar(pedido);
    // el catálogo debe conocer los productos de PedidoMother para pasar la verificación de stock
    catalogo.con(new Producto(new ProductoId("TEC-001"), "Teclado", Dinero.euros("25.00"), 10))
            .con(new Producto(new ProductoId("RAT-002"), "Ratón", Dinero.euros("9.99"), 10));

    assertThatThrownBy(() -> servicio.ejecutar(new ConfirmarPedidoCommand(pedido.id())))
            .isInstanceOf(TransicionDeEstadoInvalidaException.class);

    assertThat(publicador.publicados()).isEmpty();
    assertThat(notificador.confirmacionesEnviadas()).isEmpty();
}
```

Reflexión: el servicio verifica stock **antes** de que el dominio rechace la transición. ¿Tiene
sentido consultar el catálogo para un pedido que no se puede confirmar? Una mejora razonable es
comprobar antes el estado... pero ¿dónde? Pista: un método de consulta en el agregado
(`pedido.puedeConfirmarse()`) mantiene la regla en el dominio.

</details>

---

## Referencias

- Eric Evans, *Domain-Driven Design* (2003), cap. 4 (Layered Architecture) y cap. 5 (Services).
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023) y https://github.com/thombergs/buckpal
- Mockito: https://site.mockito.org/
- JUnit 5 User Guide: https://junit.org/junit5/docs/current/user-guide/
- AssertJ: https://assertj.github.io/doc/
- Martin Fowler, *The Practical Test Pyramid*: https://martinfowler.com/articles/practical-test-pyramid.html
- Chris Richardson, *Transactional Outbox*: https://microservices.io/patterns/data/transactional-outbox.html
- Spring Framework, gestión de transacciones: https://docs.spring.io/spring-framework/reference/data-access/transaction.html

## Mantra

> *"El servicio de aplicación dice QUÉ pasa y en qué orden. El dominio decide SI puede pasar."*
