# YadinStore CI/CD Demo

**Portafolio de habilidades: Jenkins · CI/CD · GitHub Actions · Grafana · ElasticSearch**
Repo **público** de demostración — sin `.env`, sin secrets, sin exponer repos privados. 100% free tier.

## ¿Qué demuestra?

| Skill | Dónde |
|---|---|
| **Jenkins (Pipeline as Code)** | [`Jenkinsfile`](./Jenkinsfile) — pipeline declarativo completo |
| **GitHub Actions (free)** | [`.github/workflows/ci.yml`](./.github/workflows/ci.yml) — réplica exacta del Jenkinsfile |
| **Docker / DinD** | [`demo-app/Dockerfile`](./demo-app/Dockerfile) multi-stage + Jenkins con docker.sock |
| **Grafana (métricas)** | [`docs/grafana.md`](./docs/grafana.md) — stack local free |
| **ElasticSearch + Kibana (logs)** | [`docs/elasticsearch.md`](./docs/elasticsearch.md) — stack ELK local free |
| **Java 21 + Maven + JUnit 5** | [`demo-app/`](./demo-app/) — app mínima sin secrets |

## Pipeline (idéntico en Jenkins y GitHub Actions)

```
Checkout → Compile → Test → Package → Docker Build (validación, sin push)
```

> Diseñado en **compatibilidad con el backend real desplegado en Render**:
> el pipeline **no hace deploy** — solo valida que compila, testea, empaqueta y
> que el Dockerfile construye, igual que Render lo haría. Cero deploys innecesarios.

## Cómo correr

### GitHub Actions (cloud, gratis)
Cada push/PR a `main` corre el workflow automáticamente. Ver pestaña **Actions**.

### Jenkins (local, gratis)
```bash
docker compose up -d jenkins
# 1. Password admin inicial:  docker logs yadinstore-jenkins
# 2. Abrir http://localhost:8081
# 3. Crear job "Pipeline" → "Pipeline script from SCM"
#    SCM: https://github.com/ypmanrique2/yadinstore-cicd-demo.git (público, sin credenciales)
```

### Observabilidad (local, gratis)
```bash
docker compose up -d grafana elasticsearch kibana
# Grafana: http://localhost:3000 (admin/admin)
# Kibana : http://localhost:5601
```

## Proyectos relacionados (producción)

- Backend: [back-end_YadinStore](https://github.com/ypmanrique2/back-end_YadinStore) (privado, autodeploy en Render)
- Frontend dist: [yadinstore-frontend-dist](https://github.com/ypmanrique2/yadinstore-frontend-dist) · [en vivo](https://yadinstore-frontend.onrender.com/)
- Admin dist: [yadinstore-admin-dist](https://github.com/ypmanrique2/yadinstore-admin-dist) · [en vivo](https://yadinstore-admin.onrender.com/)
- Infra local (Jenkins base): `ci-cd-infra_YadinStore` (privado)