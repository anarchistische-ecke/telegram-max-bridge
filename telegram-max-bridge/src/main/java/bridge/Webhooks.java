package bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

final class Webhooks implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Webhooks.class.getName());
    private static final int MAX_BODY = 1024 * 1024;
    private final Store store;
    private final DeliveryQueue queue;
    private final HttpServer server;
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore requests = new Semaphore(64);

    Webhooks(InetSocketAddress address, Store store, DeliveryQueue queue, String telegramSecret, String maxSecret)
            throws IOException {
        this.store = store;
        this.queue = queue;
        server = HttpServer.create(address, 128);
        server.setExecutor(handlers);
        server.createContext("/webhook/telegram", exchange -> receive(exchange, "/webhook/telegram",
                "telegram", "X-Telegram-Bot-Api-Secret-Token", telegramSecret));
        server.createContext("/webhook/max", exchange -> receive(exchange, "/webhook/max",
                "max", "X-Max-Bot-Api-Secret", maxSecret));
        server.createContext("/healthz", exchange -> {
            try (exchange) {
                if (exchange.getRequestMethod().equals("GET") && exactPath(exchange, "/healthz")) {
                    exchange.sendResponseHeaders(queue.isAlive() ? 200 : 503, -1);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            }
        });
    }

    void start() {
        server.start();
    }

    InetSocketAddress address() {
        return server.getAddress();
    }

    private void receive(HttpExchange exchange, String path, String platform, String header, String secret)
            throws IOException {
        try (exchange) {
            if (!exactPath(exchange, path) || !exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String supplied = exchange.getRequestHeaders().getFirst(header);
            if (supplied == null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                    secret.getBytes(StandardCharsets.UTF_8))) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            if (!requests.tryAcquire()) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            try {
                exchange.sendResponseHeaders(accept(exchange, platform), -1);
            } finally {
                requests.release();
            }
        }
    }

    private int accept(HttpExchange exchange, String platform) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Content-Length");
        if (header != null) {
            try {
                if (Long.parseLong(header) > MAX_BODY) {
                    return 413;
                }
            } catch (NumberFormatException error) {
                return 400;
            }
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            return 413;
        }
        JsonNode data;
        try {
            data = Json.MAPPER.readTree(body);
        } catch (JacksonException error) {
            return 400;
        }
        if (!data.isObject()) {
            return 400;
        }
        Update update = Update.from(platform, data);
        if (update != null) {
            try {
                store.enqueue(update);
                queue.wake();
            } catch (Store.Failure error) {
                LOG.severe(() -> "Could not record a " + platform + " webhook");
                return 503;
            }
        }
        return 200;
    }

    private static boolean exactPath(HttpExchange exchange, String expected) {
        return expected.equals(exchange.getRequestURI().getRawPath()) && exchange.getRequestURI().getRawQuery() == null;
    }

    @Override
    public void close() {
        server.stop(1);
        handlers.shutdownNow();
    }
}
