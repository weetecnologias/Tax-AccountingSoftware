import type { UserRef } from "./api";

export type AccountType = "ASSET" | "LIABILITY" | "EQUITY" | "INCOME" | "EXPENSE";
export const ACCOUNT_TYPES: AccountType[] = ["ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE"];

export type Account = {
  id: string;
  code: string;
  name: string;
  accountType: AccountType;
  subtype: string | null;
  parentId: string | null;
  group: boolean;
  active: boolean;
  templateCode: string | null;
  description: string | null;
  cashFlowCategory: CashFlowCategory | null;
  used: boolean;
  version: number;
};

export type CashFlowCategory = "CASH" | "OPERATING" | "NON_CASH" | "INVESTING" | "FINANCING";
export const CASH_FLOW_CATEGORIES: { value: CashFlowCategory; label: string }[] = [
  { value: "OPERATING", label: "Operating (working capital)" },
  { value: "INVESTING", label: "Investing" },
  { value: "FINANCING", label: "Financing" },
  { value: "NON_CASH", label: "Non-cash (e.g. depreciation)" },
  { value: "CASH", label: "Cash or cash equivalent" },
];

export type Period = {
  id: string;
  financialYear: string;
  periodNumber: number;
  startDate: string;
  endDate: string;
  status: "OPEN" | "CLOSED";
  closedBy: UserRef | null;
  closedAt: string | null;
  yearClosed: boolean;
  version: number;
};

export type JournalStatus = "DRAFT" | "PENDING_APPROVAL" | "APPROVED" | "POSTED" | "CANCELLED";
export type JournalType = "GENERAL" | "OPENING" | "ADJUSTMENT" | "REVERSAL" | "CLOSING";

export type JournalSummary = {
  id: string;
  entryNumber: string | null;
  entryDate: string;
  entryType: JournalType;
  status: JournalStatus;
  narration: string;
  reference: string | null;
  totalDebit: string;
  totalCredit: string;
  balanced: boolean;
  preparedBy: UserRef | null;
  createdAt: string;
  reversalOfId: string | null;
  reversedById: string | null;
};

export type JournalLine = {
  id: string;
  lineNo: number;
  accountId: string;
  accountCode: string;
  accountName: string;
  debit: string;
  credit: string;
  description: string | null;
  partyId: string | null;
  partyName: string | null;
};

export type JournalAction = "EDIT" | "SUBMIT" | "CANCEL" | "APPROVE" | "REJECT" | "POST" | "REVERSE";

export type JournalDetail = {
  id: string;
  clientId: string;
  entryNumber: string | null;
  entryDate: string;
  entryType: JournalType;
  status: JournalStatus;
  narration: string;
  reference: string | null;
  totalDebit: string;
  totalCredit: string;
  balanced: boolean;
  financialYear: string;
  periodStatus: "OPEN" | "CLOSED";
  preparedBy: UserRef | null;
  createdAt: string;
  submittedBy: UserRef | null;
  submittedAt: string | null;
  approvedBy: UserRef | null;
  approvedAt: string | null;
  approvalComment: string | null;
  postedBy: UserRef | null;
  postedAt: string | null;
  rejectedBy: UserRef | null;
  rejectedAt: string | null;
  rejectionReason: string | null;
  reversalOfId: string | null;
  reversalOfNumber: string | null;
  reversedById: string | null;
  reversedByNumber: string | null;
  lines: JournalLine[];
  allowedActions: JournalAction[];
  version: number;
};

export type Letterhead = {
  name: string;
  tagline: string | null;
  address: string | null;
  gstin: string | null;
  pan: string | null;
  contact: string | null;
  footer: string | null;
};

export type ReportHeader = {
  clientId: string;
  clientCode: string;
  clientName: string;
  from: string | null;
  to: string;
  generatedAt: string;
  basis: string;
  preparedBy: Letterhead;
  compareFrom: string | null;
  compareTo: string | null;
};

export type OrganisationSettings = {
  displayName: string;
  legalName: string | null;
  tagline: string | null;
  addressLine1: string | null;
  addressLine2: string | null;
  city: string | null;
  stateCode: string | null;
  stateName: string | null;
  pincode: string | null;
  gstin: string | null;
  pan: string | null;
  email: string | null;
  phone: string | null;
  website: string | null;
  reportFooter: string | null;
  allowSelfApproval: boolean;
  updatedAt: string;
  version: number;
};

export type PartyType = "CUSTOMER" | "VENDOR" | "BOTH";
export type Party = {
  id: string;
  partyType: PartyType;
  name: string;
  gstin: string | null;
  pan: string | null;
  stateCode: string | null;
  stateName: string | null;
  email: string | null;
  phone: string | null;
  address: string | null;
  creditDays: number;
  active: boolean;
  version: number;
};

export type InvoiceType = "SALES" | "PURCHASE" | "CREDIT_NOTE" | "DEBIT_NOTE";
export const INVOICE_TYPE_LABEL: Record<InvoiceType, string> = {
  SALES: "Sales invoice", PURCHASE: "Purchase invoice", CREDIT_NOTE: "Credit note", DEBIT_NOTE: "Debit note",
};
export type InvoiceStatus = "DRAFT" | "PENDING_APPROVAL" | "POSTED" | "CANCELLED";
export type InvoiceLine = {
  id: string;
  lineNo: number;
  description: string;
  hsnSac: string | null;
  accountId: string;
  accountCode: string;
  accountName: string;
  quantity: string;
  unitPrice: string;
  taxableValue: string;
  gstRate: string;
  cgst: string;
  sgst: string;
  igst: string;
};
export type Invoice = {
  id: string;
  clientId: string;
  invoiceType: InvoiceType;
  status: InvoiceStatus;
  invoiceNumber: string | null;
  supplierInvoiceNumber: string | null;
  invoiceDate: string;
  dueDate: string | null;
  partyId: string;
  partyName: string | null;
  partyGstin: string | null;
  gstRegistrationId: string | null;
  gstin: string | null;
  placeOfSupply: string;
  placeOfSupplyName: string | null;
  supplyType: "INTRA" | "INTER";
  reverseCharge: boolean;
  taxableTotal: string;
  cgstTotal: string;
  sgstTotal: string;
  igstTotal: string;
  grandTotal: string;
  notes: string | null;
  journalEntryId: string | null;
  journalNumber: string | null;
  preparedBy: UserRef | null;
  submittedBy: UserRef | null;
  submittedAt: string | null;
  approvedBy: UserRef | null;
  approvedAt: string | null;
  selfApproved: boolean;
  rejectionReason: string | null;
  lines: InvoiceLine[];
  allowedActions: ("EDIT" | "SUBMIT" | "CANCEL" | "APPROVE" | "REJECT" | "CREDIT_NOTE" | "DEBIT_NOTE")[];
  createdAt: string;
  version: number;
  originalInvoiceId: string | null;
  originalNumber: string | null;
  noteReason: string | null;
  notesPostedTotal: string;
  noteableBalance: string;
};
export type GstRate = { rate: string; description: string; reviewStatus: string };
export type InvoiceSeries = { kind: "INVOICE" | "CREDIT_NOTE" | "DEBIT_NOTE"; id: string; gstRegistrationId: string | null; gstin: string | null; prefix: string; nextNumberPreview: string };

export type BankImport = {
  id: string;
  clientId: string;
  ledgerAccountId: string;
  ledgerAccountName: string | null;
  bankFormat: string;
  originalFilename: string;
  periodFrom: string;
  periodTo: string;
  openingBalance: string | null;
  closingBalance: string | null;
  totalDeposits: string;
  totalWithdrawals: string;
  lineCount: number;
  duplicateCount: number;
  matched: number;
  unmatched: number;
  excluded: number;
  status: "IN_PROGRESS" | "RECONCILED";
  importedBy: UserRef | null;
  importedAt: string;
  approvedBy: UserRef | null;
  approvedAt: string | null;
  approvalComment: string | null;
  selfApproved: boolean;
  canApprove: boolean;
  warnings: string[];
};
export type MatchedLine = { lineId: string; journalId: string; entryNumber: string; entryDate: string; narration: string; amount: string };
export type BankTxn = {
  id: string;
  rowNo: number;
  txnDate: string;
  valueDate: string | null;
  description: string;
  reference: string | null;
  withdrawal: string;
  deposit: string;
  balance: string | null;
  categoryHint: string | null;
  status: "UNMATCHED" | "MATCHED" | "EXCLUDED";
  matchType: string | null;
  matchConfidence: number | null;
  matchedLine: MatchedLine | null;
  exclusionReason: string | null;
};
export type MatchSuggestion = {
  lineId: string;
  journalId: string;
  entryNumber: string;
  entryDate: string;
  narration: string;
  reference: string | null;
  amount: string;
  matchType: "PROBABLE" | "PARTIAL";
  confidence: number;
  evidence: string[];
};
export type ReconSummary = {
  importId: string;
  asOf: string;
  balancePerBank: string | null;
  balancePerBooks: string;
  bankOnlyDeposits: string;
  bankOnlyWithdrawals: string;
  bookOnlyDebits: string;
  bookOnlyCredits: string;
  adjustedBookBalance: string;
  difference: string | null;
  reconciled: boolean;
  bookOnlyEntries: MatchedLine[];
};

export type TrialBalance = {
  header: ReportHeader;
  rows: { accountId: string; code: string; name: string; accountType: AccountType; groupName: string; debit: string; credit: string }[];
  totalDebit: string;
  totalCredit: string;
  balanced: boolean;
};

export type StatementSection = {
  title: string;
  lines: { accountId: string; code: string; name: string; amount: string; previous: string | null }[];
  total: string;
  previousTotal: string | null;
};

export type ProfitAndLoss = {
  header: ReportHeader;
  income: StatementSection[];
  totalIncome: string;
  expenses: StatementSection[];
  totalExpenses: string;
  netProfit: string;
  previousTotalIncome: string | null;
  previousTotalExpenses: string | null;
  previousNetProfit: string | null;
};

export type BalanceSheet = {
  header: ReportHeader;
  assets: StatementSection[];
  totalAssets: string;
  liabilities: StatementSection[];
  totalLiabilities: string;
  equity: StatementSection[];
  profitForPeriod: string;
  totalEquity: string;
  totalLiabilitiesAndEquity: string;
  balanced: boolean;
  difference: string;
  previousTotalAssets: string | null;
  previousTotalLiabilities: string | null;
  previousProfitForPeriod: string | null;
  previousTotalEquity: string | null;
  previousTotalLiabilitiesAndEquity: string | null;
};

export type GeneralLedger = {
  header: ReportHeader;
  accountId: string;
  code: string;
  name: string;
  accountType: AccountType;
  openingBalance: string;
  openingSide: string;
  lines: { journalId: string; entryNumber: string; entryDate: string; narration: string; description: string | null;
    debit: string; credit: string; balance: string; balanceSide: string }[];
  totalDebit: string;
  totalCredit: string;
  closingBalance: string;
  closingSide: string;
};

export type CashFlowSection = { title: string; lines: { accountId: string | null; code: string | null; label: string; amount: string }[]; total: string };
export type CashFlow = {
  header: ReportHeader;
  netProfit: string;
  nonCashAdjustments: CashFlowSection;
  workingCapital: CashFlowSection;
  operatingTotal: string;
  investing: CashFlowSection;
  financing: CashFlowSection;
  netChange: string;
  openingCash: string;
  closingCash: string;
  reconciles: boolean;
  unclassifiedAccounts: string[];
};

export type AgeingRow = { partyId: string | null; partyName: string; gstin: string | null; outstanding: string; buckets: string[]; unlinked: string; advance: string };
export type Ageing = {
  kind: "RECEIVABLE" | "PAYABLE";
  asOf: string;
  bucketLabels: string[];
  rows: AgeingRow[];
  total: string;
  bucketTotals: string[];
  unlinkedTotal: string;
  advanceTotal: string;
  controlBalance: string;
  reconciles: boolean;
};

export type RegisterRow = {
  invoiceId: string; documentType: InvoiceType; number: string; date: string; partyName: string; partyGstin: string | null;
  placeOfSupply: string; supplyType: string; reverseCharge: boolean; taxable: string; cgst: string; sgst: string; igst: string; total: string;
};
export type Register = { kind: "SALES" | "PURCHASE"; from: string; to: string; rows: RegisterRow[]; taxable: string; cgst: string; sgst: string; igst: string; total: string };

export type RecurringFrequency = "MONTHLY" | "QUARTERLY" | "YEARLY";
export type RecurringTemplate = {
  id: string; name: string; narration: string; reference: string | null; frequency: RecurringFrequency; nextDate: string;
  endDate: string | null; active: boolean; amount: string;
  lines: { accountId: string; partyId: string | null; debit: string; credit: string; description: string | null }[];
  version: number;
};
export type GenerateResult = { createdJournalIds: string[]; skipped: string[] };

export type GstSlab = {
  id: string; rate: string; effectiveFrom: string; effectiveTo: string | null; description: string; officialSource: string;
  reviewStatus: "PENDING_REVIEW" | "APPROVED" | "RETIRED"; reviewedBy: string | null; reviewedAt: string | null;
};

export type ExportReport = "TRIAL_BALANCE" | "PROFIT_LOSS" | "BALANCE_SHEET" | "CASH_FLOW" | "LEDGER" | "AGEING_RECEIVABLE"
  | "AGEING_PAYABLE" | "SALES_REGISTER" | "PURCHASE_REGISTER";
