# Día 1 — Módulo 1. Fundamentos

Material de apoyo para la primera jornada del curso **Arquitectura Hexagonal
con Java y Spring Boot** (sesión de **3h45**, descanso incluido).

---

## Presentación del curso

### ¿Por qué estamos aquí?

Casi todos los equipos han vivido lo mismo: un servicio Spring Boot que empezó
limpio, con tres capas y buena intención, y que dos años después es un sitio en
el que nadie quiere tocar nada. Cambiar de proveedor de pagos cuesta un
trimestre. Subir la versión de Hibernate rompe reglas de negocio. Los tests
tardan ocho minutos porque todos levantan el contexto completo y una base de
datos.

Este curso va de **evitar eso de forma sistemática**, no a base de heroicidades.

### Objetivos del curso

1. **Estandarizar el diseño desacoplado** con arquitectura hexagonal (Ports &
   Adapters) para que cualquier servicio del equipo tenga la misma forma, sea
   fácil de mantener y pueda **evolucionar** sin reescribirse.
2. **Mitigar la deuda técnica y el riesgo operativo** creando **perímetros de
   aislamiento** que protejan la lógica transaccional (crear, confirmar, pagar
   un pedido) de los cambios, caídas e inconsistencias de los sistemas externos:
   bases de datos, pasarelas de pago, colas de mensajería, APIs de terceros.
3. Saber **probar** cada pieza al nivel adecuado: dominio sin Spring, casos de
   uso con dobles en memoria, adaptadores con Testcontainers/WireMock, y reglas
   de arquitectura verificadas con ArchUnit.
4. Tener **criterio**: cuándo compensa el hexágono, cuándo es sobreingeniería, y
   cómo se compara con Clean Architecture y Vertical Slice.

En cristiano: que la lógica de negocio sea **lo más estable** del sistema y que
la tecnología sea **un detalle enchufable**.

### Mapa de las 8 sesiones

| Día | Módulo                                      | Contenido principal                                                         |
|-----|---------------------------------------------|-----------------------------------------------------------------------------|
| 1   | Módulo 1. Fundamentos                       | Historia de los patrones, principios de diseño, introducción al hexágono    |
| 2   | Módulo 2. Anatomía del hexágono             | Componentes del dominio, definición de puertos, clasificación de puertos    |
| 3   | Módulo 3 (I). Adaptadores de entrada        | REST, mensajería, CLI/scheduler, validación y mapeo                         |
| 4   | Módulo 3 (II). Adaptadores de salida        | Persistencia, clientes HTTP, notificaciones, gestión de dependencias        |
| 5   | Módulo 4. Testing                           | Pirámide de tests, dobles en memoria, Testcontainers, WireMock, ArchUnit    |
| 6   | Módulo 5. Casos reales y patrones avanzados | Persistencia, patrones de acceso a datos, transacciones, eventos            |
| 7   | Comparativa de arquitecturas                | Hexagonal vs Clean vs Vertical Slice; arquitecturas modernas                |
| 8   | Proyecto final                              | "Del monolito al hexágono": migración, evaluación del sistema y cierre      |

### El dominio del curso: Pedidos

Todo el curso usa **el mismo dominio**: una tienda online que gestiona
`Pedido`s. Así vemos crecer el diseño día a día sin cambiar de contexto.

```
Pedido (agregado raíz)
  ├── PedidoId, ClienteId, Instant creadoEn
  ├── EstadoPedido: BORRADOR → CONFIRMADO → PAGADO → ENVIADO
  │                      └──────────┴────────► CANCELADO
  └── List<LineaPedido> (ProductoId, Cantidad, Dinero precioUnitario)
```

Stack: **Java 17** (21 recomendado en proyectos nuevos), **Spring Boot 3.3**,
Maven, JUnit 5, AssertJ, Mockito. Paquete raíz: `com.curso.pedidos`.

---

## Objetivos del día

Al terminar la sesión, el alumno será capaz de:

- Situar la arquitectura hexagonal en la **historia** de los patrones y de las
  arquitecturas de software (de Alexander a los microservicios).
- Explicar y aplicar los **principios** que la sostienen: acoplamiento,
  cohesión, SOLID (con foco en **DIP**), Demeter, composición, programar contra
  interfaces.
- Identificar los **dolores** de la arquitectura en capas clásica de Spring
  (Controller → Service → Repository).
- Describir el patrón **Ports & Adapters**: dentro/fuera, regla de dependencia,
  lado driving y lado driven.
- Implementar un primer caso de uso (`CrearPedido`) **de punta a punta** en
  hexagonal con Spring Boot y probarlo **sin Mockito y sin base de datos**.

---

## Estructura del día

| # | Bloque                                   | Duración orientativa | Fichero                                         |
|---|------------------------------------------|----------------------|-------------------------------------------------|
| 0 | Presentación del curso                   | ~10 min              | `00-README.md`                                  |
| 1 | Contexto histórico de los patrones       | ~30 min              | `01-contexto-historico-patrones.md`             |
| 2 | Principios de diseño clave               | ~45 min              | `02-principios-de-diseno-clave.md`              |
| — | Descanso                                 | ~15 min              | —                                               |
| 3 | El problema de la arquitectura en capas  | ~30 min              | `03-el-problema-de-la-arquitectura-en-capas.md` |
| 4 | Introducción a la arquitectura hexagonal | ~35 min              | `04-introduccion-arquitectura-hexagonal.md`     |
| 5 | Primer ejemplo completo                  | ~35 min              | `05-primer-ejemplo-completo.md`                 |
| 6 | Ejercicios y repaso                      | ~25 min              | `06-ejercicios-dia-01.md`                       |

Total: **~3h45** (225 min) con descanso incluido.

## Decisión de ritmo

Los markdowns están escritos con **material extendido** (variantes, trampas,
tests, ejercicios). En clase se va más rápido: lo extenso es para que os lo
llevéis, no para leerlo en voz alta. Los bloques 1 y 2 son conceptuales y se
pueden acelerar si el grupo ya domina SOLID; los bloques 4 y 5 son el corazón
del día y **no** se recortan.

---

## Cómo enfocar el bloque

Hoy no venimos a aprender anotaciones nuevas de Spring. Venimos a cambiar **la
dirección de las flechas**.

El hilo conductor del día es una sola pregunta:

> **¿De quién depende tu lógica de negocio?**

1. **Historia** (bloque 1): nadie inventó el hexágono de la nada. Es la
   destilación de 30 años de patrones, capas y errores.
2. **Principios** (bloque 2): el hexágono es DIP aplicado a escala de
   arquitectura. Si entendemos DIP, entendemos el 80 % del patrón.
3. **Dolor** (bloque 3): vemos un `PedidoService` real, acoplado a JPA, Jackson
   y `RestTemplate`, e intentamos probarlo. Duele.
4. **Solución** (bloque 4): Cockburn, puertos, adaptadores, regla de
   dependencia. Primer refactor.
5. **Práctica** (bloque 5): `CrearPedido` completo con Spring Boot, con tests
   que corren en milisegundos.

---

## Conexión con los días siguientes

- **Día 2** abre el hexágono por dentro: entidades, value objects, agregados,
  servicios de dominio y la definición y clasificación rigurosa de puertos.
  Lo que hoy llamamos "puerto de entrada/salida" mañana tendrá sus tres nombres
  (primario/driving, secundario/driven).
- **Días 3 y 4** sustituyen el adaptador en memoria de hoy por adaptadores
  reales: REST completo, JPA, cliente HTTP de pagos, mensajería.
- **Día 5** formaliza la estrategia de testing que hoy solo esbozamos.

## Punto de partida (código)

Hoy partimos de **cero**: un proyecto Spring Boot 3.3 vacío con
`spring-boot-starter-web` y `spring-boot-starter-test`. Al final del día
tendremos esta estructura (ver [05-primer-ejemplo-completo.md](05-primer-ejemplo-completo.md)):

```
com.curso.pedidos
├── domain
│   ├── model        (Pedido, LineaPedido, EstadoPedido, PedidoId, ClienteId, ...)
│   └── exception    (PedidoException, TransicionDeEstadoInvalidaException)
├── application
│   ├── port
│   │   ├── in       (CrearPedidoUseCase, CrearPedidoCommand)
│   │   └── out      (PedidoRepository)
│   └── service      (CrearPedidoService)
└── infrastructure
    ├── adapter
    │   ├── in/web   (PedidoController, DTOs)
    │   └── out/persistence (InMemoryPedidoRepositoryAdapter)
    └── config       (BeanConfiguration)
```

---

## Mantra del día

> *"La lógica de negocio no depende de nada. Todo lo demás depende de ella."*
