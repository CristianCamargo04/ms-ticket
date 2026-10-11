package com.ticketing.config.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards the DynamoDB migration files and the SQS message schemas (resources shared by the migration runner,
 * Terraform and the messaging layer).
 */
class MigrationResourcesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    private static JsonNode readJson(String classpathResource) throws IOException {
        try (InputStream input = new ClassPathResource(classpathResource).getInputStream()) {
            return JSON.readTree(input);
        }
    }

    private static List<Map.Entry<String, String>> changelogEntries() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(CHANGELOG, new ClassPathResource(CHANGELOG));
        PropertySource<?> changelog = sources.getFirst();
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        for (int index = 0; changelog.containsProperty(key(index, "id")); index++) {
            entries.add(Map.entry(String.valueOf(changelog.getProperty(key(index, "id"))),
                    String.valueOf(changelog.getProperty(key(index, "file")))));
        }
        return entries;
    }

    private static String key(int index, String attribute) {
        return "databaseChangeLog[" + index + "].migration." + attribute;
    }

    private static List<String> attributeNames(JsonNode array) {
        return array.values().stream().map(node -> node.get("AttributeName").asString()).toList();
    }

    @Test
    void shouldListTheFiveTableMigrationsInOrder() throws IOException {
        List<Map.Entry<String, String>> entries = changelogEntries();

        assertThat(entries).extracting(Map.Entry::getKey).containsExactly(
                "20261010100000_TCK-001", "20261010100100_TCK-002", "20261010100200_TCK-003",
                "20261010100300_TCK-004", "20261010100400_TCK-005");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.getValue()).startsWith("db/migrations/" + entry.getKey() + "/");
            assertThat(new ClassPathResource(entry.getValue()).exists()).as(entry.getValue()).isTrue();
        });
    }

    static Stream<Arguments> tableDefinitions() {
        return Stream.of(
                Arguments.of("db/migrations/20261010100000_TCK-001/create-events-table.json", "events",
                        List.of("eventId"), "status-eventDate-index", "ALL"),
                Arguments.of("db/migrations/20261010100100_TCK-002/create-tickets-table.json", "tickets",
                        List.of("eventId", "ticketId"), "eventId-statusKey-index", "INCLUDE"),
                Arguments.of("db/migrations/20261010100200_TCK-003/create-orders-table.json", "orders",
                        List.of("orderId"), "deadline-index", "ALL"),
                Arguments.of("db/migrations/20261010100300_TCK-004/create-ticket-transitions-table.json",
                        "ticket-transitions", List.of("ticketKey", "transitionKey"), null, null),
                Arguments.of("db/migrations/20261010100400_TCK-005/create-distributed-locks-table.json",
                        "distributed-locks", List.of("lockName"), null, null));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("tableDefinitions")
    void shouldMirrorTheCreateTableApi(String file, String logicalName, List<String> keys, String indexName,
                                       String projection) throws IOException {
        JsonNode table = readJson(file);

        assertThat(table.get("TableName").asString()).isEqualTo(logicalName);
        assertThat(table.get("BillingMode").asString()).isEqualTo("PAY_PER_REQUEST");
        assertThat(attributeNames(table.get("KeySchema"))).containsExactlyElementsOf(keys);
        List<String> defined = attributeNames(table.get("AttributeDefinitions"));
        assertThat(defined).containsAll(keys);
        JsonNode indexes = table.get("GlobalSecondaryIndexes");
        if (indexName == null) {
            assertThat(indexes).isNull();
            assertThat(defined).containsExactlyInAnyOrderElementsOf(keys);
        } else {
            assertThat(indexes.size()).isEqualTo(1);
            JsonNode index = indexes.get(0);
            assertThat(index.get("IndexName").asString()).isEqualTo(indexName);
            assertThat(index.get("Projection").get("ProjectionType").asString()).isEqualTo(projection);
            assertThat(defined).containsAll(attributeNames(index.get("KeySchema")));
        }
    }

    @Test
    void shouldExpireDistributedLocksWithTtl() throws IOException {
        JsonNode ttl = readJson("db/migrations/20261010100400_TCK-005/create-distributed-locks-table.json")
                .get("TimeToLiveSpecification");

        assertThat(ttl.get("AttributeName").asString()).isEqualTo("expiresAt");
        assertThat(ttl.get("Enabled").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "templates/messages/order-reservation-message.schema.json",
            "templates/messages/payment-requested-message.schema.json"
    })
    void shouldDescribeMessagesWithJsonSchema202012(String file) throws IOException {
        JsonNode schema = readJson(file);

        assertThat(schema.get("$schema").asString()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.get("type").asString()).isEqualTo("object");
        List<String> required = schema.get("required").values().stream().map(JsonNode::asString).toList();
        assertThat(required).contains("orderId", "correlationId", "occurredAt");
        assertThat(schema.get("properties").propertyNames()).containsAll(required);
    }
}
