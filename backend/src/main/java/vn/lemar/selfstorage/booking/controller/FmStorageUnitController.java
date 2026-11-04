package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.booking.application.FmFacilityReadService;
import vn.lemar.selfstorage.facility.application.StorageUnitListView;

/**
 * Khoang của cơ sở FM — dùng cho màn duyệt yêu cầu và quản lý khoang. Guard
 * {@code facility.access} theo cơ sở; dữ liệu đọc từ module facility.
 */
@RestController
@RequestMapping("/api/fm/storage-units")
public class FmStorageUnitController {

    static final String LIST_MESSAGE = "Lấy danh sách khoang thành công";

    private final FmFacilityReadService fmFacilityReadService;

    public FmStorageUnitController(FmFacilityReadService fmFacilityReadService) {
        this.fmFacilityReadService = fmFacilityReadService;
    }

    @Operation(summary = "Khoang của cơ sở",
            description = "Danh sách khoang theo cơ sở, order by code. Lọc optional theo `status` "
                    + "(Available / Reserved / Rented / Maintenance) và `unitTypeId`. "
                    + "`monthlyPrice` là giá hiệu lực của khoang.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách thành công"),
            @ApiResponse(responseCode = "400", description = "`status` không hợp lệ",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Không phải role FM, hoặc ngoài phạm vi cơ sở"),
            @ApiResponse(responseCode = "404", description = "Cơ sở không tồn tại",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping
    public ResponseEntity<ApiEnvelope<List<StorageUnitListView>>> list(
            @Parameter(description = "Id cơ sở") @RequestParam Long facilityId,
            @Parameter(description = "Available / Reserved / Rented / Maintenance")
            @RequestParam(required = false) String status,
            @Parameter(description = "Id loại khoang") @RequestParam(required = false) Long unitTypeId) {
        return ResponseEntity.ok(ApiEnvelope.ok(LIST_MESSAGE,
                fmFacilityReadService.storageUnits(facilityId, status, unitTypeId)));
    }
}
