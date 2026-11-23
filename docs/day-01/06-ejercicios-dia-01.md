# Día 1 — Bloque 6: Ejercicios y repaso

> *"I hear and I forget. I see and I remember. I do and I understand."*
> — Proverbio atribuido a Confucio

---

## Cómo usar este fichero

Cuatro ejercicios **graduados**: del análisis sin código (★) al diseño con
varios puertos (★★★★). En clase haremos el 1 y el 2 (~20 min); el 3 y el 4 son
para casa o para quien vaya rápido. Al final, preguntas de repaso para
comprobar que el día ha calado.

Todos parten del código de [05-primer-ejemplo-completo.md](05-primer-ejemplo-completo.md).

| # | Ejercicio                                   | Nivel | Tiempo orientativo | Conceptos                         |
|---|---------------------------------------------|-------|--------------------|-----------------------------------|
| 1 | Detecta las violaciones de la regla de dependencia | ★     | 10 min             | Regla de dependencia, DIP         |
| 2 | Cancelar pedido                             | ★★    | 15 min             | Puerto de entrada, dominio rico   |
| 3 | Precios desde el catálogo                   | ★★★   | 30 min             | Nuevo puerto de salida, adaptador |
| 4 | Notificar al confirmar                      | ★★★★  | 40 min             | Varios puertos, dobles a mano     |

---

## Ejercicio 1 (★): detecta las violaciones

Un compañero ha hecho una PR "hexagonal". Revísala: indica **cada línea** que
viola la regla de dependencia o los principios del día y **por qué**.

```java
package com.curso.pedidos.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
public class Pedido {
    @Id private UUID id;
    @JsonIgnore private List<LineaPedido> lineas;
    private String estado;

    public void setEstado(String estado) { this.estado = estado; }
}
```

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.infrastructure.adapter.out.persistence.InMemoryPedidoRepositoryAdapter;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class CancelarPedidoService {

    private final InMemoryPedidoRepositoryAdapter repo = new InMemoryPedidoRepositoryAdapter();

    public void cancelar(UUID id) {
        Pedido p = repo.buscarPorId(new PedidoId(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        p.setEstado("CANCELADO");
        repo.guardar(p);
    }
}
```

*Pista*: hay al menos **siete** problemas.

<details><summary>Solución</summary>

En `Pedido`:

1. `import jakarta.persistence.*` y `@Entity`, `@Id` en `domain`: el dominio
   depende del ORM.
2. `@JsonIgnore` en `domain`: el dominio conoce el formato de serialización
   de la API.
3. `String estado`: debería ser `EstadoPedido` (tipo de dominio, sin estados
   imposibles).
4. `setEstado(...)` público: cualquiera se salta las invariantes (modelo
   anémico). Debe ser `cancelar()` con su regla.

En `CancelarPedidoService`:

5. Depende de `InMemoryPedidoRepositoryAdapter` (**infraestructura**) en vez
   del puerto `PedidoRepository`. La flecha apunta hacia fuera: viola la regla
   de dependencia y DIP.
6. `new InMemoryPedidoRepositoryAdapter()`: instancia el detalle, imposible
   sustituirlo. Debe recibir el puerto por constructor.
7. `ResponseStatusException` y `HttpStatus`: la aplicación conoce **HTTP**.
   Debe lanzar `PedidoNoEncontradoException` y que el adaptador web lo traduzca
   a 404.
8. `@Service` en `application`: aceptable solo como decisión consciente de
   equipo; en el curso cableamos en `BeanConfiguration`.
9. No implementa ningún puerto de entrada (`CancelarPedidoUseCase`): el
   controller dependería de la clase concreta.
10. Recibe `UUID` en vez de `PedidoId`: tipos primitivos cruzando la frontera.

</details>

---

## Ejercicio 2 (★★): caso de uso `CancelarPedido`

Implementa la cancelación de pedidos de punta a punta:

- Puerto de entrada `CancelarPedidoUseCase` con `void ejecutar(PedidoId id)`.
- Servicio `CancelarPedidoService`.
- Endpoint `DELETE /api/pedidos/{id}` → `204 No Content`.
- Reglas (ya en `Pedido.cancelar()`): no se cancela un pedido `ENVIADO` ni uno
  ya `CANCELADO`.
- Tests: dominio (las dos reglas) y caso de uso con el adaptador en memoria.

*Pista*: es idéntico a `ConfirmarPedido` del bloque 5. Si te cuesta más de 15
minutos, algo de lo de hoy no ha encajado: pregunta.

<details><summary>Solución</summary>

```java
package com.curso.pedidos.application.port.in;

import com.curso.pedidos.domain.model.PedidoId;

public interface CancelarPedidoUseCase {
    void ejecutar(PedidoId id);
}
```

```java
package com.curso.pedidos.application.service;

import com.curso.pedidos.application.port.in.CancelarPedidoUseCase;
import com.curso.pedidos.application.port.out.PedidoRepository;
import com.curso.pedidos.domain.exception.PedidoNoEncontradoException;
import com.curso.pedidos.domain.model.Pedido;
import com.curso.pedidos.domain.model.PedidoId;

public class CancelarPedidoService implements CancelarPedidoUseCase {

    private final PedidoRepository pedidos;

    public CancelarPedidoService(PedidoRepository pedidos) {
        this.pedidos = pedidos;
    }

    @Override
    public void ejecutar(PedidoId id) {
        Pedido pedido = pedidos.buscarPorId(id)
                .orElseThrow(() -> new PedidoNoEncontradoException(id));
        pedido.cancelar();
        pedidos.guardar(pedido);
    }
}
```

```java
// PedidoController
@DeleteMapping("/{id}")
public ResponseEntity<Void> cancelar(@PathVariable UUID id) {
    cancelarPedido.ejecutar(new PedidoId(id));
    return ResponseEntity.noContent().build();
}

// BeanConfiguration
@Bean
CancelarPedidoUseCase cancelarPedidoUseCase(PedidoRepository pedidoRepository) {
    return new CancelarPedidoService(pedidoRepository);
}
```

Tests de dominio:

```java
class PedidoCancelacionTest {

    @Test
    void un_borrador_se_puede_cancelar() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());

        pedido.cancelar();

        assertThat(pedido.estado()).isEqualTo(EstadoPedido.CANCELADO);
    }

    @Test
    void no_se_cancela_dos_veces() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.cancelar();

        assertThatThrownBy(pedido::cancelar)
                .isInstanceOf(TransicionDeEstadoInvalidaException.class);
    }
}
```

> El caso "no se cancela un `ENVIADO`" todavía no se puede probar porque no
> tenemos `marcarComoEnviado()`. Es una señal sana: **el test te pide el
> comportamiento que falta**. Lo añadiremos en el día 2.

Test del caso de uso:

```java
class CancelarPedidoServiceTest {

    private final InMemoryPedidoRepositoryAdapter repo = new InMemoryPedidoRepositoryAdapter();
    private final CancelarPedidoService service = new CancelarPedidoService(repo);

    @Test
    void cancela_y_guarda() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        repo.guardar(pedido);

        service.ejecutar(pedido.id());

        assertThat(repo.buscarPorId(pedido.id()).orElseThrow().estado())
                .isEqualTo(EstadoPedido.CANCELADO);
    }

    @Test
    void pedido_inexistente() {
        assertThatThrownBy(() -> service.ejecutar(PedidoId.nuevo()))
                .isInstanceOf(PedidoNoEncontradoException.class);
    }
}
```

</details>

---

## Ejercicio 3 (★★★): precios desde el catálogo

En el bloque 5 dejamos una trampa: **el cliente manda el precio**. Cualquiera
puede comprar un libro a 0,01 €. Arréglalo:

1. Quita `precioUnitario` de `CrearPedidoCommand.Linea` (y del DTO web).
2. Crea el puerto de salida `CatalogoProductos` con un método que, dado un
   `ProductoId`, devuelva su precio actual. Decide qué pasa si el producto no
   existe.
3. Crea un adaptador `InMemoryCatalogoProductosAdapter` con unos cuantos
   productos precargados.
4. Modifica `CrearPedidoService` y `BeanConfiguration`.
5. Test del caso de uso con ambos adaptadores en memoria.

*Pista*: el puerto se nombra desde el **negocio** ("el catálogo"), no desde
la tecnología ("ProductoRestClient"). Y pregúntate: ¿qué excepción de dominio
lanzas si el SKU no existe?

<details><summary>Solución</summary>

Puerto y excepción:

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.ProductoId;
import java.util.Optional;

public interface CatalogoProductos {
    Optional<Dinero> precioDe(ProductoId productoId);
}
```

```java
package com.curso.pedidos.domain.exception;

import com.curso.pedidos.domain.model.ProductoId;

public class ProductoNoEncontradoException extends PedidoException {
    public ProductoNoEncontradoException(ProductoId id) {
        super("No existe el producto " + id.sku());
    }
}
```

Comando sin precio:

```java
public record CrearPedidoCommand(ClienteId clienteId, List<Linea> lineas) {
    // ... mismo constructor compacto
    public record Linea(ProductoId productoId, Cantidad cantidad) { }
}
```

Caso de uso:

```java
public class CrearPedidoService implements CrearPedidoUseCase {

    private final PedidoRepository pedidos;
    private final CatalogoProductos catalogo;
    private final Clock reloj;

    public CrearPedidoService(PedidoRepository pedidos, CatalogoProductos catalogo, Clock reloj) {
        this.pedidos = pedidos;
        this.catalogo = catalogo;
        this.reloj = reloj;
    }

    @Override
    public PedidoId ejecutar(CrearPedidoCommand command) {
        Pedido pedido = Pedido.crear(command.clienteId(), reloj);
        for (CrearPedidoCommand.Linea linea : command.lineas()) {
            Dinero precio = catalogo.precioDe(linea.productoId())
                    .orElseThrow(() -> new ProductoNoEncontradoException(linea.productoId()));
            pedido.anadirLinea(linea.productoId(), linea.cantidad(), precio);
        }
        pedidos.guardar(pedido);
        return pedido.id();
    }
}
```

Adaptador en memoria:

```java
package com.curso.pedidos.infrastructure.adapter.out.catalog;

public class InMemoryCatalogoProductosAdapter implements CatalogoProductos {

    private final Map<ProductoId, Dinero> precios = new ConcurrentHashMap<>();

    public InMemoryCatalogoProductosAdapter conProducto(String sku, String precioEuros) {
        precios.put(new ProductoId(sku), Dinero.euros(precioEuros));
        return this;
    }

    @Override
    public Optional<Dinero> precioDe(ProductoId productoId) {
        return Optional.ofNullable(precios.get(productoId));
    }
}
```

Configuración:

```java
@Bean
CatalogoProductos catalogoProductos() {
    return new InMemoryCatalogoProductosAdapter()
            .conProducto("LIBRO-DDD", "49.90")
            .conProducto("TAZA-HEX", "9.95");
}

@Bean
CrearPedidoUseCase crearPedidoUseCase(PedidoRepository pedidos, CatalogoProductos catalogo, Clock reloj) {
    return new CrearPedidoService(pedidos, catalogo, reloj);
}
```

Test:

```java
class CrearPedidoConCatalogoTest {

    private final InMemoryPedidoRepositoryAdapter repo = new InMemoryPedidoRepositoryAdapter();
    private final InMemoryCatalogoProductosAdapter catalogo =
            new InMemoryCatalogoProductosAdapter().conProducto("LIBRO-DDD", "49.90");
    private final CrearPedidoService service =
            new CrearPedidoService(repo, catalogo, Clock.systemUTC());

    @Test
    void usa_el_precio_del_catalogo() {
        PedidoId id = service.ejecutar(new CrearPedidoCommand(ClienteId.nuevo(), List.of(
                new CrearPedidoCommand.Linea(new ProductoId("LIBRO-DDD"), new Cantidad(2)))));

        assertThat(repo.buscarPorId(id).orElseThrow().total())
                .isEqualTo(Dinero.euros("99.80"));
    }

    @Test
    void falla_si_el_producto_no_existe_y_no_guarda_nada() {
        var command = new CrearPedidoCommand(ClienteId.nuevo(), List.of(
                new CrearPedidoCommand.Linea(new ProductoId("NO-EXISTE"), new Cantidad(1))));

        assertThatThrownBy(() -> service.ejecutar(command))
                .isInstanceOf(ProductoNoEncontradoException.class);
        assertThat(repo.cuantos()).isZero();
    }
}
```

Observa: **`Pedido` no ha cambiado**. Hemos añadido un puerto y un adaptador
y solo hemos tocado el caso de uso que lo necesitaba (OCP a escala de
arquitectura). En el día 4 el adaptador en memoria se sustituirá por uno que
llama por HTTP al servicio de catálogo, y este test seguirá valiendo.

</details>

---

## Ejercicio 4 (★★★★): notificar al confirmar

Cuando un pedido se confirma, hay que avisar al cliente por email. Requisitos:

1. Crea el puerto de salida `NotificadorPedidos`.
2. `ConfirmarPedidoService` (del ejercicio del bloque 5) debe notificar
   **solo si la confirmación tiene éxito**.
3. Escribe un doble **a mano** (sin Mockito) que registre las notificaciones
   enviadas, y prueba:
   - que se notifica al confirmar;
   - que **no** se notifica si el pedido está vacío.
4. Pregunta de diseño: ¿qué pasa si el email falla **después** de guardar el
   pedido? ¿Y si lo enviamos **antes** de guardar? Razona la respuesta (no hace
   falta implementarlo).

*Pista*: el puerto no debería llamarse `EmailSender`. ¿Y si mañana es un SMS o
una notificación push?

<details><summary>Solución</summary>

```java
package com.curso.pedidos.application.port.out;

import com.curso.pedidos.domain.model.ClienteId;
import com.curso.pedidos.domain.model.PedidoId;

public interface NotificadorPedidos {
    void pedidoConfirmado(PedidoId pedidoId, ClienteId clienteId);
}
```

```java
public class ConfirmarPedidoService implements ConfirmarPedidoUseCase {

    private final PedidoRepository pedidos;
    private final NotificadorPedidos notificador;

    public ConfirmarPedidoService(PedidoRepository pedidos, NotificadorPedidos notificador) {
        this.pedidos = pedidos;
        this.notificador = notificador;
    }

    @Override
    public void ejecutar(PedidoId id) {
        Pedido pedido = pedidos.buscarPorId(id)
                .orElseThrow(() -> new PedidoNoEncontradoException(id));
        pedido.confirmar();                                          // si falla, no seguimos
        pedidos.guardar(pedido);
        notificador.pedidoConfirmado(pedido.id(), pedido.clienteId());
    }
}
```

Doble a mano (un *spy* minimalista):

```java
class NotificadorPedidosEspia implements NotificadorPedidos {

    final List<PedidoId> notificados = new ArrayList<>();

    @Override
    public void pedidoConfirmado(PedidoId pedidoId, ClienteId clienteId) {
        notificados.add(pedidoId);
    }
}
```

Tests:

```java
class ConfirmarPedidoConNotificacionTest {

    private final InMemoryPedidoRepositoryAdapter repo = new InMemoryPedidoRepositoryAdapter();
    private final NotificadorPedidosEspia notificador = new NotificadorPedidosEspia();
    private final ConfirmarPedidoService service = new ConfirmarPedidoService(repo, notificador);

    @Test
    void notifica_al_confirmar() {
        Pedido pedido = Pedido.crear(ClienteId.nuevo());
        pedido.anadirLinea(new ProductoId("A"), new Cantidad(1), Dinero.euros("10"));
        repo.guardar(pedido);

        service.ejecutar(pedido.id());

        assertThat(notificador.notificados).containsExactly(pedido.id());
    }

    @Test
    void no_notifica_si_la_confirmacion_falla() {
        Pedido vacio = Pedido.crear(ClienteId.nuevo());
        repo.guardar(vacio);

        assertThatThrownBy(() -> service.ejecutar(vacio.id()))
                .isInstanceOf(TransicionDeEstadoInvalidaException.class);
        assertThat(notificador.notificados).isEmpty();
    }
}
```

Pregunta de diseño:

- **Email después de guardar y falla**: el pedido queda confirmado pero el
  cliente no se entera. Si la excepción se propaga y hay transacción, podríamos
  hacer *rollback* de un pedido válido **por culpa del SMTP**: el sistema
  externo contamina la lógica transaccional. Justo lo que el curso quiere
  evitar.
- **Email antes de guardar**: si luego `guardar` falla, hemos avisado de un
  pedido que **no existe**. Peor.
- **Solución madura** (días 4 y 6): el dominio emite el evento
  `PedidoConfirmado`; se guarda junto al pedido en la misma transacción
  (*Transactional Outbox*) y un proceso aparte lo publica y notifica con
  reintentos. La notificación pasa a ser **eventualmente consistente** y
  queda fuera del perímetro transaccional.

</details>

---

## Preguntas de repaso

Responde en una o dos frases. Las soluciones están al final.

1. ¿Qué aportó Christopher Alexander al software si nunca programó?
2. ¿Cuál es la diferencia entre DIP e inyección de dependencias?
3. ¿Por qué decimos que en la arquitectura en capas clásica "las flechas
   apuntan a la base de datos"?
4. ¿Qué es un modelo anémico y por qué Fowler lo considera un antipatrón?
5. ¿Por qué Cockburn eligió un hexágono? ¿Importa que tenga seis lados?
6. Enuncia la regla de dependencia.
7. En el lado driving, ¿quién implementa el puerto? ¿Y en el lado driven?
8. ¿`PedidoJpaRepository extends JpaRepository<...>` es un puerto de salida?
9. ¿Por qué el `CrearPedidoServiceTest` usa un adaptador en memoria en vez de
   un mock de Mockito?
10. Nombra dos situaciones en las que **no** usarías arquitectura hexagonal.
11. ¿Dónde se traduce una `PedidoNoEncontradoException` a un `404`, y por qué
    ahí?
12. ¿Qué ventaja tiene cablear los casos de uso en `BeanConfiguration` en vez
    de anotarlos con `@Service`? ¿Qué coste tiene?

<details><summary>Soluciones</summary>

1. La idea de **patrón** como solución con nombre a un problema recurrente en
   un contexto, y la de **lenguaje de patrones** que se combinan. GoF la
   trasladó al software en 1994.
2. **DIP** es un principio: los módulos de alto nivel definen y poseen las
   abstracciones de las que dependen. **DI** es una técnica: pasar los
   colaboradores desde fuera (constructor). Puedes hacer DI y violar DIP.
3. Porque el servicio de negocio **importa** el repositorio de persistencia
   (y este, la tecnología de BD): la dependencia de código fuente va de negocio
   a datos, y todo cambio en datos sube.
4. Objetos con nombres de negocio pero solo getters/setters; la lógica vive en
   servicios procedurales. Pierde las ventajas de la OO (encapsular
   invariantes) pagando el coste de mapear objetos.
5. Para romper la metáfora de capas arriba/abajo y tener sitio para dibujar
   varios puertos. El número de lados **no** importa.
6. Las dependencias de código fuente solo apuntan **hacia dentro**: la
   infraestructura depende de la aplicación y esta del dominio, nunca al revés.
7. Driving: lo implementa la **aplicación** (el caso de uso) y el adaptador lo
   usa. Driven: lo **define** la aplicación y lo implementa el **adaptador**.
8. No. Es un detalle interno del adaptador de persistencia. El puerto es
   `PedidoRepository`, en `application.port.out`, en lenguaje de negocio.
9. Para verificar **estado y comportamiento** ("el pedido está guardado y es
   correcto") y no interacciones; el test es más legible y resiste refactors
   internos del servicio.
10. CRUDs sin reglas de negocio, prototipos desechables, scripts o lambdas
    pequeñas, servicios cuyo valor es solo transformar/integrar.
11. En el **adaptador web** (`@RestControllerAdvice`). HTTP es un detalle del
    adaptador; otro adaptador (Kafka, CLI) traducirá la misma excepción de otra
    forma.
12. Ventaja: `application` queda **libre de Spring** (portable, testeable,
    regla verificable con ArchUnit). Coste: un `@Bean` explícito por caso de
    uso y un fichero más que mantener.

</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture* (2005): https://alistair.cockburn.us/hexagonal-architecture/
- Robert C. Martin, *The Clean Architecture* (2012): https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- Martin Fowler, *AnemicDomainModel*: https://martinfowler.com/bliki/AnemicDomainModel.html
- Martin Fowler, *The Practical Test Pyramid*: https://martinfowler.com/articles/practical-test-pyramid.html
- Chris Richardson, *Transactional Outbox*: https://microservices.io/patterns/data/transactional-outbox.html
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023) y https://github.com/thombergs/buckpal
- JUnit 5: https://junit.org/junit5/docs/current/user-guide/ ; AssertJ: https://assertj.github.io/doc/

---

## Mantra

> *"Un puerto nuevo, un adaptador nuevo, y el dominio sin tocar: así se sabe que el diseño aguanta."*
