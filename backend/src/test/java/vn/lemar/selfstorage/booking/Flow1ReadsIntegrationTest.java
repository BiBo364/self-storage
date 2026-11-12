package vn.lemar.selfstorage.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.identity.application.JwtService;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

/**
 * Bốn read model Flow 1 chạy thật trên Postgres + seed: invoice list, catalog cơ sở public, cơ sở
 * và khoang của FM. Kiểm cả ownership, facility scope và các nhánh lỗi 400/403/404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@TestPropertySource(properties = {
        "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class Flow1ReadsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private AccountRepository accountRepository;

    private long q7;
    private long td;
    private long customer1;
    private long fm1;
    private long fm2;
    private long smallType;
    private long mediumType;
    private long unitA01;
    private long unitC01;

    @BeforeEach
    void loadSeed() {
        q7 = facilityId("Q7");
        td = facilityId("TD");
        customer1 = accountId("customer1@lemar.vn");
        fm1 = accountId("fm1@lemar.vn");
        fm2 = accountId("fm2@lemar.vn");
        smallType = unitTypeId("Small");
        mediumType = unitTypeId("Medium");
        unitA01 = unitId("A-01");
        unitC01 = unitId("C-01");
    }

    // ------------------------------------------------------------------ invoice list

    @Test
    void invoiceListReturnsOwnInvoicesNewestFirstWithUnitAndFacility() throws Exception {
        long depositOrder = insertOrder(customer1, unitA01, "Deposited");
        long pendingOrder = insertOrder(customer1, null, "Pending");
        insertInvoice(depositOrder, customer1, "INV-DEPOSIT", "Đặt cọc", "Deposit", "Unpaid", "500000",
                "2 days");
        insertInvoice(pendingOrder, customer1, "INV-RENTAL", "Tiền thuê", "Rental", "Unpaid", "900000",
                "1 day");
        insertInvoice(depositOrder, fm1, "INV-OTHER", "Hoá đơn người khác", "Rental", "Paid", "100000",
                "0 days");

        mockMvc.perform(get("/api/invoices").header(HttpHeaders.AUTHORIZATION, bearerOf("customer1@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Lấy danh sách hoá đơn thành công"))
                .andExpect(jsonPath("$.data.length()").value(2))
                // mới nhất trước
                .andExpect(jsonPath("$.data[0].code").value("INV-RENTAL"))
                .andExpect(jsonPath("$.data[0].unitCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].facilityName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].customerId").doesNotExist())
                .andExpect(jsonPath("$.data[1].code").value("INV-DEPOSIT"))
                .andExpect(jsonPath("$.data[1].unitCode").value("A-01"))
                .andExpect(jsonPath("$.data[1].facilityName").value("Self Storage Quận 7"))
                .andExpect(jsonPath("$.data[1].facilityId").value(q7));
    }

    @Test
    void invoiceListRejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/invoices"))
                .andExpect(status().isUnauthorized());
    }

    // --------------------------------------------------------------- FM invoice list

    @Test
    void fmInvoiceListReturnsFacilityInvoicesAndHighlightsStalePending() throws Exception {
        long order = insertOrder(customer1, unitA01, "Deposited");
        insertInvoice(order, customer1, "INV-FM-PENDING", "Đặt cọc", "Deposit", "Unpaid", "500000",
                "20 minutes");
        long invoiceId = invoiceId("INV-FM-PENDING");
        jdbc.update("""
                insert into payment_transactions (invoice_id, vnp_txn_ref, amount, direction, status, created_at)
                values (?, 'TXN-FM-PENDING', 500000, 'PAY', 'Pending', now() - interval '30 minutes')
                """, invoiceId);

        mockMvc.perform(get("/api/fm/invoices")
                        .param("facilityId", String.valueOf(q7))
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Lấy danh sách hoá đơn cơ sở thành công"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("INV-FM-PENDING"))
                .andExpect(jsonPath("$.data.items[0].customerName").value("Khách test"))
                .andExpect(jsonPath("$.data.items[0].unitCode").value("A-01"))
                .andExpect(jsonPath("$.data.items[0].paymentStatus").value("Pending"))
                .andExpect(jsonPath("$.data.items[0].needsReconciliation").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].pendingAgeMinutes")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(15)));
    }

    @Test
    void fmInvoiceListFiltersByPaymentStatusAndAge() throws Exception {
        long order = insertOrder(customer1, unitA01, "Deposited");
        insertInvoice(order, customer1, "INV-FM-FILTER", "Đặt cọc", "Deposit", "Unpaid", "500000",
                "1 minute");
        long invoiceId = invoiceId("INV-FM-FILTER");
        jdbc.update("""
                insert into payment_transactions (invoice_id, vnp_txn_ref, amount, direction, status, created_at)
                values (?, 'TXN-FM-FILTER', 500000, 'PAY', 'Pending', now() - interval '1 minute')
                """, invoiceId);

        mockMvc.perform(get("/api/fm/invoices")
                        .param("facilityId", String.valueOf(q7))
                        .param("paymentStatus", "Pending")
                        .param("olderThanMinutes", "15")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void fmInvoiceListPaginatesAndIncludesContractInvoices() throws Exception {
        long order3 = insertOrder(customer1, unitA01, "Deposited");
        insertInvoice(order3, customer1, "INV-FM-PAGE-3", "Đặt cọc 3", "Deposit", "Unpaid", "500000",
                "3 minutes");
        long order2 = insertOrder(customer1, unitA01, "Deposited");
        insertInvoice(order2, customer1, "INV-FM-PAGE-2", "Đặt cọc 2", "Deposit", "Unpaid", "500000",
                "2 minutes");
        long order1 = insertOrder(customer1, unitA01, "Deposited");
        insertInvoice(order1, customer1, "INV-FM-PAGE-1", "Đặt cọc 1", "Deposit", "Unpaid", "500000",
                "1 minute");
        long contractOrder = insertOrder(customer1, unitA01, "Deposited");
        long contractId = jdbc.queryForObject("""
                insert into rental_contracts (order_id, customer_id, unit_id, code, terms_version,
                    monthly_price, deposit_amount, period, start_date, status)
                values (?, 6, ?, 'CT-FM-1', 'v1', 500000, 500000, 6, current_date, 'Active')
                returning id
                """, Long.class, contractOrder, unitA01);
        jdbc.update("""
                insert into invoices (contract_id, customer_id, code, title, type, status, amount,
                    created_at, due_date)
                values (?, 6, 'INV-FM-CONTRACT', 'Tiền thuê hợp đồng', 'Rental', 'Unpaid', 500000,
                    now() - interval '4 minutes', now() + interval '2 days')
                """, contractId);

        mockMvc.perform(get("/api/fm/invoices")
                        .param("facilityId", String.valueOf(q7))
                        .param("page", "0")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.totalPages").value(2));

        mockMvc.perform(get("/api/fm/invoices")
                        .param("facilityId", String.valueOf(q7))
                        .param("page", "1")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[1].code").value("INV-FM-CONTRACT"));

        mockMvc.perform(get("/api/fm/invoices")
                        .param("facilityId", String.valueOf(q7))
                        .param("type", "Rental")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("INV-FM-CONTRACT"));
    }

    @Test
    void fmInvoiceListRejectsCrossFacilityAndCustomer() throws Exception {
        mockMvc.perform(get("/api/fm/invoices")
                        .param("facilityId", String.valueOf(td))
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/fm/invoices")
                        .param("facilityId", String.valueOf(q7))
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("customer1@lemar.vn")))
                .andExpect(status().isForbidden());
    }

    @Test
    void fmInvoiceListRejectsInvalidFilters() throws Exception {
        mockMvc.perform(get("/api/fm/invoices")
                        .param("facilityId", String.valueOf(q7))
                        .param("status", "Broken")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Trạng thái hoá đơn không hợp lệ: Broken"));

        mockMvc.perform(get("/api/fm/invoices")
                        .param("facilityId", String.valueOf(q7))
                        .param("paymentStatus", "Broken")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Trạng thái thanh toán không hợp lệ: Broken"));
    }

    // ------------------------------------------------------------ public facilities

    @Test
    void publicFacilitiesReturnsActiveFacilitiesWithUnitTypes() throws Exception {
        mockMvc.perform(get("/api/facilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Lấy danh sách cơ sở thành công"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].code").value("Q7"))
                .andExpect(jsonPath("$.data[0].unitTypeIds.length()").value(3))
                .andExpect(jsonPath("$.data[1].code").value("TD"))
                .andExpect(jsonPath("$.data[1].unitTypeIds.length()").value(2))
                .andExpect(jsonPath("$.data[0].fm_account_id").doesNotExist());
    }

    @Test
    void publicLayoutShowsSeededFloorsWithoutUnitStatusAndMapPin() throws Exception {
        mockMvc.perform(get("/api/facilities"))
                .andExpect(jsonPath("$.data[0].latitude").value(10.72965))
                .andExpect(jsonPath("$.data[0].floorCount").value(2));
        // Public, không cần token; không lộ trạng thái khoang.
        mockMvc.perform(get("/api/facilities/" + q7 + "/layout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Sơ đồ cơ sở"))
                .andExpect(jsonPath("$.data.floorCount").value(2))
                .andExpect(jsonPath("$.data.units.length()").value(8))
                .andExpect(jsonPath("$.data.units[0].code").value("A-01"))
                .andExpect(jsonPath("$.data.units[0].floorNo").value(1))
                .andExpect(jsonPath("$.data.units[0].aisleSide").value("Left"))
                .andExpect(jsonPath("$.data.units[0].aislePosition").value(1))
                .andExpect(jsonPath("$.data.units[0].status").doesNotExist());
        mockMvc.perform(get("/api/facilities/-1/layout"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy cơ sở"));
    }

    @Test
    void publicFacilitiesHidesInactiveFacility() throws Exception {
        jdbc.update("update facilities set status = 'Inactive' where id = ?", td);

        mockMvc.perform(get("/api/facilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("Q7"));
    }

    // ---------------------------------------------------------------- FM facility

    @Test
    void fmFacilityReturnsManagedFacility() throws Exception {
        mockMvc.perform(get("/api/fm/facility")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Lấy cơ sở của FM thành công"))
                .andExpect(jsonPath("$.data.id").value(q7))
                .andExpect(jsonPath("$.data.code").value("Q7"));
    }

    @Test
    void fmFacilityReturns404WhenAccountHasNoFacility() throws Exception {
        jdbc.update("update facilities set fm_account_id = null where id = ?", td);

        mockMvc.perform(get("/api/fm/facility")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm2@lemar.vn")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Tài khoản FM chưa được gán cơ sở"));
    }

    @Test
    void customerCannotReadFmFacility() throws Exception {
        mockMvc.perform(get("/api/fm/facility")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("customer1@lemar.vn")))
                .andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------- FM storage units

    @Test
    void storageUnitsReturnsFacilityUnitsWithEffectivePrice() throws Exception {
        mockMvc.perform(get("/api/fm/storage-units")
                        .param("facilityId", String.valueOf(q7))
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Lấy danh sách khoang thành công"))
                .andExpect(jsonPath("$.data.length()").value(8))
                .andExpect(jsonPath("$.data[0].code").value("A-01"))
                .andExpect(jsonPath("$.data[0].unitTypeName").value("Small"))
                .andExpect(jsonPath("$.data[0].area").value(2.0))
                .andExpect(jsonPath("$.data[0].status").value("Available"))
                .andExpect(jsonPath("$.data[0].monthlyPrice").value(500000.0));
    }

    @Test
    void storageUnitsFilterByStatusAndType() throws Exception {
        mockMvc.perform(get("/api/fm/storage-units")
                        .param("facilityId", String.valueOf(q7))
                        .param("status", "Available")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(7));

        mockMvc.perform(get("/api/fm/storage-units")
                        .param("facilityId", String.valueOf(q7))
                        .param("unitTypeId", String.valueOf(mediumType))
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        mockMvc.perform(get("/api/fm/storage-units")
                        .param("facilityId", String.valueOf(q7))
                        .param("status", "Maintenance")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(unitC01));
    }

    @Test
    void storageUnitsRejectInvalidStatus() throws Exception {
        mockMvc.perform(get("/api/fm/storage-units")
                        .param("facilityId", String.valueOf(q7))
                        .param("status", "Broken")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Trạng thái khoang không hợp lệ: Broken"));
    }

    @Test
    void storageUnitsRejectCrossFacility() throws Exception {
        mockMvc.perform(get("/api/fm/storage-units")
                        .param("facilityId", String.valueOf(td))
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Bạn không được gán vào cơ sở này"));
    }

    @Test
    void storageUnitsReturn404ForUnknownFacility() throws Exception {
        mockMvc.perform(get("/api/fm/storage-units")
                        .param("facilityId", "99999")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------- helpers

    private String bearerOf(String email) {
        Account account = accountRepository.findByEmail(email).orElseThrow();
        return "Bearer " + jwtService.issueAccessToken(account);
    }

    private long facilityId(String code) {
        return jdbc.queryForObject("select id from facilities where code = ?", Long.class, code);
    }

    private long accountId(String email) {
        return jdbc.queryForObject("select id from accounts where email = ?", Long.class, email);
    }

    private long unitTypeId(String name) {
        return jdbc.queryForObject("select id from unit_types where name = ?", Long.class, name);
    }

    private long unitId(String code) {
        return jdbc.queryForObject("select id from storage_units where code = ?", Long.class, code);
    }

    private long invoiceId(String code) {
        return jdbc.queryForObject("select id from invoices where code = ?", Long.class, code);
    }

    private long insertOrder(long customerId, Long unitId, String status) {
        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                    customer_phone, unit_type_id, start_date, period, status)
                values (?, 'Khách test', ?, '0901234567', ?, current_date, 6, 'Converted')
                returning id
                """, Long.class, q7, "customer1@lemar.vn", smallType);
        return jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status)
                values (?, ?, ?, ?) returning id
                """, Long.class, requestId, customerId, unitId, status);
    }

    private void insertInvoice(long orderId, long customerId, String code, String title, String type,
                               String status, String amount, String createdAtOffset) {
        jdbc.update("""
                insert into invoices (order_id, customer_id, code, title, type, status, amount, created_at, due_date)
                values (?, ?, ?, ?, ?, ?, ?, now() - cast(? as interval), now() + interval '1 day')
                """, orderId, customerId, code, title, type, status, new java.math.BigDecimal(amount),
                createdAtOffset);
    }
}
