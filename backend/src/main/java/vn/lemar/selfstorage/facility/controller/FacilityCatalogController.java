package vn.lemar.selfstorage.facility.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.facility.application.FacilityCatalogQuery;
import vn.lemar.selfstorage.facility.application.FacilityCatalogView;

/**
 * Catalog cơ sở public cho trang browse và form gửi yêu cầu. Chỉ trả cơ sở {@code Active}; không lộ
 * {@code fm_account_id}.
 */
@RestController
@RequestMapping("/api/facilities")
public class FacilityCatalogController {

    static final String LIST_MESSAGE = "Lấy danh sách cơ sở thành công";
    static final String LAYOUT_MESSAGE = "Sơ đồ cơ sở";

    private final FacilityCatalogQuery facilityCatalogQuery;

    public FacilityCatalogController(FacilityCatalogQuery facilityCatalogQuery) {
        this.facilityCatalogQuery = facilityCatalogQuery;
    }

    @SecurityRequirements
    @Operation(summary = "Danh sách cơ sở đang hoạt động",
            description = "Chỉ cơ sở `Active`, order by id. Mỗi cơ sở kèm `unitTypeIds` lấy từ "
                    + "`facility_unit_type` để FE lọc theo loại khoang mà không phải gọi N lần.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách thành công")
    })
    @GetMapping
    public ResponseEntity<ApiEnvelope<List<FacilityCatalogView>>> list() {
        return ResponseEntity.ok(ApiEnvelope.ok(LIST_MESSAGE, facilityCatalogQuery.listActive()));
    }

    @SecurityRequirements
    @Operation(summary = "Sơ đồ cơ sở",
            description = "Số tầng + khoang đã xếp vị trí (tầng, bên lối đi `Left`/`Right` nhìn từ cửa vào, thứ tự "
                    + "từ cửa vào — 1 = sát cửa) kèm cờ `available` (khoang `Available`). Không lộ trạng thái chi "
                    + "tiết. Public như catalog; mọi trạng thái cơ sở.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sơ đồ cơ sở"),
            @ApiResponse(responseCode = "404", description = "Cơ sở không tồn tại",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{id}/layout")
    public ResponseEntity<?> layout(@PathVariable Long id) {
        return facilityCatalogQuery.layout(id)
                .<ResponseEntity<?>>map(view -> ResponseEntity.ok(ApiEnvelope.ok(LAYOUT_MESSAGE, view)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiError.now("Không tìm thấy cơ sở")));
    }
}
