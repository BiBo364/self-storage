package vn.lemar.selfstorage.booking.application.exception;

public class FacilityNotFoundException extends RuntimeException {

    public FacilityNotFoundException() {
        super("Không tìm thấy cơ sở");
    }

    public FacilityNotFoundException(String message) {
        super(message);
    }
}
