package vn.lemar.selfstorage.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.backoffice.application.BackofficeService;
import vn.lemar.selfstorage.backoffice.application.BackofficeService.Report;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.FacilityRow;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.UnitRow;
import vn.lemar.selfstorage.facility.application.StorageUnitListQuery;
import vn.lemar.selfstorage.identity.application.AccountAdminService;
import vn.lemar.selfstorage.identity.application.AccountAdminService.AccountRow;
import vn.lemar.selfstorage.identity.application.AuthService;
import vn.lemar.selfstorage.identity.application.dto.LoginRequest;
import vn.lemar.selfstorage.payment.application.PaymentGateway;
import vn.lemar.selfstorage.support.application.SupportService;
import vn.lemar.selfstorage.support.application.SupportService.CreateInput;
import vn.lemar.selfstorage.support.application.SupportService.ResolveInput;
import vn.lemar.selfstorage.support.application.SupportService.SupportView;
import vn.lemar.selfstorage.tenancy.application.TenancyException;

/** Hỗ trợ / sự cố + back-office (FM, BOM, Admin) chạy thật trên Postgres. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SupportBackofficeIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String CUSTOMER = "customer1@lemar.vn";
    private static final String FS = "fs1@lemar.vn";
    private static final String FM = "fm1@lemar.vn";
    private static final String BOM = "bom1@lemar.vn";
    private static final String ADMIN = "admin@lemar.vn";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private PaymentGateway gateway;
    @MockBean
    private JavaMailSender mailSender;

    @Autowired
    private SupportService support;
    @Autowired
    private BackofficeService backoffice;
    @Autowired
    private BackofficeReadRepository readRepository;
    @Autowired
    private AccountAdminService accounts;
    @Autowired
    private AuthService authService;
    @Autowired
    private StorageUnitListQuery unitList;
    @Autowired
    private JdbcTemplate jdbc;

    private long customerId;
    private long facilityId;
    private long fsId;

    @BeforeEach
    void setUp() {
        customerId = id("select id from accounts where email = '" + CUSTOMER + "'");
        fsId = id("select id from accounts where email = '" + FS + "'");
        facilityId = id("select id from facilities where code = 'Q7'");
        when(mailSender.createMimeMessage()).thenReturn(
                new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties())));
        when(gateway.newTxnRef(any())).thenCallRealMethod();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ support

    @Test
    void lostKeyRequestIsAssignedAndResolvedWithNewKeyAndFee() {
        long contractId = seedActiveContract();
        assertThatThrownBy(() -> support.create(customerId, new CreateInput(null, null, "OTHER", "x", "y", null)))
                .isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> support.create(customerId, new CreateInput(null, facilityId, "LOST_KEY", "x", "y",
                null))).isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> support.create(customerId, new CreateInput(null, facilityId, "MAGIC", "x", "y",
                null))).isInstanceOf(TenancyException.class);

        SupportView created = support.create(customerId, new CreateInput(contractId, null, "LOST_KEY", "Mất chìa",
                "Tôi làm rơi chìa khoá", List.of("https://img.test/k.jpg")));
        assertThat(created.status()).isEqualTo("Open");
        assertThat(created.facilityId()).isEqualTo(facilityId);
        assertThat(created.unitCode()).isNotBlank();
        assertThat(notifications(FM, "SUPPORT_REQUEST_CREATED")).isPositive();
        assertThat(support.mine(customerId)).extracting(SupportView::id).contains(created.id());

        loginAs(FM);
        assertThat(support.facilityRequests(facilityId, false)).extracting(SupportView::id).contains(created.id());
        assertThatThrownBy(() -> support.assign(created.id(), customerId)).isInstanceOf(TenancyException.class);
        SupportView assigned = support.assign(created.id(), fsId);
        assertThat(assigned.status()).isEqualTo("Assigned");
        assertThat(assigned.assignedStaffName()).isNotBlank();

        loginAs(FS);
        assertThat(support.staffRequests()).extracting(SupportView::id).contains(created.id());
        assertThatThrownBy(() -> support.resolve(created.id(), new ResolveInput(" ", null, null)))
                .isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> support.resolve(created.id(), new ResolveInput("Phí lẻ", null,
                new BigDecimal("50000.75")))).isInstanceOf(TenancyException.class)
                .hasMessageContaining("số nguyên");
        SupportView resolved = support.resolve(created.id(), new ResolveInput("Đã giao 2 chìa mới", 2,
                new BigDecimal("50000")));
        assertThat(resolved.status()).isEqualTo("Resolved");
        assertThat(resolved.feeInvoice().amount()).isEqualByComparingTo("50000");
        assertThat(resolved.feeInvoice().code()).startsWith("INV-SVC-Q7-");
        assertThat(count("select count(*) from unit_access_keys where contract_id = ? and status = 'Lost'",
                contractId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select quantity from unit_access_keys where contract_id = ? "
                + "and status = 'Active' and access_type = 'PhysicalKey'", Integer.class, contractId)).isEqualTo(2);
        assertThat(notifications(CUSTOMER, "SUPPORT_RESOLVED")).isPositive();
        assertThatThrownBy(() -> support.resolve(created.id(), new ResolveInput("lại", null, null)))
                .isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> support.cancel(created.id(), customerId)).isInstanceOf(TenancyException.class);

        loginAs(FM);
        assertThat(support.facilityRequests(facilityId, true)).extracting(SupportView::id).contains(created.id());
    }

    @Test
    void lostKeyReplacementChargesDefaultFeePerLostKeyUnlessAmountGiven() {
        BigDecimal perKey = new BigDecimal(jdbc.queryForObject("select value from policies where key = 'fee.lost_key'",
                String.class));
        long contractId = seedActiveContract();
        SupportView first = support.create(customerId, new CreateInput(contractId, null, "LOST_KEY", "Mất chìa",
                "Rơi cả bộ chìa", null));
        loginAs(FM);
        support.assign(first.id(), fsId);
        loginAs(FS);
        // Không nhập phí: mặc định fee.lost_key × 2 chìa của bộ cũ (bộ cũ chuyển Lost).
        SupportView charged = support.resolve(first.id(), new ResolveInput("Thay khoá, giao 3 chìa mới", 3, null));
        assertThat(charged.feeInvoice()).isNotNull();
        assertThat(charged.feeInvoice().amount()).isEqualByComparingTo(perKey.multiply(BigDecimal.valueOf(2)));
        assertThat(charged.feeInvoice().code()).startsWith("INV-SVC-Q7-");

        // Nhập phí rõ ràng (kể cả 0) thì dùng đúng số đó: bộ hiện tại 3 chìa nhưng phí 0 → không lập hoá đơn.
        SupportView second = support.create(customerId, new CreateInput(contractId, null, "LOST_KEY", "Mất chìa",
                "Lại rơi chìa", null));
        loginAs(FM);
        support.assign(second.id(), fsId);
        loginAs(FS);
        assertThat(support.resolve(second.id(), new ResolveInput("Miễn phí lần này", 1, BigDecimal.ZERO))
                .feeInvoice()).isNull();

        // Không phải LOST_KEY: thay chìa không tự thu phí.
        SupportView issue = support.create(customerId, new CreateInput(contractId, null, "UNIT_ISSUE", "Khoá kẹt",
                "Ổ khoá kẹt", null));
        loginAs(FM);
        assertThat(support.resolve(issue.id(), new ResolveInput("Thay ổ khoá", 2, null)).feeInvoice()).isNull();
        assertThat(count("select count(*) from invoices where contract_id = ? and type = 'Service'", contractId))
                .isEqualTo(1);
    }

    @Test
    void customerCancelsAndManagerResolvesWithoutContract() {
        SupportView open = support.create(customerId, new CreateInput(null, facilityId, "OTHER", "Hỏi giờ mở cửa",
                "Cuối tuần mở cửa không?", null));
        SupportView canceled = support.cancel(open.id(), customerId);
        assertThat(canceled.status()).isEqualTo("Canceled");

        SupportView billing = support.create(customerId, new CreateInput(null, facilityId, "BILLING", "Hoá đơn",
                "Thắc mắc hoá đơn", null));
        loginAs(FM);
        assertThatThrownBy(() -> support.resolve(billing.id(), new ResolveInput("Thu phí", null,
                new BigDecimal("1000")))).isInstanceOf(TenancyException.class);
        assertThat(support.resolve(billing.id(), new ResolveInput("Đã giải thích", null, null)).status())
                .isEqualTo("Resolved");
    }

    // ------------------------------------------------------------------ FM back-office

    @Test
    void managerManagesUnitsAndSeesFacilityData() {
        loginAs(FM);
        long smallType = id("select id from unit_types where name = 'Small'");
        String code = "BO-" + SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000;
        UnitRow unit = backoffice.createUnit(facilityId, code, smallType, null);
        assertThat(unit.status()).isEqualTo("Available");
        assertThatThrownBy(() -> backoffice.createUnit(facilityId, code, smallType, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoffice.createUnit(facilityId, "X", -1L, null))
                .isInstanceOf(IllegalArgumentException.class);

        UnitRow priced = backoffice.updateUnit(unit.id(), null, new BigDecimal("750000"));
        assertThat(priced.monthlyPrice()).isEqualByComparingTo("750000");
        assertThat(backoffice.setMaintenance(unit.id(), true).status()).isEqualTo("Maintenance");
        assertThatThrownBy(() -> backoffice.setMaintenance(unit.id(), true)).isInstanceOf(IllegalStateException.class);
        assertThat(backoffice.setMaintenance(unit.id(), false).status()).isEqualTo("Available");
        assertThat(count("select count(*) from audit_logs where entity_type = 'StorageUnit' and entity_id = ?",
                String.valueOf(unit.id()))).isEqualTo(4);

        assertThatThrownBy(() -> backoffice.setAccessOptions(facilityId, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        FacilityRow facility = backoffice.setAccessOptions(facilityId, true, false);
        assertThat(facility.codeAccess()).isFalse();
        backoffice.setAccessOptions(facilityId, true, true);

        seedActiveContract();
        assertThat(backoffice.facilityContracts(facilityId, "Active")).isNotEmpty();
        assertThat(backoffice.chainInvoices(facilityId, null, null)).isNotEmpty();
        Report report = backoffice.facilityReport(facilityId, null, null);
        assertThat(report.kpis().activeContracts()).isPositive();
        assertThat(report.occupancy()).isNotEmpty();
        assertThat(report.totalRevenue()).isPositive();
        assertThatThrownBy(() -> backoffice.facilityReport(facilityId, LocalDate.now(), LocalDate.now().minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void managerHoldsPostReturnMaintenanceSoCronDoesNotReopen() {
        loginAs(FM);
        long smallType = id("select id from unit_types where name = 'Small'");
        UnitRow unit = backoffice.createUnit(facilityId, "MH-" + SEQ.incrementAndGet() + "-" + System.nanoTime()
                % 100000, smallType, null);
        // Khoang Available: không có bảo trì sau trả kho để giữ.
        assertThatThrownBy(() -> backoffice.holdMaintenance(unit.id())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> backoffice.holdMaintenance(-1L)).isInstanceOf(IllegalArgumentException.class);

        jdbc.update("update storage_units set status = 'Maintenance', maintenance_started_at = now() - interval "
                + "'1 day' where id = ?", unit.id());
        assertThat(unitList.listByFacility(facilityId, "Maintenance", null))
                .filteredOn(u -> u.id().equals(unit.id()))
                .singleElement().satisfies(u -> assertThat(u.maintenanceStartedAt()).isNotNull());

        UnitRow held = backoffice.holdMaintenance(unit.id());
        assertThat(held.status()).isEqualTo("Maintenance");
        assertThat(held.maintenanceStartedAt()).isNull();
        assertThat(unitList.listByFacility(facilityId, "Maintenance", null))
                .filteredOn(u -> u.id().equals(unit.id()))
                .singleElement().satisfies(u -> assertThat(u.maintenanceStartedAt()).isNull());
        assertThat(count("select count(*) from audit_logs where entity_type = 'StorageUnit' and entity_id = ? "
                + "and action = 'STORAGE_UNIT_MAINTENANCE' and new_value ->> 'reason' = 'FM giữ bảo trì'",
                String.valueOf(unit.id()))).isEqualTo(1);
        // Đã là bảo trì thủ công: giữ lần nữa → 409; FM mở lại tay vẫn được.
        assertThatThrownBy(() -> backoffice.holdMaintenance(unit.id())).isInstanceOf(IllegalStateException.class);
        assertThat(backoffice.setMaintenance(unit.id(), false).status()).isEqualTo("Available");
    }

    // ------------------------------------------------------------------ BOM

    @Test
    void bomManagesFacilitiesPoliciesAndPrices() {
        loginAs(BOM);
        String code = "F" + SEQ.incrementAndGet() + System.nanoTime() % 1000;
        FacilityRow created = backoffice.createFacility(code, "Cơ sở thử", "1 Lê Lợi", "8:00–20:00");
        assertThat(created.status()).isEqualTo("Active");
        assertThatThrownBy(() -> backoffice.createFacility(code, "Trùng", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(backoffice.updateFacility(created.id(), "Cơ sở mới", null, null, "Inactive").status())
                .isEqualTo("Inactive");
        assertThatThrownBy(() -> backoffice.updateFacility(created.id(), null, null, null, "Closed"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoffice.assignManager(created.id(), customerId, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(backoffice.assignManager(created.id(), null, false).fmAccountId()).isNull();
        assertThat(backoffice.listFacilities()).extracting(FacilityRow::code).contains(code);

        assertThatThrownBy(() -> backoffice.updatePolicy("overdue.fee_per_day", "abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoffice.updatePolicy("no.such", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(backoffice.updatePolicy("overdue.fee_per_day", "25000").value()).isEqualTo("25000");
        backoffice.updatePolicy("overdue.fee_per_day", "20000");
        assertThat(backoffice.policies()).isNotEmpty();

        long smallType = id("select id from unit_types where name = 'Small'");
        BigDecimal price = jdbc.queryForObject("select monthly_price from unit_types where id = ?", BigDecimal.class,
                smallType);
        assertThatThrownBy(() -> backoffice.updateUnitTypePrice(smallType, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(backoffice.updateUnitTypePrice(smallType, new BigDecimal("555000")).monthlyPrice())
                .isEqualByComparingTo("555000");
        backoffice.updateUnitTypePrice(smallType, price);
        assertThat(backoffice.unitTypes()).isNotEmpty();
        assertThat(backoffice.chainReport(null, null, null).kpis()).isNotNull();
        assertThat(backoffice.chainInvoices(null, "Paid", null)).isNotNull();
    }

    // ------------------------------------------------------------------ Admin

    @Test
    void adminAssignsFacilitiesWhenCreatingAnFsAccount() {
        long adminId = id("select id from accounts where email = '" + ADMIN + "'");
        String email = "fsnew" + SEQ.incrementAndGet() + System.nanoTime() % 100000 + "@lemar.vn";

        AccountRow created = accounts.create(email, "Test@1234", "FS mới", null, "FS",
                List.of(facilityId, facilityId), adminId);

        assertThat(created.facilityIds()).containsExactly(facilityId);
        assertThat(jdbc.queryForObject("select new_value ->> 'facilityIds' from audit_logs where action = "
                + "'ACCOUNT_CREATED' and entity_id = ? order by id desc limit 1", String.class,
                String.valueOf(created.id()))).isEqualTo("[" + facilityId + "]");
        // Chỉ FS được gán cơ sở; cơ sở không tồn tại → 400, không tạo tài khoản.
        assertThatThrownBy(() -> accounts.create("fmx" + email, "Test@1234", null, null, "FM",
                List.of(facilityId), adminId)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounts.create("fsx" + email, "Test@1234", null, null, "FS",
                List.of(-1L), adminId)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from accounts where email in (?, ?)", Integer.class,
                "fmx" + email, "fsx" + email)).isZero();
    }

    @Test
    void adminManagesAccountsPermissionsAndSeesLogs() {
        long adminId = id("select id from accounts where email = '" + ADMIN + "'");
        String email = "staff" + SEQ.incrementAndGet() + System.nanoTime() % 100000 + "@lemar.vn";
        AccountRow created = accounts.create(email, "Test@1234", "Nhân viên mới", "0911", "FS", adminId);
        assertThat(created.role()).isEqualTo("FS");
        assertThat(created.verified()).isTrue();
        assertThatThrownBy(() -> accounts.create(email, "Test@1234", null, null, "FS", adminId))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accounts.create("bad", "Test@1234", null, null, "FS", adminId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounts.create("a@b.vn", "123", null, null, "FS", adminId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounts.create("a@b.vn", "Test@1234", null, null, "GOD", adminId))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(accounts.setStaffFacilities(created.id(), List.of(facilityId), adminId).facilityIds())
                .containsExactly(facilityId);
        assertThat(accounts.update(created.id(), null, "BANNED", null, null, adminId).status()).isEqualTo("BANNED");
        AccountRow promoted = accounts.update(created.id(), "FM", "ACTIVE", "FM mới", null, adminId);
        assertThat(promoted.role()).isEqualTo("FM");
        assertThat(promoted.facilityIds()).isEmpty();
        assertThatThrownBy(() -> accounts.setStaffFacilities(created.id(), List.of(facilityId), adminId))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accounts.update(adminId, "BOM", null, null, null, adminId))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accounts.update(created.id(), null, "GONE", null, null, adminId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(accounts.list("FM", null, "FM mới", 0, 50)).extracting(AccountRow::id).contains(created.id());

        authService.login(new LoginRequest(email, "Test@1234"), "10.0.0.1");
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "sai-mat-khau"), "10.0.0.1"))
                .isInstanceOf(RuntimeException.class);
        assertThat(accounts.loginEvents(email, 0, 10)).hasSize(2)
                .anySatisfy(e -> assertThat(e.success()).isTrue())
                .anySatisfy(e -> assertThat(e.reason()).isEqualTo("InvalidCredentials"));
        assertThat(accounts.get(created.id()).lastLoginAt()).isNotNull();

        Map<String, String> fsScopes = new java.util.HashMap<>();
        accounts.permissionMatrix().forEach(p -> {
            if (p.scopeByRole().containsKey("FS")) {
                fsScopes.put(p.code(), p.scopeByRole().get("FS"));
            }
        });
        Map<String, String> reduced = new java.util.HashMap<>(fsScopes);
        reduced.remove("unit_access_key.issue");
        assertThat(accounts.setRolePermissions("FS", reduced, adminId).stream()
                .filter(p -> p.code().equals("unit_access_key.issue")).findFirst().orElseThrow()
                .scopeByRole()).doesNotContainKey("FS");
        accounts.setRolePermissions("FS", fsScopes, adminId);
        assertThatThrownBy(() -> accounts.setRolePermissions("ADMIN", Map.of(), adminId))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accounts.setRolePermissions("FS", Map.of("x.y", "GLOBAL"), adminId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounts.setRolePermissions("FS", Map.of("rental_order.read", "WORLD"), adminId))
                .isInstanceOf(IllegalArgumentException.class);
        accounts.setRolePermissions("FS", fsScopes, adminId);

        assertThat(readRepository.auditLogs("ACCOUNT_CREATED", "Account", String.valueOf(created.id()), "admin", 0,
                10)).hasSize(1);
    }

    @Test
    void revenueIsAccrualBasedAndDepositIsHeldNotEarned() {
        Map<String, BigDecimal> before = revenueThisMonth();
        BigDecimal heldBefore = readRepository.kpis(facilityId).depositHeld();

        long contractId = seedActiveContract();
        long rentId = id("select id from invoices where contract_id = " + contractId + " and type = 'Rental'");
        // Thu qua cổng rồi hoàn một phần: mốc thu là giao dịch PAY, khoản hoàn trừ vào doanh thu tháng hoàn.
        jdbc.update("insert into payment_transactions (invoice_id, vnp_txn_ref, amount, direction, status, paid_at) "
                + "values (?, ?, 100000, 'PAY', 'Success', now())", rentId, "PY" + System.nanoTime());
        refund(rentId, "40000", "now()");
        // Phí trả kho đối trừ vào cọc (Paid, không có giao dịch thu): doanh thu + giảm cọc đang giữ.
        jdbc.update("insert into invoices (contract_id, customer_id, code, title, type, status, amount) values "
                + "(?, ?, ?, 'Vệ sinh (đối trừ cọc)', 'Penalty', 'Paid', 100000)", contractId, customerId,
                "INV-CLN-Q7-S" + System.nanoTime());
        // Đơn bị huỷ sau khi cọc 300.000: cọc bị giữ lại; hoàn 100.000 sau lúc huỷ (lỗi cơ sở) trừ lại.
        long requestId = id("insert into rental_requests (facility_id, customer_name, normalized_customer_email, "
                + "customer_phone, unit_type_id, start_date, period, status) values (" + facilityId
                + ", 'Khách Huỷ', '" + CUSTOMER + "', '0900000000', (select id from unit_types where name = "
                + "'Small'), current_date, 3, 'Converted') returning id");
        long canceledOrder = id("insert into rental_orders (request_id, customer_id, status, cancel_reason, "
                + "canceled_at) values (" + requestId + ", " + customerId
                + ", 'Canceled', 'Khách hủy đơn', now() - interval '1 minute') returning id");
        long depositId = id("insert into invoices (order_id, customer_id, code, title, type, status, amount) values ("
                + canceledOrder + ", " + customerId + ", 'INV-DEP-Q7-C" + System.nanoTime() + "', 'Cọc', 'Deposit', "
                + "'Paid', 300000) returning id");
        refund(depositId, "100000", "now()");

        Map<String, BigDecimal> after = revenueThisMonth();
        assertThat(delta(after, before, "Deposit")).isEqualByComparingTo("0");
        assertThat(delta(after, before, "Rental")).isEqualByComparingTo("60000");
        assertThat(delta(after, before, "Penalty")).isEqualByComparingTo("100000");
        assertThat(delta(after, before, "ForfeitedDeposit")).isEqualByComparingTo("200000");
        // Cọc 500.000 của đơn đang thuê − 100.000 đã đối trừ; cọc đơn huỷ không còn tính là đang giữ.
        assertThat(readRepository.kpis(facilityId).depositHeld().subtract(heldBefore)).isEqualByComparingTo("400000");
    }

    @Test
    void backofficeFiltersAndValidation() {
        seedActiveContract();
        loginAs(FM);
        assertThat(backoffice.chainInvoices(facilityId, "Paid", "Deposit")).isNotEmpty()
                .allSatisfy(i -> assertThat(i.type()).isEqualTo("Deposit"));
        assertThat(backoffice.facilityContracts(facilityId, null)).isNotEmpty();
        assertThat(backoffice.facilityReport(facilityId, LocalDate.now().minusMonths(1), LocalDate.now()).revenue())
                .isNotNull();
        assertThatThrownBy(() -> backoffice.createUnit(facilityId, " ", 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoffice.createUnit(facilityId, "NEG", id("select id from unit_types limit 1"),
                new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoffice.updateUnit(-1L, null, null)).isInstanceOf(IllegalArgumentException.class);
        long unitId = id("select id from storage_units where facility_id = " + facilityId
                + " and status = 'Available' limit 1");
        assertThatThrownBy(() -> backoffice.updateUnit(unitId, -5L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoffice.setMaintenance(unitId, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> backoffice.setMaintenance(-1L, true)).isInstanceOf(IllegalArgumentException.class);

        loginAs(BOM);
        assertThat(backoffice.chainInvoices(facilityId, null, null)).isNotEmpty();
        assertThat(backoffice.chainReport(facilityId, null, null).occupancy()).isNotEmpty();
        assertThatThrownBy(() -> backoffice.createFacility(" ", "x", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoffice.updateFacility(-1L, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoffice.assignManager(-1L, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoffice.updatePolicy("overdue.fee_per_day", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoffice.updatePolicy("overdue.fee_per_day", "-5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(backoffice.updatePolicy("appointment.capacity_mode", "fixed_windows").value())
                .isEqualTo("fixed_windows");
        assertThatThrownBy(() -> backoffice.updateUnitTypePrice(-1L, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        long fmId = id("select id from accounts where email = '" + FM + "'");
        assertThat(backoffice.assignManager(facilityId, fmId, true).fmAccountId()).isEqualTo(fmId);

        long adminId = id("select id from accounts where email = '" + ADMIN + "'");
        assertThat(accounts.list(null, "ACTIVE", null, 0, 5)).hasSizeLessThanOrEqualTo(5);
        assertThat(accounts.loginEvents(null, 0, 5)).isNotNull();
        assertThatThrownBy(() -> accounts.get(-1L)).isInstanceOf(IllegalArgumentException.class);
        long fs = id("select id from accounts where email = '" + FS + "'");
        assertThat(accounts.update(fs, null, null, "FS Quận 7", "0900", adminId).name()).isEqualTo("FS Quận 7");
        assertThatThrownBy(() -> accounts.setStaffFacilities(fs, List.of(-1L), adminId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(readRepository.auditLogs(null, null, null, null, 0, 5)).isNotEmpty();
        assertThat(readRepository.auditLogs("POLICY_UPDATED", "Policy", null, null, 0, 5)).isNotEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Hợp đồng Active: 1 bộ chìa (2), hoá đơn cọc 500.000 Paid (cọc đang giữ) và tiền thuê tháng đầu 100.000 Paid
     * (doanh thu).
     */
    private long seedActiveContract() {
        String suffix = SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000;
        long unitId = id("insert into storage_units (code, facility_id, unit_type_id, status) values ('S-" + suffix
                + "', " + facilityId + ", (select id from unit_types where name = 'Small'), 'Rented') returning id");
        long requestId = id("insert into rental_requests (facility_id, customer_name, normalized_customer_email, "
                + "customer_phone, unit_type_id, start_date, period, status, unit_id) values (" + facilityId
                + ", 'Khách Demo', '" + CUSTOMER + "', '0900000000', (select id from unit_types where name = "
                + "'Small'), current_date, 3, 'Converted', " + unitId + ") returning id");
        long orderId = id("insert into rental_orders (request_id, customer_id, unit_id, status) values (" + requestId
                + ", " + customerId + ", " + unitId + ", 'Done') returning id");
        jdbc.update("insert into invoices (order_id, customer_id, code, title, type, status, amount) values "
                + "(?, ?, ?, 'Cọc', 'Deposit', 'Paid', 500000)", orderId, customerId, "INV-DEP-Q7-S" + suffix);
        LocalDate end = LocalDate.now(VN).plusMonths(2);
        long contractId = id("insert into rental_contracts (order_id, customer_id, unit_id, code, terms_version, "
                + "monthly_price, deposit_amount, period, start_date, end_date, signed_at, status) values (" + orderId
                + ", " + customerId + ", " + unitId + ", 'HD-S-" + suffix + "', 'builtin-v1', 100000, 500000, 3, '"
                + end.minusMonths(3) + "', '" + end + "', now(), 'Active') returning id");
        jdbc.update("insert into unit_access_keys (unit_id, contract_id, access_type, quantity) values (?, ?, "
                + "'PhysicalKey', 2)", unitId, contractId);
        jdbc.update("insert into invoices (contract_id, customer_id, code, title, type, status, amount) values "
                + "(?, ?, ?, 'Tháng đầu', 'Rental', 'Paid', 100000)", contractId, customerId, "INV-RNT-Q7-S" + suffix);
        return contractId;
    }

    private Map<String, BigDecimal> revenueThisMonth() {
        LocalDate today = LocalDate.now(VN);
        Map<String, BigDecimal> byType = new java.util.HashMap<>();
        readRepository.revenue(facilityId, today.withDayOfMonth(1), today)
                .forEach(r -> byType.merge(r.type(), r.amount(), BigDecimal::add));
        return byType;
    }

    private static BigDecimal delta(Map<String, BigDecimal> after, Map<String, BigDecimal> before, String type) {
        return after.getOrDefault(type, BigDecimal.ZERO).subtract(before.getOrDefault(type, BigDecimal.ZERO));
    }

    private void refund(long invoiceId, String amount, String paidAt) {
        jdbc.update("insert into payment_transactions (invoice_id, vnp_txn_ref, amount, direction, status, paid_at) "
                + "values (?, ?, " + amount + ", 'REFUND', 'Success', " + paidAt + ")", invoiceId,
                "RF" + SEQ.incrementAndGet() + System.nanoTime() % 100000);
    }

    private long id(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private int notifications(String email, String type) {
        return count("select count(*) from notifications n join accounts a on a.id = n.account_id "
                + "where a.email = ? and n.type = ?", email, type);
    }

    @Test
    void pricesAndIntegerPoliciesRejectValuesTheGatewayOrReadersCannotUse() {
        loginAs(BOM);
        long smallType = id("select id from unit_types where name = 'Small'");
        String code = "PV-" + SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000;
        // Giá 0 → hoá đơn cọc 0đ không thanh toán được; giá lẻ → payOS thu số làm tròn, không khớp hoá đơn.
        assertThatThrownBy(() -> backoffice.createUnit(facilityId, code, smallType, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lớn hơn 0");
        assertThatThrownBy(() -> backoffice.createUnit(facilityId, code, smallType, new BigDecimal("750000.50")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("số nguyên");
        assertThatThrownBy(() -> backoffice.updateUnitTypePrice(smallType, new BigDecimal("500000.5")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("số nguyên");
        assertThat(backoffice.createUnit(facilityId, code, smallType, new BigDecimal("750000.00")).monthlyPrice())
                .isEqualByComparingTo("750000");

        // Policy số nguyên đọc bằng Integer.parseInt — "2.5" / "1e2" / quá miền int làm mọi chỗ đọc lỗi.
        assertThatThrownBy(() -> backoffice.updatePolicy("overdue.grace_days", "2.5"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("số nguyên");
        assertThatThrownBy(() -> backoffice.updatePolicy("overdue.grace_days", "1e2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoffice.updatePolicy("overdue.grace_days", "99999999999"))
                .isInstanceOf(IllegalArgumentException.class);
        String grace = jdbc.queryForObject("select value from policies where key = 'overdue.grace_days'",
                String.class);
        assertThat(backoffice.updatePolicy("overdue.grace_days", "4").value()).isEqualTo("4");
        backoffice.updatePolicy("overdue.grace_days", grace);
    }

    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
