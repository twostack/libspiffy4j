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
     */
    public MerkleProofData getMerkleProof(String txid) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/v1/tx/" + txid + "/merklepath"))
                    .GET();

            addAuthHeader(builder);

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new ArcServiceException("ARC merkle proof request failed with status " + response.statusCode(),
                        response.statusCode(), response.body());
            }

            return parseMerkleProofResponse(response.body());
        } catch (ArcServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ArcServiceException("Failed to get merkle proof", e);
        }
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

    private MerkleProofData parseMerkleProofResponse(String json) {
        String merklePathHex = extractJsonString(json, "merklePath");
        long blockHeight = extractJsonLong(json, "blockHeight");
        byte[] merklePathBytes = HexFormat.of().parseHex(merklePathHex);
        Bump bump = Bump.parse(merklePathBytes);
        return new MerkleProofData(bump, blockHeight);
    }

    static String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;

        String rest = json.substring(colonIdx + 1).trim();
        if (rest.startsWith("null")) return null;
        if (!rest.startsWith("\"")) return null;

        int endQuote = rest.indexOf('"', 1);
        return rest.substring(1, endQuote);
    }

    static int extractJsonInt(String json, String key) {
        String raw = extractJsonNumber(json, key);
        return raw == null ? 0 : Integer.parseInt(raw);
    }

    static long extractJsonLong(String json, String key) {
        String raw = extractJsonNumber(json, key);
        return raw == null ? 0L : Long.parseLong(raw);
    }

    private static String extractJsonNumber(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;

        String rest = json.substring(colonIdx + 1).trim();
        if (rest.startsWith("null")) return null;

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
