package org.twostack.libspiffy4j.model;

public record ArcServiceConfig(
        String baseUrl,
        String apiKey,
        String defaultCallbackUrl
) {

    public static ArcServiceConfig taalTestnet(String apiKey) {
        return new ArcServiceConfig("https://arc-test.taal.com", apiKey, null);
    }

    public static ArcServiceConfig taalMainnet() {
        return new ArcServiceConfig("https://arc.taal.com", null, null);
    }

    public static ArcServiceConfig custom(String baseUrl, String apiKey, String callbackUrl) {
        return new ArcServiceConfig(baseUrl, apiKey, callbackUrl);
    }
}
