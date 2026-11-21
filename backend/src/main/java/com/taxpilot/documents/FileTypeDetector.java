package com.taxpilot.documents;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/** Identifies an upload from its bytes and requires the extension to agree. */
public final class FileTypeDetector {

    private FileTypeDetector() {
    }

    public static Optional<String> detect(String filename, byte[] data) {
        String ext = extension(filename);
        if (data.length == 0) {
            return Optional.empty();
        }
        return switch (ext) {
            case "pdf" -> startsWith(data, "%PDF-".getBytes(StandardCharsets.US_ASCII))
                    ? Optional.of("application/pdf") : Optional.empty();
            case "png" -> startsWith(data, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})
                    ? Optional.of("image/png") : Optional.empty();
            case "jpg", "jpeg" -> startsWith(data, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})
                    ? Optional.of("image/jpeg") : Optional.empty();
            case "xlsx" -> startsWith(data, new byte[]{'P', 'K', 0x03, 0x04})
                    ? Optional.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    : Optional.empty();
            case "xls" -> startsWith(data, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1,
                    (byte) 0xB1, 0x1A, (byte) 0xE1}) ? Optional.of("application/vnd.ms-excel") : Optional.empty();
            case "csv" -> isPlainText(data) ? Optional.of("text/csv") : Optional.empty();
            default -> Optional.empty();
        };
    }

    public static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Strips any path, control characters and limits length; the result is display metadata only. */
    public static String sanitiseFilename(String original) {
        String name = original == null ? "file" : original;
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}\"<>|*?:]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "file";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPlainText(byte[] data) {
        int len = Math.min(data.length, 64 * 1024);
        for (int i = 0; i < len; i++) {
            if (data[i] == 0) {
                return false;
            }
        }
        // Allow a truncated multi-byte sequence at the cut-off point.
        int check = len == data.length ? len : Math.max(0, len - 4);
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, 0, check));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
