package vn.lemar.selfstorage;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
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
 * Chạy sau khi {@code spring.config.import} đã nạp {@code .env}. Giá trị bọc ngoặc kép (cách {@code neon env pull}
 * ghi file) được bỏ ngoặc. Có biến nhưng không đọc được, hoặc {@code DB_URL} đang lấn biến này → ghi log khi khởi động
 * để không âm thầm rơi về DB mặc định.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE = "databaseUrl";
    private static final String[] SOURCES = {"DATABASE_URL_UNPOOLED", "DATABASE_URL"};

    private final Log log;

    public DatabaseUrlEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DatabaseUrlEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String source = null;
        for (String candidate : SOURCES) {
            if (hasText(environment.getProperty(candidate))) {
                source = candidate;
                break;
            }
        }
        if (source == null) {
            return;
        }
        if (hasText(environment.getProperty("DB_URL"))) {
            log.info("DB_URL đã đặt nên bỏ qua " + source + " — muốn dùng " + source + " thì xoá DB_URL");
            return;
        }
        String name = source;
        Optional<JdbcConnection> connection = toJdbc(environment.getProperty(source));
        connection.ifPresentOrElse(found -> {
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, found.asProperties()));
            log.info("Kết nối DB lấy từ " + name + " (" + found.host() + ")");
        }, () -> log.warn(name + " có giá trị nhưng không phải URL postgresql://user:pass@host/db hợp lệ — "
                + "dùng DB_URL mặc định"));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** Đổi URL dạng libpq sang JDBC; URL không phải postgres / thiếu host → rỗng. */
    static Optional<JdbcConnection> toJdbc(String raw) {
        URI uri;
        try {
            uri = new URI(unquote(raw));
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

    /** Bỏ khoảng trắng và một cặp ngoặc kép / ngoặc đơn bao ngoài (cách file .env hay bọc giá trị). */
    private static String unquote(String raw) {
        String value = raw.trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
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

        /** Host (kèm cổng nếu có) để ghi log — không có user / mật khẩu. */
        String host() {
            String rest = url.substring("jdbc:postgresql://".length());
            int slash = rest.indexOf('/');
            return slash < 0 ? rest : rest.substring(0, slash);
        }

        @Override
        public String toString() {
            return "JdbcConnection[url=" + url + ", user=" + user + ", password=***]";
        }
    }
}
