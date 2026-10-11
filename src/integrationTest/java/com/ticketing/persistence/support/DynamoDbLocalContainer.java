package com.ticketing.persistence.support;

import com.ticketing.config.migration.DynamoMigrationRunner;
import com.ticketing.config.migration.MigrationChangelogReader;
import com.ticketing.config.migration.TableDefinitionParser;
import com.ticketing.config.properties.AwsProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.DefaultResourceLoader;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * One DynamoDB Local container per test JVM (started lazily, stopped by the Testcontainers reaper) and the helpers
 * to create an isolated set of tables from the real migration files.
 */
public final class DynamoDbLocalContainer {

    private static final int PORT = 8000;

    private static GenericContainer<?> container;
    private static DynamoDbAsyncClient client;

    private DynamoDbLocalContainer() {
    }

    /**
     * The shared client, starting the container on first use.
     *
     * @return a client pointed at DynamoDB Local
     */
    public static synchronized DynamoDbAsyncClient client() {
        if (client == null) {
            container = new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:latest"))
                    .withExposedPorts(PORT)
                    .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
                    .waitingFor(Wait.forListeningPort());
            container.start();
            client = DynamoDbAsyncClient.builder()
                    .endpointOverride(URI.create("http://" + container.getHost() + ":" + container.getMappedPort(PORT)))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                    .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                            .maxConcurrency(256)
                            .connectionAcquisitionTimeout(Duration.ofSeconds(60)))
                    .build();
        }
        return client;
    }

    /**
     * The endpoint of the shared DynamoDB Local container, starting it on first use (for applications under test
     * that build their own client, e.g. {@code aws.endpoint-override.dynamodb-endpoint}).
     *
     * @return the HTTP endpoint reachable from the test JVM
     */
    public static synchronized URI endpoint() {
        client();
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(PORT));
    }

    /**
     * Unique physical table names, so that every test class works on its own tables.
     *
     * @return table names prefixed with a random id
     */
    public static AwsProperties.Tables uniqueTables() {
        String prefix = "it-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        return new AwsProperties.Tables(prefix + "events", prefix + "tickets", prefix + "orders",
                prefix + "ticket-transitions", prefix + "distributed-locks", prefix + "schema-migrations");
    }

    /**
     * A migration runner reading the real changelog and migration files of {@code src/main/resources}.
     *
     * @param tables the physical table names
     * @return the runner
     */
    public static DynamoMigrationRunner migrationRunner(AwsProperties.Tables tables) {
        return new DynamoMigrationRunner(client(),
                new MigrationChangelogReader(new DefaultResourceLoader(), MigrationChangelogReader.DEFAULT_CHANGELOG),
                TableDefinitionParser.forTables(tables), tables.schemaMigrations(), Clock.systemUTC());
    }

    /**
     * Creates every table of {@code tables} by running the real migrations.
     *
     * @param tables the physical table names
     * @return the ids of the applied migrations
     */
    public static List<String> migrate(AwsProperties.Tables tables) {
        return migrationRunner(tables).migrate().block(Duration.ofMinutes(2));
    }
}
