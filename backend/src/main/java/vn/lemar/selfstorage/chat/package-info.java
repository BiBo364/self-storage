/**
 * Chatbot tư vấn (Google Gemini): trả lời khách về cơ sở, loại khoang, giá và quy trình thuê kho. Chỉ đọc catalog /
 * policy công khai, không đọc dữ liệu cá nhân của khách.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Chat assistant",
        allowedDependencies = {"facility::application", "pricing::application"})
package vn.lemar.selfstorage.chat;
