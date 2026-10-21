package dev.nagsgk.fleetops.auth;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.util.function.Supplier;

/** Reads a secret string from AWS Secrets Manager by ARN. */
public final class Secrets {
    private Secrets() {}

    public static Supplier<String> fromSecretsManager(SecretsManagerClient sm, String arn) {
        return () -> sm.getSecretValue(r -> r.secretId(arn)).secretString();
    }
}
