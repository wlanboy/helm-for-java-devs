# Zwei Versionen: intern/extern (Source-Namespace-Routing)

Der Chart deployt zwei Versionen derselben App parallel, konfiguriert in `values.yaml` unter `versions`. Damit lässt sich Istio Source-Namespace-Routing vorführen.

| Version | `sourceNamespace` | ConfigMap | `app.version-label` |
|---|---|---|---|
| `extern` | `testclientextern` | `helloworld-config-extern` | `extern` |
| `intern` | `testclientintern` | `helloworld-config-intern` | `intern` |

Jede Version bekommt ein eigenes Deployment, eine eigene ConfigMap aus `config/application-<name>.properties`
und ein eigenes DestinationRule-Subset. Der VirtualService routet Sidecar-zu-Sidecar-Traffic
über das Gateway `mesh` nach dem Namespace, aus dem der Call kommt. Ein Pod aus `testclientextern`
landet beim `extern`-Subset, ein Pod aus `testclientintern` beim `intern`-Subset. Traffic über den
externen Istio-Ingress `https://helloworld.tp.lan` hat keinen Source-Workload in diesen
Namespaces und landet immer auf der Default-Route `extern`.

> Der Envoy-Sidecar des aufrufenden Pods wertet das Source-Namespace-Matching aus. Die
> Testclient-Namespaces brauchen deshalb selbst Istio Sidecar-Injection, sonst greift die Regel nicht.

Das gilt für jeden Client. Die busybox-Pods weiter unten sind nur für einen schnellen Test mit `wget` da.

Ein Gegenbeispiel ist der [JavaHttpClient](../JavaHttpClient/javahttpclient-chart). Das ist ein
generischer HTTP-Client, der beliebige Ziel-URLs per `POST /client` weiterleitet. Er läuft im
Namespace `javahttpclient`, also weder in `testclientextern` noch in `testclientintern`. Ruft er
`http://helloworld.helloworld.svc.cluster.local:8080/version` auf, kommt mal `extern`, mal `intern`
zurück. Beim busybox-Test ist das Ergebnis dagegen fest.

Schuld ist `exportTo`. DestinationRule und VirtualService von `helloworld` exportieren ihre
Konfiguration nur nach `.`, `istio-ingress`, `istio-system`, `testclientextern` und
`testclientintern`. `javahttpclient` fehlt in dieser Liste, also schickt istiod dem Sidecar dort
die Routing-Regeln für den Host `helloworld` gar nicht erst. Ohne VirtualService und
DestinationRule kennt der Sidecar keine Subsets. Er behandelt den Aufruf wie normalen
Kubernetes-Service-Traffic und verteilt ihn auf alle Endpoints hinter dem Service `helloworld`.
Dessen Selector `app: helloworld` trifft `extern`- und `intern`-Pods gleichermaßen, siehe
[service.yaml](helmchart/templates/service.yaml). Daher der Zufallsmix.

## Testclient-Namespaces anlegen

Beide Namespaces brauchen das Label `istio-injection=enabled`, damit ihre Pods einen Sidecar bekommen.
Setz das Label, bevor du den ersten Pod startest.

```bash
kubectl create namespace testclientextern
kubectl create namespace testclientintern

kubectl label namespace testclientextern istio-injection=enabled
kubectl label namespace testclientintern istio-injection=enabled
```

## Routing mit einem busybox-Pod testen

`busybox` hat kein `curl`, aber `wget` reicht. Pro Testclient-Namespace ruft ein Pod den
`/version`-Endpunkt aus dem `VersionController` auf.

```bash
# Aufruf aus testclientextern, erwartet versionLabel "extern"
kubectl run testclient -n testclientextern --image=busybox --restart=Never --rm -it -- \
  wget -qO- http://helloworld.helloworld.svc.cluster.local:8080/version

# Aufruf aus testclientintern, erwartet versionLabel "intern"
kubectl run testclient -n testclientintern --image=busybox --restart=Never --rm -it -- \
  wget -qO- http://helloworld.helloworld.svc.cluster.local:8080/version
```

Die Antwort des ersten Aufrufs sieht im Auszug so aus:

```json
{"versionLabel":"extern","pod":"helloworld-extern-..."}
```

Der zweite Aufruf liefert `"versionLabel":"intern"`. Zum Vergleich landet der Aufruf über den
externen Ingress immer auf `extern`:

```bash
curl https://helloworld.tp.lan/version
```

> Fehlte das Namespace-Label beim Start des Pods, läuft er ohne Sidecar. `wget` bekommt trotzdem
> eine Antwort, aber ohne Source-Namespace-Matching landet jeder Call auf der Default-Route `extern`,
> egal aus welchem Namespace er kommt.

## Aufräumen

Das Löschen der Namespaces entfernt auch alle Testclient-Pods darin.

```bash
kubectl delete namespace testclientextern testclientintern
```
