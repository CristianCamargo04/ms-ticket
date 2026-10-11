package com.ticketing.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.enums.RuntimeModeEnum;
import java.time.Duration;
import java.util.Map;
import java.io.IOException;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class TicketingPropertiesTest {

    private static void assertSpecDefaults(TicketingProperties properties) {
        assertThat(properties.runtimeMode()).isEqualTo(RuntimeModeEnum.ALL);

        assertThat(properties.orders().holdTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.orders().confirmationTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.orders().republishAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.orders().maxTicketsPerOrder()).isEqualTo(10);
        assertThat(properties.orders().maxComplimentaryPerRequest()).isEqualTo(40);
        assertThat(properties.orders().candidateFactor()).isEqualTo(3);

        assertThat(properties.events().maxCapacity()).isEqualTo(20000);
        assertThat(properties.events().availabilityStreamInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.events().ticketWriteConcurrency()).isEqualTo(4);

        assertThat(properties.cache().eventTtl()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.cache().eventMaxSize()).isEqualTo(10000);

        assertThat(properties.sweeper().interval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.sweeper().lease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.sweeper().batchSize()).isEqualTo(100);
        assertThat(properties.sweeper().concurrency()).isEqualTo(8);

        assertThat(properties.retry().inventory().maxAttempts()).isEqualTo(6);
        assertThat(properties.retry().inventory().minBackoff()).isEqualTo(Duration.ofMillis(25));
        assertThat(properties.retry().inventory().maxBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.retry().inventory().jitter()).isEqualTo(0.5);
        assertThat(properties.retry().publish().maxAttempts()).isEqualTo(3);
        assertThat(properties.retry().publish().minBackoff()).isEqualTo(Duration.ofMillis(100));
        assertThat(properties.retry().publish().maxBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.retry().publish().jitter()).isEqualTo(0.5);

        assertThat(properties.rateLimit().enabled()).isTrue();
        assertThat(properties.rateLimit().capacity()).isEqualTo(20);
        assertThat(properties.rateLimit().refillTokens()).isEqualTo(20);
        assertThat(properties.rateLimit().refillPeriod()).isEqualTo(Duration.ofSeconds(1));

        assertThat(properties.messaging().maxMessages()).isEqualTo(10);
        assertThat(properties.messaging().waitTimeSeconds()).isEqualTo(20);
        assertThat(properties.messaging().visibilityTimeoutSeconds()).isEqualTo(60);
        assertThat(properties.messaging().concurrency()).isEqualTo(16);
        assertThat(properties.messaging().pollers()).isEqualTo(2);

        assertThat(properties.payment().connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.payment().responseTimeout()).isEqualTo(Duration.ofSeconds(3));

        assertThat(properties.security().audience()).isEqualTo("ms-ticket");
        assertThat(properties.security().rolesClaim()).isEqualTo("roles");
    }

    @Test
    void shouldApplySpecDefaultsWhenNothingIsConfigured() {
        TicketingProperties properties = new Binder().bindOrCreate("ticketing", TicketingProperties.class);

        assertSpecDefaults(properties);
        assertThat(properties.payment().baseUrl()).isNull();
        assertThat(properties.security().issuer()).isNull();
        assertThat(properties.migrations().enabled()).isFalse();
    }

    @Test
    void shouldBindRelaxedNames() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "ticketing.runtime-mode", "worker",
                "ticketing.orders.hold-ttl", "2m",
                "ticketing.retry.inventory.max-attempts", "9",
                "ticketing.rate-limit.enabled", "false")));

        TicketingProperties properties = binder.bindOrCreate("ticketing", TicketingProperties.class);

        assertThat(properties.runtimeMode()).isEqualTo(RuntimeModeEnum.WORKER);
        assertThat(properties.orders().holdTtl()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.orders().confirmationTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.retry().inventory().maxAttempts()).isEqualTo(9);
        assertThat(properties.rateLimit().enabled()).isFalse();
    }

    @Test
    void shouldBindApplicationYmlWithSpecValues() {
        TicketingProperties properties = YamlBinding.binderFor("application.yml")
                .bindOrCreate("ticketing", TicketingProperties.class);

        assertSpecDefaults(properties);
        assertThat(properties.payment().baseUrl()).isEqualTo("http://localhost:8089");
        assertThat(properties.security().issuer()).isEqualTo("http://localhost:8090/default");
        assertThat(properties.migrations().enabled()).isFalse();
    }

    @Test
    void shouldEnableMigrationsInLocalAndCiProfiles() {
        TicketingProperties local = YamlBinding.binderFor("application.yml", "application-local.yml")
                .bindOrCreate("ticketing", TicketingProperties.class);
        TicketingProperties ci = YamlBinding.binderFor("application.yml", "application-ci.yml")
                .bindOrCreate("ticketing", TicketingProperties.class);

        assertThat(local.migrations().enabled()).isTrue();
        assertThat(local.payment().baseUrl()).isEqualTo("http://localhost:8089");
        assertThat(local.security().issuer()).isEqualTo("http://localhost:8090/default");
        assertThat(ci.migrations().enabled()).isTrue();
        assertSpecDefaults(ci);
    }

    @Test
    void shouldPointLocalProfileToTheLocalJwtIssuer() {
        Binder binder = YamlBinding.binderFor("application.yml", "application-local.yml");

        assertThat(binder.bind("spring.security.oauth2.resourceserver.jwt.issuer-uri", String.class).get())
                .isEqualTo("http://localhost:8090/default");
        assertThat(binder.bind("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", String.class).get())
                .isEqualTo("http://localhost:8090/default/jwks");
    }

    @Test
    void shouldConfigureOperationalSettings() {
        Binder binder = YamlBinding.binderFor("application.yml");

        assertThat(binder.bind("spring.application.name", String.class).get()).isEqualTo("ms-ticket");
        assertThat(binder.bind("server.shutdown", String.class).get()).isEqualTo("graceful");
        assertThat(binder.bind("spring.threads.virtual.enabled", Boolean.class).get()).isTrue();
        assertThat(binder.bind("spring.lifecycle.timeout-per-shutdown-phase", Duration.class).get())
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(binder.bind("management.endpoints.web.exposure.include", String.class).get())
                .isEqualTo("health,info,prometheus");
        assertThat(binder.bind("management.endpoint.health.probes.enabled", Boolean.class).get()).isTrue();
    }

    @Test
    void shouldUseStructuredLoggingOnlyOutsideTheLocalProfile() {
        String key = "logging.structured.format.console";

        assertThat(YamlBinding.binderFor("application.yml", "application-local.yml").bind(key, String.class)
                .isBound()).isFalse();
        assertThat(YamlBinding.binderFor("application.yml", "application-dev.yml").bind(key, String.class).get())
                .isEqualTo("ecs");
        assertThat(YamlBinding.binderFor("application.yml", "application-ci.yml").bind(key, String.class).get())
                .isEqualTo("ecs");
        assertThat(YamlBinding.binderFor("application.yml", "application-prod.yml").bind(key, String.class).get())
                .isEqualTo("ecs");
    }

    @Test
    void shouldBindTheOptionalPaymentApiKeyAndNeverPrintIt() {
        TicketingProperties.Payment withKey = new Binder(new MapConfigurationPropertySource(Map.of(
                "ticketing.payment.base-url", "https://pay.example",
                "ticketing.payment.api-key", "sk-live-0123456789")))
                .bindOrCreate("ticketing", TicketingProperties.class).payment();
        TicketingProperties.Payment withoutKey = new Binder().bindOrCreate("ticketing", TicketingProperties.class)
                .payment();
        TicketingProperties.Payment blankKey = new Binder(new MapConfigurationPropertySource(Map.of(
                "ticketing.payment.api-key", "  ")))
                .bindOrCreate("ticketing", TicketingProperties.class).payment();

        assertThat(withKey.apiKey()).isEqualTo("sk-live-0123456789");
        assertThat(withKey.hasApiKey()).isTrue();
        assertThat(withKey.toString())
                .doesNotContain("sk-live", "0123456789")
                .contains("apiKey=" + TicketingProperties.Payment.MASK, "baseUrl=https://pay.example",
                        "callTimeout=PT4S", "maxRetries=2");
        assertThat(withoutKey.apiKey()).isNull();
        assertThat(withoutKey.hasApiKey()).isFalse();
        assertThat(withoutKey.toString()).contains("apiKey=<none>");
        assertThat(blankKey.hasApiKey()).isFalse();
        assertThat(new Binder(new MapConfigurationPropertySource(Map.of("ticketing.payment.api-key", "k")))
                .bindOrCreate("ticketing", TicketingProperties.class).toString()).doesNotContain("apiKey=k");
    }

    @Test
    void shouldServeTheActuatorOnTheManagementPortBehindTheLoadBalancerInProd() {
        Binder prod = YamlBinding.binderFor("application.yml", "application-prod.yml");
        Binder local = YamlBinding.binderFor("application.yml", "application-local.yml");

        assertThat(prod.bind("management.server.port", Integer.class).get()).isEqualTo(8081);
        assertThat(prod.bind("management.endpoint.health.probes.enabled", Boolean.class).get()).isTrue();
        assertThat(prod.bind("server.forward-headers-strategy", String.class).get()).isEqualTo("native");
        assertThat(prod.bind("ticketing.payment.api-key", String.class).isBound()).isFalse();
        assertThat(local.bind("management.server.port", Integer.class).isBound()).isFalse();
        assertThat(local.bind("server.forward-headers-strategy", String.class).isBound()).isFalse();
    }

    /** The variables the Terraform task definitions inject (infra/terraform/envs/{dev,prod}/main.tf), nothing else. */
    private static Map<String, Object> terraformEnvironment() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("AWS_REGION", "eu-west-1");
        variables.put("AWS_DYNAMODB_TABLES_EVENTS", "tf-events");
        variables.put("AWS_DYNAMODB_TABLES_TICKETS", "tf-tickets");
        variables.put("AWS_DYNAMODB_TABLES_ORDERS", "tf-orders");
        variables.put("AWS_DYNAMODB_TABLES_TICKETTRANSITIONS", "tf-ticket-transitions");
        variables.put("AWS_DYNAMODB_TABLES_DISTRIBUTEDLOCKS", "tf-distributed-locks");
        variables.put("AWS_SQS_ORDERRESERVATIONQUEUE", "tf-order-reservation");
        variables.put("AWS_SQS_ORDERPAYMENTQUEUE", "tf-order-payment");
        variables.put("TICKETING_MIGRATIONS_ENABLED", "false");
        variables.put("TICKETING_PAYMENT_BASEURL", "https://pay.example");
        variables.put("MANAGEMENT_SERVER_PORT", "8081");
        variables.put("TICKETING_SECURITY_ISSUER", "https://cognito-idp.eu-west-1.amazonaws.com/pool");
        variables.put("TICKETING_SECURITY_AUDIENCE", "app-client-id");
        variables.put("TICKETING_SECURITY_ROLESCLAIM", "cognito:groups");
        variables.put("SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI",
                "https://cognito-idp.eu-west-1.amazonaws.com/pool/.well-known/jwks.json");
        variables.put("TICKETING_RUNTIME_MODE", "worker");
        variables.put("TICKETING_RUNTIMEMODE", "worker");
        return variables;
    }

    /**
     * An environment like the one of an ECS task: the injected variables (as a plain source, so only placeholder
     * resolution is exercised, not relaxed binding) on top of {@code application.yml} and the profile file.
     */
    private static StandardEnvironment awsEnvironment(String profileFile) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String resource : new String[] {"application.yml", profileFile}) {
            for (PropertySource<?> source : loader.load(resource, new ClassPathResource(resource))) {
                environment.getPropertySources().addFirst(source);
            }
        }
        environment.getPropertySources().addFirst(new MapPropertySource("terraform", terraformEnvironment()));
        return environment;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application-prod.yml", "application-dev.yml"})
    void shouldResolveEveryAwsProfilePlaceholderWithTheVariablesTerraformInjects(String profileFile)
            throws IOException {
        StandardEnvironment environment = awsEnvironment(profileFile);

        // strict resolution, like Spring Boot's auto-configuration conditions: an unresolvable placeholder throws
        assertThat(environment.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                .isEqualTo("https://cognito-idp.eu-west-1.amazonaws.com/pool");
        assertThat(environment.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"))
                .endsWith("/pool/.well-known/jwks.json");
        TicketingProperties ticketing = Binder.get(environment).bindOrCreate("ticketing", TicketingProperties.class);
        assertThat(ticketing.runtimeMode()).isEqualTo(RuntimeModeEnum.WORKER);
        assertThat(ticketing.payment().baseUrl()).isEqualTo("https://pay.example");
        assertThat(ticketing.security().issuer()).isEqualTo("https://cognito-idp.eu-west-1.amazonaws.com/pool");
        assertThat(ticketing.security().audience()).isEqualTo("app-client-id");
        assertThat(ticketing.migrations().enabled()).isFalse();
        AwsProperties aws = Binder.get(environment).bindOrCreate("aws", AwsProperties.class);
        assertThat(aws.region()).isEqualTo("eu-west-1");
        assertThat(aws.dynamodb().tables().events()).isEqualTo("tf-events");
        assertThat(aws.dynamodb().tables().ticketTransitions()).isEqualTo("tf-ticket-transitions");
        assertThat(aws.dynamodb().tables().distributedLocks()).isEqualTo("tf-distributed-locks");
        assertThat(aws.sqs().orderReservationQueue()).isEqualTo("tf-order-reservation");
        assertThat(aws.sqs().orderPaymentQueue()).isEqualTo("tf-order-payment");
    }
}
