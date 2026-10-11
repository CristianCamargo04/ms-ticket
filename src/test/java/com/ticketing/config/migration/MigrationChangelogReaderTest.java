package com.ticketing.config.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.config.migration.MigrationChangelogReader.Migration;
import com.ticketing.util.HashUtil;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

class MigrationChangelogReaderTest {

    private static final String CHANGELOG = "classpath:changelog.yaml";
    private static final String TABLE_JSON = "{\n  \"TableName\": \"events\"\n}\n";

    @Test
    void shouldReadTheRealChangelogInOrder() throws IOException {
        List<Migration> migrations = new MigrationChangelogReader(new DefaultResourceLoader(),
                MigrationChangelogReader.DEFAULT_CHANGELOG).read();

        assertThat(migrations).extracting(Migration::id).containsExactly("20261010100000_TCK-001",
                "20261010100100_TCK-002", "20261010100200_TCK-003", "20261010100300_TCK-004",
                "20261010100400_TCK-005");
        Migration events = migrations.getFirst();
        assertThat(events.file()).isEqualTo("db/migrations/20261010100000_TCK-001/create-events-table.json");
        assertThat(events.definition().get("TableName").asString()).isEqualTo("events");
        try (InputStream file = new ClassPathResource(events.file()).getInputStream()) {
            assertThat(events.checksum()).isEqualTo(HashUtil.sha256Hex(file.readAllBytes()));
        }
    }

    @Test
    void shouldIgnoreLineEndingsInTheChecksum() {
        Migration lf = reader(Map.of(CHANGELOG, changelog("1_A", "a.json"), "classpath:a.json", TABLE_JSON))
                .read().getFirst();
        Migration crlf = reader(Map.of(CHANGELOG, changelog("1_A", "a.json"),
                "classpath:a.json", TABLE_JSON.replace("\n", "\r\n"))).read().getFirst();

        assertThat(crlf.checksum()).isEqualTo(lf.checksum()).isEqualTo(HashUtil.sha256Hex(TABLE_JSON));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "just a string",
            "databaseChangeLog: not-a-list",
            "databaseChangeLog:\n  - not-a-map",
            "databaseChangeLog:\n  - other: { id: 1_A, file: a.json }",
            "databaseChangeLog:\n  - migration: { file: a.json }",
            "databaseChangeLog:\n  - migration: { id: ' ', file: a.json }",
            "databaseChangeLog:\n  - migration: { id: 1_A, file: 42 }",
            "databaseChangeLog:\n  - migration: { id: 2_B, file: a.json }\n  - migration: { id: 1_A, file: a.json }",
            "databaseChangeLog:\n  - migration: { id: 1_A, file: a.json }\n  - migration: { id: 1_A, file: a.json }"
    })
    void shouldRejectAnInvalidChangelog(String yaml) {
        MigrationChangelogReader reader = reader(Map.of(CHANGELOG, yaml, "classpath:a.json", TABLE_JSON));

        assertThatThrownBy(reader::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Invalid DynamoDB migration changelog");
    }

    @Test
    void shouldRejectAMissingMigrationFile() {
        MigrationChangelogReader reader = reader(Map.of(CHANGELOG, changelog("1_A", "missing.json")));

        assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing.json");
    }

    @Test
    void shouldRejectAMissingChangelog() {
        assertThatThrownBy(() -> reader(Map.of()).read()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CHANGELOG);
    }

    @Test
    void shouldRejectInvalidJson() {
        MigrationChangelogReader reader = reader(Map.of(CHANGELOG, changelog("1_A", "a.json"),
                "classpath:a.json", "{ not json"));

        assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid JSON in migration 1_A");
    }

    @Test
    void shouldReportUnreadableResources() {
        Map<String, Resource> resources = new HashMap<>();
        resources.put(CHANGELOG, new ByteArrayResource(changelog("1_A", "a.json").getBytes(StandardCharsets.UTF_8)));
        resources.put("classpath:a.json", unreadable());
        MigrationChangelogReader reader = new MigrationChangelogReader(loader(resources), CHANGELOG);

        assertThatThrownBy(reader::read).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> new MigrationChangelogReader(loader(Map.of(CHANGELOG, unreadable())), CHANGELOG)
                .read()).isInstanceOf(UncheckedIOException.class);
    }

    private static String changelog(String id, String file) {
        return "databaseChangeLog:\n  - migration: { id: " + id + ", file: " + file + " }\n";
    }

    private static MigrationChangelogReader reader(Map<String, String> contents) {
        Map<String, Resource> resources = new HashMap<>();
        contents.forEach((location, content) ->
                resources.put(location, new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8))));
        return new MigrationChangelogReader(loader(resources), CHANGELOG);
    }

    private static ResourceLoader loader(Map<String, Resource> resources) {
        return new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return resources.getOrDefault(location, new ClassPathResource("does/not/exist-" + location.length()));
            }

            @Override
            public ClassLoader getClassLoader() {
                return MigrationChangelogReaderTest.class.getClassLoader();
            }
        };
    }

    private static Resource unreadable() {
        return new AbstractResource() {
            @Override
            public boolean exists() {
                return true;
            }

            @Override
            public String getDescription() {
                return "unreadable resource";
            }

            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("disk error");
            }
        };
    }
}
