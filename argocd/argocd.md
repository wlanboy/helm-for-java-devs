# ArgoCD

Dieses Dokument beschreibt das ArgoCD-Setup in diesem Repo: was ArgoCD ist, wie die Ressourcen in `argocd/` zusammenspielen und wie man sie anwendet.

## Was ist ArgoCD?

ArgoCD ist ein GitOps-Continuous-Delivery-Tool für Kubernetes. Statt Deployments per `kubectl apply` oder CI-Pipeline direkt ins Cluster zu pushen, beobachtet ArgoCD ein Git-Repo und gleicht den Cluster-Zustand kontinuierlich mit dem dort eingecheckten Soll-Zustand ab (Reconcile-Loop). Die wichtigsten Bausteine:

| Ressource     | Rolle                                                                 |
|---------------|------------------------------------------------------------------------|
| `AppProject`  | Mandanten-/Team-Grenze: legt fest, welche Git-Repos, Cluster und Namespaces für eine Gruppe von Applications erlaubt sind. |
| `Application` | Eine konkrete Deployment-Einheit: verbindet eine Source (Git-Repo + Pfad, z. B. ein Helm Chart) mit einem Ziel (Cluster + Namespace) und einer Sync-Policy. |
| `SyncPolicy`  | Steuert, ob und wie automatisch synchronisiert wird (`automated`), inkl. `prune` (gelöschte Ressourcen entfernen) und `selfHeal` (manuelle Cluster-Änderungen zurückrollen). |
| `Namespace`   | Wird hier bewusst **nicht** von ArgoCD selbst angelegt, sondern separat gepflegt, damit Labels (z. B. Istio-Injection) vor dem ersten Deployment gesetzt sind. |

Man checkt Applications und Projects als YAML ein (wie hier) und legt sie einmalig per `kubectl apply` im `argocd`-Namespace an – danach übernimmt ArgoCD das eigentliche Deployen und Nachziehen von Änderungen.

## Die Ressourcen in diesem Repo

Übersicht über die YAML-Dateien in `argocd/` und wie sie zusammenspielen.

```
argocd/
├── helloteam-project.yaml   # AppProject: erlaubte Repos/Cluster/Namespaces für das Team
├── hello-namespace.yml      # Ziel-Namespace mit Istio-Injection-Label
└── app-helloworld.yml       # Application: deployt das Helm Chart aus helmchart/
```

Zusammenspiel:

1. **[helloteam-project.yaml](helloteam-project.yaml)** definiert das `AppProject` `helloteam`: erlaubt als Source nur das Repo `github.com/wlanboy/helm-for-java-devs.git` und als Ziel nur die Namespaces `helloworld` und `istio-ingress` im In-Cluster-Server `https://kubernetes.default.svc`. `istio-ingress` ist zusätzlich freigegeben, damit cert-manager dort TLS-Secrets für das Gateway anlegen darf.
2. **[hello-namespace.yml](hello-namespace.yml)** legt den Namespace `helloworld` vorab an, inklusive Label `istio-injection: enabled`. Das passiert bewusst außerhalb von ArgoCD, weil laufende Pods bei nachträglicher Label-Vergabe keinen Istio-Sidecar mehr injiziert bekommen – der Namespace muss also existieren, bevor die Application das erste Mal synct.
3. **[app-helloworld.yml](app-helloworld.yml)** definiert die `Application` `helloworld` im Projekt `helloteam`: Source ist das Helm Chart unter `helmchart/` auf Branch `main`, Ziel ist der Namespace `helloworld`. Die `syncPolicy` ist `automated` mit `prune: true` und `selfHeal: true` – Git ist damit die alleinige Quelle der Wahrheit, manuelle `kubectl`-Änderungen im Cluster werden automatisch zurückgesetzt. `CreateNamespace=false` verhindert, dass ArgoCD den Namespace selbst (und ohne Istio-Label) anlegt.

Angewendet wird das Ganze in dieser Reihenfolge:

```bash
kubectl apply -f helloteam-project.yaml
kubectl apply -f hello-namespace.yml
kubectl apply -f app-helloworld.yml
```

## Automated Sync im Detail

Was `prune: true` und `selfHeal: true` konkret bedeuten und worauf man dabei achten sollte.

- **`prune: true`**: Wird eine Ressource aus dem Helm Chart im Git-Repo entfernt, löscht ArgoCD sie beim nächsten Sync auch im Cluster. Ohne `prune` blieben verwaiste Ressourcen liegen.
- **`selfHeal: true`**: Weicht der Live-Zustand im Cluster vom Git-Stand ab (z. B. durch manuelles `kubectl edit`), synct ArgoCD automatisch zurück auf den Git-Stand – ohne dass jemand `argocd app sync` auslösen muss.
- Zusammen bedeutet das: Git ist die einzige Änderungsquelle für den Namespace `helloworld`. Hotfixes direkt im Cluster werden von ArgoCD rückgängig gemacht, sofern sie nicht auch ins Repo committet werden.

**Hinweis:** Der Ziel-Namespace muss vor dem ersten Sync existieren und das Istio-Label tragen (siehe [hello-namespace.yml](hello-namespace.yml)), da `CreateNamespace=false` gesetzt ist. Wird `helloteam-project.yaml` geändert (z. B. neue erlaubte Destination), muss das Project vor der betroffenen Application aktualisiert werden, sonst lehnt ArgoCD den Sync mit einem Permission-Fehler ab.
