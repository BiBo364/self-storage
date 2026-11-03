package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.booking.application.FmFacilityReadService;
import vn.lemar.selfstorage.facility.application.ManagedFacilityView;

/**
 * Cơ sở FM đang phụ trách — route role-bound {@code /api/fm/**}; self-scoped, không cần permission
 * theo cơ sở vì tài khoản chỉ có tối đa một cơ sở qua {@code facilities.fm_account_id}.
 */
@RestController
@RequestMapping("/api/fm/facility")
public class FmFacilityController {

    static final String FACILITY_MESSAGE = "Lấy cơ sở của FM thành công";

    private final FmFacilityReadService fmFacilityReadService;

    public FmFacilityController(FmFacilityReadService fmFacilityReadService) {
        this.fmFacilityReadService = fmFacilityReadService;
    }

    @Operation(summary = "Cơ sở của FM hiện tại",
            description = "Cơ sở gắn với `facilities.fm_account_id` của account đang đăng nhập. "
                    + "Quan hệ 1–1 nên trả một object, không trả list.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy cơ sở thành công"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Không phải role FM"),
            @ApiResponse(responseCode = "404", description = "Tài khoản FM chưa được gán cơ sở",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping
    public ResponseEntity<ApiEnvelope<ManagedFacilityView>> facility() {
        return ResponseEntity.ok(ApiEnvelope.ok(FACILITY_MESSAGE, fmFacilityReadService.currentFacility()));
    }
}
