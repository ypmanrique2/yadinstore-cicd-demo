# Jenkins — Pipeline as Code

## Conceptos demostrados en este repo

### Pipeline Declarativo (Jenkinsfile as Code)
El [`Jenkinsfile`](../Jenkinsfile) define el pipeline en el repo (SCM), no en la UI:
- **`parameters`**: `SCM_URL`, `SCM_BRANCH`, `APP_SUBDIR` — el job se parametriza desde el código.
- **`options`**: `timestamps()` (logs con hora) y `disableConcurrentBuilds()` (serializa builds).
- **`environment`**: variables de entorno del pipeline (`DOCKER_IMAGE`).
- **`stages`**: Checkout → Compile → Test → Package → Docker Build.
- **`post`**: acciones según el resultado (success/failure).

### Por qué Jenkinsfile as Code
- Versionado con la app (git history del pipeline).
- Code review del pipeline como cualquier código.
- Un job se recrea desde cero en minutos (reproducibilidad).

### DinD (Docker in Docker)
El [Dockerfile de Jenkins](../jenkins/Dockerfile) instala el CLI de Docker y el
compose monta `/var/run/docker.sock` — el stage "Docker Build" corre contra el
daemon del host, igual que en un agente CI real.

### Equivalencia con GitHub Actions

| Jenkins | GitHub Actions |
|---|---|
| `pipeline { stages { ... } }` | `jobs: { steps: [ ... ] }` |
| `sh 'mvn test'` | `run: mvn test` |
| `junit '**/surefire-reports/*.xml'` | `actions/upload-artifact` (o Publish Test Results) |
| `checkout GitSCM` | `actions/checkout@v4` |
| `parameters` | `workflow_dispatch` inputs / `env` |
| `post { success { } }` | `if: success()` / `if: failure()` |

## Cómo levantar localmente (free)

```bash
docker compose up -d jenkins
docker logs -f yadinstore-jenkins   # password admin inicial
# http://localhost:8081
```

## Recursos
- [Jenkins Handbook](https://www.jenkins.io/doc/book/pipeline/)
- [Pipeline Syntax Generator](https://www.jenkins.io/doc/book/pipeline/syntax/)