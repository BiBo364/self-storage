export type View =
  | "home"
  | "units"
  | "login"
  | "register"
  | "verify"
  | "request"
  | "my-storage"
  | "contract-detail"
  | "proposals"
  | "invoices"
  | "appointments"
  | "notifications"
  | "fm-rental-requests"
  | "fm-proposal-redo"
  | "fm-appointments"
  | "fm-return-requests"
  | "fm-extend-requests"
  | "fm-support-requests"
  | "fm-storage-units"
  | "fm-staff"
  | "fm-reports"
  | "fm-invoices"
  | "fm-contracts"
  | "fm-orders"
  | "fs-schedule"
  | "fs-handover";
export type Navigate = (path: string) => void;
export type NoticeTone = "info" | "success" | "error" | "pending";
export type Notice = { tone: NoticeTone; message: string } | null;

export type FacilitySummary = {
  id: number;
  code: string;
  name: string;
  address: string | null;
  operatingHours: string | null;
  unitTypeIds: number[];
};

export type UnitTypeSummary = {
  id: number;
  name: string;
  width: number;
  depth: number;
  height: number;
  area: number;
  monthlyPrice: number;
};

export type CreateRentalRequestInput = {
  facilityId: number;
  customerName: string;
  customerEmail: string;
  customerPhone: string;
  unitTypeId: number;
  startDate: string;
  period: number;
};

export type RentalRequestStatus = "Pending" | "Rejected" | "Approved" | "Converted" | "Expired";

export type RejectReason = "UNIT_UNAVAILABLE" | "UNIT_MISMATCH" | "CUSTOMER_REQUEST" | "OTHER";

export type RentalRequestNextAction =
  "APPROVE_REQUEST" | "WAIT_CUSTOMER" | "REPROPOSE_UNIT" | "VIEW";

export type RentalRequestSummary = {
  id: number;
  customerName: string;
  customerEmail: string;
  customerPhone: string;
  unitTypeId: number;
  startDate: string;
  period: number;
  status: RentalRequestStatus;
  createdAt: string;
  expiresAt: string | null;
  orderId: number | null;
  orderStatus: string | null;
  nextAction: RentalRequestNextAction;
  lastProposal: {
    id: number;
    unitCode: string;
    status: ProposalStatus;
    note: string | null;
    createdAt: string;
  } | null;
  rejectionCount: number;
  maxRejectionCount: number;
};

export type RentalRequestPage = {
  items: RentalRequestSummary[];
  page: number;
  size: number;
  total: number;
};

export type ApproveRentalRequestResult = {
  id: number;
  status: RentalRequestStatus;
  unitId: number;
  respondedAt: string;
  expiresAt: string;
};

export type RejectRentalRequestResult = {
  id: number;
  status: RentalRequestStatus;
  rejectReason: RejectReason;
  respondedAt: string;
};

export type ManagedFacility = {
  id: number;
  code: string;
  name: string;
  address: string | null;
  operatingHours: string | null;
  status: string;
};

export type StorageUnitSummary = {
  id: number;
  code: string;
  facilityId: number;
  unitTypeId: number;
  unitTypeName: string;
  size: string | null;
  area: number;
  status: string;
  monthlyPrice: number;
};

export type InvoiceType =
  "Deposit" | "DepositSupplement" | "Rental" | "Extension" | "Penalty" | "Service";

export type InvoiceStatus = "Unpaid" | "Paid" | "Canceled" | "Expired";

export type InvoiceSummary = {
  id: number;
  orderId: number | null;
  code: string;
  title: string;
  description: string | null;
  type: InvoiceType;
  status: InvoiceStatus;
  amount: number;
  createdAt: string;
  dueDate: string | null;
  unitId: number | null;
  unitCode: string | null;
  facilityId: number | null;
  facilityName: string | null;
};

export type ProposalStatus = "Pending" | "Agreed" | "Rejected" | "Canceled";

export type CustomerProposal = {
  id: number;
  orderId: number;
  status: ProposalStatus;
  note: string | null;
  createdAt: string;
  unitId: number;
  unitCode: string;
  unitSize: string | null;
  unitTypeName: string;
  facilityId: number;
  facilityName: string;
  facilityAddress: string | null;
  monthlyPrice: number;
  rejectionCount: number;
  maxRejectionCount: number;
};

export type ProposalSnapshot = {
  id: number;
  orderId: number;
  customerId: number;
  unitId: number;
  status: ProposalStatus;
  note: string | null;
  createdAt: string;
};

export type DepositInvoice = {
  id: number;
  orderId: number | null;
  code: string;
  title: string;
  description: string | null;
  type: string;
  status: string;
  amount: number;
  dueDate: string | null;
};

export type AgreeProposalResult = {
  proposal: ProposalSnapshot;
  invoice: DepositInvoice | null;
};

export type RejectProposalResult = {
  proposal: ProposalSnapshot;
  orderCanceled: boolean;
};

export type FmRentalOrder = {
  id: number;
  status: string;
  cancelReason: string | null;
  expiresAt: string | null;
  unitId: number | null;
  facilityId: number;
  requestId: number;
  requestStatus: string;
  needsReproposal: boolean;
  customer: { accountId: number; name: string; email: string; phone: string };
  requested: { unitTypeId: number; unitTypeName: string; startDate: string; period: number };
  rejectionCount: number;
  maxRejectionCount: number;
  proposals: {
    id: number;
    unitId: number;
    unitCode: string;
    status: ProposalStatus;
    note: string | null;
    createdAt: string;
  }[];
  availableUnits: {
    id: number;
    code: string;
    size: string | null;
    monthlyPrice: number;
    previouslyRejected: boolean;
  }[];
};
