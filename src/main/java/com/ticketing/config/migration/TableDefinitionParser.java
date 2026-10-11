package com.ticketing.config.migration;

import com.ticketing.config.properties.AwsProperties;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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

public class TableDefinitionParser {
    private static final Set<String> SUPPORTED_FIELDS = Set.of("TableName", "AttributeDefinitions", "KeySchema",
            "GlobalSecondaryIndexes", "BillingMode", "TimeToLiveSpecification");

    private final Map<String, String> physicalTableNames;

    public TableDefinitionParser(Map<String, String> physicalTableNames) {
        this.physicalTableNames = Map.copyOf(physicalTableNames);
    }

    public static TableDefinitionParser forTables(AwsProperties.Tables tables) {
        return new TableDefinitionParser(Map.of(
                "events", tables.events(),
                "tickets", tables.tickets(),
                "orders", tables.orders(),
                "ticket-transitions", tables.ticketTransitions(),
                "distributed-locks", tables.distributedLocks()));
    }

    public TableDefinition parse(JsonNode definition) {
        if (definition == null || !definition.isObject()) {
            throw invalid("the definition must be a JSON object");
        }
        for (String field : definition.propertyNames()) {
            if (!SUPPORTED_FIELDS.contains(field)) {
                throw invalid("unsupported field " + field);
            }
        }
        String logicalName = text(definition, "TableName");
        String tableName = physicalTableNames.get(logicalName);
        if (tableName == null) {
            throw invalid("unknown logical table name " + logicalName);
        }
        BillingMode billingMode = enumValue(text(definition, "BillingMode"), BillingMode::fromValue, "BillingMode");
        if (billingMode != BillingMode.PAY_PER_REQUEST) {
            throw invalid("only PAY_PER_REQUEST billing is supported, found " + billingMode);
        }
        CreateTableRequest.Builder createTable = CreateTableRequest.builder()
                .tableName(tableName)
                .attributeDefinitions(elements(definition, "AttributeDefinitions").stream()
                        .map(attribute -> AttributeDefinition.builder()
                                .attributeName(text(attribute, "AttributeName"))
                                .attributeType(enumValue(text(attribute, "AttributeType"),
                                        ScalarAttributeType::fromValue, "AttributeType"))
                                .build())
                        .toList())
                .keySchema(keySchema(definition))
                .billingMode(billingMode);
        if (definition.has("GlobalSecondaryIndexes")) {
            createTable.globalSecondaryIndexes(elements(definition, "GlobalSecondaryIndexes").stream()
                    .map(TableDefinitionParser::globalSecondaryIndex)
                    .toList());
        }
        return new TableDefinition(createTable.build(), timeToLive(definition));
    }

    private static GlobalSecondaryIndex globalSecondaryIndex(JsonNode index) {
        JsonNode projection = object(index, "Projection");
        Projection.Builder projectionBuilder = Projection.builder()
                .projectionType(enumValue(text(projection, "ProjectionType"), ProjectionType::fromValue,
                        "ProjectionType"));
        if (projection.has("NonKeyAttributes")) {
            projectionBuilder.nonKeyAttributes(elements(projection, "NonKeyAttributes").stream()
                    .map(TableDefinitionParser::textValue)
                    .toList());
        }
        return GlobalSecondaryIndex.builder()
                .indexName(text(index, "IndexName"))
                .keySchema(keySchema(index))
                .projection(projectionBuilder.build())
                .build();
    }

    private static List<KeySchemaElement> keySchema(JsonNode node) {
        return elements(node, "KeySchema").stream()
                .map(element -> KeySchemaElement.builder()
                        .attributeName(text(element, "AttributeName"))
                        .keyType(enumValue(text(element, "KeyType"), KeyType::fromValue, "KeyType"))
                        .build())
                .toList();
    }

    private static TimeToLiveSpecification timeToLive(JsonNode definition) {
        if (!definition.has("TimeToLiveSpecification")) {
            return null;
        }
        JsonNode timeToLive = object(definition, "TimeToLiveSpecification");
        JsonNode enabled = timeToLive.get("Enabled");
        if (enabled == null || !enabled.isBoolean()) {
            throw invalid("TimeToLiveSpecification.Enabled must be a boolean");
        }
        return TimeToLiveSpecification.builder()
                .attributeName(text(timeToLive, "AttributeName"))
                .enabled(enabled.booleanValue())
                .build();
    }

    private static List<JsonNode> elements(JsonNode node, String field) {
        JsonNode array = node.get(field);
        if (array == null || !array.isArray() || array.isEmpty()) {
            throw invalid(field + " must be a non-empty array");
        }
        return List.copyOf(array.values());
    }

    private static JsonNode object(JsonNode node, String field) {
        JsonNode object = node.get(field);
        if (object == null || !object.isObject()) {
            throw invalid(field + " must be an object");
        }
        return object;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw invalid(field + " is required");
        }
        return textValue(value);
    }

    private static String textValue(JsonNode value) {
        if (!value.isString() || value.stringValue().isBlank()) {
            throw invalid("expected a non-blank string but found " + value);
        }
        return value.stringValue();
    }

    private static <E extends Enum<E>> E enumValue(String value, Function<String, E> parser, String field) {
        E parsed = parser.apply(value);
        if ("UNKNOWN_TO_SDK_VERSION".equals(parsed.name())) {
            throw invalid("unsupported " + field + " " + value);
        }
        return parsed;
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("Invalid DynamoDB migration definition: " + detail);
    }

    public record TableDefinition(CreateTableRequest createTable, TimeToLiveSpecification timeToLive) {
        public TableDefinition {
            Objects.requireNonNull(createTable, "createTable");
        }
    }
}
