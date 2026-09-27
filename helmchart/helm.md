# Helm Chart

Dieses Dokument beschreibt den Helm Chart `helloworld` in diesem Repo. Es zeigt, welche Kubernetes- und Istio-Ressourcen der Chart erzeugt, wie `values.yaml` aufgebaut ist und wie man ihn installiert.

## Was der Chart macht

Der Chart ([Chart.yaml](Chart.yaml)) deployt eine Spring-Boot-App (`java/`) als Kubernetes Deployment und macht sie über Istio per HTTPS erreichbar. cert-manager stellt die TLS-Zertifikate aus, eine externe MySQL-Datenbank lässt sich optional anbinden.

| Bereich | Was er liefert |
|---|---|
| App-Betrieb | Deployment, Service, ConfigMap, ServiceAccount, PodDisruptionBudget. Startup-, Liveness- und Readiness-Probes prüfen die Spring-Actuator-Endpoints, dazu Graceful Shutdown und ein restriktiver securityContext |
| Traffic-Routing | Istio Gateway, VirtualService, DestinationRule, dazu Source-Namespace-Routing zwischen mehreren App-Versionen |
| TLS | cert-manager `Certificate`, terminiert am Istio Gateway |
| Externe DB (optional) | Istio ServiceEntry/DestinationRule + Secret für eine MySQL-Instanz außerhalb des Mesh |

## Chart-Struktur

Übersicht über die Dateien in `helmchart/` und ihre Rolle.

```
helmchart/
├── Chart.yaml                          # Metadaten des Charts (Name, Version, Beschreibung)
├── values.yaml                         # Zentrale Konfiguration, siehe unten
├── config/
│   ├── application.properties          # Gemeinsame Properties-Basis für alle Versionen
│   ├── application-extern.properties   # Overrides für Version "extern"
│   └── application-intern.properties   # Overrides für Version "intern"
└── templates/
    ├── _helpers.tpl                    # Namen, Standard-Labels, Properties-Zusammenbau
    ├── deployment.yaml                 # Ein Deployment je Version aus values.yaml
    ├── service.yaml                    # Ein gemeinsamer Service für alle Versionen
    ├── serviceaccount.yaml             # Eigener ServiceAccount ohne API-Token
    ├── poddisruptionbudget.yaml        # Ein PDB je Version
    ├── configmap.yaml                  # Eine ConfigMap je Version (Basis + Overrides)
    ├── gateway.yaml                    # Istio Ingress Gateway (HTTPS + HTTP-Redirect)
    ├── virtualservice.yaml             # Gateway-VS (externe Hosts) + Mesh-VS (Source-Namespace-Matching)
    ├── destinationrule.yaml            # Subsets je Version + Circuit Breaker
    ├── certificate.yaml                # cert-manager Certificate (nur wenn certmanager.enabled)
    ├── mysql-secret.yaml               # DB-Credentials als Secret (nur wenn mysql.external.enabled)
    ├── mysql-ca-secret.yaml            # CA-Zertifikat als Secret (nur wenn TLS + createCaSecret)
    ├── mysql-serviceentry.yaml         # Registriert die externe DB im Istio-Mesh
    └── mysql-destinationrule.yaml      # TLS-Origination + Connection Pool zur externen DB
```

## Konfiguration in values.yaml

Alle einstellbaren Werte liegen in [values.yaml](values.yaml), die Kommentare dort erklären jeden einzelnen. Die wichtigsten Blöcke:

| Schlüssel | Zweck |
|---|---|
| `replicaCount`, `deploymentName`, `image` | Anzahl Pods, Ressourcen-Namen, Image-Referenz |
| `javaToolOptions`, `resources`, `probes` | JVM-Optionen (u.a. `MaxRAMPercentage`), CPU/Memory, Health-Probes |
| `shutdown` | preStop-Sleep, Spring-Shutdown-Timeout, `terminationGracePeriodSeconds` |
| `serviceAccount`, `podSecurityContext`, `containerSecurityContext` | ServiceAccount, Non-Root-User, read-only Root-FS |
| `podDisruptionBudget`, `topologySpread` | Verfügbarkeit bei Node-Drain und Verteilung über Nodes |
| `hosts`, `service.port`, `istio` | Externe Hosts, Gateway, Ingress-Namespace, `exportTo`, HTTPS-Redirect |
| `versions` | Liste paralleler App-Versionen (Name + `sourceNamespace`), siehe unten |
| `certmanager` | cert-manager an/aus, Issuer und Name des TLS-Secrets |
| `extraVolumeMounts` / `extraVolumes` / `extraEnv` | Zusätzliche Volumes und Umgebungsvariablen, ohne die Templates anzufassen |
| `logging.structured` | JSON- oder Text-Logging |
| `mysql.external` | Externe MySQL-DB über Istio: TLS, Connection Pool, Credentials |

## Mehrere Versionen parallel (extern/intern)

Der Chart deployt dieselbe App in mehreren Versionen gleichzeitig. Damit lässt sich Source-Namespace-Routing vorführen (siehe [kunden-traffic.md](../kunden-traffic.md)).

Jeder Eintrag unter `versions` in [values.yaml](values.yaml) erzeugt ein eigenes Deployment ([deployment.yaml](templates/deployment.yaml)) und eine eigene ConfigMap ([configmap.yaml](templates/configmap.yaml)). Die ConfigMap entsteht aus der gemeinsamen Basis `config/application.properties` plus den Overrides aus `config/application-<name>.properties`. Fehlt die Override-Datei einer Version, bricht das Rendern ab. Ein Hash der Properties steht als `checksum/config`-Annotation am Pod, deshalb löst jede Config-Änderung ein Rolling Update aus. Service, DestinationRule und VirtualService teilen sich alle Versionen.

Die DestinationRule ([destinationrule.yaml](templates/destinationrule.yaml)) legt je Version ein Subset an, ausgewählt über das `version`-Label im Deployment.

Es gibt zwei VirtualServices ([virtualservice.yaml](templates/virtualservice.yaml)). Der Mesh-VS gilt für den Service-FQDN `<deploymentName>.<namespace>.svc.cluster.local` und routet Sidecar-zu-Sidecar-Traffic (`gateways: [mesh]`) anhand von `sourceNamespace` auf das passende Subset. Der Gateway-VS gilt für die externen `hosts` und schickt Ingress-Traffic immer an die erste Version in der Liste. Ein einzelner VS reicht nicht, weil der Sidecar eine Regel nur anwendet, wenn der aufgerufene Host in `hosts` steht.

## TLS mit cert-manager

Mit `certmanager.enabled: true` stellt cert-manager ein Zertifikat für alle `hosts` aus und legt es im Namespace `istio.ingressNamespace` ab.

[certificate.yaml](templates/certificate.yaml) erzeugt die `Certificate`-Ressource. [gateway.yaml](templates/gateway.yaml) referenziert das resultierende Secret über `credentialName` für den HTTPS-Listener auf Port 443. Der HTTP-Listener auf Port 80 bleibt offen. Mit `istio.httpsRedirect` antwortet er mit 301 und leitet auf HTTPS um.

## Externe MySQL-Anbindung (optional)

Mit `mysql.external.enabled: true` bindet der Chart eine MySQL-Datenbank außerhalb des Istio-Mesh an. Um TLS kümmert sich der Sidecar, nicht die App.

| Template | Aufgabe |
|---|---|
| [mysql-serviceentry.yaml](templates/mysql-serviceentry.yaml) | Registriert den externen Host im Mesh (`MESH_EXTERNAL`), DNS- oder statische Auflösung |
| [mysql-destinationrule.yaml](templates/mysql-destinationrule.yaml) | TLS-Origination durch den Sidecar, Connection-Pool-Limits, Circuit Breaker |
| [mysql-ca-secret.yaml](templates/mysql-ca-secret.yaml) | Legt das CA-Zertifikat aus `tls.caCert` als Secret an (nur wenn `tls.createCaSecret: true`) |
| [mysql-secret.yaml](templates/mysql-secret.yaml) | DB-Username und -Passwort als Secret, per `envFrom` im Container verfügbar |

Die App spricht unverschlüsseltes TCP mit der DB. Der Istio-Sidecar baut die TLS-Verbindung auf (TLS-Origination), die App merkt davon nichts.

## Chart deployen

Installieren oder aktualisieren mit dem Release-Namen `helloworld`:

```bash
helm upgrade --install helloworld ./helmchart
```

Mit eigenen Werten, etwa um die externe DB einzuschalten:

```bash
helm upgrade --install helloworld ./helmchart -f my-values.yaml
```

Mit TLS zur DB und einem CA-Secret, das Helm anlegt. Die CA-Datei liegt außerhalb des Charts, `.Files` kommt also nicht an sie heran. Deshalb geht sie per `--set-file` rein:

```bash
helm upgrade --install helloworld ./helmchart \
  --set mysql.external.enabled=true \
  --set mysql.external.tls.enabled=true \
  --set mysql.external.tls.createCaSecret=true \
  --set-file mysql.external.tls.caCert=/local-ca/ca.pem
```
