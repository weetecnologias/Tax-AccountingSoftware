package com.taxpilot.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class FileTypeDetectorTest {

    @Test
    void acceptsMatchingContentAndExtension() {
        assertThat(FileTypeDetector.detect("inv.pdf", "%PDF-1.7 ...".getBytes())).contains("application/pdf");
        assertThat(FileTypeDetector.detect("scan.PNG",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0})).contains("image/png");
        assertThat(FileTypeDetector.detect("a.jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}))
                .contains("image/jpeg");
        assertThat(FileTypeDetector.detect("book.xlsx", new byte[]{'P', 'K', 3, 4, 0})).isPresent();
        assertThat(FileTypeDetector.detect("bank.csv", "date,amount\n2025-04-01,100.00\n"
                .getBytes(StandardCharsets.UTF_8))).contains("text/csv");
    }

    @Test
    void rejectsMismatchedOrDisallowedFiles() {
        assertThat(FileTypeDetector.detect("invoice.pdf", "MZ executable".getBytes())).isEmpty();
        assertThat(FileTypeDetector.detect("evil.exe", "MZ".getBytes())).isEmpty();
        assertThat(FileTypeDetector.detect("data.csv", new byte[]{'a', 0, 'b'})).isEmpty();
        assertThat(FileTypeDetector.detect("empty.pdf", new byte[0])).isEmpty();
        assertThat(FileTypeDetector.detect("noext", "%PDF-".getBytes())).isEmpty();
    }

    @Test
    void sanitisesFilenames() {
        assertThat(FileTypeDetector.sanitiseFilename("..\\..\\windows\\evil.pdf")).isEqualTo("evil.pdf");
        assertThat(FileTypeDetector.sanitiseFilename("/etc/passwd")).isEqualTo("passwd");
        assertThat(FileTypeDetector.sanitiseFilename("a\u0000b<c>.pdf")).isEqualTo("a_b_c_.pdf");
        assertThat(FileTypeDetector.sanitiseFilename("..")).isEqualTo("file");
        assertThat(FileTypeDetector.sanitiseFilename(null)).isEqualTo("file");
    }

    @Test
    void financialYearMustBeConsecutive() {
        assertThat(DocumentService.validateFinancialYear("2025-26")).isEqualTo("2025-26");
        assertThat(DocumentService.validateFinancialYear(null)).isNull();
        assertThat(DocumentService.validateFinancialYear("1999-00")).isEqualTo("1999-00");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DocumentService.validateFinancialYear("2025-27"))
                .isInstanceOf(com.taxpilot.common.ApiException.class);
    }
}
