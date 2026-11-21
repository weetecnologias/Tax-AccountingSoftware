package com.taxpilot.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.taxpilot.common.ApiException;

import tools.jackson.databind.json.JsonMapper;

class AccountingRulesTest {

    @Test
    void moneyParsesExactlyAndRejectsBadInput() {
        assertThat(Money.parse("1500", "f")).isEqualByComparingTo("1500.00");
        assertThat(Money.parse("1,23,456.7", "f")).isEqualTo(new BigDecimal("123456.70"));
        assertThat(Money.parse("", "f")).isEqualTo(Money.ZERO);
        assertThat(Money.parse(null, "f")).isEqualTo(Money.ZERO);
        assertThat(Money.parse("10.500", "f")).isEqualTo(new BigDecimal("10.50")); // trailing zero is exact
        assertThatThrownBy(() -> Money.parse("10.505", "f")).isInstanceOf(ApiException.class)
                .hasMessageContaining("two decimal");
        assertThatThrownBy(() -> Money.parse("-1", "f")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Money.parse("abc", "f")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Money.parse("1e20", "f")).isInstanceOf(ApiException.class);
    }

    @Test
    void decimalArithmeticHasNoFloatingPointDrift() {
        BigDecimal sum = Money.ZERO;
        for (int i = 0; i < 10; i++) sum = sum.add(Money.parse("0.10", "f"));
        assertThat(sum).isEqualTo(new BigDecimal("1.00"));
    }

    @Test
    void journalTotalsAndBalanceAreComputedFromLines() {
        JournalEntry e = new JournalEntry(UUID.randomUUID(), UUID.randomUUID(), JournalEntry.Type.GENERAL, null, null);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        e.replaceLines(List.of(new JournalEntry.Line(a, new BigDecimal("100.10"), BigDecimal.ZERO, null),
                new JournalEntry.Line(b, BigDecimal.ZERO, new BigDecimal("100.10"), null)));
        assertThat(e.getTotalDebit()).isEqualTo(new BigDecimal("100.10"));
        assertThat(e.isBalanced()).isTrue();
        assertThat(e.getLines()).extracting(JournalEntry.Line::getLineNo).containsExactly((short) 1, (short) 2);

        e.replaceLines(List.of(new JournalEntry.Line(a, new BigDecimal("100.10"), BigDecimal.ZERO, null),
                new JournalEntry.Line(b, BigDecimal.ZERO, new BigDecimal("100.00"), null)));
        assertThat(e.isBalanced()).isFalse();
    }

    @Test
    void financialYearsMustBeConsecutive() {
        assertThat(PeriodService.parseFinancialYear("2025-26")).isEqualTo(2025);
        assertThat(PeriodService.parseFinancialYear("2099-00")).isEqualTo(2099);
        assertThatThrownBy(() -> PeriodService.parseFinancialYear("2025-27")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PeriodService.parseFinancialYear("25-26")).isInstanceOf(ApiException.class);
    }

    @Test
    void standardTemplateIsWellFormed() {
        ChartOfAccountsService svc = new ChartOfAccountsService(null, null, null, JsonMapper.builder().build());
        ChartOfAccountsService.Template t = svc.loadTemplate();
        Set<String> codes = new HashSet<>();
        Map<String, ChartOfAccountsService.TemplateAccount> byCode = new HashMap<>();
        for (ChartOfAccountsService.TemplateAccount a : t.accounts()) {
            assertThat(codes.add(a.code())).as("duplicate code " + a.code()).isTrue();
            AccountType.valueOf(a.type());
            if (a.parent() != null) {
                ChartOfAccountsService.TemplateAccount parent = byCode.get(a.parent());
                assertThat(parent).as("parent of " + a.code() + " must come first").isNotNull();
                assertThat(parent.group()).as(a.parent() + " must be a group").isTrue();
                assertThat(parent.type()).as("type of " + a.code()).isEqualTo(a.type());
            }
            byCode.put(a.code(), a);
        }
        for (AccountType type : AccountType.values()) {
            assertThat(t.accounts()).anyMatch(a -> a.type().equals(type.name()) && !a.group());
        }
    }
}
