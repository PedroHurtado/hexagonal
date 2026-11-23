# Día 3 — Bloque 1: Qué es un adaptador de entrada

> *"Convert the interface of a class into another interface clients expect. Adapter lets classes work together that couldn't otherwise because of incompatible interfaces."*
> — Gamma, Helm, Johnson, Vlissides, *Design Patterns* (1994)

---

## 1. El problema en una frase

El dominio habla **lenguaje de negocio** (`confirmar(PedidoId)`), el mundo exterior habla **protocolos** (HTTP + JSON, mensajes Kafka, ficheros CSV, cron) y alguien tiene que traducir entre ambos **sin contaminar a ninguno de los dos**.

---

## 2. Definición

Un **adaptador de entrada** (también *primario* o *driving adapter*) es el componente que:

1. **Recibe** un estímulo en un protocolo concreto (petición HTTP, mensaje, tick de reloj, argumentos de consola…).
2. **Traduce** ese estímulo a la forma que entiende el hexágono: un **comando** o una **consulta** del puerto de entrada.
3. **Invoca** el puerto de entrada (`XxxUseCase` / `XxxQuery`).
4. **Traduce** el resultado (o la excepción) de vuelta al protocolo: un `201 Created`, un `ack` del mensaje, un código de salida del proceso…

Cockburn lo describe así en el artículo original: por cada puerto hay uno o varios adaptadores que **convierten la señal de un dispositivo externo en algo utilizable por la aplicación**. La aplicación no sabe quién la conduce: un usuario con navegador, un test automatizado o un script batch son intercambiables.

En cristiano: **el adaptador de entrada es un traductor con malas pulgas**. Traduce, delega y se calla. No opina sobre el negocio.

### Los tres nombres (recordatorio del día 2)

| Nombre        | Origen                      | Qué subraya                                   |
|---------------|-----------------------------|-----------------------------------------------|
| Primario      | Cockburn (primary actor)    | Es el actor que **inicia** la interacción     |
| De entrada    | Uso habitual en castellano  | La dirección del flujo de control             |
| Driving       | Cockburn / Hombergs         | **Conduce** la aplicación (frente a *driven*) |

A partir de aquí: **adaptador de entrada**.

---

## 3. De dónde viene: el patrón Adapter del GoF

El nombre no es casual. El patrón Adapter del GoF resuelve "dos interfaces que no encajan". En el hexágono:

- La interfaz que **el cliente espera** es el protocolo (HTTP, Kafka…).
- La interfaz que **existe** es el puerto de entrada (`ConfirmarPedidoUseCase`).
- El adaptador **compone** (contiene) el puerto y traduce las llamadas: es un *object adapter*.

```
   GoF Adapter (object adapter)              Adaptador de entrada hexagonal
 ┌────────┐     ┌─────────┐               ┌──────────┐     ┌──────────────────┐
 │ Client │────►│ Target  │               │ Cliente  │────►│ Protocolo HTTP   │
 └────────┘     └─────────┘               │ HTTP     │     │ POST /pedidos/.. │
                     ▲                    └──────────┘     └──────────────────┘
                     │                                              ▲
               ┌─────────┐   ┌─────────┐                  ┌──────────────────┐  ┌──────────────────────┐
               │ Adapter │──►│ Adaptee │                  │ PedidoController │─►│ConfirmarPedidoUseCase│
               └─────────┘   └─────────┘                  └──────────────────┘  └──────────────────────┘
                 traduce      lo que hay                     traduce               puerto de entrada
```

Diferencia importante con el GoF "de manual": aquí **el adaptado es nuestro** (el puerto lo diseñamos nosotros) y lo que no controlamos es el protocolo. Por eso el puerto debe estar en **lenguaje de dominio** y nunca "acomodarse" a HTTP.

---

## 4. Flujo de datos completo: de la petición a la respuesta

Sigamos una petición real: confirmar un pedido.

```
POST /api/pedidos/3f2a.../confirmacion
```

```
 ┌───────────────┐  HTTP + JSON
 │ Cliente HTTP  │─────────────────────┐
 └───────────────┘                     │
        ▲                              ▼
        │            ┌────────────────────────────────────────────────────┐
        │            │  ADAPTADOR DE ENTRADA  (infrastructure.adapter.in.web)
        │            │                                                    │
        │            │  1. Spring MVC deserializa / valida (Bean Validation)
        │            │  2. String "3f2a..."  ──►  PedidoId(UUID)          │
        │            │  3. new ConfirmarPedidoCommand(pedidoId)           │
        │            └───────────────────────┬────────────────────────────┘
        │                                    │ ejecutar(command)
        │                                    ▼
        │            ┌────────────────────────────────────────────────────┐
        │            │  PUERTO DE ENTRADA   ConfirmarPedidoUseCase        │
        │            │  (application.port.in)                             │
        │            └───────────────────────┬────────────────────────────┘
        │                                    │ implementado por
        │                                    ▼
        │            ┌────────────────────────────────────────────────────┐
        │            │  SERVICIO DE APLICACIÓN  ConfirmarPedidoService    │
        │            │  - pedidoRepository.buscarPorId(id)  (puerto out)  │
        │            │  - pedido.confirmar()          ◄── DOMINIO         │
        │            │  - pedidoRepository.guardar(pedido)                │
        │            │  - publicador.publicar(PedidoConfirmado)           │
        │            └───────────────────────┬────────────────────────────┘
        │                                    │ void  /  excepción de dominio
        │                                    ▼
        │            ┌────────────────────────────────────────────────────┐
        │            │  ADAPTADOR DE ENTRADA (vuelta)                     │
        │            │  - OK  ──► 204 No Content                          │
        │            │  - TransicionDeEstadoInvalidaException ──► 409     │
        │            │  - PedidoNoEncontradoException        ──► 404      │
        │            └───────────────────────┬────────────────────────────┘
        └────────────────────────────────────┘  HTTP status + ProblemDetail
```

Fíjate en **dónde vive cada traducción**:

| Paso                       | Quién lo hace                    | Capa            |
|----------------------------|----------------------------------|-----------------|
| JSON → objeto Java         | Jackson (vía Spring MVC)         | Adaptador       |
| Formato válido (no nulo…)  | Bean Validation                  | Adaptador       |
| `String` → `PedidoId`      | Adaptador / mapper               | Adaptador       |
| Orquestar el caso de uso   | Servicio de aplicación           | Aplicación      |
| "¿Se puede confirmar?"     | `Pedido.confirmar()`             | Dominio         |
| Excepción → código HTTP    | `@RestControllerAdvice`          | Adaptador       |
| Objeto → JSON              | Jackson                          | Adaptador       |

La regla de dependencias se mantiene: **las flechas de código fuente apuntan hacia dentro**. El controlador conoce el puerto; el puerto no conoce el controlador.

---

## 5. Bad: el controlador que lo sabe todo

```java
@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    @Autowired private PedidoJpaRepository jpaRepository;   // ¡salta el hexágono!
    @Autowired private JavaMailSender mailSender;

    @PostMapping("/{id}/confirmacion")
    public ResponseEntity<?> confirmar(@PathVariable String id) {
        PedidoJpaEntity entity = jpaRepository.findById(UUID.fromString(id)).orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        if (entity.getLineas().isEmpty()) {                          // regla de negocio
            return ResponseEntity.badRequest().body("Pedido vacío");
        }
        if (!"BORRADOR".equals(entity.getEstado())) {                // regla de negocio
            return ResponseEntity.status(409).body("Estado inválido");
        }
        entity.setEstado("CONFIRMADO");
        jpaRepository.save(entity);
        mailSender.send(crearEmail(entity));                         // efecto secundario
        return ResponseEntity.ok(entity);                            // expone la entidad JPA
    }
}
```

### ¿Qué duele?

- **Reglas de negocio en el borde**: "no se confirma un pedido vacío" vive en el controlador. Mañana llega el listener de Kafka y la regla se **duplica** (o, peor, se olvida).
- **Salta el hexágono**: habla con JPA y con el correo directamente. No hay puerto, no hay caso de uso, no hay dominio.
- **Expone la entidad JPA** como JSON: cualquier cambio en la tabla rompe el contrato público.
- **Imposible de testear sin base de datos y SMTP**.
- `setEstado("CONFIRMADO")`: el modelo anémico del que nos avisa Fowler (*AnemicDomainModel*).

---

## 6. Good: el adaptador que solo traduce

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.domain.model.PedidoId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/pedidos")
class PedidoController {

    private final ConfirmarPedidoUseCase confirmarPedido;

    PedidoController(ConfirmarPedidoUseCase confirmarPedido) {   // depende del PUERTO
        this.confirmarPedido = confirmarPedido;
    }

    @PostMapping("/{id}/confirmacion")
    ResponseEntity<Void> confirmar(@PathVariable UUID id) {
        confirmarPedido.ejecutar(new ConfirmarPedidoCommand(new PedidoId(id)));  // traduce y delega
        return ResponseEntity.noContent().build();                               // traduce la salida
    }
}
```

Tres líneas de trabajo real. Las reglas ("no confirmar vacío", "solo desde BORRADOR") están en `Pedido.confirmar()`. Los errores los traduce un `@RestControllerAdvice` (bloque 4). La persistencia y el correo son puertos de salida que usa el servicio (día 4).

---

## 7. Reglas del adaptador de entrada

| #  | Regla                                                                                   | Por qué                                                    |
|----|-----------------------------------------------------------------------------------------|------------------------------------------------------------|
| 1  | **Depende de puertos de entrada**, nunca de servicios concretos ni de puertos de salida | Inversión de dependencias; sustituible en tests            |
| 2  | **Sin lógica de negocio**: ni `if` sobre estados ni cálculos de importes                | Una regla, un sitio: el dominio                            |
| 3  | Valida **forma**, no **significado** (Bean Validation sí; invariantes no)               | El formato depende del protocolo; la invariante, no        |
| 4  | **Nunca expone el dominio** hacia fuera: usa DTOs                                       | Contrato público estable e independiente del modelo        |
| 5  | Traduce **excepciones de dominio** a semántica del protocolo                            | El dominio no sabe qué es un 409                           |
| 6  | Un adaptador = **un protocolo**                                                         | SRP: cambia por razones de protocolo, no de negocio        |
| 7  | **No gestiona transacciones de negocio**                                                | La frontera transaccional es el caso de uso (día 6)        |
| 8  | Es **fino**: si crece, falta algo en el puerto                                          | Síntoma de diseño, no un problema del controlador          |

### Trampa: "es solo un `if` pequeñito"

```java
if (request.cantidad() > 100) { throw new ResponseStatusException(BAD_REQUEST); }
```

¿Es formato o negocio? Pregunta clave: **¿esta regla se aplicaría igual si el pedido llegara por Kafka o por CLI?** Si la respuesta es sí, es negocio y va al dominio (`Cantidad` o `Pedido`). Si solo tiene sentido en HTTP (p. ej. límite de tamaño de página), es del adaptador.

### Trampa: el adaptador que llama a dos casos de uso

```java
PedidoId id = crearPedido.ejecutar(...);
for (var linea : request.lineas()) anadirLinea.ejecutar(...);
confirmarPedido.ejecutar(...);
```

Orquestar varios casos de uso en el controlador es **lógica de aplicación escondida**: sin transacción común, si falla la tercera línea queda un pedido a medias. Si el negocio necesita "crear y confirmar de golpe", eso es **un caso de uso nuevo** (`RealizarPedidoUseCase`).

> Matiz pragmático: en el bloque 5 el listener de Kafka encadena "crear + añadir líneas" porque así está modelado el ejercicio. Lo señalamos allí como deuda consciente.

### Trampa: inyectar el servicio en vez del puerto

```java
private final ConfirmarPedidoService service;   // MAL: clase concreta
private final ConfirmarPedidoUseCase useCase;   // BIEN: interfaz del puerto
```

Con la clase concreta el test del controlador necesita construir el servicio y todas sus dependencias; con el puerto, un mock de una línea.

---

## 8. Catálogo de adaptadores de entrada habituales

| Adaptador                | Protocolo / disparador   | Tecnología Spring                         | Fichero de hoy |
|--------------------------|--------------------------|-------------------------------------------|----------------|
| REST                     | HTTP + JSON              | Spring MVC `@RestController`              | 02             |
| GraphQL                  | HTTP + GraphQL           | Spring for GraphQL `@Controller`          | 06 (ejercicio) |
| Mensajería               | Kafka / AMQP             | `@KafkaListener`, `@RabbitListener`       | 05             |
| Planificado              | Reloj                    | `@Scheduled`                              | 05             |
| Línea de comandos        | Argumentos de proceso    | `CommandLineRunner` / `ApplicationRunner` | 05             |
| Batch de ficheros        | CSV / XML                | Spring Batch o lectura manual             | 06 (ejercicio) |
| Tests                    | Llamada Java directa     | JUnit                                     | Siempre        |

El último no es broma: **un test que llama al puerto de entrada es un adaptador más**. Cockburn lo dice explícitamente: la aplicación debe poder ser conducida igual por usuarios, programas, tests o scripts batch.

---

## 9. Testing: ¿qué se prueba en un adaptador de entrada?

Un adaptador se prueba **aislado del hexágono**: el puerto se sustituye por un doble. Lo que verificamos es **la traducción**, en ambos sentidos:

1. Dada una entrada de protocolo, ¿se invoca el puerto con el **comando correcto**?
2. Dada una respuesta del puerto, ¿se produce la **salida de protocolo correcta**?
3. Dada una excepción de dominio, ¿se produce el **error de protocolo correcto**?

Lo que **no** se prueba aquí: reglas de negocio (eso es del test de dominio del día 2).

Ejemplo mínimo, sin Spring, para fijar la idea:

```java
package com.curso.pedidos.infrastructure.adapter.in.web;

import com.curso.pedidos.application.port.in.ConfirmarPedidoCommand;
import com.curso.pedidos.application.port.in.ConfirmarPedidoUseCase;
import com.curso.pedidos.domain.model.PedidoId;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PedidoControllerUnitTest {

    private final ConfirmarPedidoUseCase confirmarPedido = mock(ConfirmarPedidoUseCase.class);
    private final PedidoController controller = new PedidoController(confirmarPedido);

    @Test
    void confirmar_traduceElUuidAComandoYDevuelve204() {
        UUID id = UUID.randomUUID();

        var respuesta = controller.confirmar(id);

        verify(confirmarPedido).ejecutar(new ConfirmarPedidoCommand(new PedidoId(id)));
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
```

Este test es rapidísimo, pero **no prueba** el mapeo de URL, la deserialización JSON ni la validación. Para eso está el *slice test* `@WebMvcTest` del bloque 2. Ambos tienen su sitio:

| Test                     | Qué cubre                                   | Velocidad   | Cuándo                       |
|--------------------------|---------------------------------------------|-------------|------------------------------|
| Unitario del controlador | Traducción comando/respuesta                | ms          | Lógica de mapeo no trivial   |
| `@WebMvcTest` + MockMvc  | URL, JSON, validación, advice, status       | ~1 s        | **El estándar** para REST    |
| `@SpringBootTest` E2E    | Todo cableado, con BD real                  | segundos    | Pocos, caminos críticos (d5) |

---

## 10. Resumen

| Concepto                  | Idea clave                                                           |
|---------------------------|----------------------------------------------------------------------|
| Adaptador de entrada      | Traduce protocolo ↔ puerto de entrada                                |
| Origen                    | Patrón Adapter (GoF), variante *object adapter*                      |
| Depende de                | El puerto de entrada (interfaz), nunca del servicio concreto         |
| Responsabilidad           | Deserializar, validar forma, mapear, invocar, mapear respuesta/error |
| No es responsable de      | Reglas de negocio, transacciones, persistencia                       |
| Señal de alarma           | Controlador gordo ⇒ puerto mal diseñado o lógica fuera de sitio      |
| Test                      | Puerto mockeado, se verifica la traducción                           |

---

## Ejercicio

Tienes este método en un controlador heredado. Clasifica cada línea como **(A) adaptador**, **(D) dominio** o **(S) servicio de aplicación** y reescríbelo.

```java
@PostMapping("/{id}/lineas")
public PedidoJpaEntity anadir(@PathVariable String id, @RequestBody Map<String, Object> body) {
    UUID uuid = UUID.fromString(id);                                       // 1
    PedidoJpaEntity p = repo.findById(uuid).orElseThrow();                 // 2
    if (p.getEstado().equals("CONFIRMADO")) throw new RuntimeException();  // 3
    int cantidad = (Integer) body.get("cantidad");                         // 4
    if (cantidad <= 0) throw new RuntimeException();                       // 5
    BigDecimal precio = catalogoRepo.precioDe((String) body.get("sku"));   // 6
    p.getLineas().add(new LineaJpa((String) body.get("sku"), cantidad, precio)); // 7
    repo.save(p);                                                          // 8
    return p;                                                              // 9
}
```

**Pista**: piensa qué pasaría si mañana esa misma operación llega por Kafka.

<details>
<summary>Solución</summary>

| Línea | Capa | Comentario                                                                 |
|-------|------|----------------------------------------------------------------------------|
| 1     | A    | Conversión de protocolo (`@PathVariable UUID` lo hace gratis)              |
| 2     | S    | Cargar el agregado vía `PedidoRepository` (puerto de salida)               |
| 3     | D    | Invariante: "no se añaden líneas a un pedido confirmado" → `Pedido`        |
| 4     | A    | Extraer dato del JSON → DTO `AnadirLineaRequest`                           |
| 5     | D/A  | `Cantidad > 0` es invariante del VO `Cantidad`; además `@Positive` en DTO  |
| 6     | S    | Consultar precio vía `CatalogoProductos` (puerto de salida)                |
| 7     | D    | `pedido.anadirLinea(productoId, cantidad, precio)`                         |
| 8     | S    | `pedidoRepository.guardar(pedido)`                                         |
| 9     | A    | Nunca devolver entidad: `201`/`204` o `PedidoResponse`                     |

```java
@PostMapping("/{id}/lineas")
ResponseEntity<Void> anadirLinea(@PathVariable UUID id,
                                 @Valid @RequestBody AnadirLineaRequest request) {
    anadirLinea.ejecutar(new AnadirLineaPedidoCommand(
            new PedidoId(id), new ProductoId(request.sku()), new Cantidad(request.cantidad())));
    return ResponseEntity.noContent().build();
}
```

Todo lo marcado como S y D desaparece del controlador y vive en `AnadirLineaPedidoService` + `Pedido` (ya escritos en el día 2).
</details>

---

## Referencias

- Alistair Cockburn, *Hexagonal Architecture* (2005): https://alistair.cockburn.us/hexagonal-architecture/
- Cockburn & Garrido de Paz, *Hexagonal Architecture Explained* (2024).
- Gamma et al., *Design Patterns: Elements of Reusable Object-Oriented Software* (1994) — capítulo Adapter.
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2ª ed., 2023), capítulo "Implementing a Web Adapter"; repo: https://github.com/thombergs/buckpal
- Martin Fowler, *AnemicDomainModel*: https://martinfowler.com/bliki/AnemicDomainModel.html
- Robert C. Martin, *The Clean Architecture*: https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- Netflix Tech Blog, *Ready for changes with Hexagonal Architecture*: https://netflixtechblog.com/ready-for-changes-with-hexagonal-architecture-b315ec967749

---

## Mantra

> *"Un adaptador de entrada traduce, delega y se calla. Si decide algo de negocio, ya no es un adaptador: es un dominio clandestino."*
