package dev.nagsgk.fleetops.api;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import dev.nagsgk.fleetops.util.Json;

import java.util.Map;

final class Http {
    private Http() {}

    static APIGatewayV2HTTPResponse json(int status, Object body) {
        return APIGatewayV2HTTPResponse.builder().withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json", "Cache-Control", "no-store"))
                .withBody(Json.write(body)).build();
    }

    static APIGatewayV2HTTPResponse error(int status, String message) {
        return json(status, Map.of("error", message));
    }
}
