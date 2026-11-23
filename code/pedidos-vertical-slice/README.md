# pedidos-vertical-slice — el mismo dominio, en Vertical Slice extremo

Versión mínima del dominio "Pedidos" para la comparativa del **día 7**. Cada feature cabe en **un solo fichero `.java`**, que contiene:

- los records `Command`/`Query` y `Response`;
- el `Handler`, con la lógica y las reglas;
- el `Endpoint` (`@RestController`);
- el acceso a datos con `JdbcClient`.

```
src/main/java/com/curso/pedidos/
├── PedidosVerticalSliceApplication.java
└── features/
    ├── CrearPedido.java       POST /api/pedidos
    ├── ConfirmarPedido.java   POST /api/pedidos/{id}/confirmacion
    └── ConsultarPedido.java   GET  /api/pedidos/{id}
```

Java 17, Spring Boot 3.3.5, H2 (`schema.sql` + `data.sql`). Hay un test de integración por feature (`@SpringBootTest` + MockMvc).

```bash
mvn verify            # o ./mvnw verify
mvn spring-boot:run

curl -i -X POST http://localhost:8080/api/pedidos -H "Content-Type: application/json" \
  -d '{"clienteId":"3f1c2d4e-1111-2222-3333-444455556666","lineas":[{"sku":"LIBRO-DDD","cantidad":2}]}'
curl -i -X POST http://localhost:8080/api/pedidos/<ID>/confirmacion
curl -s http://localhost:8080/api/pedidos/<ID>
```

## Comparativa con `pedidos-hexagonal`

| Aspecto                     | Hexagonal (`../pedidos-hexagonal`)                         | Vertical Slice extremo (este proyecto) |
|-----------------------------|------------------------------------------------------------|----------------------------------------|
| Unidad de organización      | Capa técnica/rol (domain, application, infrastructure)     | Feature de negocio (un fichero) |
| Ficheros para "confirmar"   | ~8 (controller, use case, service, agregado, repo port, adapter, mapper, entity) | 1 |
| Reglas de negocio           | En el agregado `Pedido`, reutilizadas por todos los casos de uso | En cada Handler; si dos features comparten una regla, se duplica o se extrae a mano |
| Persistencia                | Puerto + adaptador JPA + mapper; se puede cambiar de tecnología | SQL directo en el Handler; cambiar de tecnología toca todas las features |
| Testing                     | Dominio en unitario puro, fakes, slices, ArchUnit           | Sobre todo tests de integración por feature |
| Acoplamiento a Spring       | Solo en infraestructura                                     | En todas partes (y es una decisión consciente) |
| Coste de una feature nueva  | Mayor (varias piezas)                                       | Mínimo (un fichero) |
| Dónde brilla                | Dominio rico, larga vida, varias entradas/salidas           | CRUD/consultas, equipos que entregan por feature, lógica sencilla |

En cristiano: el slice extremo optimiza para **cambiar una feature sin tocar nada más**. El hexágono optimiza para **proteger el dominio de los cambios tecnológicos**. No se excluyen: es habitual organizar en slices por fuera y aplicar puertos y adaptadores dentro de los slices complejos.

Referencia: Jimmy Bogard, *Vertical Slice Architecture* — https://www.jimmybogard.com/vertical-slice-architecture/
