export type FmOrderStatus =
  "Pending" | "Deposited" | "Scheduled" | "InProgress" | "Done" | "Canceled";

export type FmOrder = {
  id: string;
  code: string;
  customerName: string;
  customerEmail: string;
  unitCode: string | null;
  unitType: string;
  facility: string;
  status: FmOrderStatus;
  createdAt: string;
  expiresAt: string;
  needsReproposal?: boolean;
  cancelReason?: string;
};

/**
 * Mock cho page theo dõi đơn thuê. `order-fm-701` / `order-fm-703` dùng đúng id
 * trong `mocks/fm.ts` để link sang trang re-propose hiện có.
 */
export const fmOrders: FmOrder[] = [
  {
    id: "order-fm-701",
    code: "ORD-2026-014",
    customerName: "Hoàng Gia Huy",
    customerEmail: "huy.hoang@example.com",
    unitCode: null,
    unitType: "Kho cơ bản · 4 m²",
    facility: "Kho Mộc — Thảo Điền Hub",
    status: "Pending",
    createdAt: "20/09/2026",
    expiresAt: "27/09/2026",
    needsReproposal: true,
  },
  {
    id: "order-fm-703",
    code: "ORD-2026-012",
    customerName: "Đặng Thu Hằng",
    customerEmail: "hang.dang@example.com",
    unitCode: "TD-101",
    unitType: "Kho cơ bản · 4 m²",
    facility: "Kho Mộc — Thảo Điền Hub",
    status: "Deposited",
    createdAt: "22/09/2026",
    expiresAt: "29/09/2026",
    needsReproposal: true,
  },
  {
    id: "order-1003",
    code: "ORD-2026-015",
    customerName: "Phạm Quốc Bảo",
    customerEmail: "bao.pham@example.com",
    unitCode: "TD-205",
    unitType: "Kho linh hoạt · 8 m²",
    facility: "Kho Mộc — Thảo Điền Hub",
    status: "Scheduled",
    createdAt: "23/09/2026",
    expiresAt: "30/09/2026",
  },
  {
    id: "order-1004",
    code: "ORD-2026-011",
    customerName: "Lê Thị Ngọc Hà",
    customerEmail: "ha.le@example.com",
    unitCode: "TD-104",
    unitType: "Kho cơ bản · 4 m²",
    facility: "Kho Mộc — Thảo Điền Hub",
    status: "InProgress",
    createdAt: "18/09/2026",
    expiresAt: "25/09/2026",
  },
  {
    id: "order-1005",
    code: "ORD-2026-009",
    customerName: "Nguyễn Minh Anh",
    customerEmail: "anh.nguyen@example.com",
    unitCode: "A-208",
    unitType: "Kho linh hoạt · 6 m²",
    facility: "Kho Mộc — Nguyễn Văn Linh",
    status: "Done",
    createdAt: "10/09/2026",
    expiresAt: "17/09/2026",
  },
  {
    id: "order-fm-702",
    code: "ORD-2026-007",
    customerName: "Lâm Quốc Việt",
    customerEmail: "viet.lam@example.com",
    unitCode: "TD-207",
    unitType: "Kho linh hoạt · 8 m²",
    facility: "Kho Mộc — Thảo Điền Hub",
    status: "Canceled",
    createdAt: "10/09/2026",
    expiresAt: "17/09/2026",
    cancelReason: "Khách từ chối quá số khoang cho phép.",
  },
];
