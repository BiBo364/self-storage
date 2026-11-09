package vn.lemar.selfstorage.pricing.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.pricing.application.UnitTypeQueryService;

/**
 * Danh mục loại khoang một cơ sở cung cấp — public cho form đặt kho lọc dropdown.
 */
@RestController
@RequestMapping("/api/unit-types")
public class UnitTypeController {

    private static final String LIST_MESSAGE = "Lấy danh sách loại khoang thành công";

    private final UnitTypeQueryService unitTypeQueryService;

    public UnitTypeController(UnitTypeQueryService unitTypeQueryService) {
        this.unitTypeQueryService = unitTypeQueryService;
    }

    @SecurityRequirements
    @Operation(summary = "Loại khoang cơ sở cung cấp",
            description = "Trả các loại khoang một cơ sở cung cấp (bảng facility_unit_type, V4). "
                    + "Cơ sở không tồn tại trả danh sách rỗng.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách thành công"),
            @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping
    public ResponseEntity<ApiEnvelope<List<UnitTypeQueryService.UnitTypeOption>>> list(
            @RequestParam(name = "facilityId") Long facilityId) {
        return ResponseEntity.ok(ApiEnvelope.ok(LIST_MESSAGE,
                unitTypeQueryService.listByFacility(facilityId)));
    }
}
