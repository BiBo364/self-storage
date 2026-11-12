package vn.lemar.selfstorage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

class DatabaseUrlEnvironmentPostProcessorTest {

    private final DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();

    private MockEnvironment run(MockEnvironment environment) {
        processor.postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    @Test
    void neonUrlBecomesJdbcSettingsPreferringTheDirectConnection() {
        MockEnvironment env = run(new MockEnvironment()
                .withProperty("DATABASE_URL",
                        "postgresql://app:pooled@ep-x-pooler.ap.aws.neon.tech/neondb?sslmode=require")
                .withProperty("DATABASE_URL_UNPOOLED",
                        "postgresql://app:p%40ss%3Aw0rd@ep-x.ap.aws.neon.tech/neondb"
                                + "?sslmode=require&channel_binding=require"));

        assertThat(env.getProperty("DB_URL"))
                .isEqualTo("jdbc:postgresql://ep-x.ap.aws.neon.tech/neondb?sslmode=require&channelBinding=require");
        assertThat(env.getProperty("DB_USER")).isEqualTo("app");
        assertThat(env.getProperty("DB_PASSWORD")).isEqualTo("p@ss:w0rd");
    }

    @Test
    void fallsBackToThePooledUrlWithAPort() {
        MockEnvironment env = run(new MockEnvironment()
                .withProperty("DATABASE_URL", "postgres://app:secret@db.example.com:6543/app"));

        assertThat(env.getProperty("DB_URL")).isEqualTo("jdbc:postgresql://db.example.com:6543/app");
        assertThat(env.getProperty("DB_PASSWORD")).isEqualTo("secret");
    }

    @Test
    void anExplicitJdbcUrlWins() {
        MockEnvironment env = run(new MockEnvironment()
                .withProperty("DB_URL", "jdbc:postgresql://localhost:5432/self_storage")
                .withProperty("DATABASE_URL", "postgresql://app:secret@ep-x.neon.tech/neondb"));

        assertThat(env.getProperty("DB_URL")).isEqualTo("jdbc:postgresql://localhost:5432/self_storage");
        assertThat(env.getPropertySources().contains(DatabaseUrlEnvironmentPostProcessor.PROPERTY_SOURCE)).isFalse();
    }

    @Test
    void nothingChangesWithoutAUsableUrl() {
        MockEnvironment none = run(new MockEnvironment());
        MockEnvironment mysql = run(new MockEnvironment().withProperty("DATABASE_URL", "mysql://u:p@host/db"));
        MockEnvironment broken = run(new MockEnvironment().withProperty("DATABASE_URL", "postgresql://bad host/db"));
        MockEnvironment blank = run(new MockEnvironment().withProperty("DATABASE_URL_UNPOOLED", " "));

        for (MockEnvironment env : new MockEnvironment[] {none, mysql, broken, blank}) {
            assertThat(env.getProperty("DB_URL")).isNull();
        }
    }

    @Test
    void urlWithoutCredentialsOrDatabaseKeepsTheDefaults() {
        MockEnvironment env = run(new MockEnvironment()
                .withProperty("DATABASE_URL", "postgresql://db.example.com?&sslmode=require")
                .withProperty("DB_USER", "self_storage"));

        assertThat(env.getProperty("DB_URL")).isEqualTo("jdbc:postgresql://db.example.com/?sslmode=require");
        assertThat(env.getProperty("DB_USER")).isEqualTo("self_storage");
        assertThat(env.getProperty("DB_PASSWORD")).isNull();
    }

    @Test
    void userWithoutPasswordAndHiddenPasswordInToString() {
        var connection = DatabaseUrlEnvironmentPostProcessor.toJdbc("postgresql://only-user@h/db").orElseThrow();

        assertThat(connection.user()).isEqualTo("only-user");
        assertThat(connection.password()).isNull();
        assertThat(DatabaseUrlEnvironmentPostProcessor.toJdbc("postgresql://u:topsecret@h/db").orElseThrow()
                .toString()).doesNotContain("topsecret");
        assertThat(processor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
