package vn.lemar.selfstorage;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Cho phép cấu hình DB bằng chuỗi kết nối dạng {@code postgresql://user:pass@host/db?sslmode=require} (Neon và các
 * nền tảng deploy) thay cho bộ {@code DB_URL} (JDBC) / {@code DB_USER} / {@code DB_PASSWORD}.
 *
 * <p>Chỉ chạy khi <b>không</b> đặt {@code DB_URL}: máy dev và docker compose (đặt {@code DB_URL}) giữ nguyên DB local.
 * Đọc {@code DATABASE_URL_UNPOOLED} trước, rồi {@code DATABASE_URL}: backend tự có pool Hikari và Flyway khoá migration
 * bằng advisory lock theo session — không hợp với pooler chế độ transaction, nên ưu tiên kết nối thẳng. Tham số
 * {@code channel_binding} (tên libpq) đổi sang {@code channelBinding} (tên pgjdbc); tham số khác giữ nguyên.
 * Chạy sau khi {@code spring.config.import} đã nạp {@code .env}.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE = "databaseUrl";
    private static final String[] SOURCES = {"DATABASE_URL_UNPOOLED", "DATABASE_URL"};

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (hasText(environment.getProperty("DB_URL"))) {
            return;
        }
        for (String source : SOURCES) {
            String raw = environment.getProperty(source);
            if (hasText(raw)) {
                toJdbc(raw).ifPresent(connection ->
                        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE,
                                connection.asProperties())));
                return;
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** Đổi URL dạng libpq sang JDBC; URL không phải postgres / thiếu host → rỗng. */
    static Optional<JdbcConnection> toJdbc(String raw) {
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("postgresql".equals(scheme) || "postgres".equals(scheme))) {
            return Optional.empty();
        }
        StringBuilder url = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            url.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath();
        url.append(path == null || path.isEmpty() ? "/" : path);
        String query = jdbcQuery(uri.getRawQuery());
        if (!query.isEmpty()) {
            url.append('?').append(query);
        }
        String user = null;
        String password = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            user = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            password = colon < 0 ? null : decode(userInfo.substring(colon + 1));
        }
        return Optional.of(new JdbcConnection(url.toString(), user, password));
    }

    private static String jdbcQuery(String rawQuery) {
        StringJoiner joined = new StringJoiner("&");
        if (rawQuery == null) {
            return "";
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            joined.add(pair.startsWith("channel_binding=") ? "channelBinding=" + pair.substring(16) : pair);
        }
        return joined.toString();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Kết quả chuyển đổi; user / password {@code null} khi URL không mang. */
    record JdbcConnection(String url, String user, String password) {

        Map<String, Object> asProperties() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("DB_URL", url);
            if (user != null) {
                properties.put("DB_USER", user);
            }
            if (password != null) {
                properties.put("DB_PASSWORD", password);
            }
            return properties;
        }

        @Override
        public String toString() {
            return "JdbcConnection[url=" + url + ", user=" + user + ", password=***]";
        }
    }
}
