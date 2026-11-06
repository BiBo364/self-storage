package vn.lemar.selfstorage.booking.application.exception;

/** Hóa đơn không tồn tại, hoặc tồn tại nhưng không thuộc về người gọi — cùng một câu trả lời. */
public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException() {
        super("Không tìm thấy hóa đơn");
    }
}
