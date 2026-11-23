# Día 4 — Bloque 3: Adaptador cliente HTTP (RestClient)

> *"Everything fails, all the time."* — Werner Vogels, CTO de Amazon

---

## 1. El problema en una frase

Para pagar un pedido tenemos que llamar al API REST de una pasarela externa
que **habla otro idioma** (céntimos, códigos de estado, JSON propio), que **a
veces tarda**, que **a veces falla** y que **no controlamos**. Nada de eso debe
llegar al caso de uso.

---

## 2. Intención

El adaptador `StripeLikePasarelaAdapter` implementa el puerto `PasarelaDePago`
usando **`RestClient`**, el cliente HTTP síncrono con API fluida introducido
en **Spring Framework 6.1** (Spring Boot 3.2+). Sus responsabilidades:

1. **Traducir la petición**: `Dinero` → céntimos + código de moneda.
2. **Traducir la respuesta**: JSON del proveedor → `ResultadoPago`.
3. **Traducir los errores**: códigos HTTP y fallos de red → resultados o
   excepciones **del puerto**.
4. **Protegerse**: timeouts, reintentos (con idempotencia) y, si hace falta,
   circuit breaker.

El puerto, recordemos (definido en `application.port.out`):

```java
package com.curso.pedidos.application.port.out;

public interface PasarelaDePago {
    /**
     * @return Aprobado o Rechazado (respuesta de negocio de la pasarela).
     * @throws PasarelaDePagoNoDisponibleException si no se pudo obtener respuesta.
     */
    ResultadoPago cobrar(PedidoId pedidoId, Dinero importe);
}

public sealed interface ResultadoPago {
    record Aprobado(String referencia) implements ResultadoPago {}
    record Rechazado(String motivo) implements ResultadoPago {}
}

public class PasarelaDePagoNoDisponibleException extends RuntimeException {
    public PasarelaDePagoNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
```

> **Rechazo ≠ fallo.** Una tarjeta rechazada es una respuesta *de negocio*
> válida (el caso de uso decide qué hacer: cancelar, avisar…). Un timeout es
> un problema *técnico*: no sabemos qué ha pasado. Modelarlos igual es uno de
> los errores más caros en integraciones de pago.

---

## 3. Bad: HTTP en el caso de uso

```java
public class PagarPedidoService implements PagarPedidoUseCase {

    private final RestTemplate rest = new RestTemplate();   // ❌ creado aquí, sin timeouts

    public void ejecutar(PagarPedidoCommand cmd) {
        Pedido pedido = repo.buscarPorId(cmd.pedidoId()).orElseThrow();
        Map<String, Object> body = Map.of(
                "amount", pedido.total().importe().multiply(BigDecimal.valueOf(100)),
                "currency", "eur");                           // ❌ modelo del proveedor
        try {
            Map<?, ?> resp = rest.postForObject(
                    "https://api.pagos.example/v1/charges", body, Map.class);
            if ("succeeded".equals(resp.get("status"))) {     // ❌ strings mágicos
                pedido.marcarComoPagado();
            }
        } catch (HttpClientErrorException e) {                // ❌ excepción HTTP en aplicación
            if (e.getStatusCode().value() == 402) { /* ... */ }
        }
        repo.guardar(pedido);
    }
}
```

### ¿Qué duele?

- **Sin timeouts**: si la pasarela se cuelga, se cuelga nuestro hilo… y con
  suficientes peticiones, toda la aplicación.
- **Vocabulario del proveedor** (`amount`, `"succeeded"`, `402`) dentro de la
  lógica de aplicación. Cambiar de pasarela = reescribir el caso de uso.
- **Para testear** el caso de uso necesito un servidor HTTP.
- **URL a fuego**, sin configuración por entorno.
- `RestTemplate` está en modo mantenimiento: para código nuevo, `RestClient`.

---

## 4. Good: `StripeLikePasarelaAdapter` con `RestClient`

### 4.1 Modelo del proveedor (privado al adaptador)

```java
package com.curso.pedidos.infrastructure.adapter.out.payment;

record CobroRequest(long amount, String currency, String reference) {}

record CobroResponse(String id, String status) {}

record ErrorPagoResponse(String code, String message) {}
```

Estos records reflejan **el JSON del proveedor**, no nuestro dominio. Si el
proveedor cambia un campo, solo cambia esto (Anticorruption Layer, ver
[bloque 1](01-adaptadores-de-salida.md#7-anticorruption-layer-acl)).

### 4.2 Configuración tipada

```yaml
# application.yml
pasarela:
  base-url: https://api.pagos.example
  api-key: ${PASARELA_API_KEY}
  connect-timeout: 2s
  read-timeout: 5s
```

```java
package com.curso.pedidos.infrastructure.adapter.out.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "pasarela")
public record PasarelaProperties(String baseUrl, String apiKey,
                                 Duration connectTimeout, Duration readTimeout) {}
```

### 4.3 Construcción del `RestClient` con timeouts

```java
package com.curso.pedidos.infrastructure.adapter.out.payment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(PasarelaProperties.class)
class PasarelaHttpConfig {

    @Bean
    StripeLikePasarelaAdapter pasarelaDePago(RestClient.Builder builder,
                                             PasarelaProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(props.readTimeout());

        RestClient restClient = builder
                .baseUrl(props.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + props.apiKey())
                .build();
        return new StripeLikePasarelaAdapter(restClient);
    }
}
```

- Inyectamos **`RestClient.Builder`** (lo autoconfigura Spring Boot con los
  `HttpMessageConverter` de Jackson y la observabilidad de Micrometer) en vez
  de `RestClient.create()`.
- **Dos timeouts**: *connect* (establecer la conexión TCP) y *read* (esperar
  la respuesta). Sin ellos, el valor por defecto puede ser **infinito**.

### 4.4 El adaptador

```java
package com.curso.pedidos.infrastructure.adapter.out.payment;

import com.curso.pedidos.application.port.out.PasarelaDePago;
import com.curso.pedidos.application.port.out.PasarelaDePagoNoDisponibleException;
import com.curso.pedidos.application.port.out.ResultadoPago;
import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.PedidoId;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Locale;

public class StripeLikePasarelaAdapter implements PasarelaDePago {

    private final RestClient restClient;

    public StripeLikePasarelaAdapter(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ResultadoPago cobrar(PedidoId pedidoId, Dinero importe) {
        var request = new CobroRequest(
                aCentimos(importe),
                importe.moneda().getCurrencyCode().toLowerCase(Locale.ROOT),
                pedidoId.valor().toString());
        try {
            return restClient.post()
                    .uri("/v1/charges")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", pedidoId.valor().toString())
                    .body(request)
                    .exchange((req, res) -> traducir(res.getStatusCode(), res));
        } catch (ResourceAccessException e) {      // timeout, conexión rechazada...
            throw new PasarelaDePagoNoDisponibleException(
                    "Sin respuesta de la pasarela de pago", e);
        }
    }

    private ResultadoPago traducir(HttpStatusCode status,
                                   RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res)
            throws java.io.IOException {
        if (status.is2xxSuccessful()) {
            CobroResponse body = res.bodyTo(CobroResponse.class);
            return "succeeded".equals(body.status())
                    ? new ResultadoPago.Aprobado(body.id())
                    : new ResultadoPago.Rechazado("Estado de pago: " + body.status());
        }
        if (status.value() == 402) {               // Payment Required = rechazo de negocio
            ErrorPagoResponse error = res.bodyTo(ErrorPagoResponse.class);
            return new ResultadoPago.Rechazado(error.code());
        }
        if (status.is5xxServerError() || status.value() == 429) {
            throw new PasarelaDePagoNoDisponibleException(
                    "Pasarela no disponible (HTTP " + status.value() + ")", null);
        }
        // 400, 401, 404...: error NUESTRO (configuración o contrato roto)
        throw new IllegalStateException(
                "Respuesta inesperada de la pasarela: HTTP " + status.value());
    }

    private static long aCentimos(Dinero dinero) {
        return dinero.importe().movePointRight(2)
                .setScale(0, java.math.RoundingMode.UNNECESSARY)
                .longValueExact();
    }
}
```

Por qué `exchange(...)` en lugar de `retrieve()`:

- `retrieve()` lanza `HttpClientErrorException` / `HttpServerErrorException`
  para 4xx/5xx por defecto. Se puede personalizar con `.onStatus(...)`, pero
  para un mapeo **status → resultado** completo, `exchange` nos da la
  respuesta cruda y decidimos todo en un sitio.
- Con `retrieve()` sería así (válido también):

```java
return restClient.post().uri("/v1/charges").body(request)
        .retrieve()
        .onStatus(s -> s.is5xxServerError(), (req, res) -> {
            throw new PasarelaDePagoNoDisponibleException(
                    "HTTP " + res.getStatusCode().value(), null);
        })
        .body(CobroResponse.class);
```

### 4.5 Tabla de traducción de errores

| Situación técnica                         | Traducción                                  | Quién decide qué hacer |
|-------------------------------------------|---------------------------------------------|------------------------|
| 2xx + `status=succeeded`                  | `ResultadoPago.Aprobado(id)`                | Caso de uso            |
| 2xx + otro estado / 402                   | `ResultadoPago.Rechazado(motivo)`           | Caso de uso            |
| 429, 5xx                                  | `PasarelaDePagoNoDisponibleException`       | Reintento / caso de uso|
| Timeout, conexión rechazada               | `PasarelaDePagoNoDisponibleException`       | Reintento / caso de uso|
| 400, 401, 404                             | `IllegalStateException` (bug/config nuestra)| Alerta, no reintentar  |

Y el caso de uso queda limpio, con un `switch` exhaustivo sobre el `sealed`
(Java 21 con *pattern matching for switch*; en Java 17 usa `instanceof`):

```java
// Java 17
ResultadoPago resultado = pasarela.cobrar(pedido.id(), pedido.total());
if (resultado instanceof ResultadoPago.Aprobado aprobado) {
    pedido.marcarComoPagado();
} else if (resultado instanceof ResultadoPago.Rechazado rechazado) {
    pedido.cancelar();
}
```

---

## 5. Diagrama

```
  PagarPedidoService                StripeLikePasarelaAdapter                 API externa
 ┌──────────────────┐  cobrar()   ┌────────────────────────────────┐  POST   ┌───────────┐
 │                  │────────────►│ Dinero ──► CobroRequest        │────────►│           │
 │ ResultadoPago    │             │            (céntimos, "eur")   │         │ /v1/      │
 │  Aprobado ◄──────│◄────────────│ 2xx  ──► Aprobado / Rechazado  │◄────────│ charges   │
 │  Rechazado       │             │ 402  ──► Rechazado             │         │           │
 │                  │   throws    │ 5xx/429/timeout ──►            │         └───────────┘
 │ (catch o deja    │◄ ─ ─ ─ ─ ─ ─│   PasarelaDePagoNoDisponible   │
 │  propagar)       │             │ timeouts + Idempotency-Key     │
 └──────────────────┘             └────────────────────────────────┘
```

---

## 6. Reintentos y resiliencia (Resilience4j)

Reintentar es tentador, pero **solo es seguro si la operación es
idempotente**. Cobrar dos veces a un cliente es el peor bug posible en una
tienda. Por eso enviamos `Idempotency-Key: <pedidoId>`: si el primer intento
llegó pero perdimos la respuesta, el proveedor reconoce la clave y no cobra
otra vez.

**Resilience4j** es la librería estándar de facto en el ecosistema Spring para
*Retry*, *CircuitBreaker*, *RateLimiter*, *Bulkhead* y *TimeLimiter*. Mejor
aplicarla **como decorador** del adaptador: el adaptador sigue siendo sencillo
y la política de resiliencia se configura en un único sitio.

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-retry</artifactId>
    <version>2.2.0</version>
</dependency>
```

```java
package com.curso.pedidos.infrastructure.adapter.out.payment;

import com.curso.pedidos.application.port.out.PasarelaDePago;
import com.curso.pedidos.application.port.out.PasarelaDePagoNoDisponibleException;
import com.curso.pedidos.application.port.out.ResultadoPago;
import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.PedidoId;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;

/** Decorador: misma interfaz, añade reintentos. Patrón Decorator (GoF). */
public class PasarelaDePagoConReintentos implements PasarelaDePago {

    private final PasarelaDePago delegada;
    private final Retry retry;

    public PasarelaDePagoConReintentos(PasarelaDePago delegada, int maxIntentos,
                                       Duration espera) {
        this.delegada = delegada;
        this.retry = Retry.of("pasarela", RetryConfig.custom()
                .maxAttempts(maxIntentos)
                .waitDuration(espera)
                .retryExceptions(PasarelaDePagoNoDisponibleException.class)
                .build());
    }

    @Override
    public ResultadoPago cobrar(PedidoId pedidoId, Dinero importe) {
        return Retry.decorateSupplier(retry, () -> delegada.cobrar(pedidoId, importe)).get();
    }
}
```

Fíjate: **solo** reintentamos `PasarelaDePagoNoDisponibleException`. Un
`Rechazado` no se reintenta (la tarjeta seguirá sin saldo) ni un 400 (el bug
seguirá ahí).

Alternativa declarativa con `resilience4j-spring-boot3` + AOP:
`@Retry(name = "pasarela")` y `@CircuitBreaker(name = "pasarela")` sobre el
método del adaptador, con la configuración en `application.yml`. Más cómodo;
el decorador explícito es más fácil de testear y de leer en clase.

> Trampa: **reintentos en cascada.** Si el adaptador reintenta 3 veces, el
> caso de uso otras 3 y el cliente HTTP del frontend otras 3, una caída
> genera 27 peticiones. Reintenta **en un solo nivel**.

---

## 7. Testing con WireMock

Probamos el adaptador **aislado**, con un servidor HTTP falso que levanta
WireMock en un puerto aleatorio. Nada de Spring context: construimos el
`RestClient` a mano.

```xml
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <version>3.9.1</version>
    <scope>test</scope>
</dependency>
```

```java
package com.curso.pedidos.infrastructure.adapter.out.payment;

import com.curso.pedidos.application.port.out.PasarelaDePagoNoDisponibleException;
import com.curso.pedidos.application.port.out.ResultadoPago;
import com.curso.pedidos.domain.model.Dinero;
import com.curso.pedidos.domain.model.PedidoId;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;

class StripeLikePasarelaAdapterTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private StripeLikePasarelaAdapter adapter;
    private final PedidoId pedidoId = new PedidoId(UUID.randomUUID());
    private final Dinero importe =
            new Dinero(new BigDecimal("19.99"), Currency.getInstance("EUR"));

    @BeforeEach
    void setUp() {
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(500));
        adapter = new StripeLikePasarelaAdapter(RestClient.builder()
                .baseUrl(wm.baseUrl())
                .requestFactory(factory)
                .build());
    }

    @Test
    void cobro_aprobado_envia_centimos_e_idempotency_key() {
        wm.stubFor(post("/v1/charges").willReturn(okJson("""
                {"id": "ch_123", "status": "succeeded"}
                """)));

        ResultadoPago resultado = adapter.cobrar(pedidoId, importe);

        assertThat(resultado).isEqualTo(new ResultadoPago.Aprobado("ch_123"));
        wm.verify(postRequestedFor(urlEqualTo("/v1/charges"))
                .withHeader("Idempotency-Key", equalTo(pedidoId.valor().toString()))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("1999")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("eur"))));
    }

    @Test
    void http_402_se_traduce_a_rechazo() {
        wm.stubFor(post("/v1/charges").willReturn(aResponse()
                .withStatus(402)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"code": "card_declined", "message": "Fondos insuficientes"}
                        """)));

        assertThat(adapter.cobrar(pedidoId, importe))
                .isEqualTo(new ResultadoPago.Rechazado("card_declined"));
    }

    @Test
    void http_503_se_traduce_a_no_disponible() {
        wm.stubFor(post("/v1/charges").willReturn(serviceUnavailable()));

        assertThatThrownBy(() -> adapter.cobrar(pedidoId, importe))
                .isInstanceOf(PasarelaDePagoNoDisponibleException.class)
                .hasMessageContaining("503");
    }

    @Test
    void timeout_se_traduce_a_no_disponible() {
        wm.stubFor(post("/v1/charges").willReturn(okJson("{}").withFixedDelay(2_000)));

        assertThatThrownBy(() -> adapter.cobrar(pedidoId, importe))
                .isInstanceOf(PasarelaDePagoNoDisponibleException.class);
    }
}
```

Test del **decorador de reintentos**, con escenarios de WireMock (primer
intento 503, segundo 200):

```java
@Test
void reintenta_tras_un_503_y_acaba_aprobando() {
    wm.stubFor(post("/v1/charges").inScenario("reintento")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(serviceUnavailable())
            .willSetStateTo("segundo"));
    wm.stubFor(post("/v1/charges").inScenario("reintento")
            .whenScenarioStateIs("segundo")
            .willReturn(okJson("""
                    {"id": "ch_999", "status": "succeeded"}
                    """)));

    var conReintentos = new PasarelaDePagoConReintentos(adapter, 3, Duration.ofMillis(10));

    assertThat(conReintentos.cobrar(pedidoId, importe))
            .isEqualTo(new ResultadoPago.Aprobado("ch_999"));
    wm.verify(2, postRequestedFor(urlEqualTo("/v1/charges")));
}
```

> Más adelante (día 5) veremos **tests de contrato** (Spring Cloud Contract /
> Pact) para no depender de que nuestros stubs de WireMock reflejen fielmente
> el API real.

---

## 8. Trampas

- **Trampa: confiar en los timeouts por defecto.** Configúralos siempre.
- **Trampa: reintentar operaciones no idempotentes** sin clave de
  idempotencia.
- **Trampa: `catch (Exception e) { return Rechazado }`.** Convierte un fallo
  técnico (no sabemos si se cobró) en una decisión de negocio (no se cobró).
- **Trampa: loguear el body completo** de peticiones de pago (datos sensibles,
  PCI-DSS). Loguea ids y códigos, no tarjetas.
- **Trampa: usar `double` para importes.** `0.1 + 0.2 != 0.3`. `BigDecimal` y
  `movePointRight(2)` con `longValueExact()` para fallar si sobran decimales.
- **Variante: interfaces HTTP declarativas** (`@HttpExchange` +
  `HttpServiceProxyFactory`). Generan el cliente a partir de una interfaz
  anotada. Útiles, pero esa interfaz **es un detalle del adaptador**, no el
  puerto.

---

## 9. Resumen

| Aspecto                   | Decisión                                                  |
|---------------------------|-----------------------------------------------------------|
| Cliente                   | `RestClient` (Spring 6.1) construido desde el `Builder`   |
| Modelo del proveedor      | Records package-private en el adaptador                   |
| Rechazo de negocio        | `ResultadoPago.Rechazado` (valor, no excepción)           |
| Fallo técnico             | `PasarelaDePagoNoDisponibleException`                     |
| Timeouts                  | Connect + read, configurables por `@ConfigurationProperties` |
| Reintentos                | Decorador con Resilience4j, solo fallos técnicos, idempotencia |
| Test                      | WireMock: éxito, 402, 503, timeout, reintento             |

---

## Ejercicio

Añade un **circuit breaker** al decorador: tras un 50 % de fallos en las
últimas 10 llamadas, la pasarela queda "abierta" 30 s y `cobrar` falla
inmediatamente con `PasarelaDePagoNoDisponibleException` sin llamar al API.

Pista: `resilience4j-circuitbreaker`, `CircuitBreaker.of(...)`,
`CircuitBreaker.decorateSupplier(...)` y la excepción
`CallNotPermittedException`.

<details><summary>Solución</summary>

```java
private final CircuitBreaker circuitBreaker = CircuitBreaker.of("pasarela",
        CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(PasarelaDePagoNoDisponibleException.class)
                .build());

@Override
public ResultadoPago cobrar(PedidoId pedidoId, Dinero importe) {
    Supplier<ResultadoPago> llamada = () -> delegada.cobrar(pedidoId, importe);
    Supplier<ResultadoPago> protegida =
            CircuitBreaker.decorateSupplier(circuitBreaker,
                    Retry.decorateSupplier(retry, llamada));
    try {
        return protegida.get();
    } catch (CallNotPermittedException e) {
        throw new PasarelaDePagoNoDisponibleException("Circuito abierto", e);
    }
}
```

El orden importa: aquí el circuit breaker envuelve al retry, así que una
secuencia de reintentos fallida cuenta como **un** fallo. Traducimos
`CallNotPermittedException` (de Resilience4j) a la excepción del puerto: ni
siquiera la librería de resiliencia debe escaparse del adaptador.

</details>

---

## Referencias

- Spring Framework — REST Clients (`RestClient`, `@HttpExchange`):
  https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- Resilience4j documentation: https://resilience4j.readme.io/
- WireMock documentation: https://wiremock.org/docs/
- Spring Boot docs: https://docs.spring.io/spring-boot/index.html
- Eric Evans, *Domain-Driven Design* (2003) — *Anticorruption Layer*.
- Gamma et al., *Design Patterns* (1994) — Adapter, Decorator.
- Martin Fowler, *The Practical Test Pyramid*:
  https://martinfowler.com/articles/practical-test-pyramid.html

---

## Mantra

> *"Un rechazo es una respuesta; un timeout es una incógnita. No los
> confundas nunca."*
