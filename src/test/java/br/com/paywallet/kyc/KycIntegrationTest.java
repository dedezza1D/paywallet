package br.com.paywallet.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.user.UserType;

class KycIntegrationTest extends IntegrationTest {

    /** Valid PNG signature followed by a few bytes. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5};

    @Test
    void uploadsToPrivateBucketAndServesViaPresignedUrl() throws Exception {
        var user = newUser(UserType.COMMON, "Kyc");

        var created = mvc.perform(multipart("/users/{id}/kyc-documents", user.id())
                        .file(new MockMultipartFile("file", "../../etc/passwd.png", "image/png", PNG))
                        .param("type", "ID_FRONT")
                        .with(as(user)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andReturn().getResponse().getContentAsString();
        String docId = JsonPath.read(created, "$.id");

        var urlJson = mvc.perform(get("/users/{id}/kyc-documents/{doc}/download-url", user.id(), docId).with(as(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(urlJson, "$.url");

        assertThat(url).contains("X-Amz-Signature").contains("X-Amz-Expires=300");
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(PNG);

            // A tampered signature (e.g. to change the object key or extend expiry) is rejected.
            var signature = url.replaceAll(".*X-Amz-Signature=([0-9a-f]+).*", "$1");
            var tampered = url.replace(signature,
                    signature.substring(0, signature.length() - 1) + (signature.endsWith("0") ? "1" : "0"));
            var forged = http.send(HttpRequest.newBuilder(URI.create(tampered)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(forged.statusCode()).isEqualTo(403);
        }
    }

    @Test
    void rejectsFileWhoseContentIsNotAnAllowedType() throws Exception {
        var user = newUser(UserType.COMMON, "Malicious");

        // Declared as image/png, but the content is an executable.
        mvc.perform(multipart("/users/{id}/kyc-documents", user.id())
                        .file(new MockMultipartFile("file", "photo.png", "image/png", new byte[] {'M', 'Z', 0, 0}))
                        .param("type", "SELFIE")
                        .with(as(user)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void documentsOfOneUserAreNotReachableThroughAnother() throws Exception {
        var owner = newUser(UserType.COMMON, "Owner");
        var other = newUser(UserType.COMMON, "Curious");
        var created = mvc.perform(multipart("/users/{id}/kyc-documents", owner.id())
                        .file(new MockMultipartFile("file", "id.png", "image/png", PNG))
                        .param("type", "ID_BACK")
                        .with(as(owner)))
                .andReturn().getResponse().getContentAsString();
        String docId = JsonPath.read(created, "$.id");

        // Through the owner's route with their own token: blocked by authorization.
        mvc.perform(get("/users/{id}/kyc-documents/{doc}/download-url", owner.id(), docId).with(as(other)))
                .andExpect(status().isForbidden());
        // Through their own route with someone else's document id: it does not exist for them.
        mvc.perform(get("/users/{id}/kyc-documents/{doc}/download-url", other.id(), docId).with(as(other)))
                .andExpect(status().isNotFound());
    }
}
