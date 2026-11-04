package vn.lemar.selfstorage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import vn.lemar.selfstorage.identity.application.AccountJwtAuthenticationConverter;
import vn.lemar.selfstorage.identity.application.AuthService;
import vn.lemar.selfstorage.identity.application.EmailVerificationResult;
import vn.lemar.selfstorage.identity.application.RegistrationService;
import vn.lemar.selfstorage.identity.application.dto.LoginRequest;
import vn.lemar.selfstorage.identity.application.dto.LoginResponse;
import vn.lemar.selfstorage.identity.application.dto.RegisterRequest;
import vn.lemar.selfstorage.identity.application.dto.ResendVerificationRequest;
import vn.lemar.selfstorage.identity.application.dto.VerifyEmailRequest;
import vn.lemar.selfstorage.identity.application.exception.AccountNotAllowedException;
import vn.lemar.selfstorage.identity.application.exception.EmailAlreadyExistsException;
import vn.lemar.selfstorage.identity.application.exception.EmailNotRegisteredException;
import vn.lemar.selfstorage.identity.application.exception.EmailNotVerifiedException;
import vn.lemar.selfstorage.identity.application.exception.InvalidCredentialsException;
import vn.lemar.selfstorage.identity.application.exception.InvalidVerificationCodeException;
import vn.lemar.selfstorage.identity.application.exception.TooManyLoginAttemptsException;
import vn.lemar.selfstorage.identity.application.exception.TooManyVerificationAttemptsException;
import vn.lemar.selfstorage.identity.application.exception.VerificationResendTooEarlyException;
import vn.lemar.selfstorage.identity.config.JwtConfiguration;
import vn.lemar.selfstorage.identity.controller.AuthController;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtConfiguration.class, AccountJwtAuthenticationConverter.class})
@TestPropertySource(properties = "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private RegistrationService registrationService;

    @MockBean
    private AccountRepository accountRepository;

    @Test
    void loginReturnsTokenContract() throws Exception {
        when(authService.login(any(LoginRequest.class), anyString()))
                .thenReturn(new LoginResponse(1L, "customer@example.com", "signed.jwt.token"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@example.com\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đăng nhập thành công"))
                .andExpect(jsonPath("$.data.accountId").value(1))
                .andExpect(jsonPath("$.data.email").value("customer@example.com"))
                .andExpect(jsonPath("$.data.token").value("signed.jwt.token"));
    }

    @Test
    void loginReturns401BodyForInvalidCredentials() throws Exception {
        when(authService.login(any(LoginRequest.class), anyString()))
                .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Email hoặc mật khẩu không đúng"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void loginReturns403BodyForBannedAccount() throws Exception {
        when(authService.login(any(LoginRequest.class), anyString()))
                .thenThrow(new AccountNotAllowedException(AccountStatus.BANNED));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"banned@lemar.vn\",\"password\":\"Test@1234\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Tài khoản đã bị chặn"));
    }

    @Test
    void loginReturns403BodyForUnverifiedEmail() throws Exception {
        when(authService.login(any(LoginRequest.class), anyString()))
                .thenThrow(new EmailNotVerifiedException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unverified@lemar.vn\",\"password\":\"Test@1234\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Tài khoản chưa xác minh email"));
    }

    @Test
    void loginReturns429BodyWhenRateLimited() throws Exception {
        when(authService.login(any(LoginRequest.class), anyString()))
                .thenThrow(new TooManyLoginAttemptsException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@example.com\",\"password\":\"password\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Quá nhiều lần thử. Vui lòng thử lại sau."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void loginRejectsMalformedEmail() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"khong-phai-email\",\"password\":\"password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Dữ liệu không hợp lệ"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void loginRejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Dữ liệu không hợp lệ"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void registerReturnsSuccessMessage() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Khách Test\",\"email\":\"customer@example.com\","
                                + "\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đăng ký thành công. Kiểm tra email để lấy mã xác minh."))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void registerReturns409ForExistingEmail() throws Exception {
        doThrow(new EmailAlreadyExistsException())
                .when(registrationService).register(any(RegisterRequest.class));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Khách Test\",\"email\":\"customer@example.com\","
                                + "\"password\":\"password123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email đã được đăng ký"));
    }

    @Test
    void registerRejectsShortPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Khách Test\",\"email\":\"customer@example.com\","
                                + "\"password\":\"ngan\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Dữ liệu không hợp lệ"));
    }

    @Test
    void verifyEmailReturnsSuccessMessage() throws Exception {
        when(registrationService.verifyEmail(any(VerifyEmailRequest.class)))
                .thenReturn(EmailVerificationResult.VERIFIED);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Xác minh email thành công"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void verifyEmailReturnsExplicitMessageWhenAlreadyVerified() throws Exception {
        when(registrationService.verifyEmail(any(VerifyEmailRequest.class)))
                .thenReturn(EmailVerificationResult.ALREADY_VERIFIED);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Email đã được xác minh trước đó"));
    }

    @Test
    void verifyEmailReturns429WhenAttemptsExhausted() throws Exception {
        doThrow(new TooManyVerificationAttemptsException())
                .when(registrationService).verifyEmail(any(VerifyEmailRequest.class));

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@example.com\",\"code\":\"000000\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message")
                        .value("Bạn đã nhập sai quá nhiều lần. Vui lòng gửi lại mã mới."));
    }

    @Test
    void verifyEmailRejectsMalformedCode() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@example.com\",\"code\":\"12ab\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Dữ liệu không hợp lệ"));
    }

    @Test
    void verifyEmailReturns400ForExpiredOrWrongCode() throws Exception {
        doThrow(new InvalidVerificationCodeException())
                .when(registrationService).verifyEmail(any(VerifyEmailRequest.class));

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Mã xác minh không đúng hoặc đã hết hạn"));
    }

    @Test
    void resendVerificationReturns429DuringCooldown() throws Exception {
        doThrow(new VerificationResendTooEarlyException())
                .when(registrationService).resendVerification(any(ResendVerificationRequest.class));

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Vui lòng chờ trước khi yêu cầu gửi lại mã."));
    }

    @Test
    void resendVerificationReturns400ForUnknownEmail() throws Exception {
        doThrow(new EmailNotRegisteredException())
                .when(registrationService).resendVerification(any(ResendVerificationRequest.class));

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ghost@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email chưa được đăng ký"));
    }
}
