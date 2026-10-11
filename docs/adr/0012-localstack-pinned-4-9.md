# ADR-0012 — LocalStack fijado en 4.9

- **Estado:** Aceptada
- **Fecha:** 2026-10-10

## Contexto

El entorno local y los tests de integración necesitan SQS real (colas, DLQ, `RedrivePolicy`,
`ChangeMessageVisibility`, `StartMessageMoveTask`). LocalStack es el emulador de referencia, pero
la etiqueta `localstack/localstack:latest` **exige un auth token de pago** desde la unificación de
sus imágenes, por lo que `latest` no arranca sin licencia.

## Decisión

Fijar **`localstack/localstack:4.9`** en todos los lugares:

- `docker-compose.yml` (servicio `localstack`, `SERVICES=sqs`, script de inicialización
  `src/main/resources/scripts/localstack/init-sqs.sh` montado en `ready.d`, healthcheck que exige las
  4 colas);
- tests de integración: `org.testcontainers.localstack.LocalStackContainer` (Testcontainers 2.x)
  con `DockerImageName.parse("localstack/localstack:4.9")`.

En compose el resto de imágenes también se fija por versión: `amazon/dynamodb-local:3.3.1`,
`wiremock/wiremock:3.13.2`, `ghcr.io/navikt/mock-oauth2-server:6.0.5`,
`aaronshaf/dynamodb-admin:5.3.4`. Los tests de integración usan `amazon/dynamodb-local:latest`
(`DynamoDbLocalContainer`); fijarla también es una mejora pendiente.

## Consecuencias

**Positivas**
- Entorno reproducible y gratuito; mismo comportamiento en la máquina del evaluador y en CI.
- Las imágenes fijadas evitan roturas por cambios de versión.

**Negativas**
- Hay que actualizar la versión de forma explícita (y verificar si la nueva requiere token).
- LocalStack es un emulador: la semántica fina de SQS (por ejemplo tiempos exactos de visibilidad)
  puede diferir; por eso el comportamiento crítico también se prueba con unit tests deterministas.

## Alternativas consideradas

- **ElasticMQ** (API compatible con SQS): ligero y sin token; plan B documentado en `PLAN.md`. No
  fue necesario.
- **SQS real en una cuenta AWS de desarrollo**: fidelidad total, pero requiere credenciales y
  costo para el evaluador.

## Referencias

- `docker-compose.yml`, `docker/README.md`, `docker/localstack/healthcheck.sh`,
  `src/integrationTest/java/com/ticketing/config/messaging/LocalStackSqsSupport.java`.
