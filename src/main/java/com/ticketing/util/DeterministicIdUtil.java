package com.ticketing.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;

public final class DeterministicIdUtil {
    public static final UUID NAMESPACE = UUID.fromString("8f6b2c1e-4d3a-4b7e-9c5f-2a1d0e9b8c7f");

    private static final int VERSION_5 = 0x50;
    private static final int VARIANT_RFC_9562 = 0x80;

    private DeterministicIdUtil() {
    }

    public static UUID nameBasedUuid(String name) {
        return nameBasedUuid(NAMESPACE, name);
    }

    public static String nameBasedId(String name) {
        return nameBasedUuid(name).toString();
    }

    static UUID nameBasedUuid(UUID namespace, String name) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        MessageDigest sha1 = HashUtil.newDigest("SHA-1");
        sha1.update(ByteBuffer.allocate(16)
                .putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits())
                .array());
        sha1.update(name.getBytes(StandardCharsets.UTF_8));
        byte[] hash = sha1.digest();
        hash[6] = (byte) ((hash[6] & 0x0f) | VERSION_5);
        hash[8] = (byte) ((hash[8] & 0x3f) | VARIANT_RFC_9562);
        ByteBuffer bits = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(bits.getLong(), bits.getLong());
    }
}
