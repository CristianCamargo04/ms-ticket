# Limitaciones conocidas y mejoras

Lista honesta de lo que la solución **no** resuelve hoy, por qué se aceptó y qué se haría en
producción. Ordenada por impacto.

## 1. Partición caliente en el ítem del evento

- **Hoy**: todos los contadores (`availableCount`, `reservedCount`, ...) viven en un único ítem por
  evento y **cada** transacción de inventario lo actualiza. Una partición de DynamoDB sostiene
  ≈ 1 000 WCU/s y un ítem transaccional cuesta 2 WCU, así que el techo es del orden de cientos de
  reservas por segundo **por evento** (los demás eventos no se ven afectados). Por encima aparecen
  `TransactionConflict` y throttling, que se absorben con reintentos y re-entregas, pero la latencia
  de la reserva crece.
- **Mejora: contadores fragmentados**. Dividir la capacidad en `N` ítems de inventario
  (`eventId#shard`), cada uno con su fracción de `availableCount`; la reserva elige un shard
  aleatorio con stock y lo incluye en la transacción; la disponibilidad suma los `N` shards (o un
  agregado mantenido por Streams). *Trade-off*: lecturas más caras y un shard puede agotarse antes
  que el total (re-intentar en otro shard o rebalancear).

## 2. Índice de disponibilidad eventualmente consistente

- **Hoy**: `findAvailable` lee el GSI `eventId-statusKey-index`, eventualmente consistente; puede
  devolver entradas recién tomadas. La condición `#status = AVAILABLE` de la transacción lo detecta
  (`StaleTicketSelection`) y se reintenta con nueva selección; con el evento casi agotado hay más
  reintentos y algunos mensajes pueden re-entregarse.
- **Alternativas**: asignar el número de entrada al confirmar (reserva solo de contador); pools de
  entradas por shard (combinado con 1); lectura consistente del rango en la tabla base (no posible
  sobre el GSI).

## 3. Rate limiter en memoria por instancia

- **Hoy**: Bucket4j local; con N réplicas de la API el límite efectivo es N × 20/s por cliente. En
  AWS el WAF agrega límites por IP (y uno estricto para crear órdenes).
- **Mejora**: Bucket4j sobre ElastiCache/Redis (cubetas compartidas), o API Gateway con *usage
  plans*; además, límite de **órdenes activas por cliente y evento** (anti-acaparamiento), que hoy
  no existe.

## 4. SSE por polling

- **Hoy**: un `GetItem` consistente por segundo **por evento e instancia** (compartido entre todos
  los espectadores), emitiendo solo cambios. Es simple y barato, pero tiene hasta 1 s de retraso, no
  limita conexiones por cliente y cada instancia de la API hace su propio poll.
- **Mejora**: *push* a partir de DynamoDB Streams del ítem del evento → (Lambda/EventBridge) →
  API Gateway WebSocket, AppSync subscriptions o un canal Redis pub/sub entre instancias.

## 5. Dual-write mitigado, no eliminado

- **Hoy**: outbox "lite" (ADR-0006): si falla la publicación, el sweeper re-publica tras 30 s.
  Efectos secundarios: duplicados acotados durante backlogs y el corte de **10 min**
  (`DISPATCH_EXHAUSTED`) que puede marcar `FAILED` órdenes legítimas si la cola acumula más de 10
  min de atraso (por ejemplo, todos los workers detenidos ese tiempo: al volver, el sweeper puede
  marcar órdenes antes de que el consumidor procese sus mensajes).
- **Mejora**: *transactional outbox* real con DynamoDB Streams + EventBridge Pipes → SQS; y basar
  el corte en la edad del mensaje/`ApproximateReceiveCount` en lugar de la edad de la orden.

## 6. Picos masivos: sala de espera virtual

- **Hoy**: la API acepta todo lo que pasa el WAF y el rate limit; la cola amortigua, pero un *drop*
  de cientos de miles de usuarios convierte la cola en minutos de espera sin feedback de posición.
- **Mejora**: sala de espera virtual (CloudFront + token de admisión firmado con tasa controlada)
  antes de la API, y feedback de posición en cola.

## 7. Multi-región

- **Hoy**: una región.
- **Consideración**: DynamoDB Global Tables es *last-writer-wins* (multi-activo) o fuertemente
  consistente con restricciones; para inventario sin sobreventa cada evento necesita una región
  "home" que procese sus reservas (las demás solo enrutan), o el modo de consistencia fuerte
  multi-región con su latencia.

## 8. Pago simulado y PCI-DSS

- **Hoy**: WireMock simula la pasarela (aprobado, rechazado, lento, 500, 404 en la consulta). El
  token de pago es opaco y nunca se registra.
- **En producción**: tokenización en el frontend (el backend nunca ve el PAN), alcance PCI mínimo,
  flujo *authorize + capture* (autorizar al confirmar y capturar al vender) y **reembolso
  automático** en lugar del ERROR para revisión manual cuando un cobro llega a una orden ya
  liberada; webhooks de la pasarela en lugar de solo consulta en la conciliación.
- **Pendiente**: Terraform inyecta `TICKETING_PAYMENT_APIKEY` desde Secrets Manager, pero el
  cliente de pagos aún no la envía.

## 9. Reportes contables

- **Hoy**: los contadores separan `soldCount` de `complimentaryCount` (las cortesías no son
  contables) y `ticket-transitions` guarda la historia completa por entrada, consultable por
  entrada (ADMIN).
- **Mejora**: exportar a un *data lake* (DynamoDB Streams → Firehose → S3 en Parquet → Athena/
  QuickSight) para reportes de ventas, conciliación con la pasarela y auditoría; exportaciones
  PITR a un bucket con Object Lock para evidencia inalterable.

## 10. Desalineaciones conocidas entre IaC y aplicación

Detectadas al documentar; afectan solo al despliegue en AWS (local funciona):

| Tema | IaC / doc de Terraform | Aplicación hoy | Ajuste |
|---|---|---|---|
| Audiencia del token | acepta `aud` **o** `client_id` (§2.5) | solo `aud` (`JwtAudienceValidator`) → los access tokens de Cognito serían rechazados | validador propio `aud ∨ client_id` |
| IP del cliente | salto más a la derecha de `X-Forwarded-For` (§2.3) | primer salto (informativo) | tomar el último salto agregado por el ALB |
| API key de la pasarela | `TICKETING_PAYMENT_APIKEY` | sin propiedad ni cabecera `Authorization` | agregar `ticketing.payment.api-key` y enviarla |
| Trazas | propagación `traceparent` por SQS (§5) | no implementada | atributo `traceparent` + exporter OTLP/ADOT |
| Alarma de cobro liberado | filtro `"payment captured for released order"` | el log empieza con mayúscula | alinear mayúsculas (los filtros distinguen) |

## 11. Otras limitaciones

- **Modo `worker`** registra igualmente los controladores REST (inalcanzables: sin puertos ni
  ingress). Mejora: condicionarlos a `RuntimeModeEnum.apiEnabled()`.
- **Ciclo de vida del evento**: no hay endpoints para cerrar/cancelar un evento ni cambiar su
  capacidad (`CLOSED` existe en el enum); las entradas son de admisión general (sin mapa de asientos)
  y crear un evento de 20 000 entradas escribe 20 000 ítems en la petición.
- **Cancelación** solo antes de confirmar (`QUEUED` o `RESERVED`); no hay devoluciones de entradas
  vendidas.
- **Claves de idempotencia sin expiración**: reutilizar una clave para otra compra siempre da 422.
- **Relojes**: las guardas de tiempo (`holdExpiresAt <= :now`) usan el reloj de cada instancia;
  la exclusión mutua confirmación/expiración no depende del reloj (misma condición de estado), pero
  un desfase grande adelantaría o retrasaría expiraciones (en AWS: Amazon Time Sync).
- **Observabilidad**: sin métricas de negocio dedicadas (órdenes aceptadas/rechazadas por motivo),
  sin métricas del circuit breaker exportadas (solo logs de transición) y sin exporter de trazas.
- **Tests de integración** con `amazon/dynamodb-local:latest` (compose usa `3.3.1` fija).
- **Soft check** con caché de 2 s: una orden puede aceptarse con el evento recién agotado; el
  worker la rechaza (`REJECTED/SOLD_OUT`). Es intencional (la decisión real es la transacción).

## 12. Qué cambiaría primero para producción

1. Corregir las desalineaciones de la sección 10 (audiencia Cognito antes que nada).
2. Outbox con Streams + Pipes y corte de despacho basado en la cola (5).
3. Contadores fragmentados para eventos de alta demanda (1) y sala de espera para *drops* (6).
4. Rate limit distribuido + límite de órdenes activas por cliente (3).
5. Autorización/captura y reembolsos automáticos con la pasarela real (8).
6. Trazas exportadas, métricas de negocio y SLOs (latencia de aceptación, tiempo hasta `RESERVED`).
7. *Push* de disponibilidad (4) y exportación analítica (9).
