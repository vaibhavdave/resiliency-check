# resiliency-check

A small Gradle multi-module project demonstrating [Resilience4j](https://resilience4j.readme.io/)
patterns in Spring Boot 3.5, built around two independently-runnable services:

```
                     HTTP                         HTTP
   (you) ── curl ──────────▶ order-service ─────────────▶ inventory-service
                              (port 8080)                    (port 8081)
                              Resilience4j lives here         Flaky-on-demand
                                                               downstream
```

- **`inventory-service`** — a deliberately unreliable downstream dependency. It serves a
  small in-memory product catalog and can be told, at runtime, to fail, respond slowly, or
  time out, so the patterns below can actually be triggered on demand.
- **`order-service`** — calls `inventory-service` over HTTP via Spring's `RestClient` and
  wraps that call with each Resilience4j module in turn, one endpoint per pattern, plus a
  combined endpoint showing them stacked together as you would in a real service.

## Stack

- Java 21, Spring Boot 3.5.4, Gradle (Groovy DSL)
- `resilience4j-spring-boot3` 2.3.0 (Circuit Breaker, Retry, Rate Limiter, Bulkhead, Time Limiter)
- Spring Boot Actuator with Resilience4j health indicators and metrics
- Micrometer + Prometheus registry for metrics export

## Running locally

Requires JDK 21. From the repo root:

```bash
# terminal 1 - the flaky downstream
./gradlew :inventory-service:bootRun

# terminal 2 - the resilient client
./gradlew :order-service:bootRun
```

Or with Docker Compose (builds both images and wires them together):

```bash
docker compose up --build
```

Run all tests (each Resilience4j pattern has an integration test that proves it actually
triggers, using an in-process mock HTTP server standing in for inventory-service):

```bash
./gradlew test
```

## Endpoints

### inventory-service (port 8081)

| Endpoint | Purpose |
|---|---|
| `GET /products/{id}` | Returns product `1`, `2`, or `3`. Behavior depends on the current failure mode (see below). |
| `GET /admin/mode` | Shows the current process-wide failure mode. |
| `POST /admin/mode` | Sets the process-wide failure mode: `{"mode": "OK\|ERROR\|SLOW\|TIMEOUT\|RANDOM", "delayMs": 0, "errorRate": 0.5}`. |

`GET /products/{id}` also accepts `?mode=...&delayMs=...&errorRate=...` query params that
override the process-wide mode for that single call only, handy for quick manual curl checks.

### order-service (port 8080)

| Endpoint | Pattern demonstrated |
|---|---|
| `GET /orders/products/{id}/circuit-breaker` | Circuit Breaker |
| `GET /orders/products/{id}/retry` | Retry |
| `GET /orders/products/{id}/rate-limiter` | Rate Limiter |
| `GET /orders/products/{id}/bulkhead` | Bulkhead (semaphore) |
| `GET /orders/products/{id}/time-limiter` | Time Limiter (async call, cancelled on timeout) |
| `GET /orders/products/{id}/combined` | Retry + Circuit Breaker + Rate Limiter + Bulkhead stacked |

Observability:

| Actuator endpoint | Shows |
|---|---|
| `GET /actuator/health` | Overall health, including circuit breaker / rate limiter health indicators |
| `GET /actuator/circuitbreakers` and `/circuitbreakerevents` | Circuit breaker state and event history |
| `GET /actuator/retryevents` | Retry attempts |
| `GET /actuator/ratelimiters` and `/ratelimiterevents` | Rate limiter state and rejections |
| `GET /actuator/bulkheads` and `/bulkheadevents` | Bulkhead state and rejections |
| `GET /actuator/timelimiterevents` | Time limiter timeouts |
| `GET /actuator/metrics` and `/actuator/prometheus` | Micrometer metrics |

## Triggering each pattern

All examples assume both services are running locally (`localhost:8081` / `localhost:8080`).

### Circuit Breaker

Configured with a sliding window of 10 calls, 50% failure threshold, opens for 10s.

```bash
curl -X POST localhost:8081/admin/mode -H 'Content-Type: application/json' \
  -d '{"mode":"ERROR","delayMs":0,"errorRate":0}'

# fire enough failing calls to trip the breaker
for i in $(seq 1 6); do curl -s localhost:8080/orders/products/1/circuit-breaker; echo; done

curl -s localhost:8080/actuator/circuitbreakers
# state should now be OPEN - further calls short-circuit to the fallback instantly,
# without ever reaching inventory-service

curl -X POST localhost:8081/admin/mode -H 'Content-Type: application/json' \
  -d '{"mode":"OK","delayMs":0,"errorRate":0}'   # reset
```

### Retry

Configured for 3 attempts with exponential backoff starting at 500ms.

```bash
curl -X POST localhost:8081/admin/mode -H 'Content-Type: application/json' \
  -d '{"mode":"RANDOM","delayMs":0,"errorRate":0.6}'

for i in $(seq 1 5); do curl -s localhost:8080/orders/products/1/retry; echo; done
# most calls succeed once retries exhaust the transient failures;
# check GET /actuator/retryevents to see the retried attempts

curl -X POST localhost:8081/admin/mode -H 'Content-Type: application/json' \
  -d '{"mode":"OK","delayMs":0,"errorRate":0}'   # reset
```

### Rate Limiter

Configured to allow 3 calls per 10s window, with a 0s wait (excess calls are rejected
immediately rather than queued).

```bash
for i in $(seq 1 5); do curl -s localhost:8080/orders/products/1/rate-limiter; echo; done
# the first 3 succeed, the rest come back as the fallback ("FALLBACK-1")
```

### Bulkhead

Configured to allow only 2 concurrent calls.

```bash
curl -X POST localhost:8081/admin/mode -H 'Content-Type: application/json' \
  -d '{"mode":"SLOW","delayMs":3000,"errorRate":0}'

for i in $(seq 1 4); do curl -s localhost:8080/orders/products/1/bulkhead & done; wait
# with 4 concurrent requests and a limit of 2, at least one comes back as a fallback

curl -X POST localhost:8081/admin/mode -H 'Content-Type: application/json' \
  -d '{"mode":"OK","delayMs":0,"errorRate":0}'   # reset
```

### Time Limiter

Configured with a 2s timeout on the async call.

```bash
curl -X POST localhost:8081/admin/mode -H 'Content-Type: application/json' \
  -d '{"mode":"TIMEOUT","delayMs":5000,"errorRate":0}'

curl -s localhost:8080/orders/products/1/time-limiter; echo
# comes back with the fallback in ~2s instead of waiting the full 5s

curl -X POST localhost:8081/admin/mode -H 'Content-Type: application/json' \
  -d '{"mode":"OK","delayMs":0,"errorRate":0}'   # reset
```

## Project layout

```
resiliency-check/
├── build.gradle              # shared plugin/config for both modules
├── settings.gradle
├── docker-compose.yml
├── inventory-service/        # flaky downstream
│   ├── Dockerfile
│   └── src/main/java/com/example/resiliency/inventory/
└── order-service/            # Resilience4j demo
    ├── Dockerfile
    └── src/main/java/com/example/resiliency/order/
        ├── client/InventoryClient.java   # one method per pattern
        ├── config/InventoryClientConfig.java
        └── controller/OrderController.java
```
