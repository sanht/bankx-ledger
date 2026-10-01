# Guía de Kubernetes para bankx-ledger

Guía desde cero para entender cómo está desplegado este proyecto en Kubernetes,
qué hace cada archivo de la carpeta `k8s/` y cómo trabajar con él.

---

## 1. La idea en 1 minuto

Con **docker-compose** tú le dices a Docker: *"arranca estos contenedores"*. Si uno se cae, se queda caído.

Con **Kubernetes** tú le dices: *"quiero que siempre haya 2 copias de mi app y 1 MongoDB"*.
Kubernetes se encarga de que eso sea verdad **todo el tiempo**: si un contenedor se cae, crea otro;
si actualizas la versión, cambia las copias de una en una sin cortar el servicio.

A esto se le llama **modelo declarativo**: describes el *estado deseado* en archivos YAML
y Kubernetes trabaja continuamente para que el *estado real* coincida.

---

## 2. Las 3 piezas que necesitas

| Pieza | Qué es | Dónde está en tu máquina |
|---|---|---|
| **Cluster** | Las máquinas donde corre Kubernetes | Un contenedor Docker llamado `bankx-control-plane` (creado con **kind**) |
| **kubectl** | El programa para darle órdenes al cluster | `~/.local/bin/kubectl` |
| **kubeconfig** | Archivo que dice a kubectl a qué cluster conectarse | `~/.kube/config` (contexto `kind-bankx`) |

```
  tú ──► kubectl ──(lee ~/.kube/config)──► cluster "bankx" ──► tu app + MongoDB
```

**¿Y Docker?** Docker solo no es Kubernetes. Aquí Docker sirve de base: **kind** (*Kubernetes IN Docker*)
simula cada máquina del cluster con un contenedor. En producción el cluster sería real
(AWS EKS, Azure AKS, Google GKE), pero **los archivos YAML y los comandos kubectl son los mismos**.

Otras alternativas a kind para local: minikube, k3d, o Docker Desktop (Windows/Mac) con "Enable Kubernetes".

---

## 3. Vocabulario mínimo

Solo los conceptos que usa este proyecto:

| Concepto | Qué es | Analogía con docker-compose |
|---|---|---|
| **Pod** | La unidad mínima: uno o más contenedores que corren juntos. Cada réplica de tu app es un pod. | Un contenedor |
| **Deployment** | Mantiene N pods idénticos de una app *sin estado*. Si uno muere, crea otro. Gestiona las actualizaciones. | Un `service:` con `deploy.replicas` |
| **StatefulSet** | Como un Deployment pero para apps *con estado* (bases de datos): nombre fijo (`mongodb-0`) y disco propio que sobrevive al pod. | Un `service:` con `volumes:` |
| **Service** | Nombre DNS y dirección estable para llegar a unos pods. Los pods cambian de IP al recrearse; el Service no. | El nombre del servicio en la red de compose (`mongodb`) |
| **ConfigMap** | Configuración no sensible (variables, archivos). | `environment:` |
| **Secret** | Configuración sensible (contraseñas). | `environment:` con contraseñas / archivo `.env` |
| **PersistentVolumeClaim (PVC)** | Petición de disco persistente. | `volumes:` con nombre |
| **Namespace** | "Carpeta" que agrupa recursos. Todo lo nuestro vive en `bankx`. | Nombre del proyecto compose |
| **Probe** | Chequeo periódico que hace Kubernetes al contenedor para saber si está vivo y listo. | `healthcheck:` |

---

## 4. El mapa de este proyecto

```
                         tu navegador / curl
                                │
                         localhost:8081
                                │  (kind-config.yaml lo mapea al puerto 30080 del nodo)
                                ▼
┌──────────────────── cluster kind "bankx" ─── namespace "bankx" ────────────────────┐
│                                                                                    │
│   Service "bankx-ledger" (NodePort 30080)                                          │
│          │  reparte las peticiones entre las réplicas                              │
│          ├────────────────────┐                                                    │
│          ▼                    ▼                                                    │
│   ┌─────────────┐      ┌─────────────┐     Deployment "bankx-ledger" (2 réplicas)  │
│   │ pod app #1  │      │ pod app #2  │ ◄── config: ConfigMap "bankx-ledger-config" │
│   └──────┬──────┘      └──────┬──────┘     URI Mongo: Secret "mongodb-credentials" │
│          └─────────┬──────────┘                                                    │
│                    ▼  mongodb://...@mongodb:27017                                  │
│          Service "mongodb" (solo interno)                                          │
│                    ▼                                                               │
│            ┌──────────────┐               StatefulSet "mongodb"                    │
│            │  mongodb-0   │ ◄── usuario/clave: Secret "mongodb-credentials"        │
│            └──────┬───────┘     datos iniciales: ConfigMap "mongodb-init"          │
│                   ▼                                                                │
│            disco persistente (PVC 1Gi)                                             │
└────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Archivo por archivo

Todos están en `k8s/`. Cada archivo YAML describe uno o más **recursos**, y todos tienen la misma estructura base:

```yaml
apiVersion: ...   # versión de la API de Kubernetes para ese tipo de recurso
kind: ...         # tipo de recurso (Deployment, Service, Secret...)
metadata:
  name: ...       # nombre del recurso
  namespace: ...  # en qué "carpeta" vive
spec: ...         # lo que quieres (el estado deseado)
```

### 5.1 `namespace.yaml` — la carpeta

```yaml
kind: Namespace
metadata:
  name: bankx
```

Crea el namespace `bankx`. Todos los demás recursos llevan `namespace: bankx`.
Por eso en los comandos siempre pones `-n bankx`; sin eso, kubectl busca en `default` y no encuentra nada.

### 5.2 `mongodb-secret.yaml` — las contraseñas

```yaml
kind: Secret
stringData:
  MONGO_INITDB_ROOT_USERNAME: admin
  MONGO_INITDB_ROOT_PASSWORD: admin123
  SPRING_DATA_MONGODB_URI: mongodb://admin:admin123@mongodb:27017/bankx?authSource=admin
```

- Guarda el usuario y la clave de MongoDB, y la URI completa que usa la app.
- Fíjate en `@mongodb:27017`: **`mongodb` es el nombre del Service** de MongoDB. Dentro del cluster,
  cada Service es un nombre DNS. Es el equivalente a cuando en docker-compose usas el nombre del servicio.
- `SPRING_DATA_MONGODB_URI` es una variable de entorno que Spring Boot entiende automáticamente
  y que **sobrescribe** el `spring.data.mongodb.uri` de `application.yml` (que apunta a `localhost`).
- ⚠️ Este archivo está versionado solo porque es un entorno local de práctica. En un proyecto real
  las contraseñas no se suben al repositorio (se usa Sealed Secrets, Vault o `kubectl create secret`).

### 5.3 `mongodb.yaml` — la base de datos

Contiene **dos recursos** separados por `---`:

**a) Service `mongodb`** — el nombre interno para llegar a MongoDB:

```yaml
kind: Service
spec:
  selector:
    app: mongodb        # envía el tráfico a los pods con la etiqueta app=mongodb
  ports:
    - port: 27017
```

Es de tipo `ClusterIP` (el tipo por defecto): **solo accesible desde dentro del cluster**.
Tu app lo usa; tú desde fuera no (ver sección 8 si quieres verlo con Compass).

**b) StatefulSet `mongodb`** — el contenedor de MongoDB:

```yaml
kind: StatefulSet
spec:
  replicas: 1
  template:                        # plantilla del pod que se va a crear
    metadata:
      labels:
        app: mongodb               # la etiqueta que busca el Service
    spec:
      containers:
        - name: mongodb
          image: mongo:7.0         # misma imagen que en docker-compose
          env:                     # usuario/clave sacados del Secret
            - name: MONGO_INITDB_ROOT_USERNAME
              valueFrom:
                secretKeyRef:
                  name: mongodb-credentials
                  key: MONGO_INITDB_ROOT_USERNAME
          volumeMounts:
            - name: data                         # disco persistente
              mountPath: /data/db
            - name: init-script                  # mongo-init.js
              mountPath: /docker-entrypoint-initdb.d
          readinessProbe:                        # "¿ya acepta conexiones?"
            exec:
              command: ["mongosh", "--quiet", "--eval", "db.adminCommand('ping').ok"]
  volumeClaimTemplates:            # pide 1Gi de disco para este pod
    - metadata:
        name: data
      spec:
        resources:
          requests:
            storage: 1Gi
```

- **¿Por qué StatefulSet y no Deployment?** Porque una base de datos necesita que su disco
  le siga aunque el pod se recree. El StatefulSet le da un nombre fijo (`mongodb-0`) y su propio
  disco (`volumeClaimTemplates`). Si borras el pod, el nuevo `mongodb-0` monta el mismo disco
  y **los datos siguen ahí**.
- **Labels y selector:** así se conectan los recursos en Kubernetes. El Service no apunta a un pod
  por su nombre, sino a "todos los pods con `app: mongodb`". Este patrón se repite en todo el proyecto.
- `/docker-entrypoint-initdb.d` es la carpeta que la imagen oficial de Mongo ejecuta **la primera vez**
  que arranca con el disco vacío. Ahí se monta `mongo-init.js` (crea colecciones, índices y las 2 cuentas de prueba).
- `resources.requests` es lo que el pod reserva; `limits.memory` es el máximo antes de que Kubernetes lo mate.

### 5.4 `mongo-init.js` — datos iniciales

Copia exacta del `mongo-init.js` de la raíz (el que usa docker-compose). Está duplicado dentro de `k8s/`
porque kustomize, por seguridad, no permite leer archivos fuera de su carpeta.
Si cambias uno, cambia el otro.

### 5.5 `app-configmap.yaml` — configuración de la app

```yaml
kind: ConfigMap
data:
  RISK_SERVICE_URL: http://risk-service:9090
  JAVA_OPTS: -XX:MaxRAMPercentage=75.0
```

- `RISK_SERVICE_URL` sobrescribe la propiedad `risk.service.url` de `application.yml`
  (Spring convierte `RISK_SERVICE_URL` → `risk.service.url` automáticamente).
  Apunta a un servicio `risk-service` **que no existe** en el cluster a propósito: así el Circuit Breaker
  falla y se activa el **fallback al módulo legacy** (`usedFallback: true` en la respuesta).
- `JAVA_OPTS` la lee el `ENTRYPOINT` del Dockerfile: la JVM usa como máximo el 75% de la memoria del contenedor.
- Diferencia con el Secret: aquí va lo que **no** es sensible.

### 5.6 `app-deployment.yaml` — tu aplicación

El archivo más importante. Por partes:

```yaml
kind: Deployment
spec:
  replicas: 2                      # siempre 2 pods de la app
  selector:
    matchLabels:
      app: bankx-ledger            # "los pods que gestiono son los que tienen esta etiqueta"
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0            # durante una actualización nunca baja de 2 pods listos
      maxSurge: 1                  # puede crear 1 pod extra temporalmente
```

**Rolling update:** al desplegar una versión nueva, Kubernetes crea 1 pod nuevo, espera a que esté *listo*,
apaga 1 viejo, y repite. El servicio nunca se corta.

```yaml
      containers:
        - name: bankx-ledger
          image: bankx-ledger:local     # la imagen construida con el Dockerfile
          imagePullPolicy: IfNotPresent # no intenta descargarla de internet si ya la tiene
          ports:
            - name: http
              containerPort: 8080
          envFrom:
            - configMapRef:
                name: bankx-ledger-config     # TODAS las claves del ConfigMap como variables
          env:
            - name: SPRING_DATA_MONGODB_URI   # UNA clave concreta del Secret
              valueFrom:
                secretKeyRef:
                  name: mongodb-credentials
                  key: SPRING_DATA_MONGODB_URI
```

- `bankx-ledger:local` no está en ningún registro de internet: existe solo en tu Docker y se copia
  al cluster con `kind load docker-image`. Por eso `IfNotPresent`.
- En un entorno real la imagen sería `ghcr.io/<usuario>/bankx-ledger:<versión>`, la que publica el pipeline de GitHub Actions.

**Las probes** — cómo sabe Kubernetes si tu app está bien:

```yaml
          startupProbe:                         # 1) "¿ya terminó de arrancar?"
            httpGet:
              path: /actuator/health/liveness
              port: http
            periodSeconds: 5
            failureThreshold: 24                #    hasta 24 × 5s = 2 min de margen
          livenessProbe:                        # 2) "¿sigue vivo?" → si falla, REINICIA el pod
            httpGet:
              path: /actuator/health/liveness
          readinessProbe:                       # 3) "¿puede atender peticiones?" → si falla,
            httpGet:                            #    lo SACA del Service (no lo reinicia)
              path: /actuator/health/readiness
```

| Probe | Pregunta | Si falla... |
|---|---|---|
| startup | ¿Ya arrancó? | Sigue esperando (hasta 2 min). Mientras tanto, las otras probes no se ejecutan. |
| liveness | ¿Está colgado? | Reinicia el contenedor. |
| readiness | ¿Puede atender? | Deja de enviarle tráfico hasta que se recupere. |

Los endpoints `/actuator/health/liveness` y `/readiness` los da Spring Boot Actuator;
se activaron con `management.endpoint.health.probes.enabled: true` en `application.yml`.

```yaml
          resources:
            requests:
              cpu: 250m          # reserva 1/4 de CPU
              memory: 384Mi
            limits:
              memory: 768Mi      # si pasa de aquí, Kubernetes mata el pod (OOMKilled)
```

### 5.7 `app-service.yaml` — la puerta de entrada a la app

```yaml
kind: Service
spec:
  type: NodePort
  selector:
    app: bankx-ledger        # reparte entre los 2 pods de la app
  ports:
    - port: 8080             # puerto del Service dentro del cluster
      targetPort: http       # puerto del contenedor (el llamado "http" = 8080)
      nodePort: 30080        # puerto abierto en el nodo del cluster
```

- Un Service de tipo **NodePort** abre un puerto (30080) en cada nodo del cluster para entrar desde fuera.
- Además hace de **balanceador**: cada petición va a uno de los 2 pods.
- En producción normalmente se usaría un `Ingress` o un `LoadBalancer` en lugar de NodePort.

### 5.8 `kind-config.yaml` — configuración del cluster local

```yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 30080   # el NodePort del Service...
        hostPort: 8081         # ...se publica en localhost:8081 de tu PC
```

- **No es un recurso de Kubernetes**: es configuración de la herramienta **kind**, y solo se usa
  al crear el cluster (`kind create cluster --config ...`). No se aplica con kubectl.
- Recuerda que el "nodo" de kind es un contenedor Docker; esto equivale a un `-p 8081:30080` en `docker run`.
- Se eligió 8081 para no chocar con tu app en local (`bootRun`), que usa el 8080.

Camino completo de una petición:
```
curl localhost:8081 → contenedor kind :30080 → Service bankx-ledger :8080 → uno de los pods :8080
```

### 5.9 `kustomization.yaml` — el índice

```yaml
kind: Kustomization
resources:                    # lista de archivos que forman el despliegue
  - namespace.yaml
  - mongodb-secret.yaml
  - mongodb.yaml
  - app-configmap.yaml
  - app-deployment.yaml
  - app-service.yaml
configMapGenerator:           # crea el ConfigMap "mongodb-init" a partir de mongo-init.js
  - name: mongodb-init
    namespace: bankx
    files:
      - init-mongo.js=mongo-init.js
generatorOptions:
  disableNameSuffixHash: true # nombre fijo "mongodb-init" (sin sufijo aleatorio)
```

- **Kustomize** viene incluido en kubectl. Gracias a este archivo puedes desplegar todo con
  **un solo comando**: `kubectl apply -k k8s` (`-k` = "usa la kustomization de esta carpeta").
- `configMapGenerator` evita escribir el contenido del `.js` dentro de un YAML: lo lee del archivo.
- Para ver el YAML final que se envía al cluster: `kubectl kustomize k8s`

---

## 6. Qué pasa cuando ejecutas `kubectl apply -k k8s`

1. kubectl junta todos los archivos (kustomize) y los envía al cluster.
2. Se crean el namespace, el Secret y los ConfigMaps.
3. El StatefulSet crea el pod `mongodb-0`; Kubernetes le asigna un disco de 1Gi.
   Mongo arranca, ve el disco vacío y ejecuta `mongo-init.js`.
4. El Deployment crea 2 pods de la app (nombres tipo `bankx-ledger-789cc45575-6kgxc`).
   Cada uno arranca Spring Boot y se conecta a `mongodb:27017`.
5. Cuando la `readinessProbe` responde OK, el Service empieza a enviarle tráfico.
6. Ya puedes llamar a `localhost:8081`.

`apply` es **idempotente**: si lo ejecutas otra vez sin cambios, no pasa nada.
Si cambiaste algo (p. ej. `replicas: 3`), solo aplica la diferencia.

---

## 7. Paso a paso desde cero

Útil para practicar: borra todo y recréalo tú mismo.

```bash
cd bankx-ledger-devops

# 0. (Opcional) Borrar el cluster actual para empezar limpio
kind delete cluster --name bankx

# 1. Crear el cluster (≈1 min). Crea el contenedor "bankx-control-plane"
#    y configura ~/.kube/config para que kubectl apunte a él
kind create cluster --name bankx --config k8s/kind-config.yaml
kubectl get nodes                       # debe salir 1 nodo "Ready"

# 2. Construir la imagen de la app con el Dockerfile
docker build -t bankx-ledger:local .

# 3. Copiar la imagen dentro del cluster (no ve las imágenes de tu Docker)
kind load docker-image bankx-ledger:local --name bankx

# 4. Desplegar todo
kubectl apply -k k8s

# 5. Esperar a que esté listo
kubectl -n bankx rollout status statefulset/mongodb
kubectl -n bankx rollout status deployment/bankx-ledger
kubectl -n bankx get pods               # 3 pods en Running y READY 1/1

# 6. Probar
curl localhost:8081/actuator/health/readiness
curl -X POST localhost:8081/api/transactions \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: demo-1' \
  -d '{"accountNumber":"001-0001","type":"DEBIT","amount":100}'
```

**Cuando cambias código de la app:** repite los pasos 2 y 3, y luego reinicia los pods para que usen la imagen nueva:
```bash
kubectl -n bankx rollout restart deployment/bankx-ledger
```

**Cuando cambias un YAML:** solo `kubectl apply -k k8s`.

**Si reinicias el PC:** el cluster queda parado. `docker start bankx-control-plane` y en ~1 min vuelve todo.

---

## 8. Chuleta de comandos

```bash
# Ver
kubectl -n bankx get pods                         # pods y su estado
kubectl -n bankx get pods -o wide                 # + IP y nodo
kubectl -n bankx get all                          # pods, services, deployments...
kubectl -n bankx describe pod <pod>               # detalle + EVENTOS (lo primero a mirar si algo falla)

# Logs
kubectl -n bankx logs <pod>                       # logs de un pod
kubectl -n bankx logs -l app=bankx-ledger -f --prefix   # logs de todas las réplicas en vivo, con el nombre del pod
kubectl -n bankx logs <pod> --previous            # logs del contenedor anterior (si se reinició)

# Entrar
kubectl -n bankx exec -it mongodb-0 -- mongosh -u admin -p admin123 --authenticationDatabase admin   # shell de Mongo
kubectl -n bankx exec -it <pod-app> -- sh                             # shell en la app

# Acceder desde tu PC a algo interno (p. ej. Mongo del cluster con Compass)
kubectl -n bankx port-forward svc/mongodb 27018:27017
#   → en Compass: mongodb://admin:admin123@localhost:27018/?authSource=admin
#   (27018 para no chocar con el Mongo de docker-compose en 27017)

# Cambiar
kubectl -n bankx scale deployment bankx-ledger --replicas=3
kubectl -n bankx rollout restart deployment/bankx-ledger
kubectl -n bankx delete pod <pod>                 # Kubernetes crea otro solo

# Borrar
kubectl delete -k k8s                             # borra todo lo desplegado (el cluster sigue)
kind delete cluster --name bankx                  # borra el cluster entero
```

---

## 9. Ejercicios para entenderlo (y para la demo)

1. **Auto-recuperación:** `kubectl -n bankx delete pod <un-pod-de-la-app>` y enseguida
   `kubectl -n bankx get pods`. Verás uno `Terminating` y otro nuevo `ContainerCreating` → `Running`.
2. **Escalado:** cambia `replicas: 2` a `3` en `app-deployment.yaml`, `kubectl apply -k k8s`, y mira los pods.
3. **Balanceo:** con `kubectl -n bankx logs -l app=bankx-ledger -f --prefix` abierto, lanza varias transacciones:
   verás que las atienden pods distintos (`--prefix` antepone a cada línea el nombre del pod).
4. **Persistencia:** haz una transacción, `kubectl -n bankx delete pod mongodb-0`, espera a que vuelva
   y consulta el saldo: el dato sigue ahí porque el disco es del StatefulSet, no del pod.
   ```bash
   kubectl -n bankx exec mongodb-0 -- mongosh -u admin -p admin123 --authenticationDatabase admin --quiet \
     --eval 'db.getSiblingDB("bankx").accounts.find({},{_id:0,accountNumber:1,balance:1}).toArray()'
   ```
5. **Resiliencia:** en los logs busca `falling back to legacy`: el servicio de riesgo no existe,
   el Circuit Breaker lo detecta y responde el módulo legacy.
6. **Configuración:** en `app-configmap.yaml` cambia `RISK_SERVICE_URL`, aplica y haz
   `kubectl -n bankx rollout restart deployment/bankx-ledger` (los pods leen las variables al arrancar).

---

## 10. Si algo falla

| Estado del pod | Qué significa | Qué mirar |
|---|---|---|
| `Pending` | No hay dónde ubicarlo (recursos o disco) | `kubectl -n bankx describe pod <pod>` → sección *Events* |
| `ErrImagePull` / `ImagePullBackOff` | No encuentra la imagen | ¿Hiciste `kind load docker-image bankx-ledger:local --name bankx`? |
| `CrashLoopBackOff` | El contenedor arranca y se cae una y otra vez | `kubectl -n bankx logs <pod> --previous` |
| `Running` pero `READY 0/1` | Arrancó pero la readinessProbe falla | Logs de la app; ¿está `mongodb-0` en Running? |
| `OOMKilled` (en describe) | Superó el `limits.memory` | Subir el límite en el Deployment |

Otros problemas típicos:
- **`curl localhost:8081` no responde:** ¿el cluster está encendido? `docker ps | grep bankx-control-plane`.
- **kubectl "connection refused":** el cluster está parado → `docker start bankx-control-plane`.
- **kubectl no encuentra nada:** te falta `-n bankx`.
- **Cambié el código y no se refleja:** faltó `docker build` + `kind load` + `rollout restart`.

---

## 11. docker-compose vs Kubernetes en este proyecto

| | docker-compose | Kubernetes (k8s/) |
|---|---|---|
| Qué levanta | Solo MongoDB | MongoDB **y** la app (2 réplicas) |
| La app corre | En tu PC con `bootRun` / IntelliJ (puerto 8080) | Dentro del cluster (puerto 8081) |
| Mongo | `localhost:27017` | `mongodb:27017` dentro del cluster (desde fuera, con port-forward) |
| Si se cae un contenedor | Se queda caído | Se recrea solo |
| Actualizar versión | Parar y arrancar | Rolling update sin cortes |
| Uso | Desarrollo diario | Simular cómo se desplegaría en producción |

Son **dos entornos independientes**: cada uno tiene su propio MongoDB con sus propios datos.

---

## 12. ¿Y el CI/CD?

El pipeline de `.github/workflows/ci.yml` **no toca ningún cluster**: compila, ejecuta tests,
verifica cobertura y publica la imagen en GitHub Container Registry.

El paso siguiente (CD, *Continuous Deployment*) sería que, tras publicar la imagen, el pipeline
la despliegue en un cluster real (EKS/AKS/GKE) cambiando la línea `image:` del Deployment.
No está implementado porque requiere un cluster en la nube; en la presentación es el "próximo paso".
