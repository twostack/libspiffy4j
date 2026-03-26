package org.twostack.libspiffy4j.service;

import org.twostack.libspiffy4j.model.ArcServiceConfig;
import org.twostack.libspiffy4j.model.ArcSubmitResponse;
import org.twostack.libspiffy4j.model.ArcTransactionResponse;
import org.twostack.libspiffy4j.model.ArcTransactionStatus;
import org.twostack.libspiffy4j.model.MerkleProofData;
import org.twostack.libspiffy4j.spv.Bump;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HexFormat;
import java.util.concurrent.Executors;

/**
 * HTTP client for the ARC transaction broadcast and query API.
 */
public class ArcService {

    private final ArcServiceConfig config;
    private final HttpClient httpClient;

    public ArcService(ArcServiceConfig config) {
        this(config, HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build());
    }

    public ArcService(ArcServiceConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    /**
     * Submits a raw transaction hex to ARC.
     */
    public ArcSubmitResponse submitTransaction(String txHex) {
        return submitTransaction(txHex, config.defaultCallbackUrl());
    }

    /**
     * Submits a raw transaction hex with an explicit callback URL.
     */
    public ArcSubmitResponse submitTransaction(String txHex, String callbackUrl) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/v1/tx"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"rawTx\":\"" + txHex + "\"}"));

            addAuthHeader(builder);
            if (callbackUrl != null && !callbackUrl.isBlank()) {
                builder.header("X-CallbackUrl", callbackUrl);
                builder.header("X-FullStatusUpdates", "true");
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new ArcServiceException("ARC submit failed with status " + response.statusCode(),
                        response.statusCode(), response.body());
            }

            return parseSubmitResponse(response.body());
        } catch (ArcServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ArcServiceException("Failed to submit transaction", e);
        }
    }

    /**
     * Submits a BEEF-encoded transaction to ARC. ARC auto-detects the BEEF
     * format from the {@code 0100BEEF} magic bytes in the hex data.
     */
    public ArcSubmitResponse submitBeef(String beefHex) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/v1/tx"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(beefHex));

            addAuthHeader(builder);
            if (config.defaultCallbackUrl() != null && !config.defaultCallbackUrl().isBlank()) {
                builder.header("X-CallbackUrl", config.defaultCallbackUrl());
                builder.header("X-FullStatusUpdates", "true");
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new ArcServiceException("ARC BEEF submit failed with status " + response.statusCode(),
                        response.statusCode(), response.body());
            }

            return parseSubmitResponse(response.body());
        } catch (ArcServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ArcServiceException("Failed to submit BEEF transaction", e);
        }
    }

    /**
     * Queries the status of a transaction by txid.
     */
    public ArcTransactionResponse queryTransaction(String txid) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/v1/tx/" + txid))
                    .GET();

            addAuthHeader(builder);

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new ArcServiceException("ARC query failed with status " + response.statusCode(),
                        response.statusCode(), response.body());
            }

            return parseTransactionResponse(response.body());
        } catch (ArcServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ArcServiceException("Failed to query transaction", e);
        }
    }

    /**
     * Gets the merkle proof for a mined transaction.
     * ARC returns the merkle path inline in the {@code GET /v1/tx/{txid}} response —
     * there is no separate merkle proof endpoint.
     */
    public MerkleProofData getMerkleProof(String txid) {
        ArcTransactionResponse response = queryTransaction(txid);
        if (response.merklePath() == null || response.merklePath().isBlank()) {
            throw new ArcServiceException("No merkle path available for transaction: " + txid, 0, null);
        }
        byte[] merklePathBytes = HexFormat.of().parseHex(response.merklePath());
        Bump bump = Bump.parse(merklePathBytes);
        return new MerkleProofData(bump, response.blockHeight());
    }

    private void addAuthHeader(HttpRequest.Builder builder) {
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + config.apiKey());
        }
    }

    // Minimal JSON parsing without external dependency
    private ArcSubmitResponse parseSubmitResponse(String json) {
        String txid = extractJsonString(json, "txid");
        int statusCode = extractJsonInt(json, "txStatus");
        String extraInfo = extractJsonString(json, "extraInfo");
        ArcTransactionStatus status = ArcTransactionStatus.fromCode(statusCode);
        return new ArcSubmitResponse(txid, status, extraInfo, statusCode);
    }

    private ArcTransactionResponse parseTransactionResponse(String json) {
        String txid = extractJsonString(json, "txid");
        int statusCode = extractJsonInt(json, "txStatus");
        long blockHeight = extractJsonLong(json, "blockHeight");
        String blockHash = extractJsonString(json, "blockHash");
        String timestamp = extractJsonString(json, "timestamp");
        String merklePath = extractJsonString(json, "merklePath");
        ArcTransactionStatus status = ArcTransactionStatus.fromCode(statusCode);
        return new ArcTransactionResponse(txid, status, blockHeight, blockHash, timestamp, merklePath);
    }

    static String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int searchFrom = 0;
        while (true) {
            int keyIdx = json.indexOf(searchKey, searchFrom);
            if (keyIdx < 0) return null;

            if (keyIdx > 0) {
                char before = json.charAt(keyIdx - 1);
                if (before != '{' && before != ',' && !Character.isWhitespace(before)) {
                    searchFrom = keyIdx + searchKey.length();
                    continue;
                }
            }

            int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
            if (colonIdx < 0) return null;

            String rest = json.substring(colonIdx + 1).trim();
            if (rest.startsWith("null")) return null;
            if (!rest.startsWith("\"")) return null;

            int endQuote = rest.indexOf('"', 1);
            return rest.substring(1, endQuote);
        }
    }

    static int extractJsonInt(String json, String key) {
        String raw = extractJsonNumber(json, key);
        if (raw == null) return 0;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            // Parser extracted a non-integer value (e.g., digits from a txid).
            // Return 0 rather than crash — the caller treats 0 as unknown status.
            return 0;
        }
    }

    static long extractJsonLong(String json, String key) {
        String raw = extractJsonNumber(json, key);
        if (raw == null) return 0L;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Extract a numeric value from a JSON string by key.
     *
     * <p>The key must appear as a proper JSON key — preceded by {@code {}, {@code ,},
     * or whitespace, and immediately followed by {@code ":} with at most whitespace
     * between the closing quote and the colon. This prevents matching keys that appear
     * as substrings of other keys or inside string values.
     */
    private static String extractJsonNumber(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int searchFrom = 0;
        while (true) {
            int keyIdx = json.indexOf(searchKey, searchFrom);
            if (keyIdx < 0) return null;

            // Guard: the key must be a real JSON key, not a substring of a value.
            // A real key is preceded by { , or whitespace.
            if (keyIdx > 0) {
                char before = json.charAt(keyIdx - 1);
                if (before != '{' && before != ',' && !Character.isWhitespace(before)) {
                    searchFrom = keyIdx + searchKey.length();
                    continue;
                }
            }

            // The colon must immediately follow the key (ignoring whitespace)
            int afterKey = keyIdx + searchKey.length();
            int colonIdx = -1;
            for (int i = afterKey; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == ':') { colonIdx = i; break; }
                if (!Character.isWhitespace(c)) break;
            }
            if (colonIdx < 0) {
                searchFrom = afterKey;
                continue;
            }

            String rest = json.substring(colonIdx + 1).trim();
            if (rest.startsWith("null")) return null;

            // Value must start with a digit or minus — if it starts with a quote,
            // this is a string value, not a number.
            if (rest.startsWith("\"")) return null;

            StringBuilder num = new StringBuilder();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (Character.isDigit(c) || c == '-') {
                    num.append(c);
                } else if (!num.isEmpty()) {
                    break;
                }
            }
            return num.isEmpty() ? null : num.toString();
        }
    }
}
