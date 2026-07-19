# Helm for Java Devs — Schritt-für-Schritt-Anleitung

Eine vollständige Kubernetes Pipeline für eine Spring Boot App in einem Container.
Spring Boot Helloworld App → Tekton baut das Image → lokale k3s-Registry → ArgoCD deployt per Helm.

Verwendet ConfigMap, Secrets, SSL Certificate, Istio Gateway, VirtualService, HealthChecks, Readness Checks.
ServiceEntry und DestinationRule für eine externe Datenbankabhängigkeit.
Hat ein Feature Flag für den Wechsel von h2 auf MySQL.

---

## Schritt — Vorbereitung: Workshop-Homepage

Alle notwendigen Dateien, Zugangsdaten und Installationsanleitungen sind über die Workshop-Homepage abrufbar:

**http://homepage.big.lan/**

Die Seite ist in folgende Tabs gegliedert:

| Tab | Inhalt |
|---|---|
| **Cluster** | Cluster-IP-Adressen, ArgoCD-URL mit Zugangsdaten, ArgoCD-CLI-Login |
| **kubectl** | `kubeconfig.yaml` herunterladen und einrichten (Linux / macOS / Windows) |
| **Apps** | Direkte Links zur `helloworld`- und `demo`-App |
| **Registry** | Registry-URL (`registry.big.lan`), Image bauen und pushen, Insecure-Registry-Konfiguration |
| **CA-Zertifikat** | Workshop-CA-Zertifikat (`ca.pem`) herunterladen und im System sowie in Docker hinterlegen |
| **Maven** | Nexus-Cache (`maven.big.lan:8081`), `settings.xml` herunterladen und einrichten |
| **Tools** | Download-Anleitungen für `kubectl`, `argocd`, `helm` und `tkn` (Tekton CLI) |
| **Spring MCP** | Spring Boot MCP-Server aufbauen (Spring AI, `@Tool`-Annotation, SSE-Transport) |

### Empfohlene Reihenfolge zur Vorbereitung

1. **kubeconfig** herunterladen (Tab *kubectl*) und `KUBECONFIG` setzen
2. **CA-Zertifikat** installieren (Tab *CA-Zertifikat*) — für Browser, kubectl und Docker
3. **Maven settings.xml** nach `~/.m2/` kopieren (Tab *Maven*) — nutzt den lokalen Nexus-Cache
4. **Tools** installieren: `kubectl`, `argocd`, `helm`, `tkn` (Tab *Tools*)
5. Verbindung testen:
   ```bash
   kubectl get nodes
   ```

---

## Schritt — Tekton-Ressourcen installieren

### git-clone Task aus dem Tekton-Katalog installieren

```bash
kubectl apply -f tekton/git-clone.yaml
```

### Eigene Tasks und Pipeline installieren

```bash
kubectl apply -f tekton/serviceaccount.yaml
kubectl apply -f tekton/task-maven-build.yml
kubectl apply -f tekton/task-kaniko.yml
kubectl apply -f tekton/pipeline.yml
```

### Alternative: Buildah statt Kaniko

`task-buildah.yml` ist ein Drop-in-Ersatz für `task-kaniko.yml` (gleiche Params/Result), verpackt in einer eigenen Pipeline `helloworld-pipeline-buildah`:

```bash
kubectl apply -f tekton/task-buildah.yml
kubectl apply -f tekton/pipeline-buildah.yml
```

> Details und Vergleich Kaniko vs. Buildah: **[tekton/tekton.md](tekton/tekton.md)**

---

## Pipeline starten

```bash
kubectl create -f tekton/pipeline-run.yml
```

> `kubectl create` statt `apply`, damit `generateName` eine eindeutige Run-ID vergibt.

Für die Buildah-Variante stattdessen:

```bash
kubectl create -f tekton/pipeline-run-buildah.yml
```

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

## Image in der Registry prüfen

```bash
kubectl run registry-check --image=curlimages/curl --restart=Never --rm -it -- \
  curl http://registry.registry.svc.cluster.local:5000/v2/helloworld/tags/list
```

Erwartet: `{"name":"helloworld","tags":["latest"]}`

---

## Namespace und ArgoCD vorbereiten

```bash
# ArgoCD-Projekt anlegen (muss vor der Application existieren)
kubectl apply -f argocd/helloteam-project.yaml

# ArgoCD-Application anlegen
kubectl apply -f argocd/hello-namespace.yml
kubectl apply -f argocd/app-helloworld.yml
```

---

## ArgoCD-Sync auslösen

ArgoCD erkennt Änderungen im Git-Repository automatisch (auto-sync ist aktiv).

Für einen manuellen Sync zuerst einloggen:

```bash
# Admin-Passwort auslesen
kubectl get secret argocd-initial-admin-secret -n argocd \
  -o jsonpath='{.data.password}' | base64 -d

# Einloggen
argocd login argocd.tp.lan --username admin

# Sync auslösen
argocd app sync helloworld
```

Alternativ direkt im ArgoCD-UI: https://argocd.tp.lan → Projekt `helloteam` → Application `helloworld`.

---

## Deployment prüfen

```bash
kubectl get pods -n helloworld
kubectl get certificate -n helloworld
kubectl get gateway -n helloworld
```

App über den Istio-Ingress erreichbar unter `https://helloworld.tp.lan`.

Health-Endpoint prüfen:

```bash
curl https://helloworld.tp.lan/actuator/health
```

Liveness- und Readiness-Status einzeln abrufen:

```bash
curl https://helloworld.tp.lan/actuator/health/readiness
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
curl https://helloworld.tp.lan/control/health/notok

# Readiness auf "not ok" setzen → Kubernetes nimmt den Pod aus dem Load Balancer
curl https://helloworld.tp.lan/control/ready/notok

# Wieder auf ok setzen
curl https://helloworld.tp.lan/control/health/ok
curl https://helloworld.tp.lan/control/ready/ok
```

---

## App-Endpunkte

| Endpunkt | Inhalt |
|---|---|
| `/` | Startseite mit Links zu allen Endpunkten, App-Version und Pod-Name |
| `/application` | Alle aktiven `application.properties`-Werte als JSON |
| `/db` | Datenbankverbindung: Typ, Version, JDBC-URL |

```bash
curl https://helloworld.tp.lan/application
curl https://helloworld.tp.lan/db
```

Im h2-Modus (Standard) liefert `/db`:

```json
{"connected":true,"type":"H2","version":"2.x.x","url":"jdbc:h2:mem:..."}
```

---

## Zwei Versionen: intern/extern (Source-Namespace-Routing)

Der Chart deployt zwei Versionen derselben App parallel und routet Traffic anhand des
Aufrufer-Namespace per Istio. Beschreibung, Testclient-Setup und busybox-Testbefehle:
**[kunden-traffic.md](kunden-traffic.md)**

---

## Helm Chart — Manuelle Befehle

### Testen (lokal rendern, kein Deployment)

```bash
# Chart auf Syntaxfehler prüfen
helm lint helmchart/

# Templates rendern und ausgeben (kein Cluster nötig)
helm template helloworld helmchart/ --namespace helloworld

# Dry-run gegen den Cluster (validiert auch gegen die Kubernetes API)
helm install helloworld helmchart/ --namespace helloworld --create-namespace --dry-run
```

### Deployen

```bash
# Erstinstallation
helm install helloworld helmchart/ --namespace helloworld --create-namespace

# Update (auch für Erstinstallation verwendbar)
helm upgrade --install helloworld helmchart/ --namespace helloworld
```

### Status prüfen

```bash
helm status helloworld --namespace helloworld
helm list --namespace helloworld
```

### Löschen

```bash
# Release entfernen (Namespace bleibt erhalten)
helm uninstall helloworld --namespace helloworld

# Namespace ebenfalls löschen
kubectl delete namespace helloworld
```

---

## MySQL aktivieren (Feature Flag)

Wechsel von der In-Memory-H2-Datenbank auf eine externe MySQL-Instanz über das Feature Flag
`mysql.external.enabled`. Schritt-für-Schritt-Anleitung inkl. DNS-Check und Verifikation:
**[db-schwenk.md](db-schwenk.md)**

---

## Structured Logging

JSON-Logging für Log-Aggregation (Loki, Elasticsearch) aktivieren — in `values.yaml`:

```yaml
logging:
  structured:
    enabled: true
    format: ecs    # Elastic Common Schema — kompatibel mit Loki, ELK, Kibana
```

Im Standard (`enabled: false`) loggt die App im Text-Format, das für lokale Entwicklung lesbar ist.