# Tekton Pipeline

Dieses Dokument beschreibt die Tekton-Pipeline in diesem Repo: was Tekton ist, wie die Pipeline aufgebaut ist und wie sich Kaniko gegen Buildah als Build-Backend tauschen lässt.

## Was ist Tekton?

Tekton ist ein Kubernetes-natives CI/CD-Framework. Statt eines eigenen CI-Servers (Jenkins, GitLab CI, …) laufen Builds als ganz normale Kubernetes-Ressourcen (CRDs) im Cluster – jeder Build-Schritt ist ein Pod bzw. Container. Die wichtigsten Bausteine:

| Ressource     | Rolle                                                                 |
|---------------|------------------------------------------------------------------------|
| `Task`        | Eine Folge von Steps (Containern), z. B. "Maven bauen" oder "Image pushen". Läuft als **ein Pod**, jeder Step als eigener Container darin. |
| `TaskRun`     | Eine konkrete Ausführung eines Tasks (wird meist automatisch von einem PipelineRun erzeugt). |
| `Pipeline`    | Verkettet mehrere Tasks, definiert Reihenfolge (`runAfter`), Datenfluss (Workspaces, Results) und Parameter. |
| `PipelineRun` | Eine konkrete Ausführung einer Pipeline mit echten Parameterwerten – das, was man tatsächlich "startet". |
| `Workspace`   | Gemeinsamer Speicher (meist ein PVC) über den sich Tasks Dateien teilen, z. B. den ausgecheckten Quellcode. |
| `Result`      | Kleiner Textwert, den ein Task nach außen zurückgibt (z. B. ein Image-Digest), den nachfolgende Tasks oder die Pipeline selbst weiterverwenden können. |
| `ServiceAccount` | Legt fest, mit welcher Kubernetes-Identität (und welchen Registry-Credentials) die Pods der Tasks laufen. |

Man schreibt Tasks und Pipelines einmal, checkt sie als YAML ein (wie hier) und startet sie über `PipelineRun`-Objekte – reproduzierbar, versioniert, ohne externen CI-Server.

## Die Pipeline in diesem Repo

Übersicht über die YAML-Dateien in `tekton/` und wie sie zusammenspielen.

```
tekton/
├── serviceaccount.yaml     # Identität, unter der die Pipeline läuft
├── git-clone.yaml          # Standard-Task aus dem Tekton-Catalog
├── task-maven-build.yml    # Eigener Task: Maven-Build mit AOT
├── task-kaniko.yml         # Eigener Task: Image bauen/pushen mit Kaniko
├── task-buildah.yml        # Eigener Task: Image bauen/pushen mit Buildah
├── pipeline.yml            # Pipeline: clone → build-jar → docker-push (Kaniko)
├── pipeline-run.yml        # Startet obige Pipeline mit konkreten Werten
├── pipeline-buildah.yml    # Gleiche Pipeline, aber docker-push via Buildah
└── pipeline-run-buildah.yml
```

Ablauf von `pipeline.yml` ([pipeline.yml](pipeline.yml)):

1. **clone** – nutzt den Standard-`git-clone`-Task ([git-clone.yaml](git-clone.yaml)), checkt das Repo in den Workspace `shared-data` aus.
2. **build-jar** – `runAfter: [clone]`, nutzt [task-maven-build.yml](task-maven-build.yml). Baut das Spring-Boot-JAR mit AOT (`compile spring-boot:process-aot package`) im selben Workspace.
3. **docker-push** – `runAfter: [build-jar]`, nutzt [task-kaniko.yml](task-kaniko.yml). Baut das Image aus dem im Workspace liegenden `java/Dockerfile` und pusht es in die lokale k3s-Registry.

Alle drei Tasks teilen sich denselben Workspace `shared-data` (ein PVC, siehe [pipeline-run.yml](pipeline-run.yml)) – so sieht der Kaniko-/Buildah-Step die vom Maven-Task gebauten Artefakte, ohne dass Dateien explizit kopiert werden müssen. Der finale `IMAGE_DIGEST` wird als Pipeline-`result` durchgereicht, sodass man nach einem Lauf per `tkn pipelinerun describe` genau weiß, welches Digest gepusht wurde.

Gestartet wird das Ganze mit:

```bash
kubectl apply -f serviceaccount.yaml
kubectl apply -f git-clone.yaml
kubectl apply -f task-maven-build.yml
kubectl apply -f task-kaniko.yml
kubectl apply -f pipeline.yml
kubectl create -f pipeline-run.yml
```

## Kaniko vs. Buildah

Beide bauen Images **ohne Docker-Daemon** aus einem Dockerfile – das ist in Kubernetes wichtig, weil man sonst einen privilegierten Docker-Daemon-Sidecar bräuchte. Der Unterschied liegt im Ansatz:

| | Kaniko ([task-kaniko.yml](task-kaniko.yml)) | Buildah ([task-buildah.yml](task-buildah.yml)) |
|---|---|---|
| Ansatz | Rein userspace, ein einziger `executor`-Aufruf baut und pusht in einem Schritt | OCI-/Podman-Ökosystem-Tool, `bud` (build) und `push` sind getrennte Befehle |
| Privilegien | Läuft ohne `privileged: true` | Braucht i. d. R. `privileged: true` (oder rootless mit `/dev/fuse` + `fuse-overlayfs`), hier per `--storage-driver=vfs` gelöst, damit kein `/dev/fuse` nötig ist |
| Insecure Registry | `--insecure --skip-tls-verify` | `--tls-verify=false` |
| Flexibilität | Nur Dockerfile-Build+Push, wenig Interaktion dazwischen | Kann Images auch Schritt für Schritt scripten/inspizieren (z. B. `buildah run`, `buildah copy`), praktisch wenn man mal ohne Dockerfile bauen will |
| Verbreitung | De-facto-Standard in vielen Tekton-Setups, sehr stabil | Stammt aus dem Red-Hat/Podman-Umfeld, gut geeignet wenn man ohnehin auf UBI/Podman setzt |

Beide Tasks in diesem Repo sind **API-kompatibel** zueinander: gleiche Parameter (`IMAGE`, `DOCKERFILE`, `CONTEXT`) und gleiches Result (`IMAGE_DIGEST`). Dadurch lässt sich der Build-Backend in der Pipeline durch einfaches Austauschen des `taskRef`-Namens wechseln – genau das macht [pipeline-buildah.yml](pipeline-buildah.yml): identische Pipeline wie [pipeline.yml](pipeline.yml), nur `taskRef: kaniko-build-push` → `taskRef: buildah-build-push`.

Buildah-Variante anwenden:

```bash
kubectl apply -f task-buildah.yml
kubectl apply -f pipeline-buildah.yml
kubectl create -f pipeline-run-buildah.yml
```

**Hinweis:** `task-buildah.yml` läuft mit `securityContext.privileged: true`, weil Buildah Mount-/Namespace-Operationen braucht, die im Standard-Container-Sandbox nicht erlaubt sind. Falls der Cluster `privileged`-Pods per PodSecurityStandard blockt, muss der Namespace der Pipeline das erlauben (z. B. `pod-security.kubernetes.io/enforce: privileged`) oder man wechselt auf eine rootless-Variante mit `fuse-overlayfs` und passendem Seccomp-Profil.
