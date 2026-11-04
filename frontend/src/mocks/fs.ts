export type FsAppointmentType = "CHECKIN" | "RETURN";
export type FsAppointmentStatus = "Pending" | "Done" | "Canceled";

export type FsAppointment = {
  id: string;
  type: FsAppointmentType;
  customerName: string;
  customerPhone: string;
  customerEmail: string;
  unitCode: string;
  unitType: string;
  facility: string;
  date: string;
  timeSlot: string;
  status: FsAppointmentStatus;
  orderCode: string;
  monthlyPrice: number;
  depositAmount: number;
  note?: string;
};

export const TODAY = new Date().toISOString().slice(0, 10);

export const fsAppointments: FsAppointment[] = [
  {
    id: "appt-fs-001",
    type: "CHECKIN",
    customerName: "Lê Thị Ngọc Hà",
    customerPhone: "090 123 4567",
    customerEmail: "ha.le@example.com",
    unitCode: "TD-101",
    unitType: "Kho cơ bản · 4 m²",
    facility: "Kho Mộc — Thảo Điền Hub",
    date: TODAY,
    timeSlot: "08:00 – 10:00",
    status: "Pending",
    orderCode: "ORD-2026-014",
    monthlyPrice: 4900000,
    depositAmount: 4900000,
    note: "Khách mang CCCD, đã thanh toán cọc.",
  },
  {
    id: "appt-fs-002",
    type: "CHECKIN",
    customerName: "Phạm Quốc Bảo",
    customerPhone: "091 234 5678",
    customerEmail: "bao.pham@example.com",
    unitCode: "TD-205",
    unitType: "Kho linh hoạt · 8 m²",
    facility: "Kho Mộc — Thảo Điền Hub",
    date: TODAY,
    timeSlot: "13:00 – 15:00",
    status: "Pending",
    orderCode: "ORD-2026-015",
    monthlyPrice: 7900000,
    depositAmount: 7900000,
  },
  {
    id: "appt-fs-003",
    type: "RETURN",
    customerName: "Nguyễn Minh Anh",
    customerPhone: "092 345 6789",
    customerEmail: "anh.nguyen@example.com",
    unitCode: "A-208",
    unitType: "Kho linh hoạt · 6 m²",
    facility: "Kho Mộc — Nguyễn Văn Linh",
    date: TODAY,
    timeSlot: "17:00 – 19:00",
    status: "Pending",
    orderCode: "ORD-2026-009",
    monthlyPrice: 7900000,
    depositAmount: 7900000,
    note: "Khách trả kho, cần kiểm tra hiện trạng.",
  },
  {
    id: "appt-fs-004",
    type: "CHECKIN",
    customerName: "Đặng Thu Hằng",
    customerPhone: "093 456 7890",
    customerEmail: "hang.dang@example.com",
    unitCode: "TD-104",
    unitType: "Kho cơ bản · 4 m²",
    facility: "Kho Mộc — Thảo Điền Hub",
    date: TODAY,
    timeSlot: "08:00 – 10:00",
    status: "Done",
    orderCode: "ORD-2026-012",
    monthlyPrice: 4900000,
    depositAmount: 4900000,
  },
];

export function findAppointment(id: string) {
  return fsAppointments.find((appointment) => appointment.id === id);
}
