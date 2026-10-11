# Operación de ms-ticket

Observabilidad, runbooks, costos y gobernanza. Los comandos locales asumen el stack de
`docker-compose.yml` levantado (`docker compose up -d --build --wait`); los de AWS, la
infraestructura de `infra/terraform`.

## 1. Observabilidad

### 1.1 Health y probes

| Endpoint | Uso |
|---|---|
| `GET /actuator/health` | estado agregado (detalles visibles solo en el perfil `local`) |
| `GET /actuator/health/liveness` | *liveness* (el proceso responde) |
| `GET /actuator/health/readiness` | *readiness*: lo usan el `HEALTHCHECK` de la imagen, los healthchecks de compose, el target group del ALB y el health check del contenedor worker |
| `GET /actuator/info` | nombre de la aplicación |
| `GET /actuator/prometheus` | métricas Micrometer |

En AWS el actuator vive en el puerto de gestión **8081** (`MANAGEMENT_SERVER_PORT`), no enrutado por
el listener público. Compose espera `service_healthy` en orden: `dynamodb-local`, `localstack`
(solo sano cuando existen las 4 colas), `auth-mock` → `ticketing-api` (aplica migraciones) →
`ticketing-worker`.

### 1.2 Métricas clave

Todas las métricas llevan los tags comunes `application` y `runtime_mode` (`api`/`worker`/`all`).

| Métrica (Prometheus) | Dónde | Qué vigilar |
|---|---|---|
| `http_server_requests_seconds_{count,sum,max}{uri,method,status}` | API | latencia p99 y tasa de 5xx; picos de `409 SOLD_OUT`, `429`, `503` |
| `sqs_messages_total{queue,outcome="acked\|failed\|poison"}` | worker | `failed` sostenido = dependencia caída; cualquier `poison` = mensaje rumbo a la DLQ |
| `sqs_message_processing_seconds{queue,outcome}` | worker | tiempo por mensaje; acercarse a 54 s (90 % del visibility timeout) provoca cancelaciones |
| `sqs_messages_in_flight{queue}` | worker | saturación: cerca de `concurrency` (16) por cola y réplica |
| `sweeper_results_total{type,outcome="completed\|skipped\|failed"}` | worker | `skipped` es normal con varias réplicas (otra tiene el lease); `failed` no |
| `sweeper_orders_total{type,result="released\|finalized\|republished\|failed"}` | worker | `republished` > 0 indica fallos de publicación o backlog; `failed` requiere revisión |
| JVM / proceso (`jvm_*`, `process_*`) | ambos | memoria, GC, CPU |

En AWS (CloudWatch): `ApproximateNumberOfMessagesVisible` y `ApproximateAgeOfOldestMessage` de cada
cola y DLQ, `ThrottledRequests`/`SystemErrors` de DynamoDB (con Contributor Insights
`THROTTLED_KEYS` para identificar la clave caliente, típicamente el ítem del evento), métricas del
ALB, WAF y ECS. El tablero `<project>-<env>` agrupa borde, colas, ECS y DynamoDB.

### 1.3 Logs estructurados y correlación

- Perfil `local`: texto plano (legible en `docker compose logs -f`). Perfiles `ci` y `prod`:
  **JSON ECS** (`logging.structured.format.console: ecs`), que incluye el MDC.
- **`correlationId`** de punta a punta: `CorrelationIdWebFilter` acepta un `X-Correlation-Id` válido
  o genera un UUID → contexto Reactor → MDC (propagación automática de contexto) → atributo
  `correlationId` del mensaje SQS (y campo del body) → el listener lo restaura en el contexto/MDC
  del consumidor. Los mensajes re-publicados por el sweeper usan `correlationId = orderId`.
- Micrometer Tracing (bridge OpenTelemetry) está en el classpath y genera `traceId`/`spanId` con
  muestreo por perfil (`1.0` local, `0.0` ci, `0.1` prod), pero **no hay exporter
  configurado** ni propagación W3C por SQS (mejora documentada).

Mensajes de log relevantes para operación:

| Nivel | Mensaje (prefijo) | Significado |
|---|---|---|
| ERROR | `Payment captured for released order ... manual refund required` | cobro capturado para una orden ya liberada: reembolso manual |
| ERROR | `Order ... still queued ... marked FAILED (DISPATCH_EXHAUSTED)` | orden no despachada en 10 min |
| ERROR | `Message ... failed on its last delivery allowed by the redrive policy` | mensaje que irá a la DLQ |
| ERROR | `Sweeper ... failed; the next tick runs it again` | barrido fallido (se reintenta en 15 s) |
| ERROR | `SQS listeners terminated unexpectedly` | el contenedor SQS se detuvo: reiniciar la tarea |
| WARN | `Circuit breaker paymentGateway changed: ...` | transición del circuit breaker de la pasarela |
| WARN | `Reservation message of order ... not published, the dispatch sweeper will republish it` | fallo de publicación absorbido por el outbox lite |
| WARN | `Payment gateway charge failed for idempotency key ...` | fallo de la pasarela (motivo: HTTP, timeout, I/O, circuito abierto) |
| WARN | `Cannot receive messages from SQS queue ...` | SQS inaccesible (log limitado a 1 cada 30 s por bucle) |
| WARN | `Lease ... not released, it will expire on its own` | el lease expirará solo en ≤ 30 s |

### 1.4 Alarmas (Terraform, `modules/observability`)

| Alarma | Señal | Efecto adicional |
|---|---|---|
| `*-dlq-not-empty` | `ApproximateNumberOfMessagesVisible > 0` en una DLQ | revierte despliegues del worker |
| `*-oldest-message-age` | `ApproximateAgeOfOldestMessage > 300 s` | revierte despliegues del worker |
| `alb-5xx-rate` | (5xx target + 5xx ELB) / peticiones > 5 % | revierte despliegues de la API |
| `api-p99-latency` | `TargetResponseTime` p99 > 1 s | revierte despliegues de la API |
| `api-no-healthy-targets` | `HealthyHostCount < 1` | — |
| `ddb-*-throttled-requests` / `ddb-*-system-errors` | throttling (> 5) / errores de sistema por tabla | — |
| `*-cpu-high` / `*-memory-high` | > 85 % durante 8 de 10 min | — |
| `payment-captured-for-released-order` | filtro de métricas sobre los logs del worker | ver nota |
| `cognito-post-confirmation-lambda-errors` | errores o throttles del trigger | — |

> Nota: el filtro de `payment-captured-for-released-order` busca el término
> `"payment captured for released order"` en minúsculas, pero el log de la aplicación empieza con
> mayúscula (`Payment captured ...`) y los filtros de CloudWatch Logs distinguen mayúsculas. Hay que
> alinear el patrón (o el mensaje) para que la alarma dispare.

Todas publican en un tópico SNS cifrado (suscripción por e-mail configurable).

## 2. Runbooks

### 2.1 DLQ con mensajes (`*-dlq-not-empty`)

1. **Identificar la cola**: `ticketing-order-reservation-dlq` (reservas) u
   `ticketing-order-payment-dlq` (pagos).
2. **Inspeccionar sin consumir** (visibility 0):

   ```bash
   # local (Git Bash en Windows: export MSYS_NO_PATHCONV=1)
   docker compose exec localstack awslocal sqs receive-message \
     --queue-url http://localhost:4566/000000000000/ticketing-order-payment-dlq \
     --max-number-of-messages 10 --visibility-timeout 0 --attribute-names All --message-attribute-names All
   # AWS
   aws sqs receive-message --queue-url <url-dlq> --max-number-of-messages 10 \
     --visibility-timeout 0 --attribute-names All --message-attribute-names All
   ```

3. **Correlacionar**: buscar en los logs del worker el `correlationId` (atributo del mensaje) o el
   `orderId`; consultar la orden (`GET /api/v1/orders/{orderId}` como ADMIN).
4. **Clasificar la causa**:
   - *Malformado* (`MalformedMessageException`): corregir el productor; el mensaje no se puede
     reprocesar → purgar tras registrarlo.
   - *Dependencia caída* (pasarela, throttling prolongado): esperar la recuperación y re-encolar.
   - *Bug*: corregir, desplegar y re-encolar.
5. **Re-encolar** (seguro: los consumidores son idempotentes; un mensaje de pago cuya orden ya pasó
   su deadline de confirmación se reconoce sin cobrar porque la conciliación es dueña de esa orden):

   ```bash
   # local
   docker compose exec localstack awslocal sqs start-message-move-task \
     --source-arn arn:aws:sqs:us-east-1:000000000000:ticketing-order-payment-dlq \
     --destination-arn arn:aws:sqs:us-east-1:000000000000:ticketing-order-payment
   # AWS (destino por defecto: la cola de origen)
   aws sqs start-message-move-task --source-arn <arn-dlq>
   ```

6. Si el despliegue del arreglo queda bloqueado por la propia alarma de DLQ (rollback por alarma),
   aplicar el *break-glass* documentado en `infra/terraform/README.md` §3
   (`-var deployment_alarm_rollback=false`), re-encolar y volver a aplicar sin el override.

### 2.2 Circuito abierto de la pasarela de pagos

- **Síntomas**: WARN `Circuit breaker paymentGateway changed: CLOSED_TO_OPEN`, fallos
  `circuit breaker is open`, `sqs_messages_total{queue="...payment",outcome="failed"}` creciente,
  órdenes en `PENDING_CONFIRMATION` más tiempo de lo normal.
- **Comportamiento automático**: el circuito abre con ≥ 50 % de fallos transitorios en las últimas
  20 llamadas (solo 5xx, timeouts e I/O cuentan; un 4xx no), permanece abierto 30 s y luego deja
  pasar 5 llamadas de prueba. Los mensajes de pago fallan rápido y se re-entregan con backoff
  (10 s → 160 s); tras 5 recepciones van a la DLQ. Las órdenes cuyo deadline de confirmación (5 min)
  vence pasan a la **conciliación** del sweeper, que consulta el cobro (también protegido por el
  circuito: si sigue caído, la orden cuenta como `failed` y se reintenta en el siguiente ciclo).
- **Acciones**: verificar el estado del proveedor; no reiniciar los workers (no ayuda); cuando se
  recupere, re-encolar la DLQ de pagos (2.1) y verificar que no queden órdenes `PENDING_CONFIRMATION`
  con deadline vencido (`sweeper_orders_total{result="finalized|released"}` debería subir).
- **Prueba local**: confirmar con `paymentToken = tok_error` (la pasarela responde 500) o
  `tok_slow` (5 s, supera el timeout de 3 s).

### 2.3 Sweeper atrasado

- **Síntomas**: órdenes `RESERVED` con `holdExpiresAt` vencido hace más de un ciclo, disponibilidad
  que no se recupera, `sweeper_results_total{outcome="failed"}` o ausencia total de `completed`.
- **Diagnóstico**:
  1. ¿Hay al menos un worker sano? (los sweepers solo corren en modo `worker`/`all`).
  2. Lease: inspeccionar `ticketing-distributed-locks` (`owner`, `leaseUntil`). Un lease huérfano
     expira solo en ≤ 30 s; nunca hace falta borrarlo a mano.
  3. Logs `Sweeper ... failed` / `Sweep of order ... failed`: throttling en el índice
     `deadline-index`, pasarela caída (conciliación) o tickets inconsistentes
     (`Tickets of order ... are not in the state of the order`).
- **Capacidad**: un ciclo procesa como máximo `batch-size` (100) órdenes por tipo cada 15 s
  (≈ 400 por minuto), con concurrencia 8. Escalar workers **no** acelera los barridos (solo uno
  tiene el lease); ajustar `ticketing.sweeper.batch-size`, `concurrency` o `interval`. Cada barrido
  está acotado por el lease (30 s) y un tick que llega con el anterior en curso se descarta.

### 2.4 Pico de demanda y escalado

- **API**: escala por CPU y `ALBRequestCountPerTarget` (prod: 3 a 12 tareas). La API solo valida,
  inserta y encola, así que soporta picos con latencia estable; vigilar `429` y `409 SOLD_OUT`.
- **Workers**: escalan por *backlog per task*
  (`(visibles reserva + visibles pago) / tareas`, objetivo 50 en prod, 2 a 10 tareas con Spot).
  Local: `docker compose up -d --wait --scale ticketing-worker=3`.
- **Límite real: el ítem del evento**. Todas las reservas de un evento escriben su ítem; una
  partición soporta ≈ 1 000 WCU/s y cada ítem transaccional cuesta 2 WCU → del orden de cientos de
  reservas por segundo por evento. Señales: `TransactionConflict`/throttling en `events`
  (Contributor Insights), `sqs_messages_total{outcome="failed"}` en reservas, edad del mensaje más
  antiguo creciendo. Más workers no lo resuelven; ver contadores fragmentados y sala de espera en
  [limitaciones](limitations-and-improvements.md).
- **Backlog > 10 min**: el sweeper de despacho marca como `FAILED/DISPATCH_EXHAUSTED` las órdenes
  que siguen `QUEUED` 10 min después de creadas (ADR-0006). Ante un pico anunciado, considerar
  aumentar `ticketing.orders.republish-after` o pre-escalar workers.
- Antes de un *drop* masivo: subir `min_capacity` de ambos servicios, pre-calentar DynamoDB
  (*warm throughput* en on-demand) y activar la regla estricta del WAF para órdenes.

### 2.5 Cobro capturado para una orden liberada

Alarma `payment-captured-for-released-order` (ver nota de 1.4). Ocurre solo si un cobro iniciado
antes del deadline de confirmación termina después de que la conciliación liberó la orden.
Acción: buscar el `orderId` en el log, verificar el cobro en la pasarela (`paymentId`) y emitir el
reembolso manual; la orden queda `EXPIRED` y sus entradas ya fueron re-vendibles.

### 2.6 Órdenes `FAILED` por `DISPATCH_EXHAUSTED`

Revisar la DLQ de reservas y los logs del worker para esa orden; tras corregir la causa, el cliente
debe crear una orden nueva (con otra `Idempotency-Key`): una orden `FAILED` es terminal.

## 3. Costos

Precios de lista aproximados (us-east-1); usar la calculadora de AWS para cifras reales.

| Palanca | Decisión | Razón |
|---|---|---|
| DynamoDB | **On-demand** en todas las tablas | demanda en picos e impredecible; pasar a provisioned + autoscaling si el tráfico se vuelve estable |
| Transacciones | presupuestar **2× WCU** por ítem | una reserva de `q` entradas escribe `2q + 2` ítems, cada uno cobrado dos veces (prepare + commit): `q = 2` → 12 WRU solo en la tabla, más las escrituras de los GSI (`statusKey`, deadline) |
| Índices | GSI de tickets con proyección `INCLUDE` estrecha; índice de deadlines disperso | menos almacenamiento y menos WRU por escritura |
| Red | **gateway endpoints** de DynamoDB y S3 (gratis) en vez de NAT para el tráfico pesado; interface endpoints en 1 AZ en dev | el NAT cobra ~USD 0.045/GB procesado |
| Cómputo | **Fargate Spot** para el burst de workers en prod y todo dev; ARM64 cuando la imagen sea multi-arquitectura | Spot ~70 % más barato; los workers toleran interrupciones (drenado + re-entrega) |
| Logs | retención 7–30 días en dev, 365 en prod; WAF solo BLOCK/COUNT; flow logs de prod a S3 Parquet | la ingesta de CloudWatch Logs suele ser el costo oculto |
| SQS | long polling de 20 s | menos `ReceiveMessage` vacíos |
| Caché | Caffeine 2 s para lecturas de eventos y *soft check* | reduce lecturas en picos de consulta |
| SSE | un poll por evento e instancia | el costo no crece con los espectadores |

Orden de magnitud por compra de 2 entradas (reserva + confirmación + venta): ~37 WRU en tablas más
las escrituras de GSI → del orden de USD 0.00003 en DynamoDB y ~6 peticiones SQS por orden. Línea
base indicativa de infraestructura ociosa (de `infra/terraform/README.md` §4): dev ≈ USD 120–150/mes,
prod ≈ USD 450–550/mes antes del tráfico.

## 4. Gobernanza

- **Etiquetas** por defecto en todos los recursos (`default_tags`): `Project`, `Environment`,
  `Owner`, `CostCenter`, `ManagedBy=terraform`, `Repository`.
- **AWS Budgets** por entorno: aviso al 80 % del gasto real y al 100 % del pronosticado.
- **Entornos aislados**: estado remoto separado por entorno (`envs/dev`, `envs/prod`, backend en S3
  con versionado y *lockfile* nativo); `allowed_account_ids` impide aplicar con credenciales de la
  cuenta equivocada; recomendado una cuenta por entorno con SCPs (regiones permitidas, sin IAM users,
  protección de CloudTrail/KMS/tablas).
- **Protección de datos** en prod: *deletion protection* en DynamoDB, ALB, user pool y log groups;
  PITR 35 días; ventanas de recuperación de KMS y secretos de 30 días.
- **Cambios controlados**: imágenes con tags inmutables (git SHA) promovidas de dev a prod; despliegues
  rolling con *circuit breaker* y rollback por alarmas; ECS Exec deshabilitado en prod.
- **Verificación estática** de la IaC sin credenciales: `infra/terraform/scripts/validate.sh`
  (`fmt`, `validate`, `terraform test` con provider simulado, `tflint`, `checkov`).
- **Código**: ArchUnit + JaCoCo ≥ 90 % + `-Werror` en cada build; pipeline de CI en
  `.github/workflows/ci.yml`.
