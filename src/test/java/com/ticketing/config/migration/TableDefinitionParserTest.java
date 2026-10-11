package com.ticketing.config.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.config.migration.MigrationChangelogReader.Migration;
import com.ticketing.config.migration.TableDefinitionParser.TableDefinition;
import com.ticketing.config.properties.AwsProperties;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TableDefinitionParserTest {

    private static final AwsProperties.Tables TABLES = new AwsProperties.Tables("p-events", "p-tickets", "p-orders",
            "p-transitions", "p-locks", "p-migrations");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TableDefinitionParser parser = TableDefinitionParser.forTables(TABLES);

    @Test
    void shouldParseEveryRealMigration() {
        Map<String, TableDefinition> definitions = new MigrationChangelogReader(new DefaultResourceLoader(),
                MigrationChangelogReader.DEFAULT_CHANGELOG).read().stream()
                .collect(Collectors.toMap(Migration::id, migration -> parser.parse(migration.definition())));

        assertThat(definitions.values()).extracting(definition -> definition.createTable().tableName())
                .containsExactlyInAnyOrder("p-events", "p-tickets", "p-orders", "p-transitions", "p-locks");
        CreateTableRequest tickets = definitions.get("20261010100100_TCK-002").createTable();
        assertThat(tickets).isEqualTo(CreateTableRequest.builder()
                .tableName("p-tickets")
                .attributeDefinitions(attribute("eventId"), attribute("ticketId"), attribute("statusKey"))
                .keySchema(key("eventId", KeyType.HASH), key("ticketId", KeyType.RANGE))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("eventId-statusKey-index")
                        .keySchema(key("eventId", KeyType.HASH), key("statusKey", KeyType.RANGE))
                        .projection(Projection.builder().projectionType(ProjectionType.INCLUDE)
                                .nonKeyAttributes("status", "version", "updatedAt").build())
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        assertThat(definitions.get("20261010100000_TCK-001").createTable().globalSecondaryIndexes().getFirst()
                .projection().projectionType()).isEqualTo(ProjectionType.ALL);
        assertThat(definitions.get("20261010100300_TCK-004").createTable().hasGlobalSecondaryIndexes()).isFalse();
        assertThat(definitions.get("20261010100300_TCK-004").timeToLive()).isNull();
        assertThat(definitions.get("20261010100400_TCK-005").timeToLive()).isEqualTo(
                TimeToLiveSpecification.builder().attributeName("expiresAt").enabled(true).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{\"TableName\": \"events\", \"Extra\": 1}",
            "{\"TableName\": \"unknown\"}",
            "{\"BillingMode\": \"PAY_PER_REQUEST\"}",
            "{\"TableName\": 42}",
            "{\"TableName\": \" \"}",
            "{\"TableName\": \"events\", \"BillingMode\": \"PROVISIONED\"}",
            "{\"TableName\": \"events\", \"BillingMode\": \"FREE\"}",
            "{\"TableName\": \"events\", \"BillingMode\": \"PAY_PER_REQUEST\"}",
            "{\"TableName\": \"events\", \"BillingMode\": \"PAY_PER_REQUEST\", \"AttributeDefinitions\": []}",
            "{\"TableName\": \"events\", \"BillingMode\": \"PAY_PER_REQUEST\", \"AttributeDefinitions\": {}}",
            "{\"TableName\": \"events\", \"BillingMode\": \"PAY_PER_REQUEST\","
                    + " \"AttributeDefinitions\": [{\"AttributeName\": \"id\", \"AttributeType\": \"X\"}]}",
            "{\"TableName\": \"events\", \"BillingMode\": \"PAY_PER_REQUEST\","
                    + " \"AttributeDefinitions\": [{\"AttributeName\": \"id\", \"AttributeType\": \"S\"}],"
                    + " \"KeySchema\": [{\"AttributeName\": \"id\", \"KeyType\": \"PRIMARY\"}]}",
            "{\"TableName\": \"events\", \"BillingMode\": \"PAY_PER_REQUEST\","
                    + " \"AttributeDefinitions\": [{\"AttributeName\": \"id\", \"AttributeType\": \"S\"}],"
                    + " \"KeySchema\": [{\"AttributeName\": \"id\", \"KeyType\": \"HASH\"}],"
                    + " \"GlobalSecondaryIndexes\": [{\"IndexName\": \"i\","
                    + " \"KeySchema\": [{\"AttributeName\": \"id\", \"KeyType\": \"HASH\"}]}]}",
            "{\"TableName\": \"events\", \"BillingMode\": \"PAY_PER_REQUEST\","
                    + " \"AttributeDefinitions\": [{\"AttributeName\": \"id\", \"AttributeType\": \"S\"}],"
                    + " \"KeySchema\": [{\"AttributeName\": \"id\", \"KeyType\": \"HASH\"}],"
                    + " \"GlobalSecondaryIndexes\": [{\"IndexName\": \"i\","
                    + " \"KeySchema\": [{\"AttributeName\": \"id\", \"KeyType\": \"HASH\"}],"
                    + " \"Projection\": {\"ProjectionType\": \"INCLUDE\", \"NonKeyAttributes\": [1]}}]}",
            "{\"TableName\": \"events\", \"BillingMode\": \"PAY_PER_REQUEST\","
                    + " \"AttributeDefinitions\": [{\"AttributeName\": \"id\", \"AttributeType\": \"S\"}],"
                    + " \"KeySchema\": [{\"AttributeName\": \"id\", \"KeyType\": \"HASH\"}],"
                    + " \"TimeToLiveSpecification\": {\"AttributeName\": \"ttl\", \"Enabled\": \"yes\"}}",
            "{\"TableName\": \"events\", \"BillingMode\": \"PAY_PER_REQUEST\","
                    + " \"AttributeDefinitions\": [{\"AttributeName\": \"id\", \"AttributeType\": \"S\"}],"
                    + " \"KeySchema\": [{\"AttributeName\": \"id\", \"KeyType\": \"HASH\"}],"
                    + " \"TimeToLiveSpecification\": {\"AttributeName\": \"ttl\"}}",
            "{\"TableName\": \"events\", \"BillingMode\": \"PAY_PER_REQUEST\","
                    + " \"AttributeDefinitions\": [{\"AttributeName\": \"id\", \"AttributeType\": \"S\"}],"
                    + " \"KeySchema\": [{\"AttributeName\": \"id\", \"KeyType\": \"HASH\"}],"
                    + " \"TimeToLiveSpecification\": \"expiresAt\"}"
    })
    void shouldRejectInvalidDefinitions(String json) {
        JsonNode definition = JSON.readTree(json);

        assertThatThrownBy(() -> parser.parse(definition))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Invalid DynamoDB migration definition");
    }

    @Test
    void shouldRejectAMissingDefinition() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldParseAProjectionWithoutNonKeyAttributes() {
        JsonNode definition = JSON.readTree("{\"TableName\": \"orders\", \"BillingMode\": \"PAY_PER_REQUEST\","
                + " \"AttributeDefinitions\": [{\"AttributeName\": \"id\", \"AttributeType\": \"S\"}],"
                + " \"KeySchema\": [{\"AttributeName\": \"id\", \"KeyType\": \"HASH\"}],"
                + " \"GlobalSecondaryIndexes\": [{\"IndexName\": \"i\","
                + " \"KeySchema\": [{\"AttributeName\": \"id\", \"KeyType\": \"HASH\"}],"
                + " \"Projection\": {\"ProjectionType\": \"KEYS_ONLY\"}}]}");

        GlobalSecondaryIndex index = parser.parse(definition).createTable().globalSecondaryIndexes().getFirst();

        assertThat(index.projection().projectionType()).isEqualTo(ProjectionType.KEYS_ONLY);
        assertThat(index.projection().hasNonKeyAttributes()).isFalse();
    }

    @Test
    void shouldResolveEveryLogicalTableName() {
        List<String> logicalNames = List.of("events", "tickets", "orders", "ticket-transitions", "distributed-locks");

        assertThat(logicalNames).extracting(name -> parser.parse(JSON.readTree("{\"TableName\": \"" + name
                        + "\", \"BillingMode\": \"PAY_PER_REQUEST\","
                        + " \"AttributeDefinitions\": [{\"AttributeName\": \"id\", \"AttributeType\": \"S\"}],"
                        + " \"KeySchema\": [{\"AttributeName\": \"id\", \"KeyType\": \"HASH\"}]}"))
                        .createTable().tableName())
                .containsExactly("p-events", "p-tickets", "p-orders", "p-transitions", "p-locks");
    }

    @Test
    void shouldRequireTheCreateTableRequest() {
        assertThatThrownBy(() -> new TableDefinition(null, null)).isInstanceOf(NullPointerException.class);
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
