package bridge;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

final class Transport implements AutoCloseable {
    static final int MEDIA_LIMIT = 20 * 1024 * 1024;
    private static final int JSON_LIMIT = 2 * 1024 * 1024;
    private static final List<String> MAX_CDN = List.of("max.ru", "oneme.ru", "okcdn.ru", "mycdn.me", "userapi.com");
    private final HttpClient client;
    private final RequestPacer pacer;

    record Upload(String field, String name, byte[] data) {}
    private record Body(String type, HttpRequest.BodyPublisher publisher) {}

    static final class ApiFailure extends RuntimeException {
        final int status;
        final int retryAfter;
        private final String code;

        ApiFailure(String operation, int status, String code, int retryAfter) {
            super(status == 0 ? operation : operation + ": HTTP " + status);
            this.status = status;
            this.code = code;
            this.retryAfter = retryAfter;
        }

        boolean retryable() {
            return status == 0 || status == 408 || status == 429 || status >= 500 || code.equals("attachment.not.ready");
        }

        boolean unavailable() {
            return (status == 400 || status == 403 || status == 404) && !retryable();
        }
    }

    static final class MediaFailure extends RuntimeException {
        MediaFailure(String message) {
            super(message);
        }
    }

    Transport(int rate, Path additionalCa) throws IOException, GeneralSecurityException {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (additionalCa != null) {
            builder.sslContext(sslWithCa(additionalCa));
        }
        client = builder.build();
        pacer = new RequestPacer(rate);
    }

    byte[] request(String operation, URI uri, String method, JsonNode payload, String token, Upload upload,
                   boolean download, boolean maxCdn) {
        if (maxCdn) {
            validateMaxCdn(uri);
        }
        Body body = requestBody(payload, upload);
        try {
            for (int redirects = 0; ; redirects++) {
                if (!maxCdn && !download) {
                    pacer.acquire();
                }
                var builder = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(upload != null || download ? 30 : 12))
                        .method(method, body.publisher())
                        .header("Accept", "application/json, */*");
                if (body.type() != null) {
                    builder.header("Content-Type", body.type());
                }
                if (token != null) {
                    builder.header("Authorization", token);
                }
                HttpResponse<byte[]> response = client.send(builder.build(),
                        info -> new LimitedBody(download && info.statusCode() / 100 == 2 ? MEDIA_LIMIT : JSON_LIMIT));
                int status = response.statusCode();
                if (maxCdn && download && List.of(301, 302, 303, 307, 308).contains(status) && redirects < 4
                        && response.headers().firstValue("Location").isPresent()) {
                    uri = uri.resolve(response.headers().firstValue("Location").orElseThrow());
                    validateMaxCdn(uri);
                    token = null;
                    continue;
                }
                if (status / 100 == 2) {
                    return response.body();
                }
                JsonNode error = object(response.body());
                int delay = error.path("parameters").path("retry_after").asInt(
                        response.headers().firstValue("Retry-After").map(Transport::parseDelay).orElse(0));
                delay = Math.max(0, delay);
                if (status == 429 && !maxCdn) {
                    pacer.coolDown(delay);
                }
                throw new ApiFailure(operation, status, Json.text(error, "code"), delay);
            }
        } catch (ResponseTooLarge error) {
            if (download) {
                throw new MediaFailure("вложение больше 20 МБ");
            }
            throw new ApiFailure(operation, 0, "", 0);
        } catch (IOException error) {
            if (download && error.getCause() instanceof ResponseTooLarge) {
                throw new MediaFailure("вложение больше 20 МБ");
            }
            throw new ApiFailure(operation, 0, "", 0);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiFailure(operation, 0, "", 0);
        }
    }

    static JsonNode object(byte[] data) {
        try {
            JsonNode response = Json.MAPPER.readTree(data);
            return response.isObject() ? response : Json.object();
        } catch (JacksonException error) {
            return Json.object();
        }
    }

    static URI uri(String base, String path, Map<String, String> query) {
        String extra = query.isEmpty() ? "" : "?" + query.entrySet().stream()
                .map(field -> encode(field.getKey()) + "=" + encode(field.getValue()))
                .collect(Collectors.joining("&"));
        return URI.create(base + path + extra);
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static URI mediaUri(String raw) {
        try {
            return URI.create(raw);
        } catch (IllegalArgumentException error) {
            throw new MediaFailure("некорректная ссылка на вложение MAX");
        }
    }

    static void validateMaxCdn(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || MAX_CDN.stream().noneMatch(domain -> host.equals(domain) || host.endsWith("." + domain))) {
            throw new MediaFailure("неизвестный сервер вложений MAX");
        }
    }

    private static int parseDelay(String header) {
        try {
            return Integer.parseInt(header);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static Body requestBody(JsonNode payload, Upload upload) {
        if (upload == null) {
            return payload == null ? new Body(null, HttpRequest.BodyPublishers.noBody())
                    : new Body("application/json", HttpRequest.BodyPublishers.ofString(payload.toString()));
        }
        String boundary = UUID.randomUUID().toString();
        StringBuilder prefix = new StringBuilder();
        if (payload != null) {
            payload.properties().forEach(field -> prefix.append("--").append(boundary)
                    .append("\r\nContent-Disposition: form-data; name=\"").append(field.getKey())
                    .append("\"\r\n\r\n")
                    .append(field.getValue().isString() ? field.getValue().asString() : field.getValue().toString())
                    .append("\r\n"));
        }
        String name = upload.name().replaceAll("[\"\\\\/\\p{Cntrl}]", "_");
        if (name.isEmpty()) {
            name = "attachment";
        }
        name = name.substring(0, name.offsetByCodePoints(0, Math.min(200, name.codePointCount(0, name.length()))));
        String mime = URLConnection.guessContentTypeFromName(name);
        prefix.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"")
                .append(upload.field()).append("\"; filename=\"").append(name)
                .append("\"\r\nContent-Type: ").append(mime == null ? "application/octet-stream" : mime)
                .append("\r\n\r\n");
        return new Body("multipart/form-data; boundary=" + boundary, HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(prefix.toString()),
                HttpRequest.BodyPublishers.ofByteArray(upload.data()),
                HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n")));
    }

    private static SSLContext sslWithCa(Path additionalCa) throws IOException, GeneralSecurityException {
        var certificates = CertificateFactory.getInstance("X.509");
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (var input = Files.newInputStream(additionalCa)) {
            int index = 0;
            for (var cert : certificates.generateCertificates(input)) {
                store.setCertificateEntry("max-ca-" + index++, cert);
            }
        }
        X509TrustManager system = trustManager(null);
        X509TrustManager extra = trustManager(store);
        var combined = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                system.checkClientTrusted(chain, authType);
            }
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    system.checkServerTrusted(chain, authType);
                } catch (CertificateException error) {
                    extra.checkServerTrusted(chain, authType);
                }
            }
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return system.getAcceptedIssuers();
            }
        };
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[] {combined}, null);
        return ssl;
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager certificates) {
                return certificates;
            }
        }
        throw new GeneralSecurityException("No X.509 trust manager available");
    }

    @Override
    public void close() {
        client.shutdownNow();
    }

    private static final class RequestPacer {
        private final long interval;
        private long next = System.nanoTime();

        RequestPacer(int rate) {
            interval = 1_000_000_000L / rate;
        }

        void acquire() throws InterruptedException {
            while (true) {
                long wait;
                synchronized (this) {
                    long now = System.nanoTime();
                    wait = next - now;
                    if (wait <= 0) {
                        next = now + interval;
                        return;
                    }
                }
                TimeUnit.NANOSECONDS.sleep(wait);
            }
        }

        synchronized void coolDown(int seconds) {
            long now = System.nanoTime();
            next = now + Math.max(next - now, TimeUnit.SECONDS.toNanos(seconds));
        }
    }

    private static final class ResponseTooLarge extends IOException {}

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long remaining;
        private boolean exceeded;

        LimitedBody(int limit) {
            remaining = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription incoming) {
            subscription = incoming;
            body.onSubscribe(incoming);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                remaining -= buffer.remaining();
            }
            if (remaining < 0) {
                exceeded = true;
                subscription.cancel();
                body.onError(new ResponseTooLarge());
            } else {
                body.onNext(buffers);
            }
        }

        @Override
        public void onError(Throwable error) {
            if (!exceeded) {
                body.onError(error);
            }
        }

        @Override
        public void onComplete() {
            if (!exceeded) {
                body.onComplete();
            }
        }
    }
}
