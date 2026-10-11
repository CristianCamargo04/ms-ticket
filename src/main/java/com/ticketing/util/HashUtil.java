package com.ticketing.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class HashUtil {
    private static final String SHA_256 = "SHA-256";
    private static final HexFormat HEX = HexFormat.of();

    private HashUtil() {
    }

    public static String sha256Hex(String input) {
        return sha256Hex(Objects.requireNonNull(input, "input").getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] input) {
        return HEX.formatHex(newDigest(SHA_256).digest(Objects.requireNonNull(input, "input")));
    }

    static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Message digest not available: " + algorithm, e);
        }
    }
}
