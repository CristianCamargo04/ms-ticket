package com.ticketing.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Hashing helpers (request fingerprints, migration checksums, idempotent client request tokens).
 */
public final class HashUtil {

    private static final String SHA_256 = "SHA-256";
    private static final HexFormat HEX = HexFormat.of();

    private HashUtil() {
    }

    /**
     * SHA-256 of the UTF-8 bytes of a string.
     *
     * @param input the text to hash
     * @return 64 lowercase hexadecimal characters
     */
    public static String sha256Hex(String input) {
        return sha256Hex(Objects.requireNonNull(input, "input").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-256 of raw bytes.
     *
     * @param input the bytes to hash
     * @return 64 lowercase hexadecimal characters
     */
    public static String sha256Hex(byte[] input) {
        return HEX.formatHex(newDigest(SHA_256).digest(Objects.requireNonNull(input, "input")));
    }

    /**
     * Creates a message digest; every JVM must provide SHA-1 and SHA-256, so a failure is an environment error.
     *
     * @param algorithm the digest algorithm
     * @return a new digest instance (not thread-safe, never shared)
     * @throws IllegalStateException when the algorithm is not available
     */
    static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Message digest not available: " + algorithm, e);
        }
    }
}
