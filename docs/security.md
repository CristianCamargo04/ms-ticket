# Seguridad de ms-ticket

Resumen de los controles de seguridad **implementados** (código, contenedores e infraestructura) y
de las brechas conocidas. Las decisiones principales están en
[ADR-0005](adr/0005-deterministic-order-id.md) (idempotencia) y
[ADR-0007](adr/0007-jwt-resource-server-anti-idor.md) (autenticación y anti-IDOR).

## 1. Secretos y credenciales

| Control | Implementación |
|---|---|
| Cero credenciales en código o YAML | Ningún `application*.yml` contiene claves. Los clientes AWS usan `DefaultCredentialsProvider` (`AwsClientConfig`): variables de entorno en local, **IAM task role** en ECS. No existen propiedades de credenciales. |
| Credenciales locales dummy | `.env` (git-ignored) se crea desde `.env.example` con valores de emulador (`test`/`test`). Usan el prefijo `LOCAL_AWS_*`: compose da prioridad a las variables de la shell sobre `.env`, así que con nombres `AWS_*` se reenviarían a los contenedores las credenciales reales exportadas por un desarrollador. Compose las inyecta como `AWS_*` solo dentro de los contenedores. |
| Contexto de build | `.dockerignore` en modo *allowlist*: solo entran el wrapper de Gradle, los scripts de build, `src/main` y el probe de salud. Nunca `.git`, `.env`, tests, docs ni infra. |
| AWS | Un task role y un execution role **por servicio** (api/worker), con permisos acotados a ARNs de tablas e índices; sin access keys de larga duración; CI debería asumir un rol por OIDC. Secretos (API key de la pasarela) en **Secrets Manager**, inyectados por ECS (`valueFrom`); Terraform crea solo un placeholder y el valor real se carga fuera de banda. |
| Datos de pago | El cliente envía un `paymentToken` tokenizado (`^[A-Za-z0-9_-]+$`, máx. 128), **nunca** un número de tarjeta. El token no se registra en logs: los consumidores SQS no hacen eco del payload (`JsonMessageReader`) y el cliente de pagos registra solo la operación y la clave de idempotencia. |
| Logs | Solo identificadores (orderId, eventId, ticketId, sub). El rate limiter no registra la clave del llamador. Los `ProblemDetail` nunca incluyen trazas ni valores rechazados. |

## 2. Autenticación y autorización

- **OAuth2 Resource Server JWT** stateless (`SecurityConfig`): RS256 contra el JWKS del emisor,
  validación de expiración (60 s de tolerancia), `iss` = `ticketing.security.issuer`,
  `aud` contiene `ticketing.security.audience` y `sub` no vacío. Sin sesión, CSRF, basic, form login
  ni logout (no hay cookies, solo bearer tokens).
- **JWKS endurecido** (`ThrottledJwkSource`): descarga perezosa con timeouts (1 s / 2 s), compartida
  entre peticiones concurrentes, y re-descarga ante un `kid` desconocido **como máximo cada 30 s**;
  así un token falsificado no puede convertir cada petición anónima en una llamada al emisor. Si la
  re-descarga falla se mantienen las claves anteriores.
- **Roles** desde un claim configurable (`roles` local, `cognito:groups` en AWS) → `ROLE_<NOMBRE>`
  (`JwtRoleConverter`, acepta array o string separado por espacios/comas).
- **Autorización por ruta**:

| Ruta | Acceso |
|---|---|
| `GET /api/v1/events`, `GET /api/v1/events/{id}`, `.../availability`, `.../availability/stream` | público |
| `POST /api/v1/events`, `POST /api/v1/events/{id}/complimentary-tickets`, `/api/v1/events/{id}/tickets/**` | `ADMIN` |
| `POST /api/v1/events/{id}/orders`, `/api/v1/orders/**` | `CUSTOMER` o `ADMIN` |
| `/actuator/health/**`, `/actuator/info`, `/actuator/prometheus` | público (ver 9) |
| cualquier otra | autenticado |

- **Anti-IDOR**: el cliente es siempre el `sub` del token; leer, confirmar o cancelar una orden
  ajena responde `404 ORDER_NOT_FOUND` (indistinguible de una inexistente). `ADMIN` puede leer
  cualquier orden pero no confirmar ni cancelar en nombre de otro. La transacción de confirmación
  repite `customerId = :customerId` como condición en DynamoDB.
- **Errores**: `401` con `WWW-Authenticate: Bearer` (o `Bearer error="invalid_token"`), `403` con
  `Bearer error="insufficient_scope"`, ambos como `ProblemDetail` sin detalles del token.

Ejemplo real (sin token):

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer
Content-Type: application/problem+json

{"detail":"A valid bearer token is required to access this resource","instance":"/api/v1/events/51871790-1a19-41da-83a9-57fd8476846d/orders","status":401,"title":"UNAUTHORIZED","errorCode":"UNAUTHORIZED","correlationId":"6e262d3e-7256-46e9-aa23-210f894ea95e","timestamp":"2026-10-11T04:20:01.082878043Z"}
```

## 3. Idempotencia y reintentos maliciosos

| Amenaza | Control |
|---|---|
| Doble clic / reintento de red | `Idempotency-Key` obligatorio en los `POST` que crean órdenes; `orderId = UUIDv5(sub + ":" + key)`; insert con `attribute_not_exists(orderId)`. El reintento devuelve la **misma** orden (`202`, mismo `Location`). |
| Reutilizar una clave para otra compra | Huella SHA-256 del payload (`requestHash`) + verificación de evento, dueño, tipo y cantidad → `422 IDEMPOTENCY_KEY_REUSED`. |
| Peticiones idénticas en paralelo | Solo una inserción gana; las demás releen y devuelven la ganadora. |
| Re-entrega o duplicado de mensajes | Consumidores idempotentes por estado (`processingStatus = QUEUED`, `ticketStatus = PENDING_CONFIRMATION`). |
| Doble cobro | La pasarela recibe `Idempotency-Key = orderId` en cada intento y reintento; un cobro sin clave es rechazado por el cliente antes de enviarse. |
| Reintentos de escrituras DynamoDB | `ClientRequestToken` determinístico por comando (deduplicación de reintentos del SDK). |
| Ráfagas de reintentos | Rate limit por sujeto (sección 4); `Retry-After` en 429 y en 503 transitorios. |

## 4. Abuso de recursos

| Control | Detalle |
|---|---|
| **Rate limit por sujeto** | `RateLimitWebFilter` (Bucket4j): cubeta de **20** tokens recargada con 20 por segundo, por `sub` del JWT (IP como respaldo). Solo `POST` y `DELETE` bajo `/api/`. Responde `429 RATE_LIMIT_EXCEEDED` con `Retry-After`; las aceptadas llevan `X-RateLimit-Remaining`. Cubetas en Caffeine acotado a **100 000** llamadores (sin crecimiento ilimitado de memoria). |
| Límites de negocio | `quantity` 1..10 por compra, 1..40 por cortesía, capacidad ≤ 20 000 por evento, página 1..100. |
| Tamaño de body | 256 KB máximo al decodificar (`413` si se excede). |
| Identificadores | ids de ruta `^[A-Za-z0-9-]{1,64}$`, `Idempotency-Key` 8..64, `X-Correlation-Id` 1..64 (si no cumple se reemplaza por un UUID: evita *log injection*). |
| *Soft check* de agotado | Con el evento agotado (según la caché de 2 s) la API responde `409 SOLD_OUT` sin crear la orden ni encolar: un bot no puede inundar la cola de un evento agotado. |
| *Load leveling* | La cola absorbe picos; los workers procesan con concurrencia acotada (16 mensajes por cola y réplica). |
| SSE | Un único poll por evento e instancia (el costo no crece con las conexiones), heartbeat cada 15 s y vida máxima de 30 min por conexión. |
| JWKS | Re-descarga limitada (ver sección 2). |
| Borde en AWS (WAF) | IP reputation, IPs anónimas/hosting, **rate limit por IP** y uno **más estricto para `POST /api/v1/events/*/orders`** (anti-scalping), OWASP Common Rule Set, Known Bad Inputs y bloqueo de `/actuator` normalizado. Logs del WAF con `Authorization` y `Cookie` redactados. |

## 5. Validación de entrada y errores

- DTOs con Jakarta Validation (`@NotBlank`, `@Size`, `@Min`/`@Max`, `@Future`, `@Digits`,
  `@Pattern`) y validación de negocio en los casos de uso (`DomainValidationException`).
- Mensajes SQS validados contra su esquema (`JsonMessageReader`): JSON inválido o restricciones
  violadas → `MalformedMessageException` → sin *ack* → DLQ tras 5 recepciones. Lector tolerante: se
  ignoran propiedades desconocidas.
- **RFC 9457 `ProblemDetail`** en todos los errores (`GlobalErrorHandler`,
  `SecurityProblemHandler`, `RateLimitWebFilter`): `title`/`errorCode` estables, `detail` seguro,
  `correlationId`, `timestamp`; las validaciones listan `{field, message}` sin el valor rechazado; un
  error inesperado es un `500 INTERNAL_ERROR` genérico y la excepción solo va al log.

## 6. Cabeceras de seguridad

Capturadas de una respuesta real de la API:

| Cabecera | Valor |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'` |
| `Referrer-Policy` | `no-referrer` |
| `Cache-Control` / `Pragma` / `Expires` | `no-cache, no-store, max-age=0, must-revalidate` / `no-cache` / `0` |
| `X-XSS-Protection` | `0` (recomendación actual: desactivar el auditor XSS heredado) |
| `Strict-Transport-Security` | `max-age=31536000 ; includeSubDomains`, solo en peticiones HTTPS **o** con `X-Forwarded-Proto: https` (TLS terminado en el ALB) |
| `X-Correlation-Id` | eco del id de correlación |

## 7. Contenedores endurecidos

| Control | Local (compose) | AWS (ECS Fargate) |
|---|---|---|
| Imagen | multi-stage; runtime solo JRE 25 (`eclipse-temurin:25-jre`); archivos de la app propiedad de root (solo lectura para la app) | misma imagen, ECR con tags inmutables, *scan on push*, KMS |
| Usuario | UID/GID **10001** sin shell ni home | `user = 10001:10001` |
| Sistema de archivos | `read_only: true` + `tmpfs /tmp` acotado | `readonlyRootFilesystem` + volumen efímero en `/tmp` |
| Capabilities | `cap_drop: [ALL]`, `no-new-privileges` | todas eliminadas, sin modo privilegiado, `initProcess` |
| Recursos | límites de CPU, memoria y PIDs; logs `json-file` 3 × 10 MB | tamaño de tarea fijo, autoscaling acotado |
| Red | puertos publicados solo en `127.0.0.1`; el worker no publica puertos; LocalStack sin socket de Docker | subredes privadas, la API solo acepta tráfico del SG del ALB, el worker **sin ingress**, egress 443 |
| JVM | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | igual |
| Health check | `/usr/local/bin/healthcheck` (bash `/dev/tcp`, sin curl/wget en la imagen) contra `/actuator/health/readiness` | ídem en el puerto de gestión 8081 |
| Acceso interactivo | — | ECS Exec deshabilitado en prod (validación cruzada de variables); en dev, sesiones auditadas en log group cifrado |

## 8. Cifrado

- **En reposo (AWS)**: 4 CMKs de KMS con rotación (`dynamodb`, `sqs`, `logs`, `platform`), usables
  solo vía su servicio (`kms:ViaService`). DynamoDB con SSE-KMS y PITR; SQS y DLQ con SSE-KMS; log
  groups, ECR, Secrets Manager y el tópico SNS cifrados. Local: SSE-SQS en LocalStack.
- **En tránsito**: TLS 1.3/1.2 en el ALB (HTTP→HTTPS), HSTS; políticas de colas, tópico y buckets que
  **niegan `aws:SecureTransport = false`**; el SDK usa HTTPS y los endpoints de VPC (gateway para
  DynamoDB/S3, interface para SQS, ECR, Logs, STS, Secrets Manager) mantienen el tráfico en la red de
  AWS.
- **Riesgo aceptado**: el tramo ALB → tarea es HTTP dentro de subredes privadas (documentado en
  `infra/terraform/README.md` §2.2 con el camino para TLS de punta a punta).

## 9. Exposición del actuator

- Local: `health`, `info` y `prometheus` en el puerto 8080 sin autenticación; el perfil `local` usa
  `show-details: always` (útil para diagnosticar); el perfil base usa `never`.
- AWS: Terraform inyecta `MANAGEMENT_SERVER_PORT=8081`; el listener del ALB solo enruta 8080, el
  health check del target group usa 8081 y el SG de la tarea solo acepta 8081 desde el ALB. Además,
  una regla del WAF bloquea `/actuator` normalizado y una regla del listener responde 404.

## 10. Amenazas y mitigaciones (STRIDE)

| Categoría | Amenaza | Mitigación |
|---|---|---|
| **S**poofing | Actuar como otro cliente | JWT firmado RS256 + issuer/audience/sub; `customerId` = `sub`, nunca del body |
| **S**poofing | Tokens forjados con `kid` desconocido para saturar el IdP | `ThrottledJwkSource` (1 descarga cada 30 s como máximo) |
| **T**ampering | Modificar inventario o estados fuera de flujo | Toda transición es una transacción condicional; la auditoría es insert-only (`attribute_not_exists`); IAM sin `UpdateItem`/`DeleteItem` sobre `ticket-transitions` |
| **T**ampering | Mensajes SQS manipulados o malformados | Validación de esquema; colas cifradas con política TLS-only; el estado autoritativo está en DynamoDB, el mensaje solo dispara |
| **R**epudiation | Negar una compra o cancelación | `ticket-transitions` registra `actor` (sub, `order-worker`, `payment-worker`, nombre del sweeper), `reason`, `orderId` y hora; `correlationId` de punta a punta en logs |
| **I**nformation disclosure | Enumerar órdenes ajenas (IDOR) | 404 idéntico para inexistente y ajena; UUIDs no secuenciales |
| **I**nformation disclosure | Fugas en errores/logs | `ProblemDetail` genérico, sin trazas ni valores; tokens de pago nunca logueados; actuator fuera del listener público en AWS |
| **D**enial of service | Ráfagas de compras / bots | WAF (rate por IP, regla estricta en órdenes), rate limit por sujeto, *soft check* de agotado, límites de cantidad y de body, cola como amortiguador |
| **D**enial of service | Muchas conexiones SSE | Poll compartido por evento, vida máxima 30 min (sin límite de conexiones por cliente: ver brechas) |
| **E**levation of privilege | Cliente que crea eventos o ve auditoría | Reglas por ruta con `hasRole('ADMIN')`; roles solo desde el token firmado |
| **E**levation of privilege | Escape del contenedor | No root, rootfs de solo lectura, sin capabilities, `no-new-privileges` |

## 11. Brechas conocidas (honestas)

1. **Audiencia de Cognito**: el validador solo acepta el claim `aud`; los access tokens de Cognito
   no lo traen (traen `client_id`). En AWS hoy habría que usar ID tokens o agregar un validador
   `aud` **o** `client_id` (lo que ya describe `infra/terraform/README.md` §2.5).
2. **API key de la pasarela**: Terraform inyecta `TICKETING_PAYMENT_APIKEY` desde Secrets Manager,
   pero el cliente de pagos aún no la envía (no existe `ticketing.payment.api-key`).
3. **IP del cliente**: `RequestContextResolverImpl` toma el **primer** salto de `X-Forwarded-For`
   (falsificable por el cliente). Solo es informativo y respaldo del rate limit para no autenticados
   (que nunca llegan a rutas limitadas); `infra/terraform/README.md` §2.3 describe el salto más a la
   derecha, que es lo correcto detrás de un ALB.
4. **Rate limit en memoria por instancia**: con N réplicas el límite efectivo es N × 20/s; en AWS lo
   complementa el WAF. Mejora: Bucket4j distribuido (ElastiCache) o API Gateway usage plans.
5. Sin límite de **órdenes activas por cliente/evento** ni de **conexiones SSE por cliente**.
6. Sin revocación inmediata de tokens (vida corta del access token como mitigación).
7. En modo `worker` las rutas REST siguen registradas (no son alcanzables: sin puertos publicados ni
   ingress).

Detalle y propuestas en [limitations-and-improvements.md](limitations-and-improvements.md).
