# Resilience4j Test Suite — Q&A Reference

---

## Q1: What does `ProductControllerTest` test?

**A:** It tests the **inventory-service HTTP API** — the fake downstream service that order-service calls.
The test starts a real Spring Boot server on a random port and hits it with actual HTTP requests using `TestRestTemplate`.

### Architecture
```
[Test]  ──HTTP──▶  [inventory-service]  (real server, random port)
                         │
                         └── in-memory product data (no real DB)
```

### The 4 test cases

```
┌────────────────────────────┬────────────────────────────────────┐
│  Test                      │  What it checks                    │
├────────────────────────────┼────────────────────────────────────┤
│  returnsProductByDefault   │  GET /products/1  → 200 OK         │
│                            │  body contains "Wireless Mouse"    │
├────────────────────────────┼────────────────────────────────────┤
│  returns404ForUnknownProduct│ GET /products/999 → 404 NOT FOUND │
├────────────────────────────┼────────────────────────────────────┤
│  perRequestErrorOverride   │  GET /products/1?mode=ERROR        │
│  ForcesFailure             │              → 500 ERROR           │
├────────────────────────────┼────────────────────────────────────┤
│  adminEndpointToggles      │  POST /admin/mode {mode:"ERROR"}   │
│  ProcessWideMode           │  → all calls fail globally         │
│                            │  POST /admin/mode {mode:"OK"}      │
│                            │  → reset back to normal            │
└────────────────────────────┴────────────────────────────────────┘
```

### Flow diagrams

**Test 1 — Happy path**
```
Test  ──GET /products/1──▶  Server
      ◀── 200 OK ───────────
      body: { id:"1", name:"Wireless Mouse" }
✅ Assert: status == 200, body contains "Wireless Mouse"
```

**Test 2 — Unknown product**
```
Test  ──GET /products/999──▶  Server  (product doesn't exist)
      ◀── 404 NOT FOUND ─────
✅ Assert: status == 404
```

**Test 3 — Per-request error via query param**
```
Test  ──GET /products/1?mode=ERROR──▶  Server
      ◀── 500 INTERNAL SERVER ERROR ──
✅ Assert: status == 500
```

**Test 4 — Process-wide error toggle**
```
POST /admin/mode { mode:"ERROR" }  →  server now fails all requests
GET  /products/1                   →  500 ✅
POST /admin/mode { mode:"OK"    }  →  reset (so other tests aren't polluted)
```

### Why does inventory-service have built-in "break yourself" modes?
They exist purely for testing. They allow order-service integration tests to simulate a broken downstream service on demand — without needing complex mock setups.

---

## Q2: What does `RetryIntegrationTest` test?

**A:** It verifies that **Retry automatically re-attempts failed calls** to the inventory service, up to a configured limit, before giving up and returning a fallback.

### Configuration used in the test
```
max-attempts = 3          ← try up to 3 times total
wait-duration = 50ms      ← flat wait between retries
exponential-backoff = off ← wait never grows
```

### Architecture
```
[Test]
  └──▶ inventoryClient.getProductWithRetry("1")
              │
              ▼
        [Retry Wrapper]  ← Resilience4j @Retry annotation
              │
              ▼
        [MockWebServer]  ← fake inventory server (programmed with errors/success)
```

### Test 1 — `retriesTransientFailuresThenSucceeds`
Two failures then a success — Retry recovers transparently.
```
Attempt 1  ──▶  MockWebServer  ──▶  500 ERROR  ──▶  wait 50ms
Attempt 2  ──▶  MockWebServer  ──▶  500 ERROR  ──▶  wait 50ms
Attempt 3  ──▶  MockWebServer  ──▶  200 OK     ──▶  ✅ return product

✅ Assert: product.id == "1", name == "Wireless Mouse"
✅ Assert: exactly 3 HTTP requests made
```

### Test 2 — `fallsBackOnceAttemptsAreExhausted`
All 3 attempts fail — Retry gives up and triggers the fallback.
```
Attempt 1  ──▶  MockWebServer  ──▶  500 ERROR  ──▶  wait 50ms
Attempt 2  ──▶  MockWebServer  ──▶  500 ERROR  ──▶  wait 50ms
Attempt 3  ──▶  MockWebServer  ──▶  500 ERROR  ──▶  no more attempts
                                         ▼
                                  [Fallback triggered]
                             returns Product{ id: "FALLBACK-..." }

✅ Assert: product.id starts with "FALLBACK"
✅ Assert: exactly 3 HTTP requests made (not more)
```

### Both tests side by side
```
┌─────────────────────────────────┬──────────────────────────────────┐
│  Test 1: recovers               │  Test 2: exhausted               │
│  enqueue: [ERR, ERR, OK]        │  enqueue: [ERR, ERR, ERR]        │
│  try 1 → ERR ─┐                 │  try 1 → ERR ─┐                  │
│  try 2 → ERR ─┤ retry          │  try 2 → ERR ─┤ retry            │
│  try 3 → OK  ─┘                 │  try 3 → ERR ─┘                  │
│  result: real Product ✅        │  result: FALLBACK Product ✅     │
│  requests made: 3 ✅            │  requests made: 3 ✅             │
└─────────────────────────────────┴──────────────────────────────────┘
```

### Key things to notice
- The test counts HTTP requests (`mockWebServer.getRequestCount()`) to prove retries actually happened.
- The `before` variable snapshots the count before the test so tests don't interfere with each other.
- The fallback returns a graceful `Product` object — not an exception — with id starting `"FALLBACK"`.

---

## Q3: What does `CircuitBreakerIntegrationTest` test?

**A:** It verifies that after enough consecutive failures, the circuit **trips OPEN** and subsequent calls are **short-circuited** — they never reach the downstream service at all.

### Circuit Breaker States
```
                   too many failures
  [CLOSED] ──────────────────────────▶ [OPEN]
  (calls go      (failure rate         (calls are
   through)       >= threshold)         blocked)
      ▲                                    │
      │         wait-duration expires      │
      └──────────────── [HALF-OPEN] ◀─────┘
                        (let a few calls
                         through to probe)
```

### Configuration used in the test
```
sliding-window-size     = 4    ← track last 4 calls
minimum-number-of-calls = 4    ← need at least 4 before tripping
failure-rate-threshold  = 50%  ← trip if ≥ 50% of last 4 calls failed
wait-duration-in-open   = 5s   ← stay OPEN for 5s before probing
```

### How the annotation wires up (InventoryClient.java)
```java
@CircuitBreaker(name = "inventoryService", fallbackMethod = "fallbackProduct")
public Product getProductWithCircuitBreaker(String id) {
    return fetchProduct(id);
}
// fallback: Product("FALLBACK-" + id, "temporarily unavailable", ...)
```

### The single test — `opensAfterFailureThresholdAndShortCircuitsSubsequentCalls`

**Phase 1: Fill the sliding window with failures**
```
Call 1  ──▶  MockWebServer  ──▶  500 ERROR  ──▶  FAILURE
Call 2  ──▶  MockWebServer  ──▶  500 ERROR  ──▶  FAILURE
Call 3  ──▶  MockWebServer  ──▶  500 ERROR  ──▶  FAILURE
Call 4  ──▶  MockWebServer  ──▶  500 ERROR  ──▶  FAILURE

Failure rate: 4/4 = 100%  ≥  50% threshold
⚡ Circuit TRIPS → state becomes OPEN
```

**Phase 2: Assert state is OPEN**
```java
assertThat(circuitBreakerRegistry.circuitBreaker("inventoryService").getState())
    .isEqualTo(CircuitBreaker.State.OPEN);  ✅
```

**Phase 3: Prove open circuit blocks calls**
```
requestsSoFar = mockWebServer.getRequestCount()  ← snapshot

Call 5  ──▶  [Circuit Breaker: OPEN] ✋  (does NOT reach MockWebServer)
                    │
                    ▼
             fallbackProduct() → Product{ id: "FALLBACK-1" }

✅ Assert: product.id starts with "FALLBACK"
✅ Assert: request count unchanged — no HTTP call was made
```

### Full picture
```
                    ┌──────────────────────────────┐
                    │       CircuitBreaker          │
  inventoryClient   │                              │   MockWebServer
  ───────────────▶  │  CLOSED: pass through  ─────▶│──▶ [real HTTP call]
                    │                              │
  ───────────────▶  │  OPEN: short-circuit   ✋    │   (never reached)
                    │      │                       │
                    │      ▼                       │
                    │  fallbackProduct()           │
                    └──────────────────────────────┘
```

### Key things to notice
- `CircuitBreakerRegistry` is injected directly to inspect internal state — asserts `State.OPEN` without guessing.
- The request count check is the most important assertion: **no HTTP call made when circuit is open**.
- Retry vs CircuitBreaker serve different roles:
  ```
  Retry          →  transient errors (1-2 failures, quick recovery)
  CircuitBreaker →  sustained outages (downstream down for minutes)
  ```

---

## Q4: What does `BulkheadIntegrationTest` test?

**A:** It verifies that when too many callers hit inventory-service **simultaneously**, the bulkhead rejects excess calls immediately (instead of letting them pile up and starve all threads).

### The core idea
```
Without Bulkhead:
  100 slow calls → 100 threads blocked → rest of app starved ❌

With Bulkhead (max-concurrent-calls=2):
  Call 1 → allowed ✅
  Call 2 → allowed ✅
  Call 3 → REJECTED instantly → fallback ✅  (app stays responsive)
```

### Configuration used in the test
```
max-concurrent-calls = 2    ← only 2 calls allowed simultaneously
max-wait-duration    = 0s   ← reject instantly, no waiting
```

### How the annotation wires up (InventoryClient.java)
```java
@Bulkhead(name = "inventoryService", fallbackMethod = "fallbackProduct")
public Product getProductWithBulkhead(String id) {
    return fetchProduct(id);
}
```

### The single test — `rejectsCallsBeyondTheConcurrencyLimit`

**Step 1: Queue 3 slow responses (each takes 1 second)**
```
mockWebServer.enqueue(successDelayedBy(1s))  ← keeps calls in-flight long enough
mockWebServer.enqueue(successDelayedBy(1s))
mockWebServer.enqueue(successDelayedBy(1s))
```

**Step 2: Create 3 threads held behind a starting gate**
```
Thread 1  ──┐
Thread 2  ──┤── all waiting at CountDownLatch(1)  (startGate)
Thread 3  ──┘
```

**Step 3: Release gate — all 3 fire simultaneously**
```
startGate.countDown()  ← all 3 threads unblock at the same instant
```

**Step 4: What happens inside Bulkhead**
```
Thread 1  ──▶  Bulkhead  ──▶  slot 1 taken  ──▶  HTTP call (1s)
Thread 2  ──▶  Bulkhead  ──▶  slot 2 taken  ──▶  HTTP call (1s)
Thread 3  ──▶  Bulkhead  ──▶  NO SLOT, wait=0s → REJECTED
                                    │
                                    ▼
                             fallbackProduct() → FALLBACK product (instant)
```

**Step 5: Assert**
```
results: [Product("1"), Product("1"), Product("FALLBACK-1")]
fallbackCount ≥ 1  ✅
```

### Full concurrency picture
```
t=0ms
  Thread1 ──▶ [Bulkhead] ──▶ MockWebServer (slow, 1s...)
  Thread2 ──▶ [Bulkhead] ──▶ MockWebServer (slow, 1s...)
  Thread3 ──▶ [Bulkhead] ✋ REJECTED → fallback() → FALLBACK (instant)

t=1000ms
  Thread1 ◀── 200 OK
  Thread2 ◀── 200 OK
  Thread3 already returned FALLBACK
```

### Why `CountDownLatch`?
Without it threads start sequentially and earlier ones finish before later ones start — no rejection happens. The latch forces all 3 to hit the bulkhead at the exact same moment.
```
Without latch (sequential):         With latch (truly parallel):
  T1 starts → finishes               T1 ─┐
  T2 starts → finishes               T2 ─┼─ all start at t=0
  T3 starts → finishes               T3 ─┘
  ❌ no rejection                    ✅ 3rd call hits full bulkhead
```

### Why `isGreaterThanOrEqualTo(1)` not `isEqualTo(1)`?
Thread scheduling isn't perfectly guaranteed. It's possible 2 calls get rejected due to timing. The test only requires *at least* 1 rejection — sufficient to prove the bulkhead is working.

---

## Q5: What does `RateLimiterIntegrationTest` test?

**A:** It verifies that the Rate Limiter allows only a fixed number of calls per time window and **rejects excess calls immediately** with a fallback — without waiting.

### The core idea
```
Time window: 10 seconds  |  Limit: 2 calls per window

Call 1  → ✅ permitted  (1 of 2 used)
Call 2  → ✅ permitted  (2 of 2 used)
Call 3  → ❌ REJECTED   (window full — reject instantly)
               │
               ▼
         fallback() → FALLBACK product
```

### Configuration used in the test
```
limit-for-period     = 2     ← max 2 calls per window
limit-refresh-period = 10s   ← window resets every 10 seconds
timeout-duration     = 0s    ← don't wait for a permit — reject instantly
```

### The single test — `rejectsCallsBeyondThePermittedRateWithoutWaiting`
```
mockWebServer: [success, success]   ← only 2 responses queued (3rd never reaches server)

Call 1  ──▶  RateLimiter  ──▶  permit granted  ──▶  MockWebServer  ──▶  Product("1") ✅
Call 2  ──▶  RateLimiter  ──▶  permit granted  ──▶  MockWebServer  ──▶  Product("1") ✅
Call 3  ──▶  RateLimiter  ──▶  NO PERMIT, wait=0s → REJECTED
                                    │
                                    ▼
                             fallbackProduct() → Product("FALLBACK-1")

✅ first.id()  == "1"
✅ second.id() == "1"
✅ third.id()  starts with "FALLBACK"
```

### Bulkhead vs Rate Limiter — side by side
```
┌─────────────────────────┬──────────────────────────────────┐
│       Bulkhead          │         Rate Limiter             │
├─────────────────────────┼──────────────────────────────────┤
│ Limits: concurrent      │ Limits: calls per time window    │
│         calls at once   │                                  │
├─────────────────────────┼──────────────────────────────────┤
│ Resets: when a call     │ Resets: every N seconds          │
│         finishes        │         (regardless of calls)    │
├─────────────────────────┼──────────────────────────────────┤
│ Protects: your threads  │ Protects: downstream from being  │
│                         │           overwhelmed            │
└─────────────────────────┴──────────────────────────────────┘
```

### Why only 2 responses are queued on MockWebServer
The 3rd call never reaches MockWebServer — rejected at the Rate Limiter layer before any HTTP call is made. This implicitly proves rejection happened before the network.

### Why `timeout-duration=0s` matters
```
timeout-duration = 0s   → reject instantly  → fast fallback  ✅
timeout-duration = 5s   → block up to 5s   → slow fallback  ⚠️
```
Without `0s`, the Rate Limiter would block the calling thread waiting for a permit — bad for latency-sensitive services.

---

## Q6: What does `TimeLimiterIntegrationTest` test?

**A:** It verifies that when a downstream call takes **longer than the configured timeout**, it is cancelled and a fallback is returned immediately — instead of blocking the caller indefinitely.

### The core idea
```
Without TimeLimiter:
  Call → MockWebServer (3s delay) → thread blocked for 3s ❌

With TimeLimiter (timeout=500ms):
  Call → MockWebServer (3s delay)
       → 500ms passes → CANCELLED → fallback instantly ✅
```

### Configuration used in the test
```
timeout-duration      = 500ms   ← deadline for the downstream call
cancel-running-future = true    ← actively cancel the in-flight async task
```

### Why is the method async (`CompletableFuture`)?
TimeLimiter works by racing two things in parallel — the actual HTTP call and a timeout timer. This requires async execution.
```java
// All other patterns:
public Product getProductWithRetry(String id)           // synchronous

// TimeLimiter:
public CompletableFuture<Product> getProductWithTimeLimiter(String id)  // async
```

Internally it runs the HTTP call on a background thread while the timer watches:
```
inventoryClient.getProductWithTimeLimiter("1")
       │
       └──▶ CompletableFuture<Product>
                    │
                    ├── background thread: doing the HTTP call
                    └── TimeLimiter:       watching the clock ⏱
```

### Test 1 — `fallsBackWhenDownstreamExceedsTheTimeout`
Downstream takes 3s, timeout is 500ms — fallback must trigger.
```
t=0ms     HTTP call sent ──▶ MockWebServer (will respond after 3000ms)
          TimeLimiter clock starts (deadline: 500ms)

t=500ms   TIMEOUT! ⏰
          future CANCELLED
          fallbackProductAsync() → CompletableFuture{ Product("FALLBACK-1") }

✅ Assert: product.id starts with "FALLBACK"
```

### Test 2 — `succeedsWhenDownstreamRespondsInTime`
Downstream takes 50ms, timeout is 500ms — real result must come back.
```
t=0ms     HTTP call sent ──▶ MockWebServer (will respond after 50ms)
          TimeLimiter clock starts (deadline: 500ms)

t=50ms    Response arrives ✅  (well before 500ms deadline)
          TimeLimiter satisfied — no timeout

✅ Assert: product.id == "1"
```

### Both tests side by side
```
┌──────────────────────────────────┬──────────────────────────────────┐
│  Test 1: too slow                │  Test 2: fast enough             │
│                                  │                                  │
│  MockWebServer delay: 3000ms     │  MockWebServer delay: 50ms       │
│  Timeout:             500ms      │  Timeout:             500ms      │
│                                  │                                  │
│  t=0    ──── call starts         │  t=0    ──── call starts         │
│  t=500  ──── TIMEOUT ⏰          │  t=50   ──── response ✅         │
│               │                  │                                  │
│               ▼                  │  result: Product("1")            │
│         FALLBACK product         │                                  │
│  result: FALLBACK ✅             │  result: real product ✅         │
└──────────────────────────────────┴──────────────────────────────────┘
```

### What does `cancel-running-future=true` do?
```
cancel-running-future = false  → fallback triggered, but HTTP call still
                                  running in background (wasted resources) ⚠️
cancel-running-future = true   → fallback triggered AND in-flight HTTP
                                  call is cancelled (clean) ✅
```

### How all 5 patterns compare
```
┌────────────────┬──────────────────────┬──────────────────────────────┐
│  Pattern       │  Problem it solves   │  How it reacts               │
├────────────────┼──────────────────────┼──────────────────────────────┤
│  Retry         │  Transient failures  │  Retries N times             │
│  CircuitBreaker│  Sustained failures  │  Stops calling for a while   │
│  Bulkhead      │  Thread exhaustion   │  Rejects when slots full     │
│  RateLimiter   │  Call rate too high  │  Rejects when window full    │
│  TimeLimiter   │  Slow responses      │  Cancels after deadline      │
└────────────────┴──────────────────────┴──────────────────────────────┘
```

---