package vn.lemar.selfstorage.notification.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEmailLayoutTest {

    @Test
    void wrapsTitleAndBodyWithBrandAndEscapesHtml() {
        String html = NotificationEmailLayout.wrap("Yêu cầu thuê kho đã được duyệt",
                "Khoang A-01 <script>alert(1)</script> đã sẵn sàng.\n\nXác nhận tại https://lemar.vn/p/1");

        assertThat(html).contains("<title>Thông báo từ Kho Mộc</title>");
        assertThat(html).contains("KHO MỘC");
        assertThat(html).contains("Yêu cầu thuê kho đã được duyệt");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("https://lemar.vn/p/1");
        assertThat(html.split("<p ", -1).length - 1).isEqualTo(2);
    }

    @Test
    void handlesNullBody() {
        String html = NotificationEmailLayout.wrap("Tiêu đề", null);

        assertThat(html).contains("Tiêu đề");
        assertThat(html).contains("KHO MỘC");
    }
}
