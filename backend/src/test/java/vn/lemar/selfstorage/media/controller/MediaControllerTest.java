package vn.lemar.selfstorage.media.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import vn.lemar.selfstorage.SecurityConfig;
import vn.lemar.selfstorage.identity.application.AccountJwtAuthenticationConverter;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.identity.config.JwtConfiguration;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.repository.AccountRepository;
import vn.lemar.selfstorage.media.application.MediaService;
import vn.lemar.selfstorage.media.application.dto.MediaBatchAttachResult;
import vn.lemar.selfstorage.media.application.dto.MediaBatchDeleteResult;
import vn.lemar.selfstorage.media.application.dto.MediaUploadResult;
import vn.lemar.selfstorage.media.application.UploadRateLimiter;
import vn.lemar.selfstorage.media.application.exception.InvalidUploadException;
import vn.lemar.selfstorage.media.application.exception.MediaRateLimitedException;

@WebMvcTest(controllers = MediaController.class)
@Import({SecurityConfig.class, JwtConfiguration.class, AccountJwtAuthenticationConverter.class})
@TestPropertySource(properties = "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class MediaControllerTest {

    private static final String INVALID_TYPE_MESSAGE =
            "Định dạng file không được hỗ trợ (chỉ nhận JPEG, PNG, WebP, PDF)";
    private static final String EMAIL = "customer@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockBean
    private MediaService mediaService;

    @MockBean
    private AccountQueryService accountQueryService;

    @MockBean
    private AccountRepository accountRepository;

    @MockBean
    private UploadRateLimiter uploadRateLimiter;

    @Test
    void uploadRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(multipart("/api/media/uploads")
                        .file(new MockMultipartFile("file", "anh.png", "image/png", new byte[]{1})))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadReturnsStoredFileOfCurrentAccount() throws Exception {
        authenticate();
        when(mediaService.upload(any(byte[].class), eq("anh.png"), eq("image/png"), eq(1L), any()))
                .thenReturn(new MediaUploadResult(
                        "https://res.cloudinary.com/x/uploads/acc-1/anh.png", "uploads/acc-1/anh", 2, "png",
                        UUID.randomUUID()));

        mockMvc.perform(multipart("/api/media/uploads")
                        .file(new MockMultipartFile("file", "anh.png", "image/png", new byte[]{1, 2}))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Tải file lên thành công"))
                .andExpect(jsonPath("$.data.url").value("https://res.cloudinary.com/x/uploads/acc-1/anh.png"))
                .andExpect(jsonPath("$.data.publicId").value("uploads/acc-1/anh"))
                .andExpect(jsonPath("$.data.bytes").value(2))
                .andExpect(jsonPath("$.data.format").value("png"));
    }

    @Test
    void uploadReturns400WithSpecificMessageWhenInvalid() throws Exception {
        authenticate();
        when(mediaService.upload(any(byte[].class), any(), any(), anyLong(), any()))
                .thenThrow(new InvalidUploadException(INVALID_TYPE_MESSAGE));

        mockMvc.perform(multipart("/api/media/uploads")
                        .file(new MockMultipartFile("file", "a.exe", "application/x-msdownload", new byte[]{1}))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(EMAIL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_TYPE_MESSAGE));
    }

    @Test
    void uploadReturns429WhenAccountExceedsRateLimit() throws Exception {
        authenticate();
        doThrow(new MediaRateLimitedException("Bạn tải lên quá nhiều file"))
                .when(uploadRateLimiter).acquire(1L);

        mockMvc.perform(multipart("/api/media/uploads")
                        .file(new MockMultipartFile("file", "anh.png", "image/png", new byte[]{1, 2}))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(EMAIL)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Bạn tải lên quá nhiều file"));
        verify(mediaService, never()).upload(any(byte[].class), any(), any(), anyLong(), any());
    }

    @Test
    void uploadRejectsMissingFilePart() throws Exception {
        authenticate();

        mockMvc.perform(multipart("/api/media/uploads")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(EMAIL)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadAcceptsBatchId() throws Exception {
        authenticate();
        UUID batchId = UUID.randomUUID();
        when(mediaService.upload(any(byte[].class), eq("anh.png"), eq("image/png"), eq(1L), eq(batchId)))
                .thenReturn(new MediaUploadResult("u", "p", 1, "png", batchId));

        mockMvc.perform(multipart("/api/media/uploads")
                        .file(new MockMultipartFile("file", "anh.png", "image/png", new byte[]{1}))
                        .param("batchId", batchId.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batchId").value(batchId.toString()));
    }

    @Test
    void deleteBatchReturnsCounts() throws Exception {
        authenticate();
        UUID batchId = UUID.randomUUID();
        when(mediaService.deleteBatch(1L, batchId)).thenReturn(new MediaBatchDeleteResult(2, 1));

        mockMvc.perform(delete("/api/media/uploads/batch/{batchId}", batchId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã huỷ batch"))
                .andExpect(jsonPath("$.data.deleted").value(2))
                .andExpect(jsonPath("$.data.skipped").value(1));
    }

    @Test
    void attachBatchReturnsCount() throws Exception {
        authenticate();
        UUID batchId = UUID.randomUUID();
        when(mediaService.attachBatch(1L, batchId)).thenReturn(new MediaBatchAttachResult(2));

        mockMvc.perform(post("/api/media/uploads/batch/{batchId}/attach", batchId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã gắn batch"))
                .andExpect(jsonPath("$.data.attached").value(2));
    }

    private void authenticate() {
        Account account = account(EMAIL, "CUSTOMER");
        when(accountRepository.findWithRoleByEmail(EMAIL)).thenReturn(Optional.of(account));
        when(accountQueryService.requireIdByEmail(EMAIL)).thenReturn(1L);
    }

    private Account account(String email, String roleName) {
        Role role = mock(Role.class);
        when(role.getName()).thenReturn(roleName);
        Account account = mock(Account.class);
        when(account.getEmail()).thenReturn(email);
        when(account.getRole()).thenReturn(role);
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.isLoginAllowed()).thenReturn(true);
        return account;
    }

    private String tokenFor(String email) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("self-storage")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .claim("email", email)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
