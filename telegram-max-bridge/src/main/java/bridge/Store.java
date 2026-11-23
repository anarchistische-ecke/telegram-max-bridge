package bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.databind.JsonNode;

final class Store implements AutoCloseable {
    record Job(long seq, String platform, String lane, String data, double received, int attempts) {}
    record Route(long id, String platform, String owner, String source, String target,
                 String mode, List<String> terms, boolean active, double enabledSince) {}
    record Origin(String target, String platform, String source, String message, String thread) {}

    private record LegacyJob(long seq, String platform, String data) {}
    private record Pair(String telegram, String maxUser) {}
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ReentrantLock databaseLock = new ReentrantLock(true);
    private final Connection db;

    static final class Failure extends RuntimeException {
        Failure(SQLException cause) {
            super("SQLite operation failed", cause);
        }
    }

    Store(Path path) throws IOException, SQLException {
        path = path.toAbsolutePath();
        Files.createDirectories(path.getParent());
        try {
            if (Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")) {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(path);
            }
        } catch (FileAlreadyExistsException ignored) {
            // Existing databases keep their original filesystem permissions.
        }
        String schema;
        try (var stream = Store.class.getResourceAsStream("/schema.sql")) {
            schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        db = DriverManager.getConnection("jdbc:sqlite:" + path);
        try {
            try (var statement = db.createStatement()) {
                statement.execute("PRAGMA busy_timeout=10000");
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA foreign_keys=ON");
                for (String sql : schema.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
            migrate();
        } catch (SQLException | RuntimeException error) {
            try {
                db.close();
            } catch (SQLException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    private void migrate() {
        transaction(() -> {
            if (query("PRAGMA table_info(inbox)", row -> row.getString("name")).stream().noneMatch("lane"::equals)) {
                execute("ALTER TABLE inbox ADD COLUMN lane TEXT");
            }
            List<LegacyJob> batch;
            do {
                batch = query("""
                        SELECT seq, platform, data FROM inbox WHERE lane IS NULL AND data IS NOT NULL
                        ORDER BY seq LIMIT 500
                        """, row -> new LegacyJob(row.getLong(1), row.getString(2), row.getString(3)));
                for (LegacyJob job : batch) {
                    Update update = Update.from(job.platform(), Json.MAPPER.readTree(job.data()));
                    String lane = update == null ? "legacy:" + job.seq() : update.lane();
                    execute("UPDATE inbox SET lane=? WHERE seq=?", lane, job.seq());
                }
            } while (!batch.isEmpty());
            execute("CREATE INDEX IF NOT EXISTS inbox_lane ON inbox(lane, seq) WHERE data IS NOT NULL");
            execute("""
                    CREATE TABLE IF NOT EXISTS inbox_heads (
                        lane TEXT PRIMARY KEY,
                        seq INTEGER NOT NULL UNIQUE REFERENCES inbox(seq)
                    )
                    """);
            execute("""
                    INSERT INTO inbox_heads(lane, seq)
                    SELECT lane, MIN(seq) FROM inbox WHERE data IS NOT NULL GROUP BY lane
                    ON CONFLICT(lane) DO UPDATE SET seq=excluded.seq
                    """);
            execute("""
                    CREATE TRIGGER IF NOT EXISTS inbox_head_on_enqueue
                    AFTER INSERT ON inbox WHEN NEW.data IS NOT NULL AND NEW.lane IS NOT NULL
                    BEGIN
                        INSERT OR IGNORE INTO inbox_heads VALUES (NEW.lane, NEW.seq);
                    END
                    """);
            execute("""
                    CREATE TRIGGER IF NOT EXISTS inbox_head_on_finish
                    AFTER UPDATE OF data ON inbox
                    WHEN OLD.data IS NOT NULL AND NEW.data IS NULL AND OLD.lane IS NOT NULL
                    BEGIN
                        DELETE FROM inbox_heads WHERE seq=OLD.seq;
                        INSERT OR IGNORE INTO inbox_heads
                            SELECT lane, seq FROM inbox WHERE lane=OLD.lane AND data IS NOT NULL
                            ORDER BY seq LIMIT 1;
                    END
                    """);
            return null;
        });
    }

    void enqueue(Update update) {
        double received = now();
        locked(() -> execute("""
                INSERT INTO inbox(platform, event, data, received, lane) VALUES(?, ?, ?, ?, ?)
                ON CONFLICT(platform, event) DO NOTHING
                """, update.platform(), update.key(), update.data().toString(), received, update.lane()));
    }

    List<Job> ready(Collection<String> running, int limit) {
        String excluded = running.isEmpty() ? ""
                : " AND head.lane NOT IN (" + String.join(",", Collections.nCopies(running.size(), "?")) + ")";
        List<Object> params = new ArrayList<>();
        params.add(now());
        params.addAll(running);
        params.add(limit);
        return locked(() -> query("""
                SELECT job.seq, job.platform, job.lane, job.data, job.received, job.attempts
                FROM inbox_heads head JOIN inbox job ON head.seq=job.seq
                WHERE job.available <= ?
                """ + excluded + " ORDER BY head.seq LIMIT ?", row -> new Job(
                row.getLong(1), row.getString(2), row.getString(3), row.getString(4),
                row.getDouble(5), row.getInt(6)), params.toArray()));
    }

    void finish(long seq) {
        transaction(() -> {
            execute("UPDATE inbox SET data=NULL WHERE seq=?", seq);
            execute("DELETE FROM steps WHERE seq=?", seq);
            return null;
        });
    }

    void retry(long seq, int delay) {
        locked(() -> execute("UPDATE inbox SET attempts=attempts+1, available=? WHERE seq=?", now() + delay, seq));
    }

    void defer(long seq, double delay) {
        locked(() -> execute("UPDATE inbox SET available=? WHERE seq=?", now() + delay, seq));
    }

    void prune() {
        transaction(() -> {
            double now = now();
            execute("DELETE FROM inbox WHERE data IS NULL AND received < ?", now - 7 * 86400);
            execute("DELETE FROM link_codes WHERE expires <= ?", now);
            return null;
        });
    }

    JsonNode step(long seq, String action) {
        String result = locked(() -> scalar("SELECT result FROM steps WHERE seq=? AND action=?", seq, action));
        return result == null ? null : Json.MAPPER.readTree(result);
    }

    void saveStep(long seq, String action, JsonNode result, Origin origin) {
        transaction(() -> {
            if (origin != null) {
                execute("INSERT INTO message_links VALUES (?, ?, ?, ?, ?, ?)", origin.target(), result.asString(),
                        origin.platform(), origin.source(), origin.message(), origin.thread());
            }
            execute("INSERT INTO steps VALUES (?, ?, ?)", seq, action, result.toString());
            return null;
        });
    }

    Origin origin(String target, String mid) {
        return locked(() -> query("""
                SELECT platform, source, source_message, thread FROM message_links
                WHERE target=? AND target_message=?
                """, row -> new Origin(target, row.getString(1), row.getString(2), row.getString(3), row.getString(4)),
                target, mid).stream().findFirst().orElse(null));
    }

    void selectSource(String platform, String owner, String source) {
        locked(() -> execute("""
                INSERT INTO profiles VALUES (?, ?, ?) ON CONFLICT(platform, owner)
                DO UPDATE SET source=excluded.source
                """, platform, owner, source));
    }

    String selectedSource(String platform, String owner) {
        return locked(() -> scalar("SELECT source FROM profiles WHERE platform=? AND owner=?", platform, owner));
    }

    String maxUser(String owner) {
        return locked(() -> scalar("SELECT max_user FROM account_links WHERE telegram=?", owner));
    }

    String issueLink(String owner, long seq) {
        return transaction(() -> {
            String saved = scalar("SELECT result FROM steps WHERE seq=? AND action='link-code'", seq);
            if (saved != null) {
                return Json.MAPPER.readTree(saved).asString();
            }
            byte[] random = new byte[24];
            RANDOM.nextBytes(random);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            execute("DELETE FROM link_codes WHERE telegram=?", owner);
            execute("INSERT INTO link_codes VALUES (?, ?, ?)", Json.sha256(token), owner, now() + 600);
            execute("INSERT INTO steps VALUES (?, 'link-code', ?)", seq, Json.MAPPER.writeValueAsString(token));
            return token;
        });
    }

    String redeemLink(String token, String maxUser, long seq) {
        return transaction(() -> {
            String saved = scalar("SELECT result FROM steps WHERE seq=? AND action='linked'", seq);
            if (saved != null) {
                return Json.MAPPER.readTree(saved).asString();
            }
            String digest = Json.sha256(token);
            String owner = scalar("SELECT telegram FROM link_codes WHERE digest=? AND expires>?", digest, now());
            if (owner == null) {
                return "Ссылка истекла или уже использована. Откройте «Аккаунт MAX» в меню бота Telegram и получите новую ссылку.";
            }
            List<Pair> pairs = query("SELECT * FROM account_links WHERE telegram=? OR max_user=?",
                    row -> new Pair(row.getString(1), row.getString(2)), owner, maxUser);
            if (pairs.stream().anyMatch(pair -> !pair.telegram().equals(owner) || !pair.maxUser().equals(maxUser))) {
                return "Один из аккаунтов уже подключён. Отключите его через «Аккаунт MAX» в Telegram.";
            }
            execute("INSERT OR IGNORE INTO account_links VALUES (?, ?)", owner, maxUser);
            execute("DELETE FROM link_codes WHERE digest=?", digest);
            String result = Menu.LINKED;
            execute("INSERT INTO steps VALUES (?, 'linked', ?)", seq, Json.MAPPER.writeValueAsString(result));
            return result;
        });
    }

    void unlink(String owner) {
        transaction(() -> {
            execute("DELETE FROM account_links WHERE telegram=?", owner);
            execute("DELETE FROM link_codes WHERE telegram=?", owner);
            execute("UPDATE routes SET active=0 WHERE platform='telegram' AND owner=?", owner);
            return null;
        });
    }

    void rememberMaxChat(String chat) {
        locked(() -> execute("INSERT OR IGNORE INTO max_chats VALUES (?)", chat));
    }

    void forgetMaxChat(String chat) {
        locked(() -> execute("DELETE FROM max_chats WHERE id=?", chat));
    }

    List<String> maxChats(int page, int size) {
        return locked(() -> query("SELECT id FROM max_chats ORDER BY id LIMIT ? OFFSET ?",
                row -> row.getString(1), size + 1, page * size));
    }

    void rememberTelegramChat(String chat) {
        locked(() -> execute("INSERT OR IGNORE INTO telegram_chats VALUES (?)", chat));
    }

    void forgetTelegramChat(String chat) {
        locked(() -> execute("DELETE FROM telegram_chats WHERE id=?", chat));
    }

    List<String> telegramSources(String owner, int page, int size) {
        return locked(() -> query("""
                SELECT id FROM telegram_chats
                UNION SELECT source FROM profiles WHERE platform='telegram' AND owner=?
                UNION SELECT source FROM routes WHERE platform='telegram' AND owner=?
                ORDER BY 1 LIMIT ? OFFSET ?
                """, row -> row.getString(1), owner, owner, size + 1, page * size));
    }

    Menu.State menu(String platform, String owner) {
        String state = locked(() -> scalar("SELECT state FROM menus WHERE platform=? AND owner=?", platform, owner));
        return state == null ? null : Json.MAPPER.readValue(state, Menu.State.class);
    }

    void saveMenu(Job job, String owner, Menu.State state, Menu.Screen screen) {
        transaction(() -> {
            execute("""
                    INSERT INTO menus VALUES (?, ?, ?) ON CONFLICT(platform, owner)
                    DO UPDATE SET state=excluded.state
                    """, job.platform(), owner, Json.MAPPER.writeValueAsString(state));
            execute("INSERT INTO steps VALUES (?, 'menu-screen', ?)", job.seq(), Json.MAPPER.writeValueAsString(screen));
            return null;
        });
    }

    Route routeFor(String platform, String owner, String source, String target) {
        return locked(() -> query("SELECT * FROM routes WHERE platform=? AND owner=? AND source=? AND target=?",
                Store::routeRow, platform, owner, source, target).stream().findFirst().orElse(null));
    }

    void editRoute(Route existing, String source, String target, String mode, List<String> terms, long seq) {
        transaction(() -> {
            if (scalar("SELECT result FROM steps WHERE seq=? AND action='menu-edit'", seq) == null) {
                boolean sameChats = existing.source().equals(source) && existing.target().equals(target);
                execute("""
                        UPDATE routes SET source=?, target=?, mode=?, terms=?, enabled_since=?
                        WHERE id=? AND platform=? AND owner=?
                        """, source, target, mode, Json.MAPPER.writeValueAsString(terms),
                        sameChats ? existing.enabledSince() : now(), existing.id(), existing.platform(), existing.owner());
                execute("INSERT INTO steps VALUES (?, 'menu-edit', 'true')", seq);
            }
            return null;
        });
    }

    long setRoute(String platform, String owner, String source, String target, String mode, List<String> terms,
                  long seq) {
        return transaction(() -> {
            String saved = scalar("SELECT result FROM steps WHERE seq=? AND action='set-route'", seq);
            if (saved != null) {
                return Long.parseLong(saved);
            }
            execute("""
                    INSERT INTO routes(platform, owner, source, target, mode, terms, active, enabled_since)
                    VALUES (?, ?, ?, ?, ?, ?, 1, ?) ON CONFLICT(platform, owner, source, target)
                    DO UPDATE SET mode=excluded.mode, terms=excluded.terms, active=1,
                        enabled_since=CASE WHEN routes.active THEN routes.enabled_since ELSE excluded.enabled_since END
                    """, platform, owner, source, target, mode, Json.MAPPER.writeValueAsString(terms), now());
            String id = scalar("SELECT id FROM routes WHERE platform=? AND owner=? AND source=? AND target=?",
                    platform, owner, source, target);
            execute("INSERT INTO steps VALUES (?, 'set-route', ?)", seq, id);
            return Long.parseLong(id);
        });
    }

    List<Route> activeRoutes(String platform, String source, double received) {
        return locked(() -> query("""
                SELECT * FROM routes WHERE platform=? AND source=? AND active=1 AND enabled_since<=?
                ORDER BY target, id
                """, Store::routeRow, platform, source, received));
    }

    List<Route> routes(String platform, String owner, int page, int size) {
        return locked(() -> query("SELECT * FROM routes WHERE platform=? AND owner=? ORDER BY id LIMIT ? OFFSET ?",
                Store::routeRow, platform, owner, size + 1, page * size));
    }

    Route route(String platform, String owner, long id) {
        return locked(() -> query("SELECT * FROM routes WHERE platform=? AND owner=? AND id=?",
                Store::routeRow, platform, owner, id).stream().findFirst().orElse(null));
    }

    void changeRoute(Route route, String command, String result, long seq) {
        transaction(() -> {
            switch (command) {
                case "remove" -> execute("DELETE FROM routes WHERE id=?", route.id());
                case "pause" -> execute("UPDATE routes SET active=0 WHERE id=?", route.id());
                case "resume" -> execute("""
                        UPDATE routes SET active=1, enabled_since=CASE WHEN active THEN enabled_since ELSE ? END
                        WHERE id=?
                        """, now(), route.id());
                default -> throw new IllegalArgumentException("Unknown route action");
            }
            execute("INSERT INTO steps VALUES (?, 'change-route', ?)", seq, Json.MAPPER.writeValueAsString(result));
            return null;
        });
    }

    private static Route routeRow(ResultSet row) throws SQLException {
        return new Route(row.getLong("id"), row.getString("platform"), row.getString("owner"),
                row.getString("source"), row.getString("target"), row.getString("mode"),
                Json.strings(Json.MAPPER.readTree(row.getString("terms"))),
                row.getBoolean("active"), row.getDouble("enabled_since"));
    }

    static double now() {
        Instant instant = Instant.now();
        return instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
    }

    private String scalar(String sql, Object... parameters) throws SQLException {
        return query(sql, row -> row.getString(1), parameters).stream().findFirst().orElse(null);
    }

    private <T> List<T> query(String sql, ReadRow<T> mapper, Object... parameters) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var rows = statement.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(mapper.read(rows));
                }
                return result;
            }
        }
    }

    private int execute(String sql, Object... parameters) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        }
    }

    private <T> T locked(SqlWork<T> work) {
        databaseLock.lock();
        try {
            return work.run();
        } catch (SQLException error) {
            throw new Failure(error);
        } finally {
            databaseLock.unlock();
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        return locked(() -> {
            db.setAutoCommit(false);
            try {
                T result = work.run();
                db.commit();
                return result;
            } catch (SQLException | RuntimeException | Error error) {
                try {
                    db.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            } finally {
                db.setAutoCommit(true);
            }
        });
    }

    @Override
    public void close() {
        locked(() -> {
            db.close();
            return null;
        });
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    @FunctionalInterface
    private interface ReadRow<T> {
        T read(ResultSet row) throws SQLException;
    }
}
