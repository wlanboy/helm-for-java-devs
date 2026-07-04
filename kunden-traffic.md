# Zwei Versionen: intern/extern (Source-Namespace-Routing)

Der Chart deployt **zwei Versionen** derselben App parallel (`values.yaml` → `versions`), um Istio
Source-Namespace-Routing zu demonstrieren (Details siehe [`request-routing/istio-routing.md`](request-routing/istio-routing.md)):

| Version | `sourceNamespace` | ConfigMap | `app.version-label` |
|---|---|---|---|
| `extern` | `testclientextern` | `helloworld-config-extern` | `extern` |
| `intern` | `testclientintern` | `helloworld-config-intern` | `intern` |

Jede Version bekommt ein eigenes Deployment, eine eigene ConfigMap (`config/application-<name>.properties`)
und ein eigenes DestinationRule-Subset. Der VirtualService routet Sidecar-zu-Sidecar-Traffic
(Gateway `mesh`) anhand des **Namespace, aus dem der Call kommt**: Ruft ein Pod aus `testclientextern`
auf, landet er beim `extern`-Subset; aus `testclientintern` beim `intern`-Subset. Traffic über den
externen Istio-Ingress (`https://helloworld.tp.lan`) hat kein Source-Workload in einem dieser
Namespaces und landet immer auf der Default-Route (`extern`).

> Das Source-Namespace-Matching wird vom **Envoy-Sidecar des aufrufenden Pods** ausgewertet — die
> Testclient-Namespaces brauchen deshalb selbst Istio Sidecar-Injection, sonst greift die Regel nicht.

## Testclient-Namespaces anlegen

```bash
kubectl create namespace testclientextern
kubectl create namespace testclientintern

kubectl label namespace testclientextern istio-injection=enabled
kubectl label namespace testclientintern istio-injection=enabled
```

## Routing mit einem busybox-Pod testen

`busybox` hat kein `curl`, aber `wget` reicht für den Test. Pro Testclient-Namespace ein Pod, der
den fachlichen `/version`-Endpunkt (`VersionController`) des Services abruft:

```bash
# Aufruf aus testclientextern → erwartet versionLabel "extern"
kubectl run testclient -n testclientextern --image=busybox --restart=Never --rm -it -- \
  wget -qO- http://helloworld.helloworld.svc.cluster.local:8080/version

# Aufruf aus testclientintern → erwartet versionLabel "intern"
kubectl run testclient -n testclientintern --image=busybox --restart=Never --rm -it -- \
  wget -qO- http://helloworld.helloworld.svc.cluster.local:8080/version
```

Erwartete Ausgabe (Auszug):

```json
{"versionLabel":"extern","pod":"helloworld-extern-..."}
```

bzw. `"versionLabel":"intern"` für den zweiten Aufruf. Zum Vergleich: der externe Ingress-Aufruf
landet immer auf `extern`:

```bash
curl https://helloworld.tp.lan/version
```

> Läuft der Pod ohne Sidecar (z.B. weil das Namespace-Label fehlte, bevor der Pod gestartet wurde),
> antwortet `wget` trotzdem — aber ohne Source-Namespace-Matching landet der Call dann ebenfalls
> immer auf der Default-Route (`extern`), unabhängig vom Aufrufer-Namespace.

## Aufräumen

```bash
kubectl delete namespace testclientextern testclientintern
```
