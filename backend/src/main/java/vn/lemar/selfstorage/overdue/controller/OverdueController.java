package vn.lemar.selfstorage.overdue.controller;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.overdue.application.OverdueService;
import vn.lemar.selfstorage.overdue.application.OverdueService.OverdueContract;

/** Flow 6 — FM xem hợp đồng quá hạn của cơ sở. */
@RestController
public class OverdueController {

    private final OverdueService overdueService;

    public OverdueController(OverdueService overdueService) {
        this.overdueService = overdueService;
    }

    @Operation(summary = "Hợp đồng quá hạn của cơ sở (FM)",
            description = "Số ngày quá hạn, số ngày tính phí (sau ân hạn, dừng khi có yêu cầu trả kho), hoá đơn mở.")
    @GetMapping("/api/fm/overdue-contracts")
    public ResponseEntity<ApiEnvelope<List<OverdueContract>>> list(@RequestParam Long facilityId) {
        return ResponseEntity.ok(ApiEnvelope.ok("Hợp đồng quá hạn", overdueService.facilityOverdue(facilityId)));
    }
}
