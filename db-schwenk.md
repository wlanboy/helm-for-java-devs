# MySQL aktivieren (Feature Flag)

Die App startet standardmäßig mit einer In-Memory-H2-Datenbank. Der Wechsel auf eine externe MySQL-Instanz erfolgt über das Feature Flag `mysql.external.enabled` in `values.yaml`.

## 1. Lokale MySQL-Instanz starten

```bash
cd mysql
docker-compose up -d
```

Startet MySQL auf Port 3306 mit Datenbank `restdata` und User `restdata` (Passwort: `restdata`).

## 2. DNS für Istio ServiceEntry prüfen

Istio registriert den Hostnamen `mysql.extern.big.lan` als `ServiceEntry` im Mesh. CoreDNS muss diesen Namen auflösen können — sonst kann der Envoy-Sidecar keine Verbindung aufbauen.

DNS-Auflösung aus dem `helloworld`-Namespace prüfen:

```bash
kubectl run dnstest -it --rm --image=busybox --restart=Never -n default -- nslookup mysql.extern.big.lan
kubectl run dnstest -it --rm --image=busybox --restart=Never -n helloworld -- nslookup mysql.extern.big.lan
```

Erwartet: eine IP-Antwort. Schlägt die Auflösung fehl, `resolution: STATIC` mit der Docker-Bridge-IP verwenden (siehe Schritt 3).

## 3. values.yaml anpassen

```yaml
mysql:
  external:
    enabled: true
    host: mysql.extern.big.lan
    port: 3306
    resolution: STATIC      # STATIC wenn kein DNS – IP direkt angeben
    address: "172.19.0.1"   # Docker-Bridge-IP (Äquivalent zu host.docker.internal)
    database: restdata
    username: "restdata"
    password: "restdata"
```

Bei `resolution: DNS` entfällt `address` – Istio löst den Hostnamen über CoreDNS auf.

## 4. Helm-Update deployen

```bash
helm upgrade --install helloworld helmchart/ --namespace helloworld
```

## 5. Verbindung prüfen

```bash
curl https://helloworld.tp.lan/db
```

Erwartet:

```json
{"connected":true,"type":"MySQL","version":"8.x.x","url":"jdbc:mysql://mysql.extern.big.lan:3306/restdata"}
```
