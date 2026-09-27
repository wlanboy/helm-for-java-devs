{{/*
Basis-Name für alle Ressourcen.
*/}}
{{- define "helloworld.name" -}}
{{- .Values.deploymentName -}}
{{- end }}

{{/*
Chart-Label nach Helm-Konvention: <name>-<version>, "+" ist in Label-Werten nicht erlaubt.
*/}}
{{- define "helloworld.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{/*
Standard-Labels für alle Ressourcen (app.kubernetes.io/* + helm.sh/chart).
Werden u.a. von Prometheus-Operator-Selektoren, Grafana und ArgoCD ausgewertet.
- instance = Helm Release Name; ermöglicht mehrere Instanzen derselben App im Cluster.
- version  = Image Tag; gequotet, damit Tags wie "1.10" nicht als Zahl interpretiert werden.
*/}}
{{- define "helloworld.labels" -}}
app: {{ include "helloworld.name" . }}
app.kubernetes.io/name: {{ include "helloworld.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Values.image.tag | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ include "helloworld.chart" . }}
{{- end }}

{{/*
Name des ServiceAccounts.
*/}}
{{- define "helloworld.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "helloworld.name" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end }}

{{/*
Namespaces, an die DestinationRule und VirtualService exportiert werden:
eigener Namespace, Ingress-Namespace und zusätzliche Istio-Namespaces.
*/}}
{{- define "helloworld.istioExportTo" -}}
- "."
- {{ .Values.istio.ingressNamespace }}
{{- range .Values.istio.extraExportNamespaces }}
- {{ . }}
{{- end }}
{{- end }}

{{/*
application.properties einer Version: gemeinsame Basis (config/application.properties)
plus versionsspezifische Overrides (config/application-<name>.properties).
Bei doppelten Keys gewinnt in Java-Properties der letzte Eintrag, also der Override.
Aufruf: include "helloworld.properties" (dict "root" $ "version" $v)
*/}}
{{- define "helloworld.properties" -}}
{{- $root := .root -}}
{{- $base := $root.Files.Get "config/application.properties" -}}
{{- if not $base -}}
{{- fail "config/application.properties fehlt im Chart" -}}
{{- end -}}
{{- $file := printf "config/application-%s.properties" .version.name -}}
{{- $override := $root.Files.Get $file -}}
{{- if not $override -}}
{{- fail (printf "%s fehlt im Chart – für jede Version unter versions wird eine Properties-Datei benötigt" $file) -}}
{{- end -}}
{{ tpl $base $root | trim }}
{{ tpl $override $root | trim }}
{{- end }}
