package br.com.paywallet.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.user.UserDtos.CreateUserRequest;
import br.com.paywallet.user.UserType;

class PiiEncryptionIntegrationTest extends IntegrationTest {

    @Autowired PiiEncryption encryption;
    @Autowired FieldCipher cipher;

    @Test
    void documentsAreStoredEncryptedAndFoundByBlindIndex() throws Exception {
        var user = newUser(UserType.COMMON, "Private Person");

        String stored = column("SELECT document FROM users WHERE id = ?", user.id());
        assertThat(stored).startsWith("enc:v1:").doesNotContain(user.document());
        assertThat(column("SELECT document_index FROM users WHERE id = ?", user.id()))
                .isEqualTo(cipher.blindIndex(user.document()));
        mvc.perform(get("/users/{id}", user.id()).with(as(user)))
                .andExpect(jsonPath("$.document").value(user.document()));
        assertThatThrownBy(() -> userService.create(new CreateUserRequest("Twin", user.document(),
                "twin-" + UUID.randomUUID() + "@mail.com", PASSWORD, UserType.COMMON)))
                .isInstanceOf(BusinessException.class).hasMessage("Document already registered");
    }

    @Test
    void pixKeysAreEncryptedAndStillResolve() throws Exception {
        var owner = newUser(UserType.COMMON, "Key Owner");
        var payer = newUserWithBalance("Key Payer", "50.00");
        String phone = "+55119" + "%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
        mvc.perform(post("/pix/keys").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PHONE\",\"value\":\"%s\"}".formatted(phone)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.value").value(phone));

        assertThat(column("SELECT key_value FROM pix_keys WHERE user_id = ?", owner.id()))
                .startsWith("enc:v1:").doesNotContain(phone.substring(3));
        mvc.perform(post("/pix/keys").with(as(payer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PHONE\",\"value\":\"%s\"}".formatted(phone)))
                .andExpect(status().isUnprocessableEntity());
        String e2e = JsonPath.read(mvc.perform(post("/pix/payments").with(as(payer))
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"%s\",\"value\":10.00}".formatted(phone)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.endToEndId");
        assertThat(column("SELECT key_value FROM pix_payments WHERE end_to_end_id = ?", e2e)).startsWith("enc:v1:");
    }

    @Test
    void plaintextLeftFromBeforeEncryptionIsEncryptedByTheJob() {
        var user = newUser(UserType.COMMON, "Legacy Person");
        jdbc.update("UPDATE users SET document = ?, document_index = NULL WHERE id = ?", user.document(), user.id());

        assertThat(userService.get(user.id()).getDocument()).isEqualTo(user.document());
        assertThat(encryption.reencryptAll()).isPositive();

        assertThat(column("SELECT document FROM users WHERE id = ?", user.id())).startsWith("enc:v1:");
        assertThat(column("SELECT document_index FROM users WHERE id = ?", user.id()))
                .isEqualTo(cipher.blindIndex(user.document()));
    }

    @Test
    void rotationMovesExistingDataToTheNewKey() throws Exception {
        var user = newUser(UserType.COMMON, "Rotated Person");
        var admin = newUser(UserType.COMMON, "Key Custodian");
        makeAdmin(admin);
        mvc.perform(post("/admin/crypto/keys/rotation").with(as(user))).andExpect(status().isForbidden());

        String activeKey = JsonPath.read(mvc.perform(post("/admin/crypto/keys/rotation").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeKey.purpose").value("DATA"))
                .andExpect(jsonPath("$.reencrypted").isNumber())
                .andReturn().getResponse().getContentAsString(), "$.activeKey.id");

        assertThat(column("SELECT document FROM users WHERE id = ?", user.id()))
                .startsWith("enc:v1:" + activeKey + ":");
        assertThat(userService.get(user.id()).getDocument()).isEqualTo(user.document());
        mvc.perform(get("/admin/crypto/keys").with(as(admin)))
                .andExpect(jsonPath("$[?(@.purpose == 'INDEX')].active").value(contains(true)));
    }

    private String column(String sql, Object arg) {
        return jdbc.queryForObject(sql, String.class, arg);
    }
}
