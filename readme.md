# Helm for Java Devs — Schritt-für-Schritt-Anleitung

Eine vollständige Kubernetes Pipeline für eine Spring Boot App in einem Container.
Spring Boot Helloworld App → Tekton baut das Image → lokale k3s-Registry → ArgoCD deployt per Helm.

Verwendet ConfigMap, Secrets, SSL Certificate, Istio Gateway, VirtualService, HealthChecks, Readness Checks.
ServiceEntry und DestinationRule für eine externe Datenbankabhängigkeit.
Hat ein Feature Flag für den Wechsel von h2 auf MySQL.

## Voraussetzungen

Folgendes muss im k3s-Cluster installiert sein:

| Komponente | Zweck |
|---|---|
| [Tekton Pipelines](https://tekton.dev/docs/installation/pipelines/) | CI-Pipeline |
| [ArgoCD](https://argo-cd.readthedocs.io/en/stable/getting_started/) | GitOps-Deployment |
| [cert-manager](https://cert-manager.io/docs/installation/) | TLS-Zertifikate |
| [Istio](https://istio.io/latest/docs/setup/install/) | Ingress Gateway |
| Registry (z.B. `registry:2`) | Lokales Image-Repository im `kube-system` Namespace |

---

## Schritt 1 — Lokale Registry in k3s konfigurieren

Damit k3s Images aus der lokalen Registry ziehen kann, muss die Mirror-Konfiguration gesetzt werden:

```yaml
# /etc/rancher/k3s/registries.yaml
mirrors:
  "registry.registry.svc.cluster.local:5000":
    endpoint:
      - "http://registry.registry.svc.cluster.local:5000"
configs:
  "registry.registry.svc.cluster.local:5000":
    tls:
      insecure_skip_verify: true
```

k3s neu starten:

```bash
sudo systemctl restart k3s
```

---

## Schritt 2 — Tekton-Ressourcen installieren

### 2.1 git-clone Task aus dem Tekton-Katalog installieren

```bash
kubectl apply -f tekton/git-clone.yaml
```

### 2.2 Eigene Tasks und Pipeline installieren

```bash
kubectl apply -f tekton/serviceaccount.yaml
kubectl apply -f tekton/task-maven-build.yml
kubectl apply -f tekton/task-kaniko.yml
kubectl apply -f tekton/pipeline.yml
```

---

## Schritt 3 — Pipeline starten

```bash
kubectl create -f tekton/pipeline-run.yml
```

> `kubectl create` statt `apply`, damit `generateName` eine eindeutige Run-ID vergibt.

Logs verfolgen (Name des Runs ermitteln und dann):

```bash
kubectl get pipelinerun
kubectl logs -l tekton.dev/pipeline=helloworld-pipeline --all-containers -f
```

Oder mit der Tekton CLI:

```bash
tkn pipelinerun logs --last -f
```

Der Run durchläuft drei Schritte:
1. **clone** — Repository auschecken
2. **build-jar** — Maven-Build mit Spring Boot AOT (`compile → process-aot → package`)
3. **docker-push** — Kaniko baut das Docker-Image und pusht nach `registry.registry.svc.cluster.local:5000/helloworld:latest`

---

## Schritt 4 — Image in der Registry prüfen

```bash
kubectl run registry-check --image=curlimages/curl --restart=Never --rm -it -- \
  curl http://registry.registry.svc.cluster.local:5000/v2/helloworld/tags/list
```

Erwartet: `{"name":"helloworld","tags":["latest"]}`

---

## Schritt 5 — Namespace und ArgoCD vorbereiten

```bash
# Namespace mit Istio-Sidecar-Injection anlegen
kubectl apply -f argocd/hello-namespace.yml

# Cluster-Secret in ArgoCD registrieren
kubectl apply -f argocd/cluster-hello.yml

# ArgoCD-Projekt anlegen
kubectl apply -f argocd/helloteam-project.yaml

# ArgoCD-Application anlegen
kubectl apply -f argocd/app-helloworld.yml
```

---

## Schritt 6 — ArgoCD-Sync auslösen

ArgoCD erkennt Änderungen im Git-Repository automatisch (auto-sync ist aktiv).

Für einen manuellen Sync zuerst einloggen:

```bash
# Admin-Passwort auslesen
kubectl get secret argocd-initial-admin-secret -n argocd \
  -o jsonpath='{.data.password}' | base64 -d

# Einloggen
argocd login argocd.gmk.lan --username admin
# oder
argocd login argocd.tp.lan --username admin

# Sync auslösen
argocd app sync helloworld
```

Alternativ direkt im ArgoCD-UI: https://argocd.gmk.lan oder https://argocd.tp.lan → Projekt `helloteam` → Application `helloworld`.

---

## Schritt 7 — Deployment prüfen

```bash
kubectl get pods -n helloworld
kubectl get certificate -n helloworld
kubectl get gateway -n helloworld
```

App über den Istio-Ingress erreichbar unter `https://helloworld.gmk.lan` oder `https://helloworld.tp.lan`.

Health-Endpoint prüfen:

```bash
curl https://helloworld.gmk.lan/actuator/health
curl https://helloworld.tp.lan/actuator/health
```

Liveness- und Readiness-Status einzeln abrufen:

```bash
curl https://helloworld.gmk.lan/actuator/health/liveness
curl https://helloworld.gmk.lan/actuator/health/readiness
```

### Probe-Status manuell steuern

Der `ProbeController` erlaubt es, den Liveness- und Readiness-Zustand per GET-Request zu setzen — nützlich zum Testen der Kubernetes-Probes ohne Neustart:

| Endpunkt | Effekt | Actuator-Status |
|---|---|---|
| `GET /control/health/ok` | Liveness → CORRECT | `/actuator/health/liveness` → UP |
| `GET /control/health/notok` | Liveness → BROKEN | `/actuator/health/liveness` → DOWN |
| `GET /control/ready/ok` | Readiness → ACCEPTING_TRAFFIC | `/actuator/health/readiness` → UP |
| `GET /control/ready/notok` | Readiness → REFUSING_TRAFFIC | `/actuator/health/readiness` → DOWN |

```bash
# Liveness auf "not ok" setzen → Kubernetes startet den Pod neu
curl https://helloworld.gmk.lan/control/health/notok

# Readiness auf "not ok" setzen → Kubernetes nimmt den Pod aus dem Load Balancer
curl https://helloworld.gmk.lan/control/ready/notok

# Wieder auf ok setzen
curl https://helloworld.gmk.lan/control/health/ok
curl https://helloworld.gmk.lan/control/ready/ok
```

---

## Helm Chart — YAML-Architektur

### Ressourcen und ihre Abhängigkeiten

```
                           ┌──────────────┐
                           │  values.yaml │  ← einzige Konfigurationsdatei für Deployer
                           └──────┬───────┘
                                  │ Helm rendert alle Templates
         ┌────────────────────────┼──────────────────────────────────┐
         │                        │                                  │
         ▼                        ▼                                  ▼
┌─── TLS ──────────┐   ┌─── Istio Ingress ──────────────┐   ┌─── App ────────────────────────────────────┐
│                  │   │                                │   │                                            │
│ certificate.yaml │   │ gateway.yaml                   │   │ configmap.yaml                             │
│ (cert-manager    │   │ port 443 credentialName ───────────── TLS-Secret                                │
│  stellt TLS-     │   │ port 80                        │   │ └─ config/application.properties           │
│  Secret aus)     │   │        │                       │   │      spring.datasource.url                 │
└──────────────────┘   │        ▼                       │   │             │ volumeMount                  │
                       │ virtualservice.yaml            │   │             ▼                              │
                       │ hosts: helloworld.*.lan        │   │ deployment.yaml                            │
                       │ gateways: gateway + mesh       │   │ ├─ volumeMount ◄──── configmap             │
                       │        │                       │   │ └─ envFrom    ◄──── mysql-secret.yaml      │
                       │        ▼ destination           │   │                     SPRING_DATASOURCE_*    │
                       │ service.yaml                   │   │                                            │
                       │ port: 8080                     │   │ destinationrule.yaml                       │
                       │        │ selector: app=...     │   │ circuit breaker für internen Service       │
                       │        ▼                       │   │                                            │
                       │      Pod  ◄─────────────────────────(image aus lokaler Registry)                │
                       └────────────────────────────────┘   └────────────────────────────────────────────┘

                       ┌─── MySQL extern (optional) ────────────────────────────────────────────────────┐
                       │                                                                                │
                       │ mysql-serviceentry.yaml              mysql-destinationrule.yaml                │
                       │ host: mysql.extern.gmk.lan           TLS-Origination (Sidecar → DB)            │
                       │ protocol: TCP, resolution: DNS       Connection Pool + Circuit Breaker         │
                       │                                                                                │
                       └────────────────────────────────────────────────────────────────────────────────┘
```

### Traffic-Fluss zur Laufzeit

```
Browser
  │  HTTPS :443
  ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Istio Gateway  (gateway.yaml)                                       │
│ TLS-Terminierung — credentialName → Secret vom cert-manager         │
└──────────────────────────┬──────────────────────────────────────────┘
                           │  entschlüsselter HTTP-Traffic
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│ VirtualService  (virtualservice.yaml)                               │
│ matched: hosts helloworld.*.lan  →  route zu service.yaml           │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Service  (service.yaml)  port 8080                                  │
│                 ↑                                                   │
│ DestinationRule (destinationrule.yaml)                              │
│ Circuit Breaker: nach 1× 5xx → Pod 30s aus dem Pool                 │
└──────────────────────────┬──────────────────────────────────────────┘
                           │  selector: app=helloworld
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Pod  (deployment.yaml)                                              │
│  ├─ /app/config/application.properties  ← ConfigMap (volumeMount)   │
│  │    spring.datasource.url=jdbc:mysql://mysql.extern.gmk.lan/...   │
│  └─ SPRING_DATASOURCE_USERNAME/PASSWORD ← Secret   (envFrom)        │
│                                                                     │
│  Istio Sidecar (automatisch injiziert)                              │
│    └─ TLS-Origination für MySQL-Traffic                             │
│         │  DestinationRule: mysql-destinationrule.yaml              │
│         │  ServiceEntry:    mysql-serviceentry.yaml                 │
└─────────────────────────────┬───────────────────────────────────────┘
                              │  TCP + TLS (Sidecar baut TLS auf)
                              ▼
                    mysql.extern.gmk.lan:3306
```

---

## Übersicht der Dateien

```
helmchart/          Helm Chart (Deployment, Service, ConfigMap, Istio Gateway/VS, cert-manager Certificate)
argocd/             ArgoCD AppProject, Application, Cluster-Secret, Namespace
tekton/             Pipeline, Tasks (maven-build, kaniko), ServiceAccount, PipelineRun
java/               Spring Boot Helloworld App (Maven, Jetty, Actuator, Prometheus)
                    ProbeController: GET /control/health/{ok|notok} und /control/ready/{ok|notok}
```

