# CI/CD y Kubernetes

## Estructura

```
Dockerfile                  Imagen multi-stage (JDK 17 para compilar, JRE 17 para ejecutar, usuario no root)
.github/workflows/ci.yml    Pipeline de GitHub Actions
k8s/                        Manifests de Kubernetes (kustomize)
  namespace.yaml            Namespace "bankx"
  mongodb-secret.yaml       Credenciales de MongoDB y URI de conexión
  mongodb.yaml              StatefulSet + Service de MongoDB con volumen persistente
  mongo-init.js             Script de datos iniciales (copia del de docker-compose)
  app-configmap.yaml        Configuración de la app (URL del servicio de riesgo, JAVA_OPTS)
  app-deployment.yaml       Deployment con 2 réplicas, probes y rolling update sin downtime
  app-service.yaml          Service NodePort (30080)
  kind-config.yaml          Cluster local con kind, expone la app en localhost:8081
  kustomization.yaml        Punto de entrada: kubectl apply -k k8s
```

## Pipeline CI (GitHub Actions)

Se ejecuta en cada push y pull request a `main`:

1. **build-test**: `./gradlew build` → compila, tests, checkstyle y verificación de cobertura (Jacoco).
   Publica los reportes como artefacto descargable.
2. **docker**: construye la imagen. En push a `main` la sube a GitHub Container Registry
   (`ghcr.io/<usuario>/bankx-ledger`) con tag `sha-<commit>` y `latest`.

Para ejecutarlo basta con subir el proyecto a un repositorio de GitHub; no requiere secretos
adicionales (usa el `GITHUB_TOKEN` automático).

## Despliegue local con kind

Requisitos: Docker, [kind](https://kind.sigs.k8s.io/) y kubectl.

```bash
# 1. Crear el cluster
kind create cluster --name bankx --config k8s/kind-config.yaml

# 2. Construir la imagen y cargarla en el cluster
docker build -t bankx-ledger:local .
kind load docker-image bankx-ledger:local --name bankx

# 3. Desplegar
kubectl apply -k k8s
kubectl -n bankx rollout status deployment/bankx-ledger

# 4. Probar
curl localhost:8081/actuator/health/readiness
curl -X POST localhost:8081/api/transactions \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: demo-1' \
  -d '{"accountNumber":"001-0001","type":"DEBIT","amount":100}'

# Logs de las 2 réplicas
kubectl -n bankx logs -l app=bankx-ledger -f
```

Limpieza: `kind delete cluster --name bankx`

## Decisiones

- **Probes**: `startupProbe` da hasta 2 min de arranque sin que la liveness reinicie el pod;
  `readinessProbe` saca el pod del Service si no puede atender (p. ej. sin MongoDB).
- **Rolling update** con `maxUnavailable: 0`: siempre hay réplicas atendiendo durante un despliegue.
- **Secret vs ConfigMap**: credenciales en Secret, configuración no sensible en ConfigMap.
  El Secret versionado es solo para entorno local; en producción se usaría Sealed Secrets o Vault.
- **Servicio de riesgo**: su URL es configurable (`RISK_SERVICE_URL`). En el cluster no existe,
  así que el Circuit Breaker activa el fallback al módulo legacy (`usedFallback: true`),
  lo que demuestra la resiliencia en un entorno real.
