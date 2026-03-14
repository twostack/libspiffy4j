package org.twostack.libspiffy4j.service;

import org.twostack.libspiffy4j.model.CdnChunkDescriptor;
import org.twostack.libspiffy4j.model.CdnHeaderSyncConfig;
import org.twostack.libspiffy4j.model.CdnManifest;
import org.twostack.libspiffy4j.model.CdnSyncResult;
import org.twostack.libspiffy4j.spv.BlockHeader;
import org.twostack.libspiffy4j.spv.BlockHeaderStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Downloads block headers from a CDN, validates SHA-256 checksums, and imports them
 * into a {@link BlockHeaderStore}.
 */
public final class CdnHeaderSyncService {

    private final CdnHeaderSyncConfig config;
    private final BlockHeaderStore chain;
    private final HttpClient httpClient;

    public CdnHeaderSyncService(CdnHeaderSyncConfig config, BlockHeaderStore chain) {
        this(config, chain, HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(config.downloadTimeout())
                .build());
    }

    public CdnHeaderSyncService(CdnHeaderSyncConfig config, BlockHeaderStore chain, HttpClient httpClient) {
        this.config = config;
        this.chain = chain;
        this.httpClient = httpClient;
    }

    /**
     * Fetches the manifest and downloads/imports any chunks needed to advance the chain.
     */
    public CdnSyncResult synchronize() {
        Instant start = Instant.now();
        try {
            CdnManifest manifest = fetchManifest();
            int headersImported = 0;
            int currentChainHeight = chain.getChainHeight();

            for (CdnChunkDescriptor chunk : manifest.chunks()) {
                // Skip chunks we've already imported
                if (chunk.endHeight() <= currentChainHeight) {
                    continue;
                }

                byte[] chunkData = downloadChunk(chunk.url());
                validateChecksum(chunkData, chunk.sha256());

                int imported = importHeaders(chunkData, chunk.startHeight());
                headersImported += imported;
            }

            return new CdnSyncResult(headersImported, chain.getChainHeight(),
                    Duration.between(start, Instant.now()));
        } catch (Exception e) {
            throw new RuntimeException("CDN header sync failed", e);
        }
    }

    private CdnManifest fetchManifest() throws Exception {
        String manifestUrl = config.baseUrl() + "/" + config.network() + "/manifest.json";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(manifestUrl))
                .timeout(config.downloadTimeout())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Failed to fetch manifest: HTTP " + response.statusCode());
        }

        return parseManifest(response.body());
    }

    private byte[] downloadChunk(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(config.downloadTimeout())
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Failed to download chunk: HTTP " + response.statusCode());
        }

        return response.body();
    }

    private void validateChecksum(byte[] data, String expectedSha256) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            String actual = HexFormat.of().formatHex(hash);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new RuntimeException("Chunk checksum mismatch: expected " + expectedSha256 + ", got " + actual);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Checksum validation failed", e);
        }
    }

    private int importHeaders(byte[] data, int startHeight) {
        int headerCount = data.length / BlockHeader.HEADER_SIZE;
        int imported = 0;

        for (int i = 0; i < headerCount; i++) {
            int offset = i * BlockHeader.HEADER_SIZE;
            BlockHeader header = BlockHeader.parse(data, offset);
            int height = startHeight + i;

            // Skip headers already in the chain
            if (chain.getHeader(height) != null) {
                continue;
            }

            chain.addHeader(height, header);
            imported++;
        }

        return imported;
    }

    CdnManifest parseManifest(String json) {
        String network = ArcService.extractJsonString(json, "network");
        int chunkSize = ArcService.extractJsonInt(json, "chunkSize");

        List<CdnChunkDescriptor> chunks = new ArrayList<>();
        String chunksKey = "\"chunks\"";
        int chunksIdx = json.indexOf(chunksKey);
        if (chunksIdx >= 0) {
            int arrayStart = json.indexOf('[', chunksIdx);
            int arrayEnd = json.lastIndexOf(']');
            if (arrayStart >= 0 && arrayEnd > arrayStart) {
                String arrayContent = json.substring(arrayStart + 1, arrayEnd);
                // Parse each chunk object
                int objStart = arrayContent.indexOf('{');
                while (objStart >= 0) {
                    int objEnd = arrayContent.indexOf('}', objStart);
                    if (objEnd < 0) break;
                    String obj = arrayContent.substring(objStart, objEnd + 1);

                    int sh = ArcService.extractJsonInt(obj, "startHeight");
                    int eh = ArcService.extractJsonInt(obj, "endHeight");
                    String url = ArcService.extractJsonString(obj, "url");
                    String sha256 = ArcService.extractJsonString(obj, "sha256");
                    chunks.add(new CdnChunkDescriptor(sh, eh, url, sha256));

                    objStart = arrayContent.indexOf('{', objEnd);
                }
            }
        }

        return new CdnManifest(network, chunkSize, chunks);
    }
}
