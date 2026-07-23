# Helm Chart

Dieses Dokument beschreibt den Helm Chart `helloworld` in diesem Repo: welche Kubernetes- und Istio-Ressourcen er erzeugt, wie `values.yaml` aufgebaut ist und wie man den Chart installiert.

## Was macht das Chart?

Der Chart ([Chart.yaml](Chart.yaml)) deployt eine Spring-Boot-App (`java/`) als Kubernetes Deployment und macht sie über Istio als HTTPS-Service erreichbar – inklusive automatischer TLS-Zertifikate via cert-manager und optionaler Anbindung an eine externe MySQL-Datenbank.

| Bereich | Was er liefert |
|---|---|
| App-Betrieb | Deployment, Service, ConfigMap – mit Startup-/Liveness-/Readiness-Probes auf die Spring-Actuator-Endpoints |
| Traffic-Routing | Istio Gateway, VirtualService, DestinationRule – inkl. Source-Namespace-Routing zwischen mehreren App-Versionen |
| TLS | cert-manager `Certificate`, terminiert am Istio Gateway |
| Externe DB (optional) | Istio ServiceEntry/DestinationRule + Secret für eine MySQL-Instanz außerhalb des Mesh |

## Chart-Struktur

Übersicht über die Dateien in `helmchart/` und ihre Rolle.

```
helmchart/
├── Chart.yaml                          # Metadaten des Charts (Name, Version, Beschreibung)
├── values.yaml                         # Zentrale Konfiguration – siehe unten
├── config/
│   ├── application-extern.properties   # Properties-Vorlage für Version "extern"
│   └── application-intern.properties   # Properties-Vorlage für Version "intern"
└── templates/
    ├── deployment.yaml                 # Ein Deployment je Version aus values.yaml
    ├── service.yaml                    # Ein gemeinsamer Service für alle Versionen
    ├── configmap.yaml                  # Eine ConfigMap je Version (aus config/*.properties)
    ├── gateway.yaml                    # Istio Ingress Gateway (HTTP + optional HTTPS)
    ├── virtualservice.yaml             # Routing-Regeln inkl. Source-Namespace-Matching
    ├── destinationrule.yaml            # Subsets je Version + Circuit Breaker
    ├── certificate.yaml                # cert-manager Certificate (nur wenn certmanager.enabled)
    ├── mysql-secret.yaml               # DB-Credentials als Secret (nur wenn mysql.external.enabled)
    ├── mysql-ca-secret.yaml            # CA-Zertifikat als Secret (nur wenn TLS + createCaSecret)
    ├── mysql-serviceentry.yaml         # Registriert die externe DB im Istio-Mesh
    └── mysql-destinationrule.yaml      # TLS-Origination + Connection Pool zur externen DB
```

## values.yaml – zentrale Konfiguration

Alle einstellbaren Werte liegen gebündelt in [values.yaml](values.yaml); die Kommentare dort erklären jeden Wert einzeln. Die wichtigsten Blöcke im Überblick:

| Schlüssel | Zweck |
|---|---|
| `replicaCount`, `deploymentName`, `image` | Basis-Deployment: Anzahl Pods, Ressourcen-Namen, Image-Referenz |
| `hosts`, `service.port`, `istio.gateway` | Wie die App von außen erreichbar ist |
| `versions` | Liste paralleler App-Versionen (Name + `sourceNamespace`), siehe unten |
| `certmanager` | Ob/wie TLS-Zertifikate automatisch ausgestellt werden |
| `extraVolumeMounts` / `extraVolumes` / `extraEnv` | Erweiterungspunkte ohne Chart-Änderung |
| `logging.structured` | JSON- vs. Text-Logging |
| `mysql.external` | Anbindung an eine externe MySQL-DB über Istio (TLS, Connection Pool, Credentials) |

## Mehrere Versionen parallel (extern/intern)

Der Chart kann dieselbe App in mehreren Versionen gleichzeitig deployen, um Source-Namespace-Routing zu demonstrieren (siehe [request-routing/istio-routing.md](../request-routing/istio-routing.md)).

Jeder Eintrag unter `versions` in [values.yaml](values.yaml) erzeugt ein eigenes Deployment ([deployment.yaml](templates/deployment.yaml)) und eine eigene ConfigMap ([configmap.yaml](templates/configmap.yaml)) aus `config/application-<name>.properties`. Service, DestinationRule und VirtualService bleiben dagegen für alle Versionen gemeinsam:

- **DestinationRule** ([destinationrule.yaml](templates/destinationrule.yaml)) definiert je Version ein Subset anhand des `version`-Labels im Deployment.
- **VirtualService** ([virtualservice.yaml](templates/virtualservice.yaml)) routet Sidecar-zu-Sidecar-Traffic (`gateways: [mesh]`) anhand von `sourceNamespace` auf das passende Subset; externer Ingress-Traffic fällt immer auf die erste konfigurierte Version zurück.

## TLS mit cert-manager

Ist `certmanager.enabled: true`, stellt cert-manager automatisch ein Zertifikat für alle `hosts` aus und legt es im Istio-Ingress-Namespace ab.

[certificate.yaml](templates/certificate.yaml) erzeugt die `Certificate`-Ressource; [gateway.yaml](templates/gateway.yaml) referenziert das resultierende Secret über `credentialName` für den HTTPS-Listener auf Port 443. Der HTTP-Listener auf Port 80 bleibt unabhängig davon immer aktiv.

## Externe MySQL-Anbindung (optional)

Ist `mysql.external.enabled: true`, bindet der Chart eine MySQL-Datenbank außerhalb des Istio-Mesh an, ohne dass die App TLS selbst terminieren muss.

| Template | Aufgabe |
|---|---|
| [mysql-serviceentry.yaml](templates/mysql-serviceentry.yaml) | Registriert den externen Host im Mesh (`MESH_EXTERNAL`), DNS- oder statische Auflösung |
| [mysql-destinationrule.yaml](templates/mysql-destinationrule.yaml) | TLS-Origination durch den Sidecar, Connection-Pool-Limits, Circuit Breaker |
| [mysql-ca-secret.yaml](templates/mysql-ca-secret.yaml) | Legt das CA-Zertifikat als Secret an (nur wenn `tls.createCaSecret: true`) |
| [mysql-secret.yaml](templates/mysql-secret.yaml) | DB-Username/-Passwort als Secret, per `envFrom` in den Container injiziert |

Die App selbst verbindet sich dabei plain TCP zur DB – die Verschlüsselung übernimmt der Istio-Sidecar transparent (TLS-Origination).

## Chart deployen

Installation bzw. Update des Charts mit dem Release-Namen `helloworld`:

```bash
helm upgrade --install helloworld ./helmchart
```

Mit eigenen Werten (z. B. externe DB aktivieren):

```bash
helm upgrade --install helloworld ./helmchart -f my-values.yaml
```
