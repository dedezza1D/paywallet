package br.com.paywallet.crypto;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Brings every encrypted column up to the active data key: rows still in plaintext (written before encryption
 * existed) and rows under a rotated-out key are re-encrypted, filling blind indexes where the column has one. Runs
 * on startup and after each rotation; safe to run again at any time.
 */
@Component
public class PiiEncryption implements ApplicationRunner {

    private record EncryptedColumn(String table, String column, String indexColumn) {
    }

    private static final Logger log = LoggerFactory.getLogger(PiiEncryption.class);
    private static final int BATCH = 500;
    private static final List<EncryptedColumn> COLUMNS = List.of(
            new EncryptedColumn("users", "document", "document_index"),
            new EncryptedColumn("pix_keys", "key_value", "value_index"),
            new EncryptedColumn("pix_payments", "key_value", null),
            new EncryptedColumn("marketplace_orders", "phone_number", null));

    private final JdbcTemplate jdbc;
    private final FieldCipher cipher;
    private final KeyRing keys;

    PiiEncryption(JdbcTemplate jdbc, FieldCipher cipher, KeyRing keys) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.keys = keys;
    }

    @Override
    public void run(ApplicationArguments args) {
        int updated = reencryptAll();
        if (updated > 0) {
            log.info("Encrypted {} values with data key {}", updated, keys.activeDataKeyId());
        }
    }

    public int reencryptAll() {
        String current = FieldCipher.PREFIX + keys.activeDataKeyId() + ":%";
        int total = 0;
        for (EncryptedColumn target : COLUMNS) {
            int batch;
            do {
                record Row(Object id, String value) {
                }
                List<Row> rows = jdbc.query("SELECT id, %s FROM %s WHERE %s IS NOT NULL AND %s NOT LIKE ? LIMIT %d"
                                .formatted(target.column(), target.table(), target.column(), target.column(), BATCH),
                        (rs, i) -> new Row(rs.getObject(1), rs.getString(2)), current);
                for (Row row : rows) {
                    String plain = cipher.decrypt(row.value());
                    if (target.indexColumn() == null) {
                        jdbc.update("UPDATE %s SET %s = ? WHERE id = ?".formatted(target.table(), target.column()),
                                cipher.encrypt(plain), row.id());
                    } else {
                        jdbc.update("UPDATE %s SET %s = ?, %s = ? WHERE id = ?"
                                        .formatted(target.table(), target.column(), target.indexColumn()),
                                cipher.encrypt(plain), cipher.blindIndex(plain), row.id());
                    }
                }
                batch = rows.size();
                total += batch;
            } while (batch == BATCH);
        }
        return total;
    }
}
