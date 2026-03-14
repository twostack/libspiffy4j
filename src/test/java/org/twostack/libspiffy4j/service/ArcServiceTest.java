package org.twostack.libspiffy4j.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.twostack.libspiffy4j.model.ArcServiceConfig;
import org.twostack.libspiffy4j.model.ArcSubmitResponse;
import org.twostack.libspiffy4j.model.ArcTransactionResponse;
import org.twostack.libspiffy4j.model.ArcTransactionStatus;
import org.twostack.libspiffy4j.model.MerkleProofData;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArcServiceTest {

    private MockWebServer server;
    private ArcService arcService;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        ArcServiceConfig config = ArcServiceConfig.custom(
                server.url("/").toString().replaceAll("/$", ""),
                "test-api-key",
                null
        );
        arcService = new ArcService(config);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void submitTransaction_parsesSuccessResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"txid\":\"abc123\",\"txStatus\":7,\"extraInfo\":\"accepted\"}")
                .setHeader("Content-Type", "application/json"));

        ArcSubmitResponse response = arcService.submitTransaction("deadbeef");

        assertThat(response.txid()).isEqualTo("abc123");
        assertThat(response.status()).isEqualTo(ArcTransactionStatus.ACCEPTED_BY_NETWORK);
        assertThat(response.extraInfo()).isEqualTo("accepted");
        assertThat(response.statusCode()).isEqualTo(7);
    }

    @Test
    void submitTransaction_sendsAuthHeader() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"txid\":\"abc123\",\"txStatus\":7}")
                .setHeader("Content-Type", "application/json"));

        arcService.submitTransaction("deadbeef");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-api-key");
    }

    @Test
    void submitTransaction_sendsCallbackHeader() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"txid\":\"abc123\",\"txStatus\":7}")
                .setHeader("Content-Type", "application/json"));

        arcService.submitTransaction("deadbeef", "https://example.com/callback");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("X-CallbackUrl")).isEqualTo("https://example.com/callback");
    }

    @Test
    void queryTransaction_parsesFullResponse() {
        server.enqueue(new MockResponse()
                .setBody("{\"txid\":\"abc123\",\"txStatus\":9,\"blockHeight\":800000,\"blockHash\":\"0000hash\",\"timestamp\":1700000000,\"merklePath\":\"beef\"}")
                .setHeader("Content-Type", "application/json"));

        ArcTransactionResponse response = arcService.queryTransaction("abc123");

        assertThat(response.txid()).isEqualTo("abc123");
        assertThat(response.status()).isEqualTo(ArcTransactionStatus.MINED);
        assertThat(response.blockHeight()).isEqualTo(800000);
        assertThat(response.blockHash()).isEqualTo("0000hash");
        assertThat(response.timestamp()).isEqualTo(1700000000L);
    }

    @Test
    void queryTransaction_throwsOnHttpError() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));

        assertThatThrownBy(() -> arcService.queryTransaction("missing"))
                .isInstanceOf(ArcServiceException.class)
                .satisfies(e -> {
                    ArcServiceException ase = (ArcServiceException) e;
                    assertThat(ase.httpStatusCode()).isEqualTo(404);
                    assertThat(ase.responseBody()).isEqualTo("not found");
                });
    }

    @Test
    void submitTransaction_throwsOn500() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("internal error"));

        assertThatThrownBy(() -> arcService.submitTransaction("deadbeef"))
                .isInstanceOf(ArcServiceException.class)
                .satisfies(e -> {
                    ArcServiceException ase = (ArcServiceException) e;
                    assertThat(ase.httpStatusCode()).isEqualTo(500);
                });
    }

    @Test
    void getMerkleProof_parsesResponse() {
        // A minimal valid BUMP: blockHeight=1 (varint 01), treeHeight=1 (01),
        // 1 leaf at level 0: nLeaves=1 (01), offset=0 (00), flags=02 (isTxid),
        // hash = 32 zero bytes
        String bumpHex = "01" + "01" + "01" + "00" + "02"
                + "0000000000000000000000000000000000000000000000000000000000000000";

        server.enqueue(new MockResponse()
                .setBody("{\"merklePath\":\"" + bumpHex + "\",\"blockHeight\":1}")
                .setHeader("Content-Type", "application/json"));

        MerkleProofData proof = arcService.getMerkleProof("sometxid");

        assertThat(proof.blockHeight()).isEqualTo(1);
        assertThat(proof.bump()).isNotNull();
        assertThat(proof.bump().blockHeight()).isEqualTo(1);
    }
}
