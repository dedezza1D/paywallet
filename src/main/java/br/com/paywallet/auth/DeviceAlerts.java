package br.com.paywallet.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

import br.com.paywallet.external.EmailSender;
import br.com.paywallet.user.User;

/**
 * Tells the customer by email when their account signs in from a device it has not seen before, so a stolen
 * password shows up right away. Devices are identified by the app's {@code X-Device-Id}, or by the browser's user
 * agent when absent. The very first sign-in of an account is not reported.
 */
@Component
class DeviceAlerts {

    record Device(String deviceId, String userAgent, String ip) {
    }

    private static final Logger log = LoggerFactory.getLogger(DeviceAlerts.class);

    private final JdbcTemplate jdbc;
    private final EmailSender email;
    private final Clock clock;

    DeviceAlerts(JdbcTemplate jdbc, EmailSender email, Clock clock) {
        this.jdbc = jdbc;
        this.email = email;
        this.clock = clock;
    }

    void recordSignIn(User user, Device device) {
        Instant now = clock.instant();
        String hash = hash(device.deviceId() != null ? "id:" + device.deviceId() : "ua:" + device.userAgent());
        Integer known = jdbc.queryForObject("SELECT count(*) FROM known_devices WHERE user_id = ?", Integer.class,
                user.getId());
        Integer seen = jdbc.queryForObject(
                "SELECT count(*) FROM known_devices WHERE user_id = ? AND device_hash = ?", Integer.class,
                user.getId(), hash);
        jdbc.update("""
                INSERT INTO known_devices (user_id, device_hash, user_agent, last_ip, first_seen, last_seen)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, device_hash)
                DO UPDATE SET last_ip = EXCLUDED.last_ip, last_seen = EXCLUDED.last_seen
                """, user.getId(), hash, truncate(device.userAgent()), device.ip(), Timestamp.from(now),
                Timestamp.from(now));
        if (seen != null && seen == 0 && known != null && known > 0) {
            try {
                email.send(user.getEmail(), "New sign-in to your PayWallet account", """
                        Your account was just accessed from a new device.

                        When: %s (UTC)
                        IP address: %s
                        Device: %s

                        If this was not you, change your password and contact support right away.
                        """.formatted(now, device.ip(), device.userAgent() == null ? "unknown" : device.userAgent()));
            } catch (MailException e) {
                log.error("New device alert to {} not sent: {}", user.getEmail(), e.getMessage());
            }
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 255 ? value : value.substring(0, 255);
    }

    private static String hash(String value) {
        try {
            byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
