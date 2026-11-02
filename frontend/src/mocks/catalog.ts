export type UnitType = {
  id: string;
  name: string;
  area: string;
  size: string;
  capacity: string;
  capacityShort: string;
  monthlyPrice: number;
  description: string;
  idealFor: string;
  accent: "sand" | "teal" | "ink";
  image?: string;
};

export type Facility = {
  id: string;
  name: string;
  district: string;
  address: string;
  hours: string;
  note: string;
  availableUnitIds: string[];
  image?: string;
};

export const unitTypes: UnitType[] = [
  {
    id: "basic",
    name: "Kho cơ bản",
    area: "4 m²",
    size: "Thông số mẫu A",
    capacity: "Quy mô nhỏ",
    capacityShort: "A",
    monthlyPrice: 4900000,
    description: "Phương án lưu trữ cho lô hàng quy mô nhỏ hoặc nhu cầu vận hành linh hoạt.",
    idealFor: "Lô hàng nhỏ, nhu cầu thử nghiệm",
    accent: "sand",
  },
  {
    id: "flexible",
    name: "Kho linh hoạt",
    area: "8 m²",
    size: "Thông số mẫu B",
    capacity: "Quy mô vừa",
    capacityShort: "B",
    monthlyPrice: 7900000,
    description:
      "Không gian cân bằng cho hàng hóa cần lưu trữ theo kế hoạch và lịch nhập xuất rõ ràng.",
    idealFor: "Hàng kinh doanh, lô hàng định kỳ",
    accent: "teal",
  },
  {
    id: "extended",
    name: "Kho mở rộng",
    area: "15 m²",
    size: "Thông số mẫu C",
    capacity: "Quy mô lớn",
    capacityShort: "C",
    monthlyPrice: 12900000,
    description:
      "Phương án có thêm dư địa cho khối lượng hàng hóa lớn và kế hoạch vận hành dài hơn.",
    idealFor: "Lô hàng lớn, hoạt động ổn định",
    accent: "ink",
  },
];

export const facilities: Facility[] = [
  {
    id: "thao-dien",
    name: "Kho Mộc Thảo Điền Hub",
    district: "TP. Thủ Đức",
    address: "18 Xuân Thủy, Thảo Điền",
    hours: "Tiếp nhận hàng: 07:00–21:00",
    note: "Thuận tiện điều phối hàng vào khu Đông.",
    availableUnitIds: ["basic", "flexible"],
  },
  {
    id: "tan-binh",
    name: "Kho Mộc Tân Bình Hub",
    district: "Tân Bình",
    address: "42 Cộng Hòa, Phường 4",
    hours: "Tiếp nhận hàng: 07:00–21:00",
    note: "Kết nối nhanh với sân bay và khu trung tâm.",
    availableUnitIds: ["flexible", "extended"],
  },
  {
    id: "quan-7",
    name: "Kho Mộc Quận 7 Hub",
    district: "Quận 7",
    address: "09 Nguyễn Thị Thập, Tân Phú",
    hours: "Tiếp nhận hàng: 08:00–20:00",
    note: "Phù hợp điều phối hàng khu Nam thành phố.",
    availableUnitIds: ["basic", "extended"],
  },
];

export function formatPrice(value: number) {
  return new Intl.NumberFormat("vi-VN").format(value);
}
