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

## Data source Prometheus — http://prometheus:9090 (local)

> Grafana ya fix pin `10.4.3` y `docker compose pull` ok (ver Troubleshooting abajo), pero falta datasource.

**Importante: dónde está Prometheus segun compose:**

| Compose | Prometheus URL | Grafana → Prometheus |
|---------|---------------|----------------------|
| `yadinstore-cicd-demo/docker-compose.yml` | **NO incluye Prometheus** (solo grafana standalone) | No hay `http://prometheus:9090` resoluble. Usar host `http://host.docker.internal:9090` si levantas `ci-cd-infra` aparte, o provisionar prometheus en mismo network. |
| `ci-cd-infra/docker-compose.yml` | `prom/prometheus:v2.52.0` `container_name: yadin-prometheus` `http://prometheus:9090` (service name `prometheus` en `yadin-net`) | Grafana en mismo `yadin-net` resuelve `http://prometheus:9090` por service name. |

**Opción A — UI (rápida, verificada):**

1. `http://localhost:3000` → Login (`admin` / tu password cambiado — ver § Admin password abajo).
2. **Connections** → **Data sources** → **Add data source** → **Prometheus**.
3. `Name: Prometheus` | `URL: http://prometheus:9090` (si usas `yadinstore-cicd-demo` sin prometheus: usar `http://host.docker.internal:9090` tras `docker compose -f ci-cd-infra/docker-compose.yml up -d prometheus`, o `http://yadin-prometheus:9090` si unes networks).
4. **Save & test** → debe dar `Successfully queried the Prometheus API` (green). Si `Bad Gateway`: prometheus no está up — `docker ps | grep prometheus` y `curl http://localhost:9090/-/healthy`.
5. Si Grafana y Prometheus están en composes separados y `http://prometheus:9090` da `Unknown host`: crear network compartida:
   ```bash
   docker network create yadin-net  # si no existe (ci-cd-infra ya la crea)
   docker network connect yadin-net yadinstore-grafana
   # o añadir en yadinstore-cicd-demo/docker-compose.yml: networks: [yadin-net] + networks: yadin-net: external: true
   ```

**Opción B — Provisioning as code (recomendado):**

```yaml
# yadinstore-cicd-demo/grafana/provisioning/datasources/prometheus.yml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```
Montar en compose: `volumes: ["./grafana/provisioning:/etc/grafana/provisioning"]` + `GF_PATHS_PROVISIONING=/etc/grafana/provisioning`.

**Alternativa sin Prometheus:** usar backend metrics directo `http://host.docker.internal:8080/actuator/prometheus` como datasource Prometheus (el backend expone `/actuator/prometheus` si `micrometer-registry-prometheus` está activo), pero se recomienda Prometheus real para RED.

## Dashboard import — observability-live.json (RED)

**Fuente:** `ci-cd-infra/monitoring/grafana/dashboards/observability-live.json` (uid `yadinstore-observability-live`, title `YadinStore - Observability Live RED`, Grafana 10.4.3, requiere Prometheus datasource).

**Import UI:**

1. Grafana → **Dashboards** → **Import** → **Upload JSON file** → seleccionar `observability-live.json`.
2. En **Options** → **Prometheus** → elegir datasource `Prometheus` (el creado arriba).
3. **Import** → dashboard aparece con panels RED: `Rate (req/s)`, `Errors (5xx)`, `Duration p95`, `outbox.pending gauge`, `http.server.requests` histogram.

**Provisioning dashboards:**

```yaml
# grafana/provisioning/dashboards/observability.yml
apiVersion: 1
providers:
  - name: yadinstore
    orgId: 1
    folder: YadinStore
    type: file
    options:
      path: /etc/grafana/dashboards
```
Mount: `volumes: ["../ci-cd-infra/monitoring/grafana/dashboards:/etc/grafana/dashboards:ro"]`

## Admin password — cómo configurar tras cambio

User reporta: cambió admin password en Grafana y pregunta cómo configurarlo persistente.

| Via | Cómo | Cuándo |
|-----|------|--------|
| **Env var (recomendado)** | `yadinstore-cicd-demo/docker-compose.yml` → `grafana.environment: GF_SECURITY_ADMIN_USER=admin GF_SECURITY_ADMIN_PASSWORD=tu_pass_seguro` | Rebuild `docker compose up -d grafana` recrea con nuevo pass. Persistido en `grafana_data` volume; si ya cambiaste via UI, el env no sobreescribe (prioriza DB). |
| **UI** | `http://localhost:3000` → avatar → **Change password** | Solo runtime, se pierde si `down -v`. |
| **Reset si olvidaste** | `docker exec -it yadinstore-grafana grafana-cli admin reset-admin-password nuevoPass` → `docker restart yadinstore-grafana` | Sin perder dashboards. |
| **Via API** | `curl -X PUT http://admin:oldpass@localhost:3000/api/admin/users/1/password -d '{"password":"nuevo"}' -H "Content-Type: application/json"` | Scripting. |

> Si cambiaste pass via UI y luego `docker compose down -v` borra volumen, vuelve a `admin/admin` salvo que uses `GF_SECURITY_ADMIN_PASSWORD`. Para porcelain, commitear `GF_SECURITY_ADMIN_PASSWORD` solo si es dummy local; en prod usar secret.

## Verificación API 25-08-2026 — datasource Prometheus + dashboard RED 7 panels importados

> Verificado `2026-08-25T05:40Z`: Grafana `10.4.3` `database: ok`, datasource `Prometheus` `uid: DS_PROMETHEUS` `http://host.docker.internal:9090` `isDefault: true`, dashboard `yadinstore-observability-live` 7 panels `GET /api/dashboards/uid/yadinstore-observability-live → 200`. `yadinstore-cicd-demo` no trae `prometheus` service — es esperado, usar `host.docker.internal:9090` tras `docker compose -f ci-cd-infra/docker-compose.yml up -d prometheus` o `http://yadin-prometheus:9090` con `yadin-net`.

**Datasource vía API (sin tocar compose, solo API calls):**

```powershell
# Reset admin si password cambiado
docker exec yadinstore-grafana grafana-cli admin reset-admin-password admin

# Crear Prometheus datasource host.docker.internal:9090 (isDefault true, uid DS_PROMETHEUS para que dashboard resuelva ${DS_PROMETHEUS})
curl.exe -s -u admin:admin -X POST http://localhost:3000/api/datasources -H "Content-Type: application/json" -d '{\"name\":\"Prometheus\",\"type\":\"prometheus\",\"uid\":\"DS_PROMETHEUS\",\"url\":\"http://host.docker.internal:9090\",\"access\":\"proxy\",\"isDefault\":true,\"editable\":true,\"jsonData\":{\"httpMethod\":\"POST\",\"timeInterval\":\"5s\",\"queryTimeout\":\"60s\"}}'
# → {\"datasource\":{\"id\":1,\"uid\":\"DS_PROMETHEUS\",\"name\":\"Prometheus\",\"url\":\"http://host.docker.internal:9090\",\"isDefault\":true},\"message\":\"Datasource added\"}
curl.exe -s -u admin:admin http://localhost:3000/api/datasources
# → [{\"id\":1,\"uid\":\"DS_PROMETHEUS\",\"name\":\"Prometheus\",\"type\":\"prometheus\",\"url\":\"http://host.docker.internal:9090\",\"isDefault\":true}]
```

**Dashboard RED import vía API:**

```powershell
$json = Get-Content -Raw \"C:\GITHUB\YadinStore\ci-cd-infra\monitoring\grafana\dashboards\observability-live.json\" | ConvertFrom-Json
$payload = @{ dashboard = $json; overwrite = $true; inputs = @(@{ name=\"DS_PROMETHEUS\"; type=\"datasource\"; pluginId=\"prometheus\"; value=\"DS_PROMETHEUS\" }) } | ConvertTo-Json -Depth 100
Set-Content -Path $tmp -Value $payload
curl.exe -s -u admin:admin -X POST http://localhost:3000/api/dashboards/db -H \"Content-Type: application/json\" --data-binary \"@$tmp\"
# → {\"id\":1,\"uid\":\"yadinstore-observability-live\",\"url\":\"/d/yadinstore-observability-live/...\",\"status\":\"success\",\"version\":1}

curl.exe -s -u admin:admin http://localhost:3000/api/dashboards/uid/yadinstore-observability-live | python -c \"import sys,json; d=json.load(sys.stdin); print(len(d['dashboard']['panels']))\"
# → 7  (Rate, Errors 5xx, p95, Outbox Pending gauge, Published counter, Failed counter, Kafka Publish Errors)
```

URL: `http://localhost:3000/d/yadinstore-observability-live/yadinstore-e28094-observability-live-red` (refresh 5s, templating `DS_PROMETHEUS` → `Prometheus`).

**Nota prometheus esperado:** `http://localhost:9090/-/healthy` y `http://host.docker.internal:9090/-/healthy` dan vacío / Bad Gateway si `ci-cd-infra` no está Up — normal en `yadinstore-cicd-demo` standalone. Levantar con `docker compose -f ci-cd-infra/docker-compose.yml up -d prometheus` y `docker network connect yadin-net yadinstore-grafana` si querés `Save & test` en verde; sin eso el datasource queda creado pero `Test` falla, dashboard igual importado (panels muestran No data hasta que prometheus scrapee `http_server_requests_seconds_count`).

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