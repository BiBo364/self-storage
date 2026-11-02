package vn.lemar.selfstorage.booking.application.exception;

/** Filter danh sách khoang không hợp lệ — 400. */
public class StorageUnitFilterException extends RuntimeException {

    public StorageUnitFilterException(String message) {
        super(message);
    }
}
