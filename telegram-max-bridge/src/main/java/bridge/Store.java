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
                 String mode, List<String> terms, boolean active, double enabledSince,
                 String targetPlatform, String name, long revision) {
        Route(long id, String platform, String owner, String source, String target,
              String mode, List<String> terms, boolean active, double enabledSince) {
            this(id, platform, owner, source, target, mode, terms, active, enabledSince, "max", "", 0);
        }

        String label() {
            return name.isBlank() ? "Профиль №" + id : name;
        }
    }
    record Identity(String telegram, String max) {
        String on(String platform) { return platform.equals("telegram") ? telegram : max; }
        boolean owns(Route route) { return route.owner().equals(on(route.platform())); }
        boolean linked() { return telegram != null && max != null; }
    }
    record Destination(String platform, String id) {}
    record Origin(Destination destination, String platform, String source, String message, String thread) {}

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
            migrateProfiles();
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

    private void migrateProfiles() throws SQLException {
        if (query("PRAGMA table_info(routes)", row -> row.getString("name")).contains("target_platform")) {
            return;
        }
        String previousSequence = scalar("SELECT seq FROM sqlite_sequence WHERE name='routes'");
        execute("ALTER TABLE routes RENAME TO legacy_routes");
        execute("""
                CREATE TABLE routes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, platform TEXT NOT NULL, owner TEXT NOT NULL,
                    source TEXT NOT NULL, target TEXT NOT NULL, mode TEXT NOT NULL, terms TEXT NOT NULL,
                    active INTEGER NOT NULL, enabled_since REAL NOT NULL,
                    target_platform TEXT NOT NULL DEFAULT 'max', name TEXT NOT NULL DEFAULT '',
                    revision INTEGER NOT NULL DEFAULT 0
                )
                """);
        execute("""
                INSERT INTO routes(id, platform, owner, source, target, mode, terms, active, enabled_since)
                SELECT id, platform, owner, source, target, mode, terms,
                       CASE WHEN platform='max' THEN 0 ELSE active END, enabled_since FROM legacy_routes
                """);
        execute("DROP TABLE legacy_routes");
        if (previousSequence != null) {
            execute("INSERT INTO sqlite_sequence(name, seq) SELECT 'routes', ? WHERE NOT EXISTS "
                    + "(SELECT 1 FROM sqlite_sequence WHERE name='routes')", Long.parseLong(previousSequence));
            execute("UPDATE sqlite_sequence SET seq=MAX(seq, ?) WHERE name='routes'", Long.parseLong(previousSequence));
        }
        execute("CREATE INDEX routes_by_source ON routes(platform, source, active, target)");
        execute("CREATE INDEX routes_by_owner ON routes(platform, owner, id)");

        execute("ALTER TABLE message_links RENAME TO legacy_message_links");
        execute("""
                CREATE TABLE message_links (
                    target TEXT NOT NULL, target_message TEXT NOT NULL, platform TEXT NOT NULL,
                    source TEXT NOT NULL, source_message TEXT NOT NULL, thread TEXT,
                    target_platform TEXT NOT NULL DEFAULT 'max',
                    PRIMARY KEY (target_platform, target, target_message)
                )
                """);
        execute("""
                INSERT INTO message_links(target, target_message, platform, source, source_message, thread)
                SELECT target, target_message, platform, source, source_message, thread FROM legacy_message_links
                """);
        execute("DROP TABLE legacy_message_links");
        execute("DELETE FROM menus");
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
            execute("DELETE FROM max_link_codes WHERE expires <= ?", now);
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
                execute("INSERT INTO message_links VALUES (?, ?, ?, ?, ?, ?, ?)", origin.destination().id(),
                        result.asString(), origin.platform(), origin.source(), origin.message(), origin.thread(),
                        origin.destination().platform());
            }
            execute("INSERT INTO steps VALUES (?, ?, ?)", seq, action, result.toString());
            return null;
        });
    }

    Origin origin(Destination target, String mid) {
        return locked(() -> query("""
                SELECT platform, source, source_message, thread FROM message_links
                WHERE target_platform=? AND target=? AND target_message=?
                """, row -> new Origin(target, row.getString(1), row.getString(2), row.getString(3), row.getString(4)),
                target.platform(), target.id(), mid).stream().findFirst().orElse(null));
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
        return identity("telegram", owner).max();
    }

    Identity identity(String platform, String owner) {
        return locked(() -> platform.equals("telegram")
                ? new Identity(owner, scalar("SELECT max_user FROM account_links WHERE telegram=?", owner))
                : new Identity(scalar("SELECT telegram FROM account_links WHERE max_user=?", owner), owner));
    }

    String issueLink(String owner, long seq) {
        return issueLink("telegram", owner, seq);
    }

    String issueLink(String platform, String owner, long seq) {
        String table = platform.equals("telegram") ? "link_codes" : "max_link_codes";
        String column = platform.equals("telegram") ? "telegram" : "owner";
        return transaction(() -> {
            String saved = scalar("SELECT result FROM steps WHERE seq=? AND action='link-code'", seq);
            if (saved != null) {
                return Json.MAPPER.readTree(saved).asString();
            }
            byte[] random = new byte[24];
            RANDOM.nextBytes(random);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            execute("DELETE FROM " + table + " WHERE " + column + "=?", owner);
            execute("INSERT INTO " + table + " VALUES (?, ?, ?)", Json.sha256(token), owner, now() + 600);
            execute("INSERT INTO steps VALUES (?, 'link-code', ?)", seq, Json.MAPPER.writeValueAsString(token));
            return token;
        });
    }

    String redeemLink(String platform, String token, String recipient, long seq) {
        boolean openedInMax = platform.equals("max");
        String table = openedInMax ? "link_codes" : "max_link_codes";
        String column = openedInMax ? "telegram" : "owner";
        return transaction(() -> {
            String saved = scalar("SELECT result FROM steps WHERE seq=? AND action='linked'", seq);
            if (saved != null) {
                return Json.MAPPER.readTree(saved).asString();
            }
            String digest = Json.sha256(token);
            String owner = scalar("SELECT " + column + " FROM " + table + " WHERE digest=? AND expires>?", digest, now());
            if (owner == null) {
                return "Ссылка истекла или уже использована. Откройте меню в другом приложении и получите новую ссылку.";
            }
            String telegram = openedInMax ? owner : recipient;
            String maxUser = openedInMax ? recipient : owner;
            List<Pair> pairs = query("SELECT * FROM account_links WHERE telegram=? OR max_user=?",
                    row -> new Pair(row.getString(1), row.getString(2)), telegram, maxUser);
            if (pairs.stream().anyMatch(pair -> !pair.telegram().equals(telegram) || !pair.maxUser().equals(maxUser))) {
                return "Один из аккаунтов уже связан. Отключите старую связь в разделе «Аккаунты».";
            }
            execute("INSERT OR IGNORE INTO account_links VALUES (?, ?)", telegram, maxUser);
            execute("DELETE FROM " + table + " WHERE digest=?", digest);
            String result = Menu.LINKED;
            execute("INSERT INTO steps VALUES (?, 'linked', ?)", seq, Json.MAPPER.writeValueAsString(result));
            return result;
        });
    }

    void unlink(String owner) {
        unlink("telegram", owner);
    }

    void unlink(String platform, String owner) {
        transaction(() -> {
            var pair = identity(platform, owner);
            execute("DELETE FROM account_links WHERE telegram=?", pair.telegram());
            execute("DELETE FROM link_codes WHERE telegram=?", pair.telegram());
            execute("DELETE FROM max_link_codes WHERE owner=?", pair.max());
            execute("""
                    UPDATE routes SET active=0, revision=revision+1
                    WHERE (platform='telegram' AND owner=? AND target_platform='max')
                       OR (platform='max' AND owner=? AND target_platform='telegram')
                    """, pair.telegram(), pair.max());
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

    List<Route> userProfiles(String platform, String owner, int page, int size) {
        return locked(() -> {
            var user = identity(platform, owner);
            return query("""
                    SELECT * FROM routes
                    WHERE platform<>target_platform
                      AND ((platform='telegram' AND owner=?) OR (platform='max' AND owner=?))
                    ORDER BY id LIMIT ? OFFSET ?
                    """, Store::routeRow, user.telegram(), user.max(), size + 1, page * size);
        });
    }

    Route userProfile(String platform, String owner, long id) {
        return locked(() -> {
            var profile = query("SELECT * FROM routes WHERE id=? AND platform<>target_platform", Store::routeRow, id)
                    .stream().findFirst().orElse(null);
            return profile != null && identity(platform, owner).owns(profile) ? profile : null;
        });
    }

    long saveProfile(Job job, String owner, Identity expectedUser, Menu.Draft draft) {
        return transaction(() -> {
            String prior = scalar("SELECT result FROM steps WHERE seq=? AND action='profile-save'", job.seq());
            if (prior != null) return Long.parseLong(prior);
            var user = identity(job.platform(), owner);
            if (!user.equals(expectedUser) || !user.linked() || draft.from().equals(draft.to())) return 0L;
            String sourceOwner = user.on(draft.from());
            String words = Json.MAPPER.writeValueAsString(draft.words());
            long id = draft.route();
            if (id == 0) {
                id = Long.parseLong(scalar("""
                        INSERT INTO routes(platform, owner, source, target_platform, target, mode, terms,
                                           name, active, enabled_since)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?) RETURNING id
                        """, draft.from(), sourceOwner, draft.source(), draft.to(), draft.target(),
                        draft.mode(), words, draft.name(), now()));
            } else {
                var existing = userProfile(job.platform(), owner, id);
                if (existing == null || existing.revision() != draft.revision()) return 0L;
                boolean sameChats = existing.platform().equals(draft.from())
                        && existing.source().equals(draft.source())
                        && existing.targetPlatform().equals(draft.to()) && existing.target().equals(draft.target());
                execute("""
                        UPDATE routes SET platform=?, owner=?, source=?, target_platform=?, target=?, mode=?,
                                          terms=?, name=?, enabled_since=?, revision=revision+1 WHERE id=?
                        """, draft.from(), sourceOwner, draft.source(), draft.to(), draft.target(), draft.mode(),
                        words, draft.name(), sameChats ? existing.enabledSince() : now(), id);
            }
            execute("INSERT INTO steps VALUES (?, 'profile-save', ?)", job.seq(), id);
            return id;
        });
    }

    boolean changeProfile(Job job, String owner, Route expected, String action) {
        return transaction(() -> {
            if (scalar("SELECT result FROM steps WHERE seq=? AND action='change-route'", job.seq()) != null) {
                return true;
            }
            var current = userProfile(job.platform(), owner, expected.id());
            if (!expected.equals(current)) return false;
            if (action.equals("resume") && !current.platform().equals(current.targetPlatform())
                    && !identity(job.platform(), owner).linked()) return false;
            changeRouteRow(current.id(), action);
            execute("INSERT INTO steps VALUES (?, 'change-route', ?)", job.seq(), Json.MAPPER.writeValueAsString(action));
            return true;
        });
    }

    long setRoute(String platform, String owner, String source, String target, String mode, List<String> terms,
                  long seq) {
        return transaction(() -> {
            String saved = scalar("SELECT result FROM steps WHERE seq=? AND action='set-route'", seq);
            if (saved != null) return Long.parseLong(saved);
            String words = Json.MAPPER.writeValueAsString(terms);
            String id = scalar("""
                    SELECT id FROM routes WHERE platform=? AND owner=? AND source=? AND target=?
                    AND target_platform='max' ORDER BY id LIMIT 1
                    """, platform, owner, source, target);
            if (id == null) {
                id = scalar("""
                        INSERT INTO routes(platform, owner, source, target, mode, terms, active, enabled_since)
                        VALUES (?, ?, ?, ?, ?, ?, 1, ?) RETURNING id
                        """, platform, owner, source, target, mode, words, now());
            } else {
                execute("""
                        UPDATE routes SET mode=?, terms=?, active=1, revision=revision+1,
                                          enabled_since=CASE WHEN active THEN enabled_since ELSE ? END WHERE id=?
                        """, mode, words, now(), Long.parseLong(id));
            }
            execute("INSERT INTO steps VALUES (?, 'set-route', ?)", seq, id);
            return Long.parseLong(id);
        });
    }

    List<Route> activeRoutes(String platform, String source, double received) {
        return locked(() -> query("""
                SELECT * FROM routes WHERE platform=? AND source=? AND platform<>target_platform
                  AND active=1 AND enabled_since<=?
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

    boolean changeRoute(Route route, String command, String result, long seq) {
        return transaction(() -> {
            if (!route.equals(route(route.platform(), route.owner(), route.id()))) return false;
            changeRouteRow(route.id(), command);
            execute("INSERT INTO steps VALUES (?, 'change-route', ?)", seq, Json.MAPPER.writeValueAsString(result));
            return true;
        });
    }

    private void changeRouteRow(long id, String action) throws SQLException {
        switch (action) {
            case "remove" -> execute("DELETE FROM routes WHERE id=?", id);
            case "pause" -> execute("UPDATE routes SET active=0, revision=revision+1 WHERE id=?", id);
            case "resume" -> execute("""
                    UPDATE routes SET active=1, revision=revision+1,
                                      enabled_since=CASE WHEN active THEN enabled_since ELSE ? END WHERE id=?
                    """, now(), id);
            default -> throw new IllegalArgumentException("Unknown profile action");
        }
    }

    private static Route routeRow(ResultSet row) throws SQLException {
        return new Route(row.getLong("id"), row.getString("platform"), row.getString("owner"),
                row.getString("source"), row.getString("target"), row.getString("mode"),
                Json.strings(Json.MAPPER.readTree(row.getString("terms"))),
                row.getBoolean("active"), row.getDouble("enabled_since"),
                row.getString("target_platform"), row.getString("name"), row.getLong("revision"));
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
