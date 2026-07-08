package ru.glowbyte.streaming.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Конфигурация из переменных окружения.
 *
 * Обязательные:
 *   YANDEX_EMAIL            — адрес почты (логин), например user@company.ru
 *
 * Авторизация (одно из двух):
 *   YANDEX_OAUTH_TOKEN      — OAuth-токен (используется и для почты, и для календаря)
 *   YANDEX_MAIL_PASSWORD    — пароль приложения типа «Почта» (IMAP/SMTP)
 *   YANDEX_CALDAV_PASSWORD  — пароль приложения типа «Календарь» (CalDAV)
 *
 * Опциональные:
 *   YANDEX_MCP_ATTACHMENTS_DIR — куда сохранять вложения (по умолчанию ~/Downloads/yandex-mcp)
 */
public record Config(
        String email,
        String oauthToken,
        String mailPassword,
        String caldavPassword,
        Path attachmentsDir
) {

    public static Config fromEnv() {
        String email = env("YANDEX_EMAIL");
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("Не задана переменная окружения YANDEX_EMAIL");
        }
        String token = env("YANDEX_OAUTH_TOKEN");
        String mailPassword = env("YANDEX_MAIL_PASSWORD");
        String caldavPassword = env("YANDEX_CALDAV_PASSWORD");
        if (token == null && mailPassword == null && caldavPassword == null) {
            throw new IllegalStateException(
                    "Задайте YANDEX_OAUTH_TOKEN либо пароли приложений YANDEX_MAIL_PASSWORD / YANDEX_CALDAV_PASSWORD");
        }
        String dir = env("YANDEX_MCP_ATTACHMENTS_DIR");
        Path attachmentsDir = dir != null
                ? Paths.get(dir)
                : Paths.get(System.getProperty("user.home"), "Downloads", "yandex-mcp");
        return new Config(email, token, mailPassword, caldavPassword, attachmentsDir);
    }

    public boolean useOauth() {
        return oauthToken != null && !oauthToken.isBlank();
    }

    public boolean mailConfigured() {
        return useOauth() || (mailPassword != null && !mailPassword.isBlank());
    }

    public boolean caldavConfigured() {
        return useOauth() || (caldavPassword != null && !caldavPassword.isBlank());
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v.trim();
    }
}
