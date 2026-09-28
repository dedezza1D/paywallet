{{- define "paywallet.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "paywallet.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "paywallet.selectorLabels" -}}
app.kubernetes.io/name: {{ include "paywallet.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "paywallet.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{ include "paywallet.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "paywallet.secretName" -}}
{{- default (include "paywallet.fullname" .) .Values.existingSecret -}}
{{- end -}}

{{- define "paywallet.web.fullname" -}}
{{- printf "%s-web" (include "paywallet.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Distinct from the API's selector, so the API's Service, PDB and HPA never count web pods. */}}
{{- define "paywallet.web.selectorLabels" -}}
app.kubernetes.io/name: {{ include "paywallet.name" . }}-web
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "paywallet.web.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{ include "paywallet.web.selectorLabels" . }}
app.kubernetes.io/component: web
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
