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
  used: boolean;
  version: number;
};

export type Period = {
  id: string;
  financialYear: string;
  periodNumber: number;
  startDate: string;
  endDate: string;
  status: "OPEN" | "CLOSED";
  closedBy: UserRef | null;
  closedAt: string | null;
  version: number;
};

export type JournalStatus = "DRAFT" | "PENDING_APPROVAL" | "APPROVED" | "POSTED" | "CANCELLED";
export type JournalType = "GENERAL" | "OPENING" | "ADJUSTMENT" | "REVERSAL";

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

export type ReportHeader = {
  clientId: string;
  clientCode: string;
  clientName: string;
  from: string | null;
  to: string;
  generatedAt: string;
  basis: string;
};

export type TrialBalance = {
  header: ReportHeader;
  rows: { accountId: string; code: string; name: string; accountType: AccountType; groupName: string; debit: string; credit: string }[];
  totalDebit: string;
  totalCredit: string;
  balanced: boolean;
};

export type StatementSection = { title: string; lines: { accountId: string; code: string; name: string; amount: string }[]; total: string };

export type ProfitAndLoss = {
  header: ReportHeader;
  income: StatementSection[];
  totalIncome: string;
  expenses: StatementSection[];
  totalExpenses: string;
  netProfit: string;
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
