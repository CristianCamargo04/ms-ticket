package com.ticketing.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.enums.RuntimeModeEnum;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
}
