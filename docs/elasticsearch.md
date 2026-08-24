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

## Aclaracion: Filebeat ES local free OK vs Prod Render 512MB descartado (no hay contradiccion)

**No hay contradiccion** entre "Filebeat ES local free ok" y "Prod descartado por 512MB OOM":

| Entorno | ES config | RAM | Estado verificado | Uso |
|---------|-----------|-----|-------------------|-----|
| **Local `yadinstore-cicd-demo`** | `docker.elastic.co/elasticsearch/elasticsearch:8.15.3` `discovery.type=single-node` `ES_JAVA_OPTS=-Xms512m -Xmx512m` `xpack.security.enabled=false` | 512m heap + ~200m overhead → ~700m container | `docker ps` → `yadinstore-elasticsearch Up 53m` `curl /_cluster/health → green 1 node 30 shards 100%` (verificado 23-08-2026, `yadinstore-logs-2026.08.23 green open 2 docs`) | Demo local, shop + observabilidad. Free ok porque WSL2/desktop tiene 4GB. `filebeat.yml` + `output.elasticsearch: http://elasticsearch:9200` funciona. |
| **Prod Render free tier** | Mismo ES intentaria levantar con 512MB limite hard del container | OOMKilled al boot (JVM 512m + lucene off-heap > limite) | Descartado documentado en `yadinStore-Spec/` y `deployed -Prod.md` | En prod free no se levanta ES. Alternativa: **ring 100 volatil** `GET /api/v1/observability/logs` + OTel `debug` (sin ES), o SaaS externo (Elastic Cloud free 14d). |

**Conclusión**: local `docker compose up -d elasticsearch kibana` sigue siendo la via demo/porcelain. Prod Render free usa ring volatil sin ES (no requiere Filebeat). Si migras a prod con RAM (>2GB) o Elastic Cloud, mismo `filebeat.yml` sirve sin cambios — solo cambia `hosts`.

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

**Errores comunes reportados 23-08-2026 y causa:**

| Sintoma | Causa raiz | Fix |
|---------|------------|-----|
| `PUT http://localhost:9200/yadinstore-logs-2026.08.23` → `405 Incorrect HTTP method` | En Postman/curl usaste **Method GET** (default del browser) en vez de **PUT**. El endpoint `/yadinstore-logs-*` solo acepta PUT para crear indice. `GET` da 405. | Cambiar dropdown a **PUT**. |
| `POST http://localhost:9200/yadinstore-logs-2026.08.23/_doc` → `404 index_not_found_exception` | Hiciste POST sin haber creado el indice antes (PUT). ES no hace auto-create si `action.auto_create_index` restringido o mapping requerido. | Hacer **PUT** primero, luego **POST**. |
| `_cat/indices` solo `.internal.*` sin `yadinstore-logs-*` | Sin PUT previo, indice nunca existio. Kibana Discover queda cargando infinito. | Secuencia PUT → POST → _cat. |

**Paso exacto Postman (replica curl verificado 23-08-2026 green 2 docs):**

1. **Health OK**
   - Method: `GET` | URL: `http://localhost:9200/_cluster/health?pretty` | Send → `{"status":"green","active_shards_percent_as_number":100.0}`

2. **Crear index template (opcional, idempotente)**
   - Method: `PUT` | URL: `http://localhost:9200/_index_template/yadinstore-logs`
   - Headers: `Content-Type: application/json`
   - Body → raw → JSON:
     ```json
     {
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
     }
     ```

3. **Crear index del dia con mapping ECS explicito** — **si usas Postman, fijate que Method sea PUT, no GET** (el 405 viene de aca)
   - Method: `PUT` | URL: `http://localhost:9200/yadinstore-logs-2026.08.23`
   - Headers: `Content-Type: application/json`
   - Body → raw → JSON:
     ```json
     {
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
     }
     ```
   - Send → `{"acknowledged":true,"shards_acknowledged":true,"index":"yadinstore-logs-2026.08.23"}`

4. **POST doc ERROR demo (ECS sanitizado, sin PII)** — requiere indice ya creado o da 404
   - Method: `POST` | URL: `http://localhost:9200/yadinstore-logs-2026.08.23/_doc`
   - Headers: `Content-Type: application/json`
   - Body:
     ```json
     {
       "@timestamp": "2026-08-23T12:00:00.000Z",
       "level": "ERROR",
       "logger": "com.yadinstore.demo",
       "message": "KafkaException: SASL timeout causeChain sanitized",
       "traceId": "demo-trace-abc123",
       "spanId": "span-err-001",
       "causeChain": "KafkaException: SASL auth failed -> Timeout after 30000ms",
       "service": "yadinstore-backend"
     }
     ```
   - → `{"result":"created"}`

5. **POST doc INFO demo**
   - Method: `POST` | URL: `http://localhost:9200/yadinstore-logs-2026.08.23/_doc`
   - Body:
     ```json
     {
       "@timestamp": "2026-08-23T12:05:00.000Z",
       "level": "INFO",
       "logger": "com.yadinstore.demo",
       "message": "Observability gateway ready",
       "traceId": "demo-trace-abc123",
       "spanId": "span-info-001",
       "causeChain": "",
       "service": "yadinstore-backend"
     }
     ```

6. **Verificar _cat/indices ahora SI muestra yadinstore-logs-*** — debe dar green 1 doc (verificado 24-08-2026: green 2 docs tras fix replica 0)
   - Method: `GET` | URL: `http://localhost:9200/_cat/indices/yadinstore-logs-*?v`
   - → `green open yadinstore-logs-2026.08.23 1 0 2 0 14.4kb 14.4kb` (docs.count=2)
   - Si ves `yellow open ... 1 1` (replica 1 en single-node) → `PUT http://localhost:9200/yadinstore-logs-2026.08.23/_settings` Body `{"index":{"number_of_replicas":0}}` → vuelve a `green`.

7. **Query**
   - `GET http://localhost:9200/yadinstore-logs-*/_search?pretty` con Body `{"query":{"match_all":{}}}` o via Postman `POST http://localhost:9200/yadinstore-logs-*/_search?pretty` Body same.

**Curl equivalente (si backend no corre, usa curl localhost:9200 directo — verificado OK):**

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
      "spanId": {"type": "keyword"},
      "causeChain": {"type": "text"},
      "service": {"type": "keyword"}
    }
  }
}'
# → {"acknowledged":true,"shards_acknowledged":true,"index":"yadinstore-logs-2026.08.23"}

# 4. POST doc ERROR + INFO (ECS sanitizado, sin PII)
curl -X POST http://localhost:9200/yadinstore-logs-2026.08.23/_doc -H "Content-Type: application/json" -d '{
  "@timestamp": "2026-08-23T12:00:00.000Z",
  "level": "ERROR",
  "logger": "com.yadinstore.demo",
  "message": "KafkaException: SASL timeout causeChain sanitized",
  "traceId": "demo-trace-abc123",
  "spanId": "span-err-001",
  "causeChain": "KafkaException: SASL auth failed -> Timeout after 30000ms",
  "service": "yadinstore-backend"
}'
curl -X POST http://localhost:9200/yadinstore-logs-2026.08.23/_doc -H "Content-Type: application/json" -d '{
  "@timestamp": "2026-08-23T12:05:00.000Z",
  "level": "INFO",
  "logger": "com.yadinstore.demo",
  "message": "Observability gateway ready",
  "traceId": "demo-trace-abc123",
  "spanId": "span-info-001",
  "causeChain": "",
  "service": "yadinstore-backend"
}'
# → {"result":"created"} x2

# 5. Verificar _cat/indices ahora si muestra yadinstore-logs-* — debe ser green 2 docs
curl http://localhost:9200/_cat/indices/yadinstore-logs-*?v
# → green open yadinstore-logs-2026.08.23 1 0 2 0 14.4kb 14.4kb

# 6. Query
curl -X POST "http://localhost:9200/yadinstore-logs-*/_search?pretty" -H "Content-Type: application/json" -d '{"query":{"match_all":{}}}'
# Fix yellow si quedo con replica 1:
curl -X PUT http://localhost:9200/yadinstore-logs-2026.08.23/_settings -H "Content-Type: application/json" --data-binary '{"index":{"number_of_replicas":0}}'
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

1. `http://localhost:5601` → **Management** → **Stack Management** → **Data Views** → **Create data view** → `yadinstore-logs-*` → **Time field** `@timestamp` → Create.
2. Si ya existe pero queda cargando infinito, borrar y recrear (el index debe existir primero — paso B arriba). Refresh fields (`*`).
3. **Discover** → seleccionar `yadinstore-logs-*` → filtrar `level: ERROR` o `traceId: demo-trace-abc123` → ver docs del POST demo + Filebeat.
4. Verificar mappings: `level` es `keyword` (filtrable), `message` es `text` (buscable).

### Verificacion: Kibana http://localhost:5601/app/home ya no da "Kibana server is not ready yet" despues de ES green + index creado

**Sintoma reportado:** `http://localhost:5601/app/home` → `Kibana server is not ready yet` (spinner infinito) aun con `curl /_cluster/health → green`.

**Causa:** Kibana espera a que ES este `green` **y** que el `.kibana` index migre. Si ES estaba `yellow` (replica 1 en single-node) o recien creado `yadinstore-logs-*` sin `number_of_replicas:0`, el cluster queda `yellow` y Kibana marca `available` pero tarda. Tambien si ES se levanto hace <30s, Kibana aun no termina migrations (`status: available` pero `migrated:0`).

**Fix verificado 24-08-2026:**

```bash
# 1. Esperar 30s tras docker compose up -d elasticsearch kibana
curl http://localhost:9200/_cluster/health?pretty # debe ser green 100%, no yellow
curl http://localhost:9200/_cat/indices/yadinstore-logs-*?v # green open 2 docs (si yellow → PUT _settings replica 0 arriba)
curl http://localhost:5601/api/status | jq .status.overall.level # → "available"
# Si da "available" pero /app/home aun spinner → esperar 30s mas, hard reload Ctrl+F5
```

2. **Crear Data View** (paso obligatorio tras index green):
   - `http://localhost:5601` → **Stack Management** → **Data Views** → **Create data view**
   - Name: `yadinstore-logs-*` → Timestamp field: `@timestamp` → Save.
   - Si ya existe y quedaba cargando infinito (porque indice no existia al crearlo), **borrar y recrear** o `Refresh field list`.

3. **Discover** → seleccionar `yadinstore-logs-*` → time picker `Last 15 minutes` o `Last 7 days` → deben aparecer 2 docs (ERROR + INFO). Filtros `level: ERROR` debe dar 1 hit.

> Nota: `Stack Management → Data Views` reemplaza al viejo `Index Patterns` desde Kibana 8.x. Ambos nombres refieren al mismo objeto.

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