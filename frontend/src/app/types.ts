export type FmRentalOrderSummary = {
  id: number;
  status: string;
  cancelReason: string | null;
  expiresAt: string | null;
  unitId: number | null;
  unitCode: string | null;
  requestId: number;
  customerName: string;
  customerEmail: string;
  customerPhone: string;
  needsReproposal: boolean;
  needsRepropose: boolean;
  rejectionCount: number;
  maxRejectionCount: number;
  lastProposal: {
    id: number;
    unitCode: string;
    status: string;
    note: string | null;
    createdAt: string;
  } | null;
  /** Thao tác FM được làm trên đơn — BE tính: REPROPOSE, CANCEL. */
  availableActions: string[];
};

export type FmRentalOrderPage = {
  items: FmRentalOrderSummary[];
  page: number;
  size: number;
  total: number;
};

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
  | "fm-shifts"
  | "fm-leaves"
  | "fm-staff-report"
  | "admin-payments"
  | "fs-schedule"
  | "fs-handover"
  | "fs-handovers"
  | "fs-returns"
  | "fs-return-detail"
  | "fs-support"
  | "fs-shifts"
  | "fs-leaves"
  | "handover"
  | "payment-result"
  | "support"
  | "bom-dashboard"
  | "bom-facilities"
  | "bom-staff"
  | "bom-pricing"
  | "bom-invoices"
  | "bom-staff-report"
  | "admin-accounts"
  | "admin-permissions"
  | "admin-login-events"
  | "admin-audit-logs";
export type Navigate = (path: string) => void;
export type NoticeTone = "info" | "success" | "error" | "pending";
export type Notice = { tone: NoticeTone; message: string } | null;
