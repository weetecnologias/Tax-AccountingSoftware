package com.taxpilot.documents;

public enum DocumentType {
    SALES_INVOICE("Sales invoice"),
    PURCHASE_INVOICE("Purchase invoice"),
    CREDIT_NOTE("Credit note"),
    DEBIT_NOTE("Debit note"),
    BANK_STATEMENT("Bank statement"),
    GST_RETURN("GST return"),
    GSTR_2B("GSTR-2B"),
    FORM_16("Form 16"),
    FORM_16A("Form 16A"),
    FORM_26AS("Form 26AS"),
    AIS_TIS("AIS / TIS"),
    SALARY_SLIP("Salary slip"),
    EXPENSE_BILL("Expense bill"),
    PAYMENT_RECEIPT("Payment receipt"),
    LOAN_STATEMENT("Loan statement"),
    FIXED_ASSET_INVOICE("Fixed asset invoice"),
    TAX_NOTICE("Tax notice"),
    AGREEMENT("Agreement"),
    KYC("KYC document"),
    REGISTRATION_CERTIFICATE("Registration certificate"),
    OTHER("Other");

    private final String label;

    DocumentType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
