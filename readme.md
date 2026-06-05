# Helm for Java Devs — Schritt-für-Schritt-Anleitung

Spring Boot Helloworld App → Tekton baut das Image → lokale k3s-Registry → ArgoCD deployt per Helm.

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

ArgoCD erkennt Änderungen im Git-Repository automatisch (auto-sync ist aktiv). Manueller Sync:

```bash
argocd app sync helloworld
```

Oder im ArgoCD-UI unter dem Projekt `helloteam` → Application `helloworld`.

---

## Schritt 7 — Deployment prüfen

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

---

## Übersicht der Dateien

```
helmchart/          Helm Chart (Deployment, Service, ConfigMap, Istio Gateway/VS, cert-manager Certificate)
argocd/             ArgoCD AppProject, Application, Cluster-Secret, Namespace
tekton/             Pipeline, Tasks (maven-build, kaniko), ServiceAccount, PipelineRun
java/               Spring Boot Helloworld App (Maven, Jetty, Actuator, Prometheus)
```

## Hinweise

- **Maven-Image**: Der Task `maven-build` nutzt `maven:3.9-eclipse-temurin-25` (passend zur `<java.version>25</java.version>` im pom.xml).
- **Kein Re-deploy bei gleichem Tag**: Da das Image-Tag `latest` ist, muss in ArgoCD `selfHeal: true` aktiv sein oder der Deployment-Pod manuell neu gestartet werden (`kubectl rollout restart deployment/helloworld -n helloworld`).
