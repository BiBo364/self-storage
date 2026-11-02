package vn.lemar.selfstorage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String ACTIVE_ACCOUNT_AUTHORITY = "ACCOUNT_ACTIVE";

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            Converter<Jwt, JwtAuthenticationToken> authenticationConverter,
            ObjectMapper objectMapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/me").hasAuthority(ACTIVE_ACCOUNT_AUTHORITY)
                        .requestMatchers("/api/health", "/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/rental-requests").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/unit-types").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/facilities").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Callback của VNPay: server VNPay gọi `/ipn` và không có JWT, còn `/return`
                        // là redirect trình duyệt nên cũng không mang Authorization. Bắt buộc public,
                        // phải đứng trước dòng vét `/api/**` bên dưới. Thay vì token, hai route này
                        // tự xác thực bằng chữ ký HMAC-SHA512 trong `vnp_SecureHash`, và `/return`
                        // không ghi dữ liệu.
                        .requestMatchers("/api/payments/vnpay/ipn", "/api/payments/vnpay/return").permitAll()
                        .requestMatchers("/api/customer/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/staff/**").hasRole("FS")
                        .requestMatchers("/api/fm/**").hasRole("FM")
                        .requestMatchers("/api/bom/**").hasRole("BOM")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasAuthority(ACTIVE_ACCOUNT_AUTHORITY)
                        .anyRequest().permitAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter))
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder(
            @Value("${security.password.bcrypt-strength:10}") int bcryptStrength) {
        return new BCryptPasswordEncoder(bcryptStrength);
    }
}
