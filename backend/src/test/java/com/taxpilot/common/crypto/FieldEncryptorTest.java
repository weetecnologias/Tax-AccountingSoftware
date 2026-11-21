package com.taxpilot.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.taxpilot.config.TaxpilotProperties;

class FieldEncryptorTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_B = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private static TaxpilotProperties props(String encKey, String hmacKey) {
        return new TaxpilotProperties(null, new TaxpilotProperties.Crypto(encKey, hmacKey), null, null, null, null);
    }

    @Test
    void roundTripsAndUsesFreshIvEachTime() {
        FieldEncryptor enc = new FieldEncryptor(props(KEY_A, KEY_B));
        byte[] a = enc.encryptString("ABCPE1234F");
        byte[] b = enc.encryptString("ABCPE1234F");
        assertThat(a).isNotEqualTo(b);
        assertThat(enc.decryptString(a)).isEqualTo("ABCPE1234F");
        assertThat(enc.decryptString(b)).isEqualTo("ABCPE1234F");
    }

    @Test
    void detectsTampering() {
        FieldEncryptor enc = new FieldEncryptor(props(KEY_A, KEY_B));
        byte[] ct = enc.encryptString("secret");
        ct[ct.length - 1] ^= 1;
        assertThatThrownBy(() -> enc.decrypt(ct)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongKeyCannotDecrypt() {
        byte[] ct = new FieldEncryptor(props(KEY_A, KEY_B)).encryptString("secret");
        FieldEncryptor other = new FieldEncryptor(props(KEY_B, KEY_A));
        assertThatThrownBy(() -> other.decrypt(ct)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsShortKeys() {
        assertThatThrownBy(() -> new FieldEncryptor(props(Base64.getEncoder().encodeToString(new byte[16]), KEY_B)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blindIndexIsDeterministicAndKeyed() {
        BlindIndex a = new BlindIndex(props(KEY_A, KEY_A));
        BlindIndex b = new BlindIndex(props(KEY_A, KEY_B));
        assertThat(a.of("ABCPE1234F")).isEqualTo(a.of("ABCPE1234F")).hasSize(64);
        assertThat(a.of("ABCPE1234F")).isNotEqualTo(b.of("ABCPE1234F"));
    }
}
