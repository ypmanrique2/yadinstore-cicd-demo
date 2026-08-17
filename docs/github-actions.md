# GitHub Actions — CI/CD gratis en la nube

## Por qué GitHub Actions para CI

- **Gratis**: repos **públicos** tienen minutos ilimitados (free tier); repos privados 2.000 min/mes.
- **Zero infra**: runners `ubuntu-latest` gestionados por GitHub — no hay servidor que mantener.
- **Integración nativa**: corre en el mismo lugar donde vive el código (PR checks, badges).

## El workflow de este repo

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) replica 1:1 el pipeline del
Jenkinsfile — la misma lógica en dos motores, para demostrar la equivalencia:

```yaml
on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:          # Compile → Test → Package + upload artifact
  docker-build:   # Valida que el Dockerfile construye (needs: build)
```

## Decisiones de diseño

| Decisión | Por qué |
|---|---|
| `actions/setup-java@v4` con `cache: maven` | Cachea dependencias → builds más rápidos (minutos gratis valen) |
| `docker build` **sin push** | El deploy real lo hace Render desde el repo privado; aquí solo validamos |
| `needs: build` | El job de Docker corre después del de build (secuencial, como stages de Jenkins) |
| Sin secrets en el workflow | Repo público → nada sensible; los secrets se usan solo donde hacen falta |

## Deploy real del backend (Render)

El backend vive en `back-end_YadinStore` (privado) y Render hace **autodeploy** al pushear
a `main` — el workflow de este repo demo **no toca Render** (compatibilidad total:
cero deploys innecesarios).

## Recursos
- [GitHub Actions docs](https://docs.github.com/en/actions)
- [Workflow syntax](https://docs.github.com/en/actions/reference/workflow-syntax-for-github-actions)