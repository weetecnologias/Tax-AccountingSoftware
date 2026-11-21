package com.taxpilot.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import com.taxpilot.common.ApiException;

class BankStatementParserTest {

    static final Path SAMPLES = Path.of("..", "database", "seed-data", "bank-statements");

    @ParameterizedTest(name = "{0}")
    @CsvSource({"hdfc-sample.csv,HDFC", "icici-sample.csv,ICICI", "sbi-sample.csv,SBI", "axis-sample.csv,AXIS",
            "kotak-sample.csv,KOTAK"})
    void everySampleLayoutParsesToTheSameTransactions(String file, String format) throws Exception {
        BankStatementParser.Result r = BankStatementParser.parse(file, Files.readAllBytes(SAMPLES.resolve(file)));
        assertThat(r.format().name()).isEqualTo(format);
        assertThat(r.rows()).hasSize(5);
        assertThat(r.warnings()).isEmpty();
        // Chronological regardless of the file's order (Axis lists newest first).
        assertThat(r.rows().getFirst().date()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(r.rows().getFirst().deposit()).isEqualByComparingTo("500000.00");
        assertThat(r.rows().get(1).withdrawal()).isEqualByComparingTo("118.00");
        assertThat(r.rows().get(3).withdrawal()).isEqualByComparingTo("45000.00");
        assertThat(r.rows().getLast().deposit()).isEqualByComparingTo("1250.00");
        assertThat(r.openingBalance()).isEqualByComparingTo("0.00");
        assertThat(r.closingBalance()).isEqualByComparingTo("692132.00");
    }

    @Test
    void readsExcelWorkbooksWithRealDateCells() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Statement");
            s.createRow(0).createCell(0).setCellValue("SYNTHETIC TEST DATA");
            Row h = s.createRow(2);
            String[] headers = {"Transaction Date", "Transaction Remarks", "Withdrawal Amount (INR )",
                    "Deposit Amount (INR )", "Balance (INR )"};
            for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            Row r1 = s.createRow(3);
            r1.createCell(0).setCellValue(LocalDate.of(2026, 4, 1));
            r1.getCell(0).setCellStyle(dateStyle);
            r1.createCell(1).setCellValue("NEFT CR CAPITAL");
            r1.createCell(3).setCellValue(1000.5);
            r1.createCell(4).setCellValue(1000.5);
            Row r2 = s.createRow(4);
            r2.createCell(0).setCellValue(LocalDate.of(2026, 4, 2));
            r2.getCell(0).setCellStyle(dateStyle);
            r2.createCell(1).setCellValue("ATM WDL");
            r2.createCell(2).setCellValue(500);
            r2.createCell(4).setCellValue(500.5);
            wb.write(out);
            BankStatementParser.Result r = BankStatementParser.parse("statement.xlsx", out.toByteArray());
            assertThat(r.rows()).hasSize(2);
            assertThat(r.rows().getFirst().date()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(r.rows().getFirst().deposit()).isEqualByComparingTo("1000.50");
            assertThat(r.rows().get(1).withdrawal()).isEqualByComparingTo("500.00");
            assertThat(r.closingBalance()).isEqualByComparingTo("500.50");
        }
    }

    @Test
    void reportsBrokenRunningBalance() {
        String csv = """
                Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
                01/04/2026,A,,100.00,100.00
                02/04/2026,B,10.00,,95.00
                """;
        BankStatementParser.Result r = BankStatementParser.parse("x.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(r.warnings()).anyMatch(w -> w.contains("running balance"));
    }

    @Test
    void rejectsFilesWithoutARecognisableHeader() {
        assertThatThrownBy(() -> BankStatementParser.parse("x.csv", "foo,bar\n1,2\n".getBytes()))
                .isInstanceOf(ApiException.class).hasMessageContaining("header");
        assertThatThrownBy(() -> BankStatementParser.parse("x.pdf", new byte[]{1}))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void parsesIndianDateAndAmountFormats() {
        assertThat(BankStatementParser.parseDate("01/04/26")).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(BankStatementParser.parseDate("1 Apr 2026")).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(BankStatementParser.parseDate("01-APR-2026")).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(BankStatementParser.parseDate("01/04/2026 10:15:00")).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(BankStatementParser.parseDate("Opening Balance")).isNull();
        assertThat(BankStatementParser.parseAmount("5,00,000.00")).isEqualByComparingTo("500000.00");
        assertThat(BankStatementParser.parseAmount("1,234.50 Cr")).isEqualByComparingTo("1234.50");
        assertThat(BankStatementParser.parseAmount("(250.00)")).isEqualByComparingTo(new BigDecimal("-250.00"));
        assertThat(BankStatementParser.parseAmount("")).isNull();
    }

    @Test
    void readsQuotedCsvFields() {
        assertThat(BankStatementParser.readCsv("a,\"b, c\",\"say \"\"hi\"\"\"\r\nd,e,f"))
                .containsExactly(java.util.List.of("a", "b, c", "say \"hi\""), java.util.List.of("d", "e", "f"));
    }

    @Test
    void suggestsCategoriesFromDescriptions() {
        assertThat(ReconciliationService.categoryHint("SMS ALERT CHGS INCL GST")).isEqualTo("BANK_CHARGES");
        assertThat(ReconciliationService.categoryHint("INT.PD:01-04-2026 to 31-05-2026")).isEqualTo("INTEREST");
        assertThat(ReconciliationService.categoryHint("ATM WDL MG ROAD")).isEqualTo("CASH");
        assertThat(ReconciliationService.categoryHint("NEFT MEHTA GARMENTS")).isNull();
    }
}
