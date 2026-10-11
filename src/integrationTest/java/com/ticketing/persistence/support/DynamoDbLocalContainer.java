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

public final class DynamoDbLocalContainer {
    private static final int PORT = 8000;

    private static GenericContainer<?> container;
    private static DynamoDbAsyncClient client;

    private DynamoDbLocalContainer() {
    }

    public static synchronized DynamoDbAsyncClient client() {
        if (client == null) {
            container = new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
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

    public static synchronized URI endpoint() {
        client();
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(PORT));
    }

    public static AwsProperties.Tables uniqueTables() {
        String prefix = "it-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        return new AwsProperties.Tables(prefix + "events", prefix + "tickets", prefix + "orders",
                prefix + "ticket-transitions", prefix + "distributed-locks", prefix + "schema-migrations");
    }

    public static DynamoMigrationRunner migrationRunner(AwsProperties.Tables tables) {
        return new DynamoMigrationRunner(client(),
                new MigrationChangelogReader(new DefaultResourceLoader(), MigrationChangelogReader.DEFAULT_CHANGELOG),
                TableDefinitionParser.forTables(tables), tables.schemaMigrations(), Clock.systemUTC());
    }

    public static List<String> migrate(AwsProperties.Tables tables) {
        return migrationRunner(tables).migrate().block(Duration.ofMinutes(2));
    }
}
