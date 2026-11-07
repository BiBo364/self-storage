/**
 * Flow 2 - check-in và bàn giao kho tại cơ sở.
 *
 * <p>Chiều phụ thuộc theo issue #15: handover đọc được từ booking, không có chiều ngược lại.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Handover (F2)",
        allowedDependencies = {"booking::application", "facility::application", "pricing::application",
                "identity::application", "payment", "payment::application", "payment::event", "payment::dto",
                "notification::application", "audit::application"})
package vn.lemar.selfstorage.handover;
