package com.ticketing.config.migration;

import com.ticketing.util.HashUtil;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RequiredArgsConstructor
public class MigrationChangelogReader {
    public static final String DEFAULT_CHANGELOG = "classpath:db/changelog/db.changelog-master.yaml";

    private final ResourceLoader resourceLoader;
    private final String changelogLocation;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public List<Migration> read() {
        Object root = loadYaml(changelogLocation);
        if (!(root instanceof Map<?, ?> rootMap) || !(rootMap.get("databaseChangeLog") instanceof List<?> entries)) {
            throw invalid("the changelog must contain a 'databaseChangeLog' list");
        }
        List<Migration> migrations = new ArrayList<>();
        String previousId = null;
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> entryMap) || !(entryMap.get("migration") instanceof Map<?, ?> migration)) {
                throw invalid("every changelog entry must be a 'migration' with an id and a file");
            }
            String id = requiredText(migration, "id");
            String file = requiredText(migration, "file");
            if (previousId != null && id.compareTo(previousId) <= 0) {
                throw invalid("migration ids must be unique and in ascending order, found " + id + " after "
                        + previousId);
            }
            migrations.add(readMigration(id, file));
            previousId = id;
        }
        return List.copyOf(migrations);
    }

    private Migration readMigration(String id, String file) {
        String content = readText(ResourceLoader.CLASSPATH_URL_PREFIX + file).replace("\r\n", "\n");
        JsonNode definition;
        try {
            definition = jsonMapper.readTree(content);
        } catch (RuntimeException malformedJson) {
            throw new IllegalStateException("Invalid JSON in migration " + id + " (" + file + ")", malformedJson);
        }
        return new Migration(id, file, HashUtil.sha256Hex(content), definition);
    }

    private Object loadYaml(String location) {
        try (InputStream input = open(location)) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + location, e);
        }
    }

    private String readText(String location) {
        try (InputStream input = open(location)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + location, e);
        }
    }

    private InputStream open(String location) throws IOException {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Migration resource not found: " + location);
        }
        return resource.getInputStream();
    }

    private static String requiredText(Map<?, ?> migration, String attribute) {
        if (!(migration.get(attribute) instanceof String value) || value.isBlank()) {
            throw invalid("migration '" + attribute + "' must be a non-blank string");
        }
        return value;
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("Invalid DynamoDB migration changelog: " + detail);
    }

    public record Migration(String id, String file, String checksum, JsonNode definition) {
    }
}
