# Guía 48h: Miércoles a Sábado

## MIÉRCOLES (hoy) - 6-8 horas

### Fase 1: Setup Inicial (30 min)

```bash
# 1. Descargar archivos generados
cp -r /mnt/user-data/bankx-ledger ~/tu-workspace/

# 2. Verificar estructura
cd ~/tu-workspace/bankx-ledger
ls -la
tree -L 2

# 3. Levantar Docker
docker-compose up -d
docker ps  # Verificar MongoDB corriendo
```

### Fase 2: Compilación Inicial (30 min)

```bash
# 1. Compilar
gradle clean build

# Si hay errores de imports:
# - Verifica que los archivos .java estén en src/main/java/com/bankx/
# - Rebuild: gradle clean build --refresh-dependencies

# 2. Test compilation
gradle test
```

### Fase 3: Levantar el Servicio (15 min)

```bash
gradle bootRun
# Debe imprimir:
# Started BankxLedgerApplication in X.XXX seconds
```

### Fase 4: Pruebas Manuales (30 min)

En otra terminal:

```bash
# 1. Health
curl http://localhost:8080/api/health
# Esperado: {"status":"UP"}

# 2. Transacción exitosa DEBIT
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: test-001" \
  -d '{
    "accountNumber": "001-0001",
    "type": "DEBIT",
    "amount": 100
  }'

# Esperado: 200 OK con transactionId + newBalance

# 3. Error: cuenta no existe
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "accountNumber": "999-9999",
    "type": "DEBIT",
    "amount": 100
  }'

# Esperado: 422 {"error":"account_not_found"}

# 4. Error: fondos insuficientes
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "accountNumber": "001-0001",
    "type": "DEBIT",
    "amount": 5000
  }'

# Esperado: 422 {"error":"insufficient_funds"}
```

### Fase 5: Revisar Logs (30 min)

```bash
# Buscar en logs:
# - [test-001] correlationId propagado
# - Risk evaluation: OK
# - Account validation passed
# - Balance updated

grep "test-001" logs/
```

### Checklist Miércoles
- [ ] Docker running (MongoDB)
- [ ] Gradle build successful
- [ ] Servicio levanta sin errores
- [ ] POST /api/transactions → 200 OK
- [ ] Error account_not_found → 422
- [ ] Error insufficient_funds → 422
- [ ] Health check → 200 UP
- [ ] Correlación en logs

---

## JUEVES - 6-8 horas

### Fase 1: Revisar y Mejorar Tests (2 horas)

```bash
# 1. Ejecutar tests
gradle test

# 2. Ver cobertura
gradle jacocoTestReport
# Abrir: build/reports/jacoco/test/html/index.html

# 3. Si coverage < 70%:
# Agregar más tests en src/test/java/com/bankx/service/
# Casos: timeout, fallback, validación vacía, etc.
```

### Fase 2: Agregar Tests Faltantes (2 horas)

Crear `src/test/java/com/bankx/controller/TransactionControllerTest.java`:

```java
@WebFluxTest
@ExtendWith(MockitoExtension.class)
public class TransactionControllerTest {
    
    @MockBean
    private TransactionService transactionService;
    
    private WebTestClient webTestClient;
    
    @BeforeEach
    void setUp(WebApplicationContext context) {
        webTestClient = WebTestClient.bindToApplicationContext(context).build();
    }
    
    @Test
    void testCreateTransaction_Success() {
        TransactionRequest request = TransactionRequest.builder()
            .accountNumber("001-0001")
            .type("DEBIT")
            .amount(new BigDecimal("100"))
            .build();
        
        TransactionResponse response = TransactionResponse.builder()
            .transactionId("tx-1")
            .accountNumber("001-0001")
            .status("OK")
            .newBalance(new BigDecimal("900"))
            .build();
        
        when(transactionService.processTransaction(any()))
            .thenReturn(Mono.just(response));
        
        webTestClient.post()
            .uri("/api/transactions")
            .header("X-Correlation-Id", "test-123")
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.transactionId").isEqualTo("tx-1")
            .jsonPath("$.status").isEqualTo("OK");
    }
}
```

### Fase 3: Resiliencia y Observabilidad (2 horas)

#### 3.1 Mejorar Logs JSON

Crear `src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>

    <logger name="com.bankx" level="DEBUG"/>
</configuration>
```

#### 3.2 Verificar Resilience4j Metrics

```bash
# En http://localhost:8080/actuator/metrics
curl http://localhost:8080/actuator/metrics | grep resilience4j

# Debe mostrar:
# resilience4j_circuitbreaker_state
# resilience4j_retry_calls
# resilience4j_timelimiter_duration
```

### Fase 4: Validaciones Faltantes (2 horas)

En `TransactionRequest`, agregar validaciones:

```java
@NotBlank(message = "type must be DEBIT or CREDIT")
@Pattern(regexp = "^(DEBIT|CREDIT)$", message = "type must be DEBIT or CREDIT")
private String type;

@DecimalMin(value = "0.01", message = "amount must be > 0")
private BigDecimal amount;
```

En test:

```java
@Test
void testCreateTransaction_InvalidAmount() {
    webTestClient.post()
        .uri("/api/transactions")
        .bodyValue(TransactionRequest.builder()
            .accountNumber("001-0001")
            .type("DEBIT")
            .amount(BigDecimal.valueOf(-100)) // ← Invalid
            .build())
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.error").isEqualTo("validation_error");
}
```

### Checklist Jueves
- [ ] `gradle test` → all pass
- [ ] Coverage ≥ 70% líneas
- [ ] Logback configurado
- [ ] Resilience4j metrics expuestos
- [ ] Validaciones en DTOs
- [ ] TransactionControllerTest creado
- [ ] `gradle check` → no violations

---

## VIERNES - 5-6 horas

### Fase 1: Preparar README y Documentación (2 horas)

```bash
# El README.md ya está, pero verifica:
# - Comandos curl funcionan
# - Secciones técnicas claras
# - Decisiones explicadas

# Agrega al README sección "Demostración Sábado":
```

Agrega al final de README.md:

```markdown
## Demostración para Tech Connect

### Demo 1: Flujo Exitoso (3 min)
1. `curl health` → UP
2. `curl POST transacción DEBIT` → OK
3. Mostrar logs con correlación

### Demo 2: Resiliencia (2 min)
1. `curl POST con simulate=fail`
2. Mostrar fallback al legado en logs
3. Mostrar Circuit Breaker state en /actuator/metrics

### Demo 3: Errores Homogéneos (2 min)
1. `curl POST cuenta inexistente` → 422
2. `curl POST fondos insuficientes` → 422
3. Mostrar estructura JSON error

### Demo 4: Correlación (1 min)
1. `curl con X-Correlation-Id: demo-xyz`
2. Mostrar 5+ logs con mismo correlationId
```

### Fase 2: Preparar Presentación (2 horas)

Crear `PRESENTACION.md`:

```markdown
# Tech Connect - BankX Reactive Ledger

## Título
Spring Boot WebFlux + MongoDB Reactivo + Resilience4j

## Problema
- BankX necesita microservicio de transacciones en tiempo real
- Servicio remoto de riesgo puede fallar/timeout
- Necesita trazabilidad por petición
- Debe escalar sin bloquear

## Solución
- **WebFlux:** Event-loop sin bloqueo
- **MongoDB reactivo:** BD reactiva
- **Resilience4j:** Circuit Breaker + Retry + TimeLimiter
- **boundedElastic:** Aislamiento del legacy bloqueante
- **MDC + Context:** Propagación de correlación

## Decisiones Clave
1. **¿Por qué WebFlux?**
   - Event-loop única
   - M de requests con pocos threads
   - Reactivo por naturaleza

2. **¿Bloqueo dónde?**
   - Legacy JPA en boundedElastic
   - Event-loop libre para otras requests

3. **¿Resiliencia cómo?**
   - Circuit abre si 50% fallan
   - Timeout 5s → fallback inmediato
   - Retry 3 veces

4. **¿Correlación cómo?**
   - MDC.put() en controller
   - Reactor Context en cadena
   - Visible en todos los logs

## Resultados
- ✅ Transacciones exitosas
- ✅ Errores homogéneos
- ✅ Resiliencia demostrable
- ✅ Correlación rastreable
- ✅ Tests with StepVerifier

## No hice (tiempo)
- ❌ Quarkus (benchmark requiere aislamiento)
- ❌ Kubernetes (manifests listos, deployment en local)
- ❌ CI/CD (GitHub Actions definido, no ejecutado)

## Próximos pasos
1. Track D: Quarkus
2. Track C: K8s + APIM
3. Benchmark: igual condiciones
```

### Fase 3: Organizar Evidencias (1 hora)

```bash
# 1. Código
mkdir -p evidencias/codigo
find src/main -name "*.java" -type f | head -5 | xargs cp -t evidencias/codigo

# 2. Tests
cp src/test/java/com/bankx/service/TransactionServiceTest.java evidencias/

# 3. Logs
mkdir -p evidencias/logs
gradle bootRun > evidencias/logs/startup.log 2>&1 &
# (déjalo correr 10 seg)
kill %1

# 4. Screenshots de curl
cat > evidencias/DEMO_COMMANDS.sh << 'EOF'
#!/bin/bash

echo "=== Health Check ==="
curl http://localhost:8080/api/health

echo -e "\n=== Transacción OK ==="
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: demo-001" \
  -d '{"accountNumber":"001-0001","type":"DEBIT","amount":100}'

echo -e "\n=== Error: Cuenta no existe ==="
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"accountNumber":"999-9999","type":"DEBIT","amount":100}'

echo -e "\n=== Logs con Correlación ==="
tail -20 logs/application.log | grep "demo-"
EOF

chmod +x evidencias/DEMO_COMMANDS.sh
```

### Checklist Viernes
- [ ] README.md actualizado con demo
- [ ] PRESENTACION.md creado
- [ ] Carpeta evidencias/ con código y logs
- [ ] DEMO_COMMANDS.sh listo para ejecutar
- [ ] Puedo explicar 5 decisiones técnicas
- [ ] Build pasa: `gradle clean build`

---

## SÁBADO (antes del taller) - 2 horas

### 30 min: Setup Final

```bash
# 1. Borrar artefactos viejos
gradle clean

# 2. Build fresco
gradle build

# 3. Levantar servicios
docker-compose up -d
sleep 3

# 4. Verificar MongoDB
mongosh "mongodb://admin:admin123@localhost:27017" --eval "db.adminCommand('ping')"

# 5. Levantar app
gradle bootRun &
sleep 10
```

### 30 min: Demo Scripts

```bash
#!/bin/bash
# DEMO_COMPLETA.sh

set -e

echo "=== BankX Reactive Ledger Demo ==="
echo ""

# 1. Health
echo "1. Health Check"
curl -s http://localhost:8080/api/health | jq .
echo ""

# 2. Transacción OK
echo "2. Transacción DEBIT OK"
curl -s -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: demo-debit-001" \
  -d '{
    "accountNumber": "001-0001",
    "type": "DEBIT",
    "amount": 100
  }' | jq .
echo ""

# 3. Error: fondos insuficientes
echo "3. Error: Fondos Insuficientes"
curl -s -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: demo-error-001" \
  -d '{
    "accountNumber": "001-0001",
    "type": "DEBIT",
    "amount": 5000
  }' | jq .
echo ""

# 4. Error: cuenta no existe
echo "4. Error: Cuenta No Existe"
curl -s -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: demo-error-002" \
  -d '{
    "accountNumber": "999-9999",
    "type": "CREDIT",
    "amount": 100
  }' | jq .
echo ""

echo "=== Demo Completa ==="
```

### 30 min: Ensayo Mental

**Responde estas 5 preguntas en voz alta (como si estuvieras en el taller):**

1. **"¿Qué ocurre si JPA/H2 se ejecuta en event-loop?"**
   - ❌ Malo: "WebFlux lo maneja automático"
   - ✅ Bueno: "Event-loop se bloquea. Con boundedElastic, cada operación JPA va a thread pool separado. Puedo demostrarlo en logs viendo Thread: elastic-1 vs Thread: main"

2. **"¿Cómo se propaga X-Correlation-Id?"**
   - ❌ Malo: "Está en el header"
   - ✅ Bueno: "MDC.put() en controller, luego Reactor Context en cadena reactiva. MDC garantiza que todo log dentro del scope tenga correlationId. Puedo filtrar: grep 'demo-001' app.log"

3. **"¿Qué falla primero cuando risk-service es lento?"**
   - ❌ Malo: "Circuit Breaker lo maneja"
   - ✅ Bueno: "TimeLimiter (5s timeout) cancela primero. Si fallan requests, Retry 3x. Si 50% continúan fallando, Circuit abre y fallback al legacy en boundedElastic. Puedo simular con simulate=timeout"

4. **"¿Dónde está el bloqueo? ¿Cómo lo aislaste?"**
   - ❌ Malo: "No hay bloqueo en WebFlux"
   - ✅ Bueno: "JPA y legacy tienen bloqueo. LegacyService.evaluateRiskLegacy() tiene Thread.sleep(). Lo aislé: Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic()). Thread pool separado = event-loop libre"

5. **"¿Cuándo NO recomendarías migrar a Quarkus?"**
   - ❌ Malo: "Quarkus es siempre mejor"
   - ✅ Bueno: "Si no hay problema de startup o memory footprint. Spring Boot WebFlux es más maduro, comunidad más grande. Benchmark justo requeriría condiciones iguales. Costo migración > beneficio en este caso"

### Checklist Sábado
- [ ] Docker corriendo
- [ ] App en bootRun
- [ ] Health → UP
- [ ] Demo script ejecuta sin errores
- [ ] Puedo explicar 5 decisiones
- [ ] Repo limpio y pushado (si aplica)
- [ ] Presentación en mente

---

## Tips Generales

### Si se te traba algo:

```bash
# Limpiar todo
docker-compose down -v
gradle clean
rm -rf build/

# Rebuild
gradle build

# Levantar de nuevo
docker-compose up -d
gradle bootRun
```

### Debugging rápido:

```bash
# Ver qué requests entran
tail -f logs/application.log | grep -E "POST|GET|ERROR"

# Ver estado de resilience4j
curl http://localhost:8080/actuator/metrics | grep resilience4j

# Ver cuentas en MongoDB
mongosh 'mongodb://admin:admin123@localhost:27017/bankx' \
  --eval "db.accounts.find().pretty()"
```

### Si algo no compila:

1. Verifica que los archivos `.java` estén en `src/main/java/com/bankx/`
2. `gradle clean` + `gradle build --refresh-dependencies`
3. En IntelliJ: Invalidate Caches → Restart

---

**Objetivo:** El sábado entras con código funcionando, demos listas, y respuestas claras a las 5 preguntas técnicas. Good luck!
