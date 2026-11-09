package vn.lemar.selfstorage.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.chat.application.ChatModelClient.Reply;
import vn.lemar.selfstorage.chat.application.ChatModelClient.Turn;
import vn.lemar.selfstorage.chat.application.ChatService.ChatException;
import vn.lemar.selfstorage.facility.application.FacilityCatalogQuery;
import vn.lemar.selfstorage.facility.application.FacilityCatalogView;
import vn.lemar.selfstorage.pricing.application.PricingAdminCommands;
import vn.lemar.selfstorage.pricing.application.PricingAdminCommands.PolicyRow;
import vn.lemar.selfstorage.pricing.application.PricingAdminCommands.UnitTypeRow;

/** Chatbot tư vấn: kiểm đầu vào, giới hạn tần suất, từ chối / lỗi mô hình, nội dung system prompt. */
class ChatServiceTest {

    private final ChatModelClient model = mock(ChatModelClient.class);
    private final FacilityCatalogQuery facilities = mock(FacilityCatalogQuery.class);
    private final PricingAdminCommands pricing = mock(PricingAdminCommands.class);
    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(model, facilities, pricing);
        when(model.available()).thenReturn(true);
        when(facilities.listActive()).thenReturn(List.of(
                new FacilityCatalogView(1L, "Q7", "Kho Quận 7", "1 Nguyễn Văn Linh", "7:00–21:00", List.of(1L)),
                new FacilityCatalogView(2L, "TD", "Kho Thủ Đức", null, null, List.of(9L))));
        when(pricing.unitTypes()).thenReturn(List.of(new UnitTypeRow(1L, "Small", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.TEN, new BigDecimal("1.00"), "Vali, thùng nhỏ", new BigDecimal("500000")),
                new UnitTypeRow(2L, "Large", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, null,
                        new BigDecimal("2000000"))));
        when(pricing.policies()).thenReturn(List.of(
                new PolicyRow(1L, "overdue.fee_per_day", "20000", "Phí quá hạn", Instant.now()),
                new PolicyRow(2L, "fee.cleaning", "100000", null, Instant.now()),
                new PolicyRow(3L, "account.claim_ttl_days", "7", "nội bộ", Instant.now())));
    }

    @Test
    void returnsModelReplyWithCatalogInSystemPrompt() {
        when(model.complete(anyString(), anyList())).thenAnswer(inv -> {
            String system = inv.getArgument(0);
            assertThat(system).contains("Kho Quận 7").contains("1 Nguyễn Văn Linh").contains("7:00–21:00")
                    .contains("Small").contains("500.000 đ/tháng").contains("Vali, thùng nhỏ")
                    .contains("overdue.fee_per_day = 20000 (Phí quá hạn)").contains("fee.cleaning = 100000")
                    .doesNotContain("account.claim_ttl_days");
            return new Reply("Khoang Small giá 500.000 đ/tháng.", false);
        });
        Reply reply = service.reply(List.of(new Turn("user", "Giá khoang nhỏ?"),
                new Turn("assistant", "Bạn cần cơ sở nào?"), new Turn("user", "Quận 7")), "1.1.1.1");
        assertThat(reply.text()).contains("500.000");
        assertThat(reply.refused()).isFalse();
    }

    @Test
    void refusalAndEmptyRepliesBecomeAPoliteMessage() {
        when(model.complete(anyString(), anyList())).thenReturn(new Reply("", true));
        assertThat(service.reply(List.of(new Turn("user", "x")), "2.2.2.2").refused()).isTrue();
        when(model.complete(anyString(), anyList())).thenReturn(new Reply(" ", false));
        Reply blank = service.reply(List.of(new Turn("user", "x")), "2.2.2.2");
        assertThat(blank.text()).isEqualTo(ChatService.REFUSAL_TEXT);
        assertThat(blank.refused()).isFalse();
    }

    @Test
    void invalidConversationsAreRejected() {
        assertStatus(() -> service.reply(List.of(), "k"), 400);
        assertStatus(() -> service.reply(null, "k"), 400);
        assertStatus(() -> service.reply(java.util.Collections.nCopies(21, new Turn("user", "x")), "k"), 400);
        assertStatus(() -> service.reply(List.of(new Turn("user", " ")), "k"), 400);
        assertStatus(() -> service.reply(List.of(new Turn("user", "x".repeat(2001))), "k"), 400);
        assertStatus(() -> service.reply(List.of(new Turn("system", "x")), "k"), 400);
        assertStatus(() -> service.reply(List.of(new Turn("user", "x"), new Turn("assistant", "y")), "k"), 400);
        assertStatus(() -> service.reply(List.of(new Turn("user", null)), "k"), 400);
    }

    @Test
    void unavailableModelErrorsAndRateLimitMapToStatus() {
        when(model.available()).thenReturn(false);
        assertStatus(() -> service.reply(List.of(new Turn("user", "x")), "k"), 503);

        when(model.available()).thenReturn(true);
        when(model.complete(anyString(), anyList())).thenThrow(new IllegalStateException("network"));
        assertStatus(() -> service.reply(List.of(new Turn("user", "x")), "err"), 502);

        doReturn(new Reply("ok", false)).when(model).complete(anyString(), anyList());
        for (int i = 0; i < 30; i++) {
            service.reply(List.of(new Turn("user", "x")), "flood");
        }
        assertStatus(() -> service.reply(List.of(new Turn("user", "x")), "flood"), 429);
        assertThat(service.reply(List.of(new Turn("user", "x")), null).text()).isEqualTo("ok");
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ChatException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }
}
