# BankX Reactive Ledger - Spring Boot WebFlux

Microservicio reactivo para movimientos de cuenta (débitos y créditos) con resiliencia, observabilidad y validación de riesgo.

## Contexto del Reto

**Tech Connect 2026 - NTT DATA**

BankX necesita un microservicio capaz de:
- Registrar débitos y créditos en tiempo real
- Consultar un servicio remoto de riesgo (con tolerancia a fallos)
- Fallback al módulo legacy si el riesgo remoto falla
- Trazabilidad por petición (X-Correlation-Id)
- Eventos en vivo (SSE)

## Stack Técnico

| Componente | Versión | Propósito |
|---|---|---|
| **Spring Boot** | 3.4.0 | Framework reactivo |
| **WebFlux** | 3.4.0 | Programación reactiva (event-loop) |
| **MongoDB** | 7.0 | BD reactiva para transacciones y cuentas |
| **H2** | 2.2.224 | BD legacy (JPA bloqueante) |
| **Resilience4j** | 2.2.0 | Circuit Breaker, Retry, TimeLimiter |
| **Reactor** | 2022.0.x | Manejo de Mono/Flux |
| **Gradle** | 8.5+ | Build tool |
| **JDK** | 17+ | Java runtime |

## Instalación

### Requisitos
```bash
java -version          # JDK 17+
gradle -version        # Gradle 8.5+
docker --version       # Docker
```

### Pasos

1. **Clonar y navegar**
```bash
cd bankx-ledger
```

2. **Levantar MongoDB y H2**
```bash
docker-compose up -d
```

3. **Compilar**
```bash
gradle clean build
```

4. **Ejecutar**
```bash
gradle bootRun
```

El servicio estará en `http://localhost:8080`

### Acceso a BD

**MongoDB Compass**
```
mongodb://admin:admin123@localhost:27017/bankx?authSource=admin
```

**H2 Console**
```
http://localhost:8080/h2-console
```

## API Endpoints

### 1. Crear Transacción

```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: demo-001" \
  -d '{
    "accountNumber": "001-0001",
    "type": "DEBIT",
    "amount": 100.00
  }'
```

**Response (200 OK)**
```json
{
  "transactionId": "uuid",
  "accountNumber": "001-0001",
  "type": "DEBIT",
  "amount": 100.00,
  "status": "OK",
  "newBalance": 900.00,
  "createdAt": "2026-09-30T10:30:00",
  "correlationId": "demo-001",
  "usedFallback": false
}
```

**Errores Esperados**
- `account_not_found` (422) — Cuenta no existe
- `insufficient_funds` (422) — Fondos insuficientes
- `risk_rejected` (422) — Servicio de riesgo rechazó
- `validation_error` (400) — Validación fallida

### 2. Stream de Transacciones (SSE)

```bash
curl -N http://localhost:8080/api/stream/transactions?accountNumber=001-0001
```

**Response (event stream)**
```
event: transaction
data: {"type":"CREDIT","amount":250,"status":"OK"}

event: transaction
data: {"type":"DEBIT","amount":100,"status":"OK"}
```

### 3. Health Check

```bash
curl http://localhost:8080/api/health
```

**Response (200)**
```json
{"status":"UP"}
```

## Decisiones Técnicas Clave

### 1. **¿Por qué WebFlux y no MVC?**

WebFlux permite:
- Event-loop único (no bloquea threads)
- Manejo de M de peticiones con pocos threads
- Mejor performance en I/O (MongoDB, risk service)
- Reactivo por naturaleza

**Mono** para una respuesta única:
```java
public Mono<TransactionResponse> processTransaction(TransactionRequest request)
```

**Flux** para streams:
```java
public Flux<Transaction> getTransactionsByAccount(String accountNumber)
```

### 2. **¿Dónde está el bloqueo? ¿Cómo lo aislaste?**

**Bloqueo está en:**
- JPA (legacy) — consultas síncronas a H2
- Thread.sleep en LegacyService — simula I/O bloqueante

**Aislamiento:**
```java
// En RiskService.fallbackRiskEvaluation()
return Mono.fromCallable(() -> legacyService.evaluateRiskLegacy(...))
    .subscribeOn(Schedulers.boundedElastic())  // ← Thread pool aislado
```

`Schedulers.boundedElastic()` = pool de threads separado
- No bloquea event-loop (netty)
- Escalable para operaciones bloqueantes
- Threads bajo demanda

### 3. **Resiliencia: Circuit Breaker + Retry + TimeLimiter**

```yaml
resilience4j:
  circuitbreaker:
    risk-service:
      failure-rate-threshold: 50%
      wait-duration-in-open-state: 10s
  
  retry:
    risk-service:
      max-attempts: 3
  
  timelimiter:
    risk-service:
      timeout: 5s
```

**Flujo:**
1. Intenta evaluar riesgo remoto
2. Si timeout > 5s → cancela
3. Si falla 50% de intentos → Circuit OPEN
4. Circuit OPEN → fallback a legacy inmediato
5. Retry 3 veces con backoff

### 4. **Correlación X-Correlation-Id**

```java
// Controller: extrae o genera
String correlationId = exchange.getRequest()
    .getHeaders()
    .getFirst("X-Correlation-Id") 
    ?? UUID.randomUUID();

// MDC: propaga en logs
MDC.put("correlationId", correlationId);

// Reactor Context: propaga en cadena reactiva
.contextWrite(Context.of("correlationId", correlationId))
```

**En logs:**
```
[demo-001] Processing DEBIT transaction for account 001-0001
[demo-001] Risk evaluation: OK
[demo-001] Transaction completed successfully
```

### 5. **¿Por qué no Quarkus en 48h?**

- Tiempo insuficiente
- Benchmark justo requiere:
  - Mismas condiciones (threads, memory, GC)
  - Máquina aislada sin ruido
  - Múltiples runs para promedios
- Spring Boot WebFlux demuestra reactivity igual de bien

## Tests

```bash
# Ejecutar todos
gradle test

# Con cobertura
gradle test jacocoTestReport

# Verificar umbrales (70% líneas, 60% ramas)
gradle jacocoTestCoverageVerification
```

### Casos Cubiertos
- ✅ Transacción DEBIT exitosa
- ✅ Transacción CREDIT exitosa
- ✅ Fondos insuficientes
- ✅ Cuenta no encontrada
- ✅ Riesgo rechazado
- ✅ Validación (monto negativo, vacío)
- ✅ Correlación en logs

## Lo que NO implementé en 48h

| Feature | Por qué | Cómo completar |
|---|---|---|
| **Quarkus** | Tiempo + benchmark justo | Reimplementar con RESTEasy Reactive |
| **CI/CD** | Infrastructure setup | GitHub Actions + Docker Registry |
| **Kubernetes** | Setup cluster | Manifests YAML + APIM policy |
| **Benchmark** | Requiere aislamiento | JMH + wrk, mismas condiciones |
| **SSE completo** | Mock parcial | Integrar con event bus real |

## Cómo Explicar en Tech Connect

### Pregunta 1: "¿Qué ocurre si JPA/H2 se ejecuta en event-loop?"
> "Event-loop se bloquea en cada query SQL. Con 100 requests concurrentes, queuean esperando a que termine cada query. Con boundedElastic, cada request va a thread pool separado."

### Pregunta 2: "¿Cómo se propaga X-Correlation-Id?"
> "MDC.put() en controller, Reactor Context en cadena reactiva. Logs JSON incluyen correlationId. Puedo rastrear 1 request por todo el flujo."

### Pregunta 3: "¿Qué falla primero si risk-service es lento?"
> "TimeLimiter (5s) → si no responde, cancela. Retry 3x si timeout. Si 50% fallan → Circuit OPEN. Luego fallback a legacy en boundedElastic."

### Pregunta 4: "¿Cómo evitas desplegar imagen distinta a validada?"
> "CI/CD hashea imagen antes de push. Deploy compara hash. Si no coincide, rechaza."

### Pregunta 5: "¿Cuándo NO recomendarías migrar a Quarkus?"
> "Si no hay problema de startup (Lambda) o memory. Spring Boot WebFlux es más maduro. Costo de migración > beneficio."

## Logs y Debugging

```bash
# Ver logs en tiempo real
gradle bootRun | grep -E "ERROR|WARN|\[.*\]"

# Conectar Compass a MongoDB
# mongodb://admin:admin123@localhost:27017/bankx

# Curl con correlación visible
curl -v -H "X-Correlation-Id: test-123" http://localhost:8080/api/transactions
```

## Próximos Pasos

1. **Track D:** Reimplementar en Quarkus
2. **Track C:** CI/CD + Kubernetes
3. **Observabilidad:** ELK Stack / Prometheus
4. **Performance:** Benchmark con equal conditions
5. **Production:** Secrets, RBAC, rate limiting en APIM

---

**Autor:** Sandino | **Fecha:** 2026-10-02 | **Framework:** Spring Boot 3.4 + WebFlux
