package org.twostack.libspiffy4j.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.twostack.libspiffy4j.model.CdnHeaderSyncConfig;
import org.twostack.libspiffy4j.model.CdnSyncResult;
import org.twostack.libspiffy4j.spv.BlockHeader;
import org.twostack.libspiffy4j.spv.BlockHeaderChain;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CdnHeaderSyncServiceTest {

    private MockWebServer server;
    private BlockHeaderChain chain;
    private CdnHeaderSyncService syncService;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        chain = new BlockHeaderChain();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private CdnHeaderSyncService createService() {
        CdnHeaderSyncConfig config = new CdnHeaderSyncConfig(
                server.url("/").toString().replaceAll("/$", ""),
                "testnet",
                1,
                Duration.ofSeconds(10),
                false,
                false,
                null,
                3
        );
        return new CdnHeaderSyncService(config, chain);
    }

    private byte[] createFakeHeader() {
        byte[] header = new byte[80];
        // version = 1
        header[0] = 1;
        // prevBlockHash = 32 bytes of zeros (already zeroed)
        // merkleRoot = 32 bytes starting at offset 36
        for (int i = 36; i < 68; i++) {
            header[i] = (byte) (i & 0xFF);
        }
        // timestamp, bits, nonce all zeroed
        return header;
    }

    @Test
    void synchronize_importsHeadersFromManifest() throws Exception {
        syncService = createService();
        byte[] headerBytes = createFakeHeader();
        String sha256 = sha256Hex(headerBytes);
        String chunkUrl = server.url("/chunk0.bin").toString();

        String manifest = "{\"network\":\"testnet\",\"chunkSize\":1,\"chunks\":["
                + "{\"startHeight\":0,\"endHeight\":0,\"url\":\"" + chunkUrl + "\",\"sha256\":\"" + sha256 + "\"}"
                + "]}";

        // Manifest request
        server.enqueue(new MockResponse().setBody(manifest));
        // Chunk download
        server.enqueue(new MockResponse().setBody(new Buffer().write(headerBytes)));

        CdnSyncResult result = syncService.synchronize();

        assertThat(result.headersImported()).isEqualTo(1);
        assertThat(result.finalHeight()).isEqualTo(0);
        assertThat(chain.getHeader(0)).isNotNull();
    }

    @Test
    void synchronize_skipsAlreadySyncedChunks() throws Exception {
        // Pre-populate chain with header at height 0
        byte[] headerBytes = createFakeHeader();
        BlockHeader header = BlockHeader.parse(headerBytes);
        chain.addHeader(0, header);

        syncService = createService();
        String chunkUrl = server.url("/chunk0.bin").toString();

        String manifest = "{\"network\":\"testnet\",\"chunkSize\":1,\"chunks\":["
                + "{\"startHeight\":0,\"endHeight\":0,\"url\":\"" + chunkUrl + "\",\"sha256\":\"abc\"}"
                + "]}";

        server.enqueue(new MockResponse().setBody(manifest));
        // No chunk request should be made

        CdnSyncResult result = syncService.synchronize();

        assertThat(result.headersImported()).isEqualTo(0);
        assertThat(server.getRequestCount()).isEqualTo(1); // only manifest
    }

    @Test
    void synchronize_rejectsInvalidChecksum() throws Exception {
        syncService = createService();
        byte[] headerBytes = createFakeHeader();
        String chunkUrl = server.url("/chunk0.bin").toString();

        String manifest = "{\"network\":\"testnet\",\"chunkSize\":1,\"chunks\":["
                + "{\"startHeight\":0,\"endHeight\":0,\"url\":\"" + chunkUrl + "\",\"sha256\":\"0000000000000000000000000000000000000000000000000000000000000000\"}"
                + "]}";

        server.enqueue(new MockResponse().setBody(manifest));
        server.enqueue(new MockResponse().setBody(new Buffer().write(headerBytes)));

        assertThatThrownBy(() -> syncService.synchronize())
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .hasMessageContaining("checksum mismatch");
    }

    @Test
    void synchronize_importsMultipleHeadersFromSingleChunk() throws Exception {
        syncService = createService();

        // Create 3 headers in sequence
        byte[] threeHeaders = new byte[240]; // 3 * 80
        for (int i = 0; i < 3; i++) {
            byte[] h = createFakeHeader();
            h[0] = (byte) (i + 1); // different versions
            System.arraycopy(h, 0, threeHeaders, i * 80, 80);
        }

        String sha256 = sha256Hex(threeHeaders);
        String chunkUrl = server.url("/chunk0.bin").toString();

        String manifest = "{\"network\":\"testnet\",\"chunkSize\":3,\"chunks\":["
                + "{\"startHeight\":100,\"endHeight\":102,\"url\":\"" + chunkUrl + "\",\"sha256\":\"" + sha256 + "\"}"
                + "]}";

        server.enqueue(new MockResponse().setBody(manifest));
        server.enqueue(new MockResponse().setBody(new Buffer().write(threeHeaders)));

        CdnSyncResult result = syncService.synchronize();

        assertThat(result.headersImported()).isEqualTo(3);
        assertThat(chain.getHeader(100)).isNotNull();
        assertThat(chain.getHeader(101)).isNotNull();
        assertThat(chain.getHeader(102)).isNotNull();
    }

    @Test
    void synchronize_reportsDuration() throws Exception {
        syncService = createService();
        byte[] headerBytes = createFakeHeader();
        String sha256 = sha256Hex(headerBytes);
        String chunkUrl = server.url("/chunk0.bin").toString();

        String manifest = "{\"network\":\"testnet\",\"chunkSize\":1,\"chunks\":["
                + "{\"startHeight\":0,\"endHeight\":0,\"url\":\"" + chunkUrl + "\",\"sha256\":\"" + sha256 + "\"}"
                + "]}";

        server.enqueue(new MockResponse().setBody(manifest));
        server.enqueue(new MockResponse().setBody(new Buffer().write(headerBytes)));

        CdnSyncResult result = syncService.synchronize();

        assertThat(result.elapsed()).isNotNull();
        assertThat(result.elapsed()).isPositive();
    }

    private String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }
}
