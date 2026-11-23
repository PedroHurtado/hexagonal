# Día 2 — Módulo 2. Anatomía del hexágono

Material de apoyo para la segunda sesión del curso (**3h45**, descanso incluido).

> *"Allow an application to equally be driven by users, programs, automated test
> or batch scripts, and to be developed and tested in isolation from its eventual
> run-time devices and databases."*
> — Alistair Cockburn, *Hexagonal Architecture* (2005)

---

## Objetivos del día

Al terminar la sesión el alumno será capaz de:

1. Distinguir **entidades**, **value objects**, **agregados**, **servicios de dominio**,
   **eventos** y **excepciones de dominio**, y modelarlos en Java 17 sin Spring, JPA ni Lombok.
2. Escribir un **modelo rico** (`Pedido` protege sus invariantes) en lugar de un modelo anémico.
3. Probar el dominio con **tests unitarios puros** (JUnit 5 + AssertJ) que corren en milisegundos.
4. Definir **puertos** como contratos del hexágono: interfaces Java, comandos y resultados como `record`.
5. Clasificar los puertos en **de entrada (primarios, driving)** y **de salida (secundarios, driven)**,
   y saber quién los define y quién los implementa.
6. Implementar un **servicio de aplicación** (`ConfirmarPedidoService`) que orquesta sin contener
   lógica de negocio, y probarlo con **fakes** y con **Mockito**.

---

## Estructura del día

| # | Bloque                                      | Duración orientativa | Fichero                                                                     |
|---|---------------------------------------------|----------------------|-----------------------------------------------------------------------------|
| 0 | Repaso del día 1 y mapa del día             | ~10 min              | este fichero                                                                |
| 1 | Componentes internos del dominio            | ~50 min              | [`01-componentes-internos-del-dominio.md`](01-componentes-internos-del-dominio.md) |
| 2 | Testing del modelo de dominio               | ~30 min              | [`02-testing-del-modelo-de-dominio.md`](02-testing-del-modelo-de-dominio.md)       |
| — | Descanso                                    | ~15 min              | —                                                                           |
| 3 | Definición de puertos                       | ~30 min              | [`03-definicion-de-puertos.md`](03-definicion-de-puertos.md)                       |
| 4 | Clasificación de puertos                    | ~30 min              | [`04-clasificacion-de-puertos.md`](04-clasificacion-de-puertos.md)                 |
| 5 | Servicios de aplicación (casos de uso)      | ~35 min              | [`05-servicios-de-aplicacion.md`](05-servicios-de-aplicacion.md)                   |
| 6 | Ejercicios y preguntas de repaso            | ~25 min              | [`06-ejercicios-dia-02.md`](06-ejercicios-dia-02.md)                               |

Total: **~3h45** (≈3h30 de contenido + 15 min de descanso).

## Decisión de ritmo

Los markdowns están escritos con **material extendido** (variantes, trampas, tests completos,
tablas). En clase se da **más rápido**: lo extenso es para que os lo llevéis, no para leerlo en voz
alta. Los bloques 1 y 2 van muy pegados: se escribe una regla del agregado y se prueba al momento.

---

## Cómo enfocar el bloque

Ayer vimos el hexágono **desde fuera**: por qué existe, de dónde viene y un primer `CrearPedido`
de punta a punta (controlador → caso de uso → repositorio). Funcionaba, pero pasamos de puntillas
por casi todo lo que hay *dentro*.

Hoy abrimos el hexágono y lo desmontamos pieza a pieza, **de dentro hacia fuera**:

```
            ┌──────────────────────────────────────────────┐
            │                APLICACIÓN                    │
  puertos   │   ┌──────────────────────────────────────┐   │   puertos
  de        │   │              DOMINIO                 │   │   de
  entrada ─►│   │  Pedido · LineaPedido · Dinero ...   │   │◄─ salida
            │   │  (bloques 1 y 2)                     │   │
            │   └──────────────────────────────────────┘   │
            │   Servicios de aplicación (bloque 5)         │
            └──────────────────────────────────────────────┘
                 ▲ Puertos: definición (3) y clasificación (4)
```

1. **Dominio** (bloques 1–2): el corazón. Sin frameworks. Se prueba con `new` y `assertThat`.
2. **Puertos** (bloques 3–4): los "enchufes" del hexágono. Interfaces Java que habla el negocio.
3. **Servicios de aplicación** (bloque 5): el director de orquesta que conecta puertos y dominio.

Los **adaptadores** (lo que se enchufa a los puertos: controladores REST, JPA, clientes HTTP)
quedan **fuera de hoy a propósito**. Son los días 3 y 4.

## Decisión de diseño del día

**Hoy no arrancamos Spring ni una sola vez.** Todo el código de hoy vive en `domain` y
`application`, y ninguno de esos paquetes importa `org.springframework`, `jakarta.persistence`
ni `com.fasterxml.jackson`. Si al final del día tenéis un proyecto que compila y pasa los tests
**sin contexto de Spring**, lo hemos hecho bien. Esa es la prueba del algodón del hexágono.

---

## Punto de partida (código)

Recuperamos lo que dejamos ayer. El `CrearPedido` del día 1 tenía esta forma (simplificada):

```java
// application/port/in
public interface CrearPedidoUseCase {
    PedidoId ejecutar(CrearPedidoCommand comando);
}

// application/service
public class CrearPedidoService implements CrearPedidoUseCase {
    private final PedidoRepository pedidoRepository;

    public CrearPedidoService(PedidoRepository pedidoRepository) {
        this.pedidoRepository = pedidoRepository;
    }

    @Override
    public PedidoId ejecutar(CrearPedidoCommand comando) {
        Pedido pedido = Pedido.crear(comando.clienteId());
        // ... añadir líneas ...
        pedidoRepository.guardar(pedido);
        return pedido.id();
    }
}
```

Y un `Pedido` que, siendo sinceros, todavía era poco más que una bolsa de datos. Hoy lo
convertimos en un **agregado de verdad**.

La estructura de paquetes que usamos (la misma durante todo el curso):

```
com.curso.pedidos
├── domain
│   ├── model        ← hoy: Pedido, LineaPedido, EstadoPedido, Dinero, Cantidad, ...
│   ├── event        ← hoy: PedidoConfirmado, PedidoPagado, PedidoCancelado
│   ├── exception    ← hoy: PedidoException y familia
│   └── service      ← hoy: CalculadoraDePrecios
├── application
│   ├── port
│   │   ├── in       ← hoy: CrearPedidoUseCase, ConfirmarPedidoUseCase, ConsultarPedidoQuery
│   │   └── out      ← hoy: PedidoRepository, CatalogoProductos, PasarelaDePago, ...
│   └── service      ← hoy: ConfirmarPedidoService
└── infrastructure   ← días 3 y 4
```

---

## Conexión con días anteriores y siguientes

- **Día 1** nos dio el *porqué*: historia de los patrones, SOLID (sobre todo **DIP**: el dominio
  no depende de los detalles, los detalles dependen del dominio) y la intuición del hexágono.
  Hoy vemos que los puertos son **DIP aplicado a escala de arquitectura** y que la granularidad
  de los puertos es **ISP**.
- **Día 3** construirá los **adaptadores de entrada** (REST con Spring MVC, listeners, CLI) que
  llaman a los puertos de entrada que definimos hoy.
- **Día 4** construirá los **adaptadores de salida** (JPA, cliente HTTP de pagos, email, Kafka) que
  implementan los puertos de salida de hoy, y cableará todo en `BeanConfiguration`.
- **Día 5** profundiza en testing: los fakes y los *contract tests* que asomamos hoy serán la base.

## Material para llevar

- Todo el código Java de los ficheros 01–05 forma un proyecto coherente: si lo copiáis en
  `src/main/java` y `src/test/java` bajo `com.curso.pedidos`, compila con Java 17 y solo necesita
  `spring-boot-starter-test` (por JUnit 5, AssertJ y Mockito) en `test`.
- Los ejercicios del fichero 06 tienen solución desplegable. Intentadlos antes de abrirla.

## Mantra del día

> *"El dominio no sabe que existe Spring. Los puertos hablan el idioma del negocio. Los servicios
> de aplicación orquestan; las reglas viven en el dominio."*
