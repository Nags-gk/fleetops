package dev.nagsgk.fleetops.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class Json {
    public static final ObjectMapper M = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .findAndAddModules()
            .build();

    private Json() {}

    public static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static <T> T read(String s, Class<T> t) {
        try {
            return M.readValue(s, t);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getMessage(), e);
        }
    }
}
