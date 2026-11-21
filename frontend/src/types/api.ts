export type Page<T> = { items: T[]; page: number; size: number; totalItems: number; totalPages: number };

export type Me = {
  id: string;
  email: string;
  fullName: string;
  mustChangePassword: boolean;
  roles: string[];
  permissions: string[];
};

export type TokenResponse = { accessToken: string; expiresIn: number; user: Me };

export type UserRef = { id: string; fullName: string; email: string };

export type User = {
  id: string;
  email: string;
  fullName: string;
  active: boolean;
  mustChangePassword: boolean;
  locked: boolean;
  lastLoginAt: string | null;
  roles: string[];
  createdAt: string;
  version: number;
};

export type Role = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  systemRole: boolean;
  permissions: string[];
  version: number;
};

export type Permission = { code: string; module: string; description: string };

export const CLIENT_STATUSES = [
  "DRAFT",
  "DOCUMENTS_PENDING",
  "UNDER_VERIFICATION",
  "READY_FOR_APPROVAL",
  "ACTIVE",
  "ON_HOLD",
  "CLOSED",
] as const;
export type ClientStatus = (typeof CLIENT_STATUSES)[number];

export type ClientSummary = {
  id: string;
  clientCode: string;
  displayName: string;
  legalName: string;
  entityType: string;
  panMasked: string;
  status: ClientStatus;
  accountant: UserRef | null;
  reviewer: UserRef | null;
  createdAt: string;
};

export type ClientDetail = {
  id: string;
  clientCode: string;
  displayName: string;
  legalName: string;
  entityType: string;
  pan: string;
  panRevealed: boolean;
  tan: string | null;
  dateOfIncorporation: string | null;
  email: string | null;
  phone: string | null;
  fyStartMonth: number;
  accountingMethod: "ACCRUAL" | "CASH";
  status: ClientStatus;
  accountant: UserRef | null;
  reviewer: UserRef | null;
  notes: string | null;
  closureReason: string | null;
  closedAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  allowedTransitions: ClientStatus[];
  counts: { gstRegistrations: number; contacts: number; documents: number; documentsAwaitingReview: number };
};

export type StatusHistoryEntry = {
  id: string;
  fromStatus: ClientStatus | null;
  toStatus: ClientStatus;
  changedBy: UserRef | null;
  comment: string | null;
  changedAt: string;
};

export type Contact = {
  id: string;
  name: string;
  designation: string | null;
  email: string | null;
  phone: string | null;
  primary: boolean;
};

export type Address = {
  id: string;
  addressType: string;
  line1: string;
  line2: string | null;
  city: string;
  stateCode: string;
  stateName: string;
  pincode: string;
};

export type GstRegistration = {
  id: string;
  gstin: string;
  tradeName: string | null;
  stateCode: string;
  stateName: string;
  registrationType: string;
  effectiveDate: string;
  filingFrequency: string;
  principalPlace: string | null;
  status: string;
  cancelledOn: string | null;
};

export type BankAccount = {
  id: string;
  bankName: string;
  branch: string | null;
  accountHolder: string;
  accountNumber: string;
  accountNumberRevealed: boolean;
  ifsc: string;
  accountType: string;
  active: boolean;
};

export type DocumentStatus =
  | "UPLOADED"
  | "DUPLICATE_SUSPECTED"
  | "REVIEW_REQUIRED"
  | "APPROVED"
  | "REJECTED"
  | "ARCHIVED";

export type DocumentItem = {
  id: string;
  clientId: string;
  clientCode: string | null;
  clientName: string | null;
  documentType: string;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  financialYear: string | null;
  status: DocumentStatus;
  scanStatus: "CLEAN" | "NOT_SCANNED";
  duplicateOfId: string | null;
  uploadedBy: UserRef | null;
  uploadedAt: string;
  reviewedBy: UserRef | null;
  reviewedAt: string | null;
  reviewComment: string | null;
  version: number;
  canReview: boolean;
};

export type AuditEntry = {
  id: string;
  occurredAt: string;
  actorUserId: string | null;
  actorEmail: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  clientId: string | null;
  details: Record<string, unknown> | null;
  ipAddress: string | null;
  requestId: string | null;
};

export type DashboardSummary = {
  generatedAt: string;
  totalClients: number;
  activeClients: number;
  clientsAwaitingApproval: number;
  clientsWithoutAccountant: number;
  clientsByStatus: Record<string, number>;
  totalDocuments: number;
  documentsAwaitingReview: number;
  suspectedDuplicates: number;
  documentsUploadedToday: number;
  documentsByStatus: Record<string, number>;
  uploadsLast14Days: { day: string; count: number }[];
  recentActivity: AuditEntry[];
};

export type Option = { value: string; label: string };
export type EntityTypeOption = Option & { panHolderTypes: string[] };
