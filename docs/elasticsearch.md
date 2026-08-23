# ElasticSearch + Kibana — Logs (ELK, free tier)

## Qué es

- **ElasticSearch**: motor de búsqueda/agregación distribuido (documentos JSON, inverted index).
- **Kibana**: UI para explorar logs, dashboards y alertas sobre ES.
- (Filebeat/Logstash opcionales: shipping de logs al cluster.)

## Stack local (free)

```bash
docker compose up -d elasticsearch kibana
# Kibana  : http://localhost:5601
# ES      : http://localhost:9200 (health: /_cluster/health)
```

> Demo: single-node con `xpack.security.enabled=false` (solo local). En prod:
> multi-node, TLS y auth obligatorios.

## Caso de uso en YadinStore

Centralizar logs del backend (Spring Boot) para:
- Correlacionar errores con el tiempo (p.ej. el flujo de notificaciones).
- Buscar por `traceId`/`requestId` (correlación de requests).
- Alertar en Kibana sobre patrones de error (5xx, timeouts).

## Conceptos clave

| Concepto | Descripción |
|---|---|
| **Index** | Colección de documentos con mapping (≈ tabla en SQL) |
| **Document** | Un log/evento en JSON |
| **Mapping** | Esquema de campos + tipos (text, keyword, date...) |
| **Ingest pipeline** | Transformaciones al ingerir (parseo, enriquecimiento) |
| **ILM (Index Lifecycle Management)** | Políticas de hot/warm/cold/delete |

## Buenas prácticas

1. **ILM**: retención por política (p.ej. 30 días), no borrar índices a mano.
2. **Mapping explícito** para campos de alto cardinality (no dejar ES adivinar).
3. **Logs estructurados** (JSON) desde la app → campos parseables.
4. **Index patterns + aliases** para versionar mappings sin downtime.

## Diagnostico: por que yadinstore-logs-* no aparece (Kibana carga infinito)

**Sintoma reportado 23-08-2026:**

```bash
curl http://localhost:9200/_cluster/health?pretty # → green 1 node 29 shards 100% OK
curl http://localhost:9200/_cat/indices?v          # → solo .internal.* y .kibana*, NO yadinstore-logs-* (0 docs)
# Kibana Management → yadinstore-logs-* queda cargando infinito
```

**Causa raiz:** No existe shipping de logs a ES. El backend hoy solo hace OTel `debug` + ring 100 volatil (`ObservabilityLogBuffer.java:95` / `ObservabilityController.java:128` `GET /logs` VIEWER), y escribe logs JSON ECS a stdout/archivo local pero **ningun Filebeat/Logstash los envia a `http://elasticsearch:9200`**. Sin productor, el index `yadinstore-logs-*` nunca se crea (ES crea indices solo al primer POST/ingest), por eso Kibana no puede resolver el index pattern.

## Solucion A: Filebeat minimo (recomendado local)

`yadinstore-cicd-demo/filebeat.yml` ya incluido en este repo (ver raiz):

```yaml
filebeat.inputs:
  - type: log
    enabled: true
    paths: ["/var/log/yadinstore/*.log"]
    json.keys_under_root: true
    json.add_error_key: true
    json.message_key: message
output.elasticsearch:
  hosts: ["http://elasticsearch:9200"]
  index: "yadinstore-logs-%{+yyyy.MM.dd}"
setup.ilm.enabled: true
setup.ilm.rollover_alias: "yadinstore-logs"
setup.template.name: "yadinstore-logs"
setup.template.pattern: "yadinstore-logs-*"
```

Pasos:

```bash
# 1. Backend debe loguear ECS JSON a /var/log/yadinstore/*.log
#    application.yml: logging.structured.format.console: ecs  (ya en prod) + file appender si hace falta
#    Mount del log dir en docker-compose.yml filebeat service:
#    volumes: ["./logs:/var/log/yadinstore:ro", "./filebeat.yml:/usr/share/filebeat/filebeat.yml:ro"]

# 2. Levantar Filebeat (descomentar servicio en docker-compose.yml si esta comentado)
docker compose up -d filebeat
docker logs -f yadinstore-filebeat  # debe mostrar "Harvester started for file .../yadinstore.log" + "PublishEvents 1"

# 3. Verificar index creado
curl http://localhost:9200/_cat/indices/yadinstore-logs-*?v
# → green open yadinstore-logs-2026.08.23 docs.count 1+
```

> Alternativa OTel native: si usas OTel Collector con exporter `elasticsearch` directo, Filebeat es opcional. Hoy `ci-cd-infra/docker/otel/otel-config.yaml` usa `debug` + `otlphttp/grafana_cloud`; para ES local añadir exporter `elasticsearch` + pipeline `logs: {receivers:[otlp], exporters:[elasticsearch]}`.

## Solucion B: sin Filebeat — backend ring 100 + POST manual a ES (demo rapida)

Si no queres correr Filebeat, tenes dos atajos para que `yadinstore-logs-*` exista y Kibana Discover lo vea:

### B1. Crear index + mapping ECS y POST un doc demo (verifica que _cat/indices muestra green)

```bash
# 1. Health OK
curl http://localhost:9200/_cluster/health?pretty

# 2. Crear index template (opcional, idempotente)
curl -X PUT http://localhost:9200/_index_template/yadinstore-logs -H "Content-Type: application/json" -d '{
  "index_patterns": ["yadinstore-logs-*"],
  "template": {
    "settings": {"number_of_shards": 1, "number_of_replicas": 0},
    "mappings": {
      "properties": {
        "@timestamp": {"type": "date"},
        "message": {"type": "text"},
        "level": {"type": "keyword"},
        "logger": {"type": "keyword"},
        "traceId": {"type": "keyword"},
        "spanId": {"type": "keyword"},
        "causeChain": {"type": "text"},
        "service": {"type": "keyword"}
      }
    }
  },
  "priority": 500
}'

# 3. Crear index del dia con mapping ECS explicito (inline si no usas template)
curl -X PUT http://localhost:9200/yadinstore-logs-2026.08.23 -H "Content-Type: application/json" -d '{
  "mappings": {
    "properties": {
      "@timestamp": {"type": "date"},
      "message": {"type": "text"},
      "level": {"type": "keyword"},
      "logger": {"type": "keyword"},
      "traceId": {"type": "keyword"},
      "causeChain": {"type": "text"}
    }
  }
}'
# → {"acknowledged":true,"shards_acknowledged":true,"index":"yadinstore-logs-2026.08.23"}

# 4. POST doc demo (ECS sanitizado, sin PII)
curl -X POST http://localhost:9200/yadinstore-logs-2026.08.23/_doc -H "Content-Type: application/json" -d '{
  "@timestamp": "2026-08-23T00:00:00.000Z",
  "level": "INFO",
  "logger": "com.yadinstore.demo",
  "message": "Observability gateway ready",
  "traceId": "demo-trace-abc123",
  "causeChain": "",
  "service": "yadinstore-backend"
}'
# → {"result":"created"}

# 5. Verificar _cat/indices ahora si muestra yadinstore-logs-*
curl http://localhost:9200/_cat/indices/yadinstore-logs-*?v
# → green open yadinstore-logs-2026.08.23 1 0 1 0 5kb 5kb

# 6. Query
curl "http://localhost:9200/yadinstore-logs-*/_search?pretty" -H "Content-Type: application/json" -d '{"query":{"match_all":{}}}'
```

### B2. Usar el ring 100 del backend como fuente Live (sin ES)

`GET /api/v1/observability/logs?level=ERROR&limit=20` ya devuelve ring 100 sanitizado sin necesidad de ES (prod free tier). Ver `yadinStore-Spec/avance_22-08-2026.md` §3.

Ingest pipeline demo (enriquecimiento opcional):

```bash
curl -X PUT http://localhost:9200/_ingest/pipeline/yadinstore-ecs -H "Content-Type: application/json" -d '{
  "description": "Parse ECS JSON, sanitize causeChain",
  "processors": [
    {"set": {"field": "service", "value": "yadinstore"}},
    {"trim": {"field": "message"}},
    {"lowercase": {"field": "level"}}
  ]
}'
# Uso: POST /yadinstore-logs-2026.08.23/_doc?pipeline=yadinstore-ecs
```

## Como ver en Kibana Discover

1. `http://localhost:5601` → **Management** → **Stack Management** → **Index Patterns** → **Create index pattern** → `yadinstore-logs-*` → **Time field** `@timestamp` → Create.
2. Si ya existe pero queda cargando infinito, borrar y recrear (el index debe existir primero — paso B arriba). Refresh fields (`*`).
3. **Discover** → seleccionar `yadinstore-logs-*` → filtrar `level: ERROR` o `traceId: demo-trace-abc123` → ver docs del POST demo + Filebeat.
4. Verificar mappings: `level` es `keyword` (filtrable), `message` es `text` (buscable).

## Troubleshooting Docker Desktop 500 //./pipe/dockerDesktopLinuxEngine/_ping

Sintoma:

```
docker compose up -d elasticsearch kibana grafana
# → 500 Internal Server Error //./pipe/dockerDesktopLinuxEngine/_ping
docker ps
# → request returned 500 ... check if server supports API version
```

Diagnostico 23-08-2026: `Get-Service com.docker.service` → `Stopped`, `docker context ls` → `desktop-linux *` en `npipe:////./pipe/dockerDesktopLinuxEngine` pero service parado. WSL2 sigue con puertos 9200/5601/3000 abiertos (containers zombie) pero daemon no responde.

Solucion:

```powershell
# 1. Restart Docker Desktop service
Restart-Service com.docker.service
# o services.msc → Docker Desktop Service → Restart

# 2. Si no revive: WSL shutdown + relanzar Docker Desktop
wsl --shutdown
# Esperar 10s, abrir Docker Desktop desde Start Menu, esperar whale verde

# 3. Si queda kindest/node control-plane colgado
wsl -d docker-desktop -- kill 1  # reinicia init del distro
# o Docker Desktop → Troubleshoot → Restart / Reset to factory defaults (ultimo recurso)

# 4. Verificar
docker context use desktop-linux
docker ps
curl http://localhost:9200/_cluster/health?pretty  # debe volver a green tras up -d
docker compose -f yadinstore-cicd-demo/docker-compose.yml up -d elasticsearch kibana grafana
```

## Recursos
- [Elastic docs](https://www.elastic.co/docs)
- [Spring Boot + Elastic APM / logging](https://docs.spring.io/spring-boot/reference/actuator/logging.html)
- Filebeat reference: https://www.elastic.co/guide/en/beats/filebeat/current/filebeat-installation.html
- Troubleshooting Grafana ChunkLoadError: ver `docs/grafana.md` § Troubleshooting