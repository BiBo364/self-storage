package vn.lemar.selfstorage.facility.application;

/**
 * Vị trí khoang trong cơ sở (V34): tầng + bên lối đi nhìn từ cửa vào + thứ tự tính từ cửa vào (1 = sát cửa).
 *
 * @param floorNo       tầng, 1..20 (và không quá {@code facilities.floor_count} — caller kiểm)
 * @param aisleSide     {@code Left} / {@code Right}
 * @param aislePosition 1..200
 */
public record UnitLocation(int floorNo, String aisleSide, int aislePosition) {

    public static final String LEFT = "Left";
    public static final String RIGHT = "Right";
    public static final int MAX_FLOORS = 20;
    public static final int MAX_POSITION = 200;

    public UnitLocation {
        if (floorNo < 1 || floorNo > MAX_FLOORS) {
            throw new IllegalArgumentException("Tầng phải từ 1 đến " + MAX_FLOORS);
        }
        if (!LEFT.equals(aisleSide) && !RIGHT.equals(aisleSide)) {
            throw new IllegalArgumentException("Bên lối đi phải là Left hoặc Right");
        }
        if (aislePosition < 1 || aislePosition > MAX_POSITION) {
            throw new IllegalArgumentException("Thứ tự từ cửa vào phải từ 1 đến " + MAX_POSITION);
        }
    }

    /**
     * Ba giá trị cùng {@code null} → {@code null} (chưa xếp vị trí); thiếu một phần → 400.
     *
     * @throws IllegalArgumentException chỉ nhập một phần hoặc giá trị ngoài miền
     */
    public static UnitLocation of(Integer floorNo, String aisleSide, Integer aislePosition) {
        boolean none = floorNo == null && (aisleSide == null || aisleSide.isBlank()) && aislePosition == null;
        if (none) {
            return null;
        }
        if (floorNo == null || aisleSide == null || aisleSide.isBlank() || aislePosition == null) {
            throw new IllegalArgumentException("Vị trí khoang cần đủ tầng, bên lối đi và thứ tự từ cửa vào");
        }
        return new UnitLocation(floorNo, aisleSide.trim(), aislePosition);
    }

    /** Ví dụ "Tầng 2 · bên trái · vị trí 3" — dùng trong thông báo lỗi trùng vị trí. */
    public String label() {
        return "Tầng " + floorNo + " · " + (LEFT.equals(aisleSide) ? "bên trái" : "bên phải") + " · vị trí "
                + aislePosition;
    }
}
