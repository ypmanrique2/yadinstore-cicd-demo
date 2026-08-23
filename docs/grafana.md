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

## Troubleshooting Grafana ChunkLoadError + LoginPage-react19 missing chunk 3883

Sintoma 23-08-2026:

```
http://localhost:5601 Kibana y http://localhost:3000 Grafana
ChunkLoadError LoginPage-react19 missing chunk 3883
Grafana latest vs 10.4.3 mismatch
```

Causa raiz: `yadinstore-cicd-demo/docker-compose.yml` tenia `grafana/grafana:12.4.8` (latest) mientras `ci-cd-infra/docker-compose.yml` pinnea `10.4.3`. Al hacer `docker compose up` sin `pull`, queda layer vieja 10.4.3 + nuevo JS bundle 12.x, y el browser cachea `public/build/*.js` viejo. React19 chunk `3883` no existe en 12.4.8, el loader falla. Grafana reload muestra pantalla blanca / infinite spinner.

Solucion (elegir una):

```bash
# A. Pinneado + limpieza total (recomendado — ya aplicado en docker-compose.yml:10.4.3)
docker compose down -v grafana   # borra grafana_data (dashboards provisioning se recrean)
docker compose pull grafana      # baja 10.4.3 explicit
docker compose up -d grafana
# Verificar: docker logs yadinstore-grafana | grep "Grafana.*10.4.3"

# B. Sin borrar volumen (si tenes dashboards a mano sin provisioning)
docker compose down grafana
docker volume rm yadinstore-cicd-demo_grafana_data  # o grafana_data segun project name
docker compose pull grafana && docker compose up -d grafana

# C. Solo browser (si el fix A ya esta pero sigue chunk error)
# Hard reload: Ctrl+F5 (Win) / Cmd+Shift+R (Mac) + Clear site data
# Incognito: abrir http://localhost:3000 en ventana incognito (sin cache)
# DevTools → Application → Clear storage → Clear site data + unregister service workers
```

Prevencion:

- Nunca usar `grafana:latest` en compose — siempre pinnear `grafana/grafana:10.4.3` (o `12.4.8` si todos los envs migran juntos). Ver `yadinstore-cicd-demo/docker-compose.yml:32` pinneado.
- `ci-cd-infra` y `yadinstore-cicd-demo` deben usar misma version (hoy `10.4.3` en ambos).
- Si migras a 12.x, hacer `down -v` + `pull` coordinado y vaciar cache navegador.

## Grafana reload error (probe liveness)

Si Grafana no responde tras fix pero `docker ps` muestra `Up`, revisar `http://localhost:3000/api/health` → debe dar `{"database":"ok","version":"10.4.3"}`. Si da 302 a `/login`, es normal (auth). ChunkLoadError ya no debe aparecer.

## Recursos
- [Grafana docs](https://grafana.com/docs/)
- [Prometheus docs](https://prometheus.io/docs/)