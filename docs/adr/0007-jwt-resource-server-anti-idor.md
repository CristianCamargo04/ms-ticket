# ADR-0007 — JWT resource server (mock-oauth2 local / Cognito) y anti-IDOR

- **Estado:** Aceptada
- **Fecha:** 2026-10-10

## Contexto

Hay dos perfiles de usuario: **CUSTOMER** (compra, consulta y cancela sus órdenes) y **ADMIN**
(crea eventos, emite cortesías, consulta la auditoría). La API es *stateless* y escala
horizontalmente. Un error típico de APIs de órdenes es el **IDOR**: tomar el `customerId` del body o
permitir leer cualquier orden conociendo su id.

## Decisión

- **OAuth2 Resource Server con JWT** (`SecurityConfig`): sin sesión, CSRF, basic, form login ni
  logout. Firmas **RS256** validadas contra el JWKS del emisor; validadores de **expiración**
  (60 s de tolerancia), **issuer** (`ticketing.security.issuer`), **audience**
  (`ticketing.security.audience`, claim `aud`) y **`sub` no vacío**.
- El JWKS se descarga con timeouts (1 s conexión / 2 s respuesta) mediante `ThrottledJwkSource`: se
  cachea, comparte la descarga en curso y solo la re-descarga ante un `kid` desconocido **como mucho
  cada 30 s** (un atacante no puede forzar una llamada al emisor por cada token falso) y conserva las
  claves anteriores si la descarga falla.
- Roles: `JwtRoleConverter` lee el claim configurable `ticketing.security.roles-claim` (`roles` en
  local; en AWS Terraform inyecta `cognito:groups`) y produce autoridades `ROLE_<NOMBRE>`.
- Autorización por ruta:

| Ruta | Acceso |
|---|---|
| `/actuator/health/**`, `/actuator/info`, `/actuator/prometheus` | público (en AWS van a un puerto de gestión no enrutado, 8081) |
| `/api/v1/events/*/tickets/**` | `ADMIN` |
| `GET /api/v1/events`, `GET /api/v1/events/**` | público |
| `POST /api/v1/events`, `POST /api/v1/events/*/complimentary-tickets` | `ADMIN` |
| `POST /api/v1/events/*/orders`, `/api/v1/orders/**` | `CUSTOMER` o `ADMIN` |
| resto | autenticado |

- **Anti-IDOR / anti-spoofing**: el cliente es **siempre** el `sub` del token
  (`RequestContextResolverImpl`), nunca un campo del body. `GetOrderStatus`, `ConfirmOrder` y
  `CancelOrder` exigen `order.customerId == sub` (ADMIN puede leer cualquier orden, pero no
  confirmarla ni cancelarla en nombre de otro). Una orden ajena responde **`404 ORDER_NOT_FOUND`**,
  igual que una inexistente (no filtra existencia). La transacción de confirmación además repite la
  condición `customerId = :customerId` en DynamoDB.
- Errores 401/403 como `ProblemDetail` (`SecurityProblemHandler`) con `WWW-Authenticate` RFC 6750,
  sin detalles de validación del token.
- Local: `ghcr.io/navikt/mock-oauth2-server` (clientes `customer-1`, `customer-2`, `admin`;
  `scripts/infra/get-token.sh`). El emisor `iss` es la URL del host (`http://localhost:8090/default`)
  y las claves se descargan por la red de compose (`http://auth-mock:8080/default/jwks`), por eso se
  usa `jwk-set-uri` + validador de issuer explícito en vez de *discovery* por `issuer-uri`.
- AWS: Amazon Cognito (grupos `ADMIN`/`CUSTOMER`, trigger post-confirmación que asigna `CUSTOMER`).

## Consecuencias

**Positivas**
- Mismo flujo de validación en local y en AWS (solo cambian URLs y claim de roles).
- Imposible actuar sobre órdenes ajenas aunque se adivine un `orderId`.

**Negativas / pendientes**
- El validador de audiencia de Spring (`JwtAudienceValidator`) solo mira el claim **`aud`**. Los
  *access tokens* de Cognito no traen `aud` (traen `client_id`), así que en AWS hoy solo se aceptan
  ID tokens; aceptar `aud` **o** `client_id` (como describe `infra/terraform/README.md` §2.5)
  requiere un validador propio. Registrado en [limitaciones](../limitations-and-improvements.md).
- Sin revocación inmediata: un token robado vale hasta su expiración (15 min en Cognito).

## Alternativas consideradas

- **API keys por cliente**: simples, pero sin identidad de usuario ni roles estándar.
- **Sesiones con cookie**: requieren CSRF y estado compartido; no encajan con una API stateless.
- **`issuer-uri` con discovery**: falla en local por la diferencia host/red de compose.

## Referencias

- `config/security/SecurityConfig.java`, `JwtRoleConverter.java`, `ThrottledJwkSource.java`,
  `SecurityProblemHandler.java`, `presentation/context/impl/RequestContextResolverImpl.java`,
  `docker/auth-mock/config.json`, `infra/terraform/modules/auth/`.
