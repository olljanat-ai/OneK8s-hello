{{/*
Names. There is one release of this chart, in the tenant's namespace on the
hub, so the objects are simply named after the chart; the truncation is the
usual guard against a name overrunning a DNS label.
*/}}
{{- define "db-hello.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "db-hello.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "db-hello.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: onek8s
onek8s.io/cloud: {{ .Values.cloud | quote }}
onek8s.io/environment: {{ .Values.environment | quote }}
onek8s.io/tenant: {{ .Values.tenant | quote }}
{{- end -}}

{{- define "db-hello.selectorLabels" -}}
app.kubernetes.io/name: {{ include "db-hello.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
