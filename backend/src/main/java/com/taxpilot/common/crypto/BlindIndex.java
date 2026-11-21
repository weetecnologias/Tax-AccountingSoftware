package com.taxpilot.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.taxpilot.config.TaxpilotProperties;

/** Keyed HMAC-SHA256 used to look up / enforce uniqueness of encrypted values without decrypting them. */
@Component
public class BlindIndex {

    private final SecretKeySpec key;

    public BlindIndex(TaxpilotProperties props) {
        this.key = new SecretKeySpec(FieldEncryptor.decodeKey(props.crypto().hmacKey(), "HMAC"), "HmacSHA256");
    }

    public String of(String normalisedValue) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(normalisedValue.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    /** Unkeyed SHA-256, used for refresh-token storage and file fingerprints. */
    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }
}
