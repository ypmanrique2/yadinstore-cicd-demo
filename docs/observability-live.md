# Observabilidad Live — Prometheus/Grafana local vs Grafana Cloud+Loki (Prod)

> **Local free ok → Prod Grafana Cloud roadmap §8 `avance_21-08-2026.md`** — la observabilidad ya funciona en local con `ci-cd-infra`; en prod free tier ES/Kibana es inviable (512MB vs 1-2GB heap, ADR-014). Roadmap: Grafana Cloud free + Loki OTLP, sin tocar `SecurityConfig` hoy (Fase1 `permitAll` agregados).

## Stack local (free tier) — vivo hoy

`ci-cd-infra/docker-compose.yml` (ver `puerto&ambiente.md`) corre sin ES en prod, con ES local opcional:

```
Postgres 5432 · Mongo 27017 · Kafka 9092 · Kafka-UI 8090
otel-collector 4317/4318 · Prometheus 9090 · Grafana 3000 · MailHog 1025/8025 · Jenkins 8081 · backend 8080
ElasticSearch 9200 · Kibana 5601  (local only, single-node xpack.security.enabled=false)
```

```bash
docker compose -f ci-cd-infra/docker-compose.yml up -d prometheus grafana jenkins backend

# Prometheus: http://localhost:9090
# Grafana   : http://localhost:3000 (admin/admin)
# Kibana    : http://localhost:5601  (si ES levantado)
# ES health : http://localhost:9200/_cluster/health
# Jenkins   : http://localhost:8081
```

### Prometheus 9090 — métricas

- Backend expone `http://localhost:8080/actuator/prometheus` (dev; en prod `401/404` — solo gateway).
- Query local ya funciona:

```bash
curl "http://localhost:9090/api/v1/query?query=outbox_pending" | jq .
curl "http://localhost:9090/api/v1/query?query=kafka_publish_errors_total" | jq .
curl "http://localhost:8080/actuator/prometheus" | grep outbox_pending
# hoy: outbox.pending no instrumentado en prod (solo http_server_requests_* en pom.xml:116)
# roadmap Fase2: Gauge + Counters
```

- Agente `jenkins-obs-agent.ps1` hace scrape dummy cada `5s` de `http://localhost:9090/api/v1/query?query=outbox_pending` y lo pushea a `POST /api/jenkins/metrics` → `yadinstore-jenkins-obs-live` `obs.outboxPending` (estructura lista, `0` ok).

### Grafana 3000 — dashboards as-code

- `ci-cd-infra/monitoring/grafana/dashboards/*.json` provisionados vía YAML (Provisioning as code, ver `grafana.md:29`).
- Paneles roadmap `observability-live.json` (Fase2): `outbox.pending`, `kafka.publish.errors rate(5m)`, `http_server_requests latency p95`, `jvm.memory.used`.
- Data source Prometheus → `http://prometheus:9090` (local). En prod → OTLP a Grafana Cloud.

### ElasticSearch 9200 + Kibana 5601 — logs (ELK local only)

```bash
docker compose up -d elasticsearch kibana
curl "http://localhost:9200/_cluster/health?pretty"
# → green, single-node

# Ingesta: Filebeat/Logstash opcional, hoy logs JSON directos desde Spring Boot (ver abajo)
curl "http://localhost:9200/_cat/indices?v"
```

- Conceptos: Index (≈tabla), Document (log JSON), Mapping, ILM hot/warm/cold/delete (ver `elasticsearch.md:28`).
- **Buenas prácticas** (ELK): ILM 30d, mapping explícito, logs estructurados JSON, index patterns + aliases.

> **Prod inviable**: Render free `512MB/0.1CPU` no puede correr ES heap `1-2GB` + Kibana 0.5GB → OOM/GC thrash garantizado (explore #674 risks + design ADR-014). Ver § *Por qué ES descartado* abajo.

## Prod Grafana Cloud+Loki (roadmap Fase2) — sin ES

### Por qué ES descartado en prod free tier (ADR-014)

| Constraint | Detalle |
|---|---|
| RAM Render free | 512 MB / 0.1 CPU (`render.yaml` free). ES 8+ recomienda heap 1-2 GB + 0.5 CPU mínimo; Kibana +0.5 GB. |
| Coste | 2 servicios free (BE + jenkins-obs-live) ~750h/mes c/u con UptimeRobot; 3er servicio 750h + disco 1GB (no free) + cold-start volatiliza ring. |
| Retención ring 200 | 400s ventana (~6min); métricas críticas van a Cloud persist, ring solo “live view”. Pérdida por sleep 15m aceptada. |
| Diagnóstico local | `ci-cd-infra` Prometheus 9090 + Grafana 3000 siguen vivos para dev; no bloquea prod. |

**Decisión**: Grafana Cloud free (10k series Prometheus, 50GB logs, 50GB traces, 14d retención) + Loki — 0 RAM prod, OTLP nativo, dashboards as-code, vendor 14d aceptado. ES fica local dev.

### Roadmap Cloud free — 5 pasos (post-T104)

1. **Provider free**: Crear cuenta [Grafana Cloud free](https://grafana.com/pricing/) — 10k series, 50GB logs, 50GB traces, 14d. Obtener `GRAFANA_CLOUD_API_KEY` y `OTLP endpoint` (`https://otlp-gateway-prod-us-central-0.grafana.net/otlp`).
2. **OTel Collector** `ci-cd-infra/docker/otel/otel-config.yaml:1-29` hoy `debug` → exporter `otlphttp/grafana-cloud`:

```yaml
exporters:
  otlphttp/grafana-cloud:
    endpoint: https://otlp-gateway-prod-us-central-0.grafana.net/otlp
    headers:
      Authorization: "Basic ${GRAFANA_CLOUD_API_KEY}"
  loki:
    endpoint: https://logs-prod-us-central1.grafana.net/loki/api/v1/push

service:
  pipelines:
    metrics:  { receivers:[otlp], exporters:[otlphttp/grafana-cloud] }
    logs:     { receivers:[otlp], exporters:[loki] }
```

3. **Instrumentación BE Fase2** (no este batch) `OutboxService.java:66,76` + `KafkaPublisher` — `MeterRegistry`:

```java
Gauge.builder("outbox.pending", repo, r -> r.countByStatus(PENDING)).tag("application","yadinstore").register(registry);
Counter.builder("outbox.published").register(registry);
Counter.builder("outbox.failed").tag("cause","...sanitizado250...").register(registry);
Counter.builder("kafka.publish.errors").tag("topic","yadinstore.*.v1").tag("causeChain","...250...").register(registry);
```
  Nombres exactos Spec Delta2: `outbox.pending` (Gauge), `outbox.published`, `outbox.failed`, `kafka.publish.errors`, `http.server.requests`, `jvm.memory.used`.

4. **Logs ECS sanitizados** `application-prod.yml:104` ya emite `logging.structured.format.console: ecs` → JSON `traceId,spanId,level,logger,message,causeChain` (sin `password,token,secret,email,tenant payload`). Forwarder OTel a Loki filtrable `level=ERROR` `traceId`.

```json
// ECS ejemplo (cause sanitizado 250c)
{ "timestamp":"2026-08-21T22:00:00Z","level":"ERROR","traceId":"abc123","spanId":"xyz","logger":"OutboxService","message":"publish failed","causeChain":"SASL authentication failed password=***" }
```

5. **API gateway Fase2** `SecurityConfig:310` + `application-prod.yml:74`:

```yaml
management.endpoints.web.exposure.include: health   # PROD: no prometheus directo, solo gateway
# Fase2 gateway: GET /api/v1/observability/metrics|logs  ADMIN/VIEWER, rate-limit 10/min
# hoy T101: SecurityConfig intacto (permitAll solo yadinstore-jenkins-obs-live agregados)
```

### NFR free tier

- Latencia live &lt;5s (poll 2s + agente 2s), RAM Node &lt;50MB, BE &lt;400MB, UptimeRobot 5m, CSP `default-src 'self'`, rate-limit POST 10/min IP, `sync:false` sin secrets, `autoDeploy:true`.

## ECS sanitizado — sin PII

- `sanitizeCause(cause,250)` en `server.js:72` y `KafkaActivityController:250` + `OutboxService` oculta `password=***`, `secret=***`, `token=***`, `api_key=***`, `email → ***@***`, 5 niveles, sin stacktrace, causeChain max 250c.
- Dashboard `esc()+textContent` XSS-safe (job `"<script>"` → literal).
- Logs futuros: `causeChain:250` tag `cause`, filtrable en Loki `| json | causeChain=~"password=\\*\\*\\*"`.

## Verificación

```bash
# Local Prometheus responde
curl http://localhost:9090/api/v1/query?query=up | jq .
curl http://localhost:3000/api/health  # Grafana
curl http://localhost:9200/_cluster/health?pretty  # ES si levantado

# Outbox gauge roadmap (Fase2) — hoy 0 dummy ok
curl http://localhost:3000/api/jenkins/live  | jq .obs
# → {"outboxPending":0,"kafkaPublishErrors":0,"serverTime":"..."}

# Prod tras T103 — ES no existe, Cloud es source-of-truth
curl https://yadinstore-jenkins-obs-live.onrender.com/api/jenkins/live | jq .obs
# → mismo obs dummy, no 9200

# Fase2 futura (gateway VIEWER)
# curl -H "Authorization: Bearer $VIEWER" https://yadinstore-backend.onrender.com/api/v1/observability/metrics | jq .data."outbox.pending"
# → número (401 hoy directo a /actuator/prometheus es esperado 404)
```

## Comparativa local vs prod

| Aspecto | Local (`ci-cd-infra`, free docker) | Prod Render free (roadmap) |
|---|---|---|
| Métricas | Prometheus 9090 + Grafana 3000 scraping `actuator/prometheus` directo (401 libre en dev) | Prometheus directo `401/404` (SecurityConfig + exposure health only); métricas vía `GET /api/v1/observability/metrics` gateway VIEWER + OTLP → Grafana Cloud 10k series 14d |
| Logs | ElasticSearch 9200 + Kibana 5601 single-node `xpack.security.enabled=false` (ILM 30d) | ES descartado 512MB; ECS JSON → OTel → Loki Cloud 50GB 14d, filtrable `level=ERROR` `traceId` |
| Dashboard live | `docker-dashboard.html` 2s alive 15s + `jenkins-dashboard.html` ring 200 dummy obs | `yadinstore-jenkins-obs-live` + `jenkins-obs-agent.ps1` 3 streams (jenkins queue/docker/metrics) → Pages `docs/observability-live.html` (este archivo) |
| Retención | Prometheus 15d, ES 30d, ring 400s | Cloud 14d, ring volátil 400s (UptimeRobot 5m) |
| Coste | 0 (docker) | 0 (free tier Cloud) |

## Links

- Local: `http://localhost:9090` Prometheus · `http://localhost:3000` Grafana · `http://localhost:5601` Kibana
- Live prod (tras T103): `https://yadinstore-jenkins-obs-live.onrender.com/api/jenkins/live` · `https://yadinstore-jenkins-obs-live.onrender.com/jenkins-dashboard.html`
- Pages (tras T102): `https://ypmanrique2.github.io/yadinstore-cicd-demo/` badge · `https://ypmanrique2.github.io/yadinstore-cicd-demo/docs/observability-live.html` (este archivo)
- Avance: `avance_21-08-2026.md` §8 (ES descartado + Cloud roadmap) y §6 plan Fase1
- Docs relacionados: [`jenkins-live.md`](./jenkins-live.md) · [`grafana.md`](./grafana.md) · [`elasticsearch.md`](./elasticsearch.md) · [`jenkins.md`](./jenkins.md)
- Spec #676 Delta2/3 + Design #677 ADR-014 (engram YadinStore)
```

