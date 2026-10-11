# ADR-0008 — Una imagen, modos de ejecución `api` / `worker`

- **Estado:** Aceptada
- **Fecha:** 2026-10-10

## Contexto

La carga de la API (picos de peticiones HTTP cortas) y la de los consumidores (backlog de colas,
transacciones contendidas, llamadas a la pasarela) escalan por señales distintas. Separarlas en dos
microservicios duplicaría build, versionado y despliegue; mantenerlas juntas obliga a escalar todo
por la señal más cara.

## Decisión

Un único artefacto (`ms-ticket.jar`, imagen `ms-ticket`) con la propiedad
`ticketing.runtime-mode` = `all` | `api` | `worker` (`RuntimeModeEnum`, por defecto `all`):

| Componente | `all` | `api` | `worker` |
|---|---|---|---|
| Servidor HTTP (REST, SSE, actuator) | sí | sí | sí (solo se usa para health/métricas) |
| `SqsListenerContainer` (consumidores de reserva y pago) | sí | no | sí |
| `MaintenanceTimer` (sweepers de expiración/conciliación y de despacho) | sí | no | sí |
| Migraciones DynamoDB locales | según `ticketing.migrations.enabled` | | |

La condición `WorkerModeCondition` (binding relajado: `worker`, `WORKER`, ...) guarda
`SqsListenerConfig` y `SchedulingConfig`. La métrica común `runtime_mode` distingue las instancias en
los tableros.

- **Local (compose)**: `ticketing-api` (`TICKETING_RUNTIMEMODE=API`, publica 8080 y aplica las
  migraciones) y `ticketing-worker` (`WORKER`, sin puertos publicados, escalable con
  `docker compose up -d --scale ticketing-worker=3`).
- **AWS (Terraform)**: dos servicios ECS Fargate con la misma imagen: `api` detrás del ALB, con
  autoscaling por CPU y `ALBRequestCountPerTarget`; `worker` sin ingress, con autoscaling por
  *backlog per task* (`(visibles reserva + visibles pago) / tareas`) y capacidad Spot.

## Consecuencias

**Positivas**
- Escalado independiente con un solo pipeline de build y un solo digest escaneado.
- El modo `all` simplifica el desarrollo desde el IDE (`SPRING_PROFILES_ACTIVE=local`).
- Los sweepers corren solo donde hay workers (mínimo 1 tarea worker en AWS).

**Negativas**
- En modo `worker` los `@RestController` siguen registrados; `WorkerModeApiWebFilter` responde `404`
  a todo `/api/**` (además, compose no publica puertos y en AWS el worker no tiene ingress). Mejora:
  no registrar los controladores en ese modo.
- Ambas imágenes cargan todas las dependencias (tamaño mayor que dos binarios especializados).

## Alternativas consideradas

- **Dos microservicios/repositorios**: más aislamiento, más costo de mantenimiento para un mismo
  dominio y modelo de datos.
- **Workers como AWS Lambda** (event source mapping SQS): escala a cero, pero los sweepers y el
  entorno local serían distintos.

## Referencias

- `enums/RuntimeModeEnum.java`, `config/messaging/WorkerModeCondition.java`,
  `config/messaging/SqsListenerConfig.java`, `config/scheduling/SchedulingConfig.java`,
  `docker-compose.yml`, `infra/terraform/envs/*/main.tf`, `infra/terraform/modules/ecs_service/`.
