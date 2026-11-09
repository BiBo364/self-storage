package vn.lemar.selfstorage.chat.application;

import java.util.List;

/** Cổng gọi mô hình ngôn ngữ — tách khỏi SDK để test bằng mock. */
public interface ChatModelClient {

    /** Một lượt hội thoại. {@code role}: {@code user} hoặc {@code assistant}. */
    record Turn(String role, String content) {
    }

    /** Kết quả: {@code refused} khi mô hình từ chối vì lý do an toàn. */
    record Reply(String text, boolean refused) {
    }

    /** Đã cấu hình khoá API chưa. */
    boolean available();

    Reply complete(String systemPrompt, List<Turn> turns);
}
