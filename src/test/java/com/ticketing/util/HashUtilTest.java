package com.ticketing.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HashUtilTest {
    @ParameterizedTest(name = "\"{0}\"")
    @CsvSource(value = {
            "'',  e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "abc, ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }, emptyValue = "")
    void shouldMatchKnownSha256Vectors(String input, String expected) {
        assertThat(HashUtil.sha256Hex(input)).isEqualTo(expected);
        assertThat(HashUtil.sha256Hex(input.getBytes(StandardCharsets.UTF_8))).isEqualTo(expected);
    }

    @Test
    void shouldProduceLowercaseHexOf64Characters() {
        assertThat(HashUtil.sha256Hex("place-order|e-1|2")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void shouldRejectNullInput() {
        assertThatNullPointerException().isThrownBy(() -> HashUtil.sha256Hex((String) null));
        assertThatNullPointerException().isThrownBy(() -> HashUtil.sha256Hex((byte[]) null));
    }

    @Test
    void shouldCreateDigests() {
        assertThat(HashUtil.newDigest("SHA-1").getAlgorithm()).isEqualTo("SHA-1");
    }

    @Test
    void shouldReportUnavailableAlgorithmAsIllegalState() {
        assertThatIllegalStateException()
                .isThrownBy(() -> HashUtil.newDigest("NOT-A-DIGEST"))
                .withMessage("Message digest not available: NOT-A-DIGEST")
                .withCauseInstanceOf(NoSuchAlgorithmException.class);
    }
}
