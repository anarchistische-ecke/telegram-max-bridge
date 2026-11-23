package bridge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;
import tools.jackson.databind.JsonNode;

import static bridge.Json.object;
import static bridge.Json.text;

public final class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());
    private static final String TELEGRAM = "https://api.telegram.org";
    private static final String MAX = "https://platform-api2.max.ru";

    private Main() {}

    public static void main(String[] args) {
        if (args.length != 1 || !Set.of("serve", "check", "register-webhooks").contains(args[0])) {
            System.out.println("Usage: java -jar telegram-max-bridge.jar {serve|check|register-webhooks}");
            if (args.length != 1 || !args[0].equals("--help")) {
                System.exit(2);
            }
            return;
        }
        try {
            run(args[0], System.getenv());
        } catch (Transport.ApiFailure | IllegalArgumentException | IOException | GeneralSecurityException
                 | SQLException | Store.Failure error) {
            LOG.severe(error.getMessage());
            System.exit(1);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static void run(String command, Map<String, String> env)
            throws IOException, GeneralSecurityException, SQLException, InterruptedException {
        String telegramToken = required(env, "TELEGRAM_BOT_TOKEN");
        String maxToken = required(env, "MAX_BOT_TOKEN");
        if (!telegramToken.matches("[A-Za-z0-9:_-]+") || maxToken.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid bot token format.");
        }
        String certificate = env.get("MAX_CA_FILE");
        try (var telegramTransport = new Transport(25, null);
             var maxTransport = new Transport(25, certificate == null || certificate.isBlank() ? null : Path.of(certificate))) {
            var telegram = new Telegram(telegramTransport, TELEGRAM, telegramToken);
            var max = new Max(maxTransport, MAX, maxToken);
            if (command.equals("register-webhooks")) {
                register(telegram, max, Secrets.from(env), required(env, "PUBLIC_WEBHOOK_URL"));
                LOG.info("Registered Telegram and MAX webhooks.");
                return;
            }
            JsonNode telegramBot = telegram.call("getMe", object());
            JsonNode maxBot = max.get("/me");
            if (text(telegramBot, "username").isEmpty() || text(maxBot, "username").isEmpty()) {
                throw new IllegalArgumentException("Both bot accounts need a public username.");
            }
            if (command.equals("check")) {
                LOG.info(() -> "Authenticated as @" + text(telegramBot, "username") + " on Telegram and @"
                        + text(maxBot, "username") + " on MAX.");
                return;
            }
            Secrets secrets = Secrets.from(env);
            int workers = integer(env, "BRIDGE_WORKERS", 24, 1, 256);
            int media = integer(env, "BRIDGE_MEDIA_WORKERS", Math.min(workers, 4), 1, workers);
            int port = integer(env, "BOT_PORT", 8787, 1, 65535);
            var address = new InetSocketAddress(env.getOrDefault("BOT_BIND_HOST", "127.0.0.1"), port);
            Path db = Path.of(env.getOrDefault("BRIDGE_DB", "bridge.sqlite3")).toAbsolutePath().normalize();
            serve(db, address, secrets, telegram, max, telegramBot, maxBot, workers, media);
        }
    }

    static void register(Telegram telegram, Max max, Secrets secrets, String origin) {
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("PUBLIC_WEBHOOK_URL must be a public HTTPS origin on port 443.");
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getPort() != -1 && uri.getPort() != 443 || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !Set.of("", "/").contains(uri.getRawPath())) {
            throw new IllegalArgumentException("PUBLIC_WEBHOOK_URL must be a public HTTPS origin on port 443.");
        }
        String base = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        if (!telegram.call("setWebhook", object("url", base + "/webhook/telegram", "secret_token", secrets.telegram(),
                "allowed_updates", Update.TELEGRAM_TYPES, "max_connections", 1)).asBoolean(false)) {
            throw new IllegalArgumentException("Telegram did not confirm its webhook.");
        }
        max.post("/subscriptions", object("url", base + "/webhook/max", "secret", secrets.max(),
                "update_types", Update.MAX_TYPES));
        var start = object("command", "start", "description", "Открыть главное меню");
        var group = object("command", "bridge", "description", "Создать профиль для этой группы");
        telegram.call("setMyCommands", object("commands", java.util.List.of(start),
                "scope", object("type", "all_private_chats")));
        telegram.call("setMyCommands", object("commands", java.util.List.of(group),
                "scope", object("type", "all_group_chats")));
        max.commands(java.util.List.of(object("name", "start", "description", "Открыть меню профилей"),
                object("name", "bridge", "description", "Создать профиль для этой группы")));
    }

    private static void serve(Path db, InetSocketAddress address, Secrets secrets, Telegram telegram, Max max,
                              JsonNode telegramBot, JsonNode maxBot, int workers, int media)
            throws IOException, SQLException, InterruptedException {
        Files.createDirectories(db.getParent());
        Path lockFile = db.resolveSibling(db.getFileName() + ".lock");
        try (var file = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = file.tryLock()) {
            if (lock == null) {
                throw new IllegalArgumentException("A bridge process is already using this database.");
            }
            var stopping = new CountDownLatch(1);
            var stopped = new CountDownLatch(1);
            var hook = Thread.ofPlatform().name("bridge-shutdown").unstarted(() -> {
                stopping.countDown();
                try {
                    stopped.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            try (var store = new Store(db);
                 var queue = new DeliveryQueue(store, workers, new Bridge(store, telegram, max, telegramBot, maxBot, media)::handle);
                 var webhooks = new Webhooks(address, store, queue, secrets.telegram(), secrets.max())) {
                Runtime.getRuntime().addShutdownHook(hook);
                queue.start();
                webhooks.start();
                LOG.info(() -> "Listening on " + webhooks.address() + " with " + workers + " delivery slots");
                stopping.await();
            } finally {
                stopped.countDown();
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ignored) {
                    // Already shutting down.
                }
            }
        } catch (OverlappingFileLockException error) {
            throw new IllegalArgumentException("A bridge process is already using this database.");
        }
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set " + name + ". See .env.example.");
        }
        return value;
    }

    private static int integer(Map<String, String> env, String name, int defaultValue, int min, int max) {
        try {
            int value = Integer.parseInt(env.getOrDefault(name, Integer.toString(defaultValue)));
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Report the valid range for malformed values too.
        }
        throw new IllegalArgumentException(name + " must be an integer between " + min + " and " + max + ".");
    }

    record Secrets(String telegram, String max) {
        static Secrets from(Map<String, String> env) {
            String telegram = secret(env, "TELEGRAM_WEBHOOK_SECRET");
            String max = secret(env, "MAX_WEBHOOK_SECRET");
            if (telegram.equals(max)) {
                throw new IllegalArgumentException("Use a different webhook secret for each platform.");
            }
            return new Secrets(telegram, max);
        }

        private static String secret(Map<String, String> env, String key) {
            String value = required(env, key);
            if (!value.matches("[A-Za-z0-9_-]{5,256}")) {
                throw new IllegalArgumentException(key + " must be 5–256 URL-safe characters.");
            }
            return value;
        }
    }
}
