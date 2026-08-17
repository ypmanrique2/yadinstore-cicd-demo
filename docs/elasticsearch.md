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

## Recursos
- [Elastic docs](https://www.elastic.co/docs)
- [Spring Boot + Elastic APM / logging](https://docs.spring.io/spring-boot/reference/actuator/logging.html)