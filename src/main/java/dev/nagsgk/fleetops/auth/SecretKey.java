package dev.nagsgk.fleetops.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A shared secret checked in constant time. The value comes from a supplier
 * (Secrets Manager in Lambda, a constant in tests) and is fetched on first use, not at
 * class init, so it is never captured in a SnapStart snapshot. It is cached for
 * {@code ttlMillis} so a rotated secret takes effect without a redeploy.
 *
 * Fail closed: if the secret cannot be loaded, every request is rejected.
 */
public final class SecretKey {
    private final Supplier<String> source;
    private final LongSupplier clock;
    private final long ttlMillis;
    private volatile String cached;
    private volatile long loadedAt;

    public SecretKey(Supplier<String> source, LongSupplier clock, long ttlMillis) {
        this.source = source;
        this.clock = clock;
        this.ttlMillis = ttlMillis;
    }

    public static SecretKey fixed(String value) {
        return new SecretKey(() -> value, System::currentTimeMillis, Long.MAX_VALUE);
    }

    String current() {
        long now = clock.getAsLong();
        if (cached == null || now - loadedAt > ttlMillis) {
            try {
                String v = source.get();
                if (v != null && !v.isBlank()) {
                    cached = v;
                    loadedAt = now;
                }
            } catch (RuntimeException e) {
                // keep the last good value if we have one; otherwise stay closed
            }
        }
        return cached;
    }

    public boolean matches(String presented) {
        String expected = current();
        if (expected == null || presented == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
