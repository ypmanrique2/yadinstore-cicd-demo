# Grafana + Prometheus — Métricas (free tier)

## Qué es

- **Prometheus**: recolector de métricas time-series (pull model, `scrape`).
- **Grafana**: dashboarding y alertas sobre cualquier fuente de datos.

## Stack local (free)

```bash
docker compose up -d grafana
# Grafana: http://localhost:3000 (admin/admin — cambiar en prod)
```

> En este repo el compose levanta Grafana standalone. En el proyecto real de YadinStore
> se usa para métricas de la infra (mongo, kafka, backend) — Kibana/ElasticSearch quedan
> para logs (ver [`elasticsearch.md`](./elasticsearch.md)).

## Conceptos clave

| Concepto | Descripción |
|---|---|
| **Data source** | Origen de datos conectado a Grafana (Prometheus, InfluxDB, etc.) |
| **Dashboard** | Paneles (panels) con queries sobre métricas |
| **Alerting** | Reglas que disparan notificaciones (email, Slack, webhook) |
| **Provisioning** | Dashboards/datasources declarados en YAML (Infra as Code) |

## Buenas prácticas

1. **Provisioning as code**: definir datasources y dashboards en `provisioning/` (YAML), no a mano en la UI.
2. **USE_RED / RED method**: monitorear Rate, Errors, Duration de cada servicio.
3. **Alertas sobre SLOs**, no sobre métricas crudas sueltas.
4. **Retención** acotada en Prometheus (p.ej. 15 días) y archivar en long-term storage.

## Recursos
- [Grafana docs](https://grafana.com/docs/)
- [Prometheus docs](https://prometheus.io/docs/)