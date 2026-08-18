{{/*
Names. There is exactly one release of this chart per cluster, in the tenant's
namespace, so the objects are simply named after the chart; the truncation is
the usual guard against a name overrunning a DNS label. The release name
("hello-<cloud>" when Argo CD applies it) is what tells two clusters' objects
apart in Argo CD, not the object names.
*/}}
{{- define "hello.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
The Kubernetes Secret External Secrets writes and the Deployment mounts. Named
here rather than in two templates, because those two have to agree.
*/}}
{{- define "hello.secretName" -}}
{{- printf "%s-secret" (include "hello.name" .) -}}
{{- end -}}

{{- define "hello.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "hello.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: onek8s
onek8s.io/cloud: {{ .Values.cloud | quote }}
onek8s.io/environment: {{ .Values.environment | quote }}
onek8s.io/tenant: {{ .Values.tenant | quote }}
{{- end -}}

{{- define "hello.selectorLabels" -}}
app.kubernetes.io/name: {{ include "hello.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
