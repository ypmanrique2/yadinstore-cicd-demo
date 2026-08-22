# Jenkins Live — Dashboard en vivo (Render free tier)

> **Clone `docker-live` → `yadinstore-jenkins-obs-live`** — pipeline Jenkins + métricas en tiempo real sin tocar el backend (hexagonal). Ring 200, poll 2s, lag &lt;5s, zero-deps, free tier como BE-KD.

## Pipeline as Code (DinD) — base local

Reusa el mismo patrón de [`jenkins.md`](./jenkins.md) y [`Jenkinsfile`](../Jenkinsfile):

- `Jenkinsfile` as Code versionado (SCM), `parameters` `SCM_URL/BRANCH/APP_SUBDIR`, `options { timestamps; disableConcurrentBuilds() }`, `stages Checkout → Compile → Test → Package → Docker Build → Compose Validate → Trivy`.
- **DinD**: `jenkins/Dockerfile` instala Docker CLI + `docker-compose.yml` monta `/var/run/docker.sock` → el stage *Docker Build* corre contra el daemon del host (igual que un agente real).
- Equiv. Jenkins ↔ GHA ver `jenkins.md:23` y `github-actions.md`.

Stack local (free) — ver `puerto&ambiente.md` en `ci-cd-infra`:

```
Jenkins 8081  → http://localhost:8081/api/json?tree=jobs[name,lastBuild[number,result,timestamp,duration],queueItem,primaryView]&queue[items[id]]&overallLoad
Backend 8080  → http://localhost:8080/actuator/prometheus  (ver outbox_pending local)
Prometheus 9090 → http://localhost:9090/api/v1/query?query=outbox_pending
```

## Servidor `yadinstore-jenkins-obs-live/server.js` — ring 200

Clone de `yadinstore-cicd-demo/docker-live/server.js:22-116` (zero-deps, solo `http/fs/crypto`), ~226 líneas:

- **Estado en memoria** `state={events:[],containers:[],jenkins:{queue,executors:{busy,idle},jobs[]},obs:{outboxPending,kafkaPublishErrors},lastSeen}` — `events` ring `MAX_EVENTS=200` (~400s ventana 2s×200), `containers` último `docker ps`, `jenkins` queue/executors/jobs `lastBuild`, `obs` dummy (estructura para 3 streams futuros).
- **Endpoints**
  - `POST /api/jenkins/events` batch|single (`[{type:"build",job,build,result,ts}]` o `{events:[...]}` o evento único) — 401 sin `x-live-token`.
  - `POST /api/jenkins/snapshot` `{jenkins:{queue,executors:{busy,idle},jobs:[{name,lastBuild}]},containers:[],obs:{outboxPending}}`
  - `POST /api/jenkins/metrics` alias `/api/obs/metrics` `{outboxPending,kafkaPublishErrors}`
  - `GET /api/jenkins/live → {events,containers,jenkins,obs,lastSeen,serverTime}` — **público**, `healthCheckPath` Render.
  - `GET /jenkins-dashboard.html` (+ `/`) — CSP `default-src 'self'`.
  - `OPTIONS` 204 CORS.

- **Seguridad agregados (Fase1 `permitAll`)**:
  - Auth: si `DOCKER_LIVE_TOKEN` (o `JENKINS_LIVE_TOKEN` / `JENKINS_OBS_TOKEN`) está seteada, `POST` exige `x-live-token` `timingSafeEqual`; `GET /live` siempre público (como BE-KD `kafka-dashboard.html` `permitAll` `SecurityConfig:192`).
  - **CORS Pages-only**: `Access-Control-Allow-Origin: https://ypmanrique2.github.io` (no `*` en prod). `Allow-Headers: Content-Type, x-live-token`, `Allow-Methods: GET, POST, OPTIONS`. Localhost también permitido para dev.
  - **Rate-limit** `10 req/min/IP` solo POSTs → `429 {error:"rate_limited",retryAfter}` + `Retry-After`.
  - **Sanitización** `sanitizeCause(s,250)` oculta `password/secret/token/api_key/email → ***` 5 niveles, sin stacktrace (igual `KafkaActivityController:250`).
  - **CSP** `default-src 'self'; connect-src 'self' https://yadinstore-jenkins-obs-live.onrender.com`.

## Agente `yadinstore-jenkins-obs-live/jenkins-obs-agent.ps1`

Fork `docker-agent.ps1:17-94`, ~180 líneas, `param Endpoint Token SnapshotIntervalSec BatchIntervalSec`:

```
.\jenkins-obs-agent.ps1 -Endpoint https://yadinstore-jenkins-obs-live.onrender.com -Token "xxx" 
# defaults: Endpoint= https://yadinstore-jenkins-obs-live.onrender.com  SnapshotIntervalSec=5  BatchIntervalSec=2
```

- **3 jobs paralelos** cada 2s (batch) / 5s (snapshot):
  1. `docker events --format json --filter type=container --filter container=yadin-jenkins` streaming (Start-Job) + `docker ps --format json` snapshot.
  2. `Invoke-RestMethod http://localhost:8081/api/json?tree=jobs[name,lastBuild[number,result,timestamp,duration],queueItem]&queue[items[id]]&overallLoad` + `overallLoad` executors busy/idle.
  3. `Invoke-RestMethod http://localhost:8080/actuator/prometheus` o `http://localhost:9090/api/v1/query?query=outbox_pending` → parse `outbox_pending` dummy cada 5s (estructura lista, datos dummy `0` ok).
- POST batch `events` + `snapshot` con `x-live-token` cada `BatchIntervalSec=2`. **No crashea si Jenkins caído**: envía `{jenkins:{status:"unavailable",causeChain:"Connection refused"}}` sanitizado y reintenta 2s.
- Requiere: Docker Desktop + Jenkins local `8081`. Ctrl+C termina jobs.

## Render free + UptimeRobot

`render.yaml` 33 líneas, clone `yadinstore-cicd-demo/render.yaml:15-26`:

```yaml
services:
  - type: web
    name: yadinstore-jenkins-obs-live
    runtime: node
    plan: free
    buildCommand: ""
    startCommand: node server.js
    healthCheckPath: /api/jenkins/live
    autoDeploy: true
    envVars:
      - key: DOCKER_LIVE_TOKEN
        sync: false      # token POST x-live-token, GETs públicos
      - key: NODE_ENV
        value: production
```

- Zero-deps → <50MB RAM (límite free 512MB, ver ADR-014 ES inviable).
- **UptimeRobot** monitor cada `5m` a `GET https://yadinstore-jenkins-obs-live.onrender.com/api/jenkins/live` — evita sleep 15m free tier (cold 30s, ring volatiliza pero Cloud persiste métricas).

## Dashboard `jenkins-dashboard.html`

Clone `docker-dashboard.html:72-139` poll `2s` `alive 15s` `esc()+textContent` XSS-safe, ~168 líneas:

- Título *Jenkins en vivo*, KPIs `queue busy/idle outboxPending`, jobs table `lastBuild number/result/duration`, events 40 reverse, containers table, obs panel dummy.
- Badge ONLINE si `serverTime-lastSeen <15s` else OFFLINE (verde/rojo). Chart SVG 60 muestras.
- `fetch` `GET /api/jenkins/live` cada `2000ms`; `setInterval(esc)` + `textContent` previene XSS `<script>alert(1)</script>` → literal.

URL: `https://yadinstore-jenkins-obs-live.onrender.com/jenkins-dashboard.html` (tras T103) y local `http://localhost:3000/jenkins-dashboard.html`.

## Curl ejemplos (verificación T104)

```bash
# GET público — health / live
curl http://localhost:3000/api/jenkins/live | jq '.serverTime, .lastSeen, .jenkins'
# → 200, serverTime ISO8601, lastSeen null antes de POST

# POST sin token → 401 (si DOCKER_LIVE_TOKEN seteada)
DOCKER_LIVE_TOKEN=secret node yadinstore-jenkins-obs-live/server.js &
curl -s -X POST http://localhost:3000/api/jenkins/events \
  -H "Content-Type: application/json" -d '[{"type":"build","job":"x"}]' | jq .error
# → "unauthorized"

# POST con token → 200
curl -s -X POST http://localhost:3000/api/jenkins/events \
  -H "x-live-token: secret" -H "Content-Type: application/json" \
  -d '[{"type":"build","job":"yadin-pipeline","build":7,"result":"SUCCESS","ts":1724200000}]' | jq
# → {"ok":true,"stored":1,"total":1}

curl http://localhost:3000/api/jenkins/live | jq '.events[0].job, .serverTime, .lastSeen'
# → "yadin-pipeline" y lastSeen hace <5s

# snapshot jenkins
curl -s -X POST http://localhost:3000/api/jenkins/snapshot \
  -H "x-live-token: secret" -H "Content-Type: application/json" \
  -d '{"jenkins":{"queue":1,"executors":{"busy":1,"idle":1},"jobs":[{"name":"yadin-pipeline","lastBuild":{"number":7,"result":"SUCCESS"}}]}}' | jq
# → {"ok":true,"jenkins":{"queue":1}}

# rate-limit 11 POSTs/min → 429
for i in {1..11}; do curl -s -o /dev/null -w "%{http_code} " -X POST http://localhost:3000/api/jenkins/events -H "x-live-token: secret" -H "Content-Type: application/json" -d '[{}]'; done; echo
# → 200 ... 200 429 429

# CORS Pages-only
curl -s -D - http://localhost:3000/api/jenkins/live -H "Origin: https://ypmanrique2.github.io" | grep -i access-control-allow-origin
# → Access-Control-Allow-Origin: https://ypmanrique2.github.io

# XSS
curl -s -X POST http://localhost:3000/api/jenkins/events -H "x-live-token: secret" -H "Content-Type: application/json" \
  -d '[{"job":"<script>alert(1)</script>"}]' | jq .stored
# browser: textContent muestra literal, no ejecuta
```

Prod tras deploy:

```bash
curl https://yadinstore-jenkins-obs-live.onrender.com/api/jenkins/live | jq .serverTime
curl -X POST https://yadinstore-jenkins-obs-live.onrender.com/api/jenkins/events -H "x-live-token: bad" -H "Content-Type: application/json" -d '[{}]'  # → 401
```

## PermitAll agregados (Fase1)

Fase1 MVP: `GET /api/jenkins/live` y `GET /jenkins-dashboard.html` son **públicos** (`permitAll`) — solo agregados sin PII (queue, executors, job names sanitizados). `POST` protegido por `x-live-token`. No hay JWT/RBAC de dashboards (como BE-KD `kafka-dashboard.html:273` + `SecurityConfig:192`).

Fase2 (VIEWER gate): `GET /api/v1/observability/metrics|logs` requerirá `ADMIN/VIEWER` + `SecurityConfig:310`; `actuator/prometheus` queda `401/404` directo (solo gateway). Hoy `permitAll` es intencional para el live view.

## URLs tras T103

- Live prod: `https://yadinstore-jenkins-obs-live.onrender.com/api/jenkins/live` (health)
- Dashboard prod: `https://yadinstore-jenkins-obs-live.onrender.com/jenkins-dashboard.html`
- Demo docker-live (referencia): `https://yadinstore-cicd-demo-live.onrender.com/api/docker/live`
- Pages (tras T102): `https://ypmanrique2.github.io/yadinstore-cicd-demo/docs/jenkins-live.html` (este archivo) + badge en `index.html`.
- Avance detallado: `avance_21-08-2026.md` §7 y §8 (ES descartado).

## Recursos

- [Jenkins Handbook](https://www.jenkins.io/doc/book/pipeline/) — Pipeline as Code
- [Render free tier](https://docs.render.com/free) — 512MB/0.1CPU/15m sleep
- [UptimeRobot](https://uptimerobot.com/) — monitor 5m gratis
- Explore #674 + Proposal #675 + Spec #676 + Design #677 (engram YadinStore)
