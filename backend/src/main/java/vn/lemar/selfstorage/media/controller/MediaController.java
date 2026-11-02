package vn.lemar.selfstorage.media.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.media.application.MediaService;
import vn.lemar.selfstorage.media.application.dto.MediaBatchAttachResult;
import vn.lemar.selfstorage.media.application.dto.MediaBatchDeleteResult;
import vn.lemar.selfstorage.media.application.dto.MediaUploadResult;

/** Upload file dùng chung cho mọi flow — FE upload trước, submit URL vào flow sau. */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaService mediaService;
    private final AccountQueryService accountQueryService;

    public MediaController(MediaService mediaService, AccountQueryService accountQueryService) {
        this.mediaService = mediaService;
        this.accountQueryService = accountQueryService;
    }

    @Operation(summary = "Upload file",
            description = "Nhận ảnh (JPEG/PNG/WebP) hoặc PDF, tối đa 10MB. Trả URL để dùng lại "
                    + "trong các flow (ảnh hiện trạng, scan hợp đồng, ảnh giấy tờ). File được lưu "
                    + "theo folder riêng của account đang đăng nhập để truy vết. `batchId` tuỳ chọn: "
                    + "các file cùng một đợt upload dùng chung batchId để Hủy/attach cả batch.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Upload thành công",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"message":"Tải file lên thành công",
                                     "data":{"url":"https://res.cloudinary.com/demo/anh.png",
                                             "publicId":"anh","bytes":20480,"format":"png",
                                             "batchId":"0f8b9a3c-2e6e-4e80-9f5d-2d4f9a3b1c77"}}"""))),
            @ApiResponse(responseCode = "400",
                    description = "Thiếu file / file rỗng / sai định dạng / không đọc được / quá 10MB"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập")
    })
    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiEnvelope<MediaUploadResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "batchId", required = false) UUID batchId,
            Authentication authentication) throws IOException {
        Long accountId = accountQueryService.requireIdByEmail(authentication.getName());
        return ApiEnvelope.ok("Tải file lên thành công",
                mediaService.upload(file.getBytes(), file.getOriginalFilename(), file.getContentType(),
                        accountId, batchId));
    }

    @Operation(summary = "Huỷ batch upload",
            description = "Xoá các asset `Uploading`/`Pending` của batch; asset `Attached` được giữ "
                    + "lại. Idempotent: gọi lại trả deleted=0, skipped=n.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã huỷ batch"),
            @ApiResponse(responseCode = "403", description = "Batch thuộc tài khoản khác"),
            @ApiResponse(responseCode = "404", description = "Không có asset nào thuộc batch")
    })
    @DeleteMapping("/uploads/batch/{batchId}")
    public ApiEnvelope<MediaBatchDeleteResult> deleteBatch(
            @PathVariable UUID batchId, Authentication authentication) {
        Long accountId = accountQueryService.requireIdByEmail(authentication.getName());
        return ApiEnvelope.ok("Đã huỷ batch", mediaService.deleteBatch(accountId, batchId));
    }

    @Operation(summary = "Gắn batch upload",
            description = "Flow đã submit URL: đánh dấu các asset Pending thành Attached để Hủy "
                    + "batch không xoá nữa.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã gắn batch"),
            @ApiResponse(responseCode = "403", description = "Batch thuộc tài khoản khác"),
            @ApiResponse(responseCode = "404", description = "Không có asset nào thuộc batch"),
            @ApiResponse(responseCode = "409", description = "Batch đã Canceled/Deleted")
    })
    @PostMapping("/uploads/batch/{batchId}/attach")
    public ApiEnvelope<MediaBatchAttachResult> attachBatch(
            @PathVariable UUID batchId, Authentication authentication) {
        Long accountId = accountQueryService.requireIdByEmail(authentication.getName());
        return ApiEnvelope.ok("Đã gắn batch", mediaService.attachBatch(accountId, batchId));
    }
}
