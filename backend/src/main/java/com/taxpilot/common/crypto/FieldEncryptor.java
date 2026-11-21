package com.taxpilot.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.taxpilot.config.TaxpilotProperties;

/**
 * AES-256-GCM encryption for sensitive fields and stored files.
 * Format: [1 byte key version][12 byte IV][ciphertext + 16 byte tag].
 */
@Component
public class FieldEncryptor {

    private static final byte KEY_VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptor(TaxpilotProperties props) {
        this.key = new SecretKeySpec(decodeKey(props.crypto().encryptionKey(), "encryption"), "AES");
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(1 + IV_LENGTH + ct.length).put(KEY_VERSION).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] payload) {
        if (payload == null || payload.length < 1 + IV_LENGTH + 16 || payload[0] != KEY_VERSION) {
            throw new IllegalStateException("Unrecognised ciphertext");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 1, IV_LENGTH));
            return cipher.doFinal(payload, 1 + IV_LENGTH, payload.length - 1 - IV_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    public byte[] encryptString(String value) {
        return encrypt(value.getBytes(StandardCharsets.UTF_8));
    }

    public String decryptString(byte[] payload) {
        return new String(decrypt(payload), StandardCharsets.UTF_8);
    }

    static byte[] decodeKey(String base64, String name) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("Missing " + name + " key (taxpilot.crypto)");
        }
        byte[] bytes = Base64.getDecoder().decode(base64.trim());
        if (bytes.length != 32) {
            throw new IllegalStateException("The " + name + " key must be 32 bytes (base64-encoded)");
        }
        return bytes;
    }
}
