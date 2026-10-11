package com.ticketing.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeterministicIdUtilTest {

    private static final UUID NAMESPACE_DNS = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            // RFC 9562, appendix A.4 (UUIDv5 example)
            "www.example.com, 2ed6657d-e927-568b-95e1-2665a8aea6a2",
            // Python documentation: uuid.uuid5(uuid.NAMESPACE_DNS, 'python.org')
            "python.org,      886313e1-3b8a-5372-9b90-0c9aee199e5d"
    })
    void shouldMatchKnownTestVectors(String name, String expected) {
        assertThat(DeterministicIdUtil.nameBasedUuid(NAMESPACE_DNS, name)).isEqualTo(UUID.fromString(expected));
    }

    @Test
    void shouldProduceVersion5RfcVariantUuids() {
        UUID uuid = DeterministicIdUtil.nameBasedUuid("customer-1:key-12345678");

        assertThat(uuid.version()).isEqualTo(5);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void shouldBeDeterministicWithinTheTicketingNamespace() {
        UUID first = DeterministicIdUtil.nameBasedUuid("customer-1:key-12345678");

        assertThat(DeterministicIdUtil.nameBasedUuid("customer-1:key-12345678")).isEqualTo(first);
        assertThat(DeterministicIdUtil.nameBasedUuid(DeterministicIdUtil.NAMESPACE, "customer-1:key-12345678"))
                .isEqualTo(first);
        assertThat(DeterministicIdUtil.nameBasedUuid("customer-2:key-12345678")).isNotEqualTo(first);
        assertThat(DeterministicIdUtil.nameBasedUuid(NAMESPACE_DNS, "customer-1:key-12345678")).isNotEqualTo(first);
    }

    @Test
    void shouldExposeCanonicalStringForm() {
        String id = DeterministicIdUtil.nameBasedId("customer-1:key-12345678");

        assertThat(id).hasSize(36).isEqualTo(DeterministicIdUtil.nameBasedUuid("customer-1:key-12345678").toString());
    }

    @Test
    void shouldEncodeNamesAsUtf8() {
        assertThat(DeterministicIdUtil.nameBasedUuid("cliente-ñandú:clé"))
                .isEqualTo(DeterministicIdUtil.nameBasedUuid("cliente-ñandú:clé"))
                .isNotEqualTo(DeterministicIdUtil.nameBasedUuid("cliente-nandu:cle"));
    }

    @Test
    void shouldRejectNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> DeterministicIdUtil.nameBasedUuid(null));
        assertThatNullPointerException().isThrownBy(() -> DeterministicIdUtil.nameBasedUuid(null, "name"));
    }
}
