# Perfiles de arquitectura

El código generado es **el mismo** en los tres perfiles; solo cambian los paquetes (y, en
vertical slice, quién consume los puertos). Elige el perfil una vez y usa su tabla para
sustituir los marcadores de paquete de las plantillas.

## Cómo detectarlo

| Señal en el código | Perfil |
|---|---|
| `domain/`, `application/port/in`, `application/port/out`, `infrastructure/adapter` | **hexagonal** |
| `domain/entities` (o `entity`), `application/usecases` (o `usecase`), `application/gateways` (o `interfaces`), `infrastructure` / `frameworks` | **clean** |
| `features/` (o `slices/`) con una clase/carpeta por caso de uso, y un `domain/` compartido | **vertical slice** |
| `features/` con SQL directo y **sin** clases de dominio | No aplica: avisar y parar |

Si no encaja con ninguna, muestra al usuario la tabla del perfil hexagonal adaptada a lo
que ves y pide confirmación antes de generar.

## Tabla de marcadores de paquete

`{{base}}` es el paquete raíz del proyecto (p. ej. `com.curso.pizza`). `{{agg}}` es el
nombre del agregado en minúsculas (`pizza`).

| Marcador | Hexagonal | Clean | Vertical slice |
|---|---|---|---|
| `{{pkg.domainModel}}` | `{{base}}.domain.model` | `{{base}}.domain.entities` | `{{base}}.domain` |
| `{{pkg.domainException}}` | `{{base}}.domain.exception` | `{{base}}.domain.exceptions` | `{{base}}.domain` |
| `{{pkg.portRepository}}` | `{{base}}.application.port.out.repository` | `{{base}}.application.gateways.repository` | `{{base}}.shared.persistence.port` |
| `{{pkg.portAgg}}` | `{{base}}.application.port.out.{{agg}}` | `{{base}}.application.gateways.{{agg}}` | `{{base}}.shared.persistence.port.{{agg}}` |
| `{{pkg.portLookup}}` | `{{base}}.application.port.out.lookup` | `{{base}}.application.gateways.lookup` | `{{base}}.shared.persistence.port.lookup` |
| `{{pkg.locator}}` | `{{base}}.application.lookup` | `{{base}}.application.lookup` | `{{base}}.shared.lookup` |
| `{{pkg.persistenceCommon}}` | `{{base}}.infrastructure.adapter.out.persistence.common` | `{{base}}.infrastructure.persistence.common` | `{{base}}.shared.persistence.jpa.common` |
| `{{pkg.persistenceAgg}}` | `{{base}}.infrastructure.adapter.out.persistence.{{agg}}` | `{{base}}.infrastructure.persistence.{{agg}}` | `{{base}}.shared.persistence.jpa.{{agg}}` |
| `{{pkg.config}}` | `{{base}}.infrastructure.config` | `{{base}}.infrastructure.config` | `{{base}}.config` |

Respeta los nombres reales si el proyecto ya usa variantes (`usecase` en singular,
`adapters` en plural...): la tabla es el valor por defecto, no una imposición.

## Particularidades

### Hexagonal
Es el perfil de referencia (documentos 01–03). Los casos de uso inyectan los puertos por
agregado y se cablean en `BeanConfiguration` con `TransactionTemplate`.

### Clean Architecture
Los "puertos de salida" se llaman *gateways*. Mismo código, mismo cableado. Las reglas de
ArchUnit cambian solo en los nombres de paquete.

### Vertical slice (con dominio)
- El dominio sigue siendo compartido (`{{base}}.domain`), y la persistencia también:
  todas las *slices* que tocan `Pizza` usan **el mismo** adaptador. Por eso el núcleo y
  los adaptadores van en `shared`.
- Cada *slice* (`features/CrearPizza`) inyecta el puerto mínimo que usa (`IAddPizza`) en
  su `Handler`. ISP encaja de forma natural: cada slice declara exactamente su acceso a
  datos.
- Si el proyecto usa `@Transactional` en los handlers (como `pedidos-vertical-slice`),
  respétalo en lugar de introducir `TransactionTemplate`.
- La regla ArchUnit pasa a ser: `{{base}}.domain..` no depende de Spring ni de JPA, y
  `{{base}}.shared.persistence.port..` tampoco.
