package bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static bridge.Json.object;
import static bridge.Json.text;
import static org.junit.jupiter.api.Assertions.*;

class BridgeTest {
    private static final long TG_OWNER = 101;
    private static final long TG_OTHER = 102;
    private static final long TG_BOT = 103;
    private static final long TG_GROUP = -1001234569870L;
    private static final long TG_CHANNEL = -1001234569872L;
    private static final long TG_GROUP_TWO = -1001234569871L;
    private static final long MAX_OWNER = 9007199254740997L;
    private static final long MAX_OTHER = 9007199254740999L;
    private static final long MAX_BOT = 9007199254741000L;
    private static final long MAX_GROUP = -9007199254741001L;
    private static final long MAX_CHANNEL = -9007199254741003L;
    private static final long MAX_SOURCE = -9007199254741005L;
    private static final String TG_SECRET = "telegram_secret";
    private static final String MAX_SECRET = "max_secret";

    @TempDir Path temp;
    private Rig rig;

    @AfterEach
    void shutdown() {
        if (rig != null) {
            rig.close();
        }
    }

    @Test
    void filtersDeduplicatesAndRepliesInOriginalTopicAfterRestart() throws Exception {
        rig = new Rig(temp);
        rig.route(TG_OWNER, MAX_OWNER, TG_GROUP, MAX_GROUP, "include доставка, new order");
        rig.route(TG_OTHER, MAX_OTHER, TG_GROUP, MAX_GROUP, "exclude spam");
        var source = rig.telegramEvent("ДОСТАВКА!", TG_OWNER, TG_GROUP, 88);
        var message = (ObjectNode) source.path("message");
        message.put("is_topic_message", true).put("message_thread_id", 17)
                .put("date", Instant.parse("2026-11-23T10:07:45Z").getEpochSecond());
        ((ObjectNode) message.path("from")).put("first_name", "Анна").put("last_name", "Петрова")
                .put("username", "anna_petrova");
        message.set("forward_origin", object("type", "hidden_user", "date", 1700000000,
                "sender_user_name", "Другой отправитель"));
        rig.send("telegram", source);
        rig.send("telegram", source);
        var forwarded = rig.platforms.to(MAX_GROUP);
        assertEquals(1, forwarded.size());
        assertEquals("Анна Петрова (@anna_petrova, 23.11.2026 13:07:45 МСК) · Telegram / Dispatch\n\nДОСТАВКА!",
                text(forwarded.getFirst().body(), "text"));
        String mid = forwarded.getFirst().mid();

        rig.reopen();
        var answer = rig.maxEvent("/help needs a reply", MAX_OTHER, MAX_GROUP, "chat", mid);
        ((ObjectNode) answer.path("message")).put("timestamp", Instant.parse("2026-11-23T10:09:12.360Z").toEpochMilli());
        rig.send("max", answer);
        rig.send("max", answer);
        var replies = rig.platforms.telegramSends.stream().filter(body -> body.has("reply_parameters")).toList();
        assertEquals(1, replies.size());
        assertEquals(88, replies.getFirst().path("reply_parameters").path("message_id").asInt());
        assertEquals(17, replies.getFirst().path("message_thread_id").asInt());
        assertEquals("Lena (ник не указан, 23.11.2026 13:09:12 МСК) · MAX / Room " + MAX_GROUP + "\n\n/help needs a reply",
                text(replies.getFirst(), "text"));
    }

    @Test
    void relaysMaxChannelCommentsToTheOriginalTelegramMessage() throws Exception {
        rig = new Rig(temp);
        rig.route(TG_OWNER, MAX_OWNER, TG_GROUP, MAX_CHANNEL, "all");
        var source = rig.telegramEvent("Новость", TG_OWNER, TG_GROUP, 88);
        ((ObjectNode) source.path("message")).put("is_topic_message", true).put("message_thread_id", 17);
        rig.send("telegram", source);
        String post = rig.platforms.to(MAX_CHANNEL).getFirst().mid();
        rig.reopen();

        var comment = rig.maxComment("Комментарий", MAX_OWNER, post);
        ((ObjectNode) comment.path("message")).put("timestamp", Instant.parse("2026-11-23T10:15:25.737Z").toEpochMilli());
        rig.send("max", comment);
        rig.send("max", comment);
        var nested = rig.maxComment("Ответ на комментарий", MAX_OWNER, post);
        ((ObjectNode) nested.path("message")).set("link", object("type", "reply", "message",
                object("mid", text(comment.path("message").path("body"), "mid"))));
        rig.send("max", nested);
        var channelReply = rig.maxEvent("Ответ от имени канала", MAX_OWNER, MAX_CHANNEL, "channel", post);
        ((ObjectNode) channelReply.path("message"))
                .put("timestamp", Instant.parse("2026-11-23T10:17:18.425Z").toEpochMilli()).remove("sender");
        rig.send("max", channelReply);
        rig.send("max", channelReply);
        rig.send("max", rig.maxComment("Чужой пост", MAX_OWNER, "mid.unrelated"));
        rig.send("max", rig.maxComment("Не подписан", MAX_OTHER, post));

        var replies = rig.platforms.telegramSends.stream().filter(body -> body.has("reply_parameters")).toList();
        assertEquals(3, replies.size());
        assertEquals("Lena (ник не указан, 23.11.2026 13:15:25 МСК) · MAX / Room " + MAX_CHANNEL + "\n\nКомментарий",
                text(replies.getFirst(), "text"));
        assertEquals("Lena (ник не указан, время не указано) · MAX / Room " + MAX_CHANNEL + "\n\nОтвет на комментарий",
                text(replies.get(1), "text"));
        assertEquals("Room " + MAX_CHANNEL + " (ник не указан, 23.11.2026 13:17:18 МСК) · MAX / Room " + MAX_CHANNEL
                        + "\n\nОтвет от имени канала",
                text(replies.getLast(), "text"));
        for (var reply : replies) {
            assertEquals(TG_GROUP, reply.path("chat_id").asLong());
            assertEquals(88, reply.path("reply_parameters").path("message_id").asInt());
            assertEquals(17, reply.path("message_thread_id").asInt());
        }
    }

    @Test
    void protectsProfileOwnershipAndRechecksMembership() throws Exception {
        rig = new Rig(temp);
        rig.route(TG_OWNER, MAX_OWNER, TG_GROUP, MAX_GROUP, "include urgent");
        long id = rig.store.routes("telegram", Long.toString(TG_OWNER), 0, 8).getFirst().id();

        rig.telegram("/remove " + id, TG_OTHER, TG_OTHER, 1);
        assertTrue(rig.lastTelegram().contains("нет такой пересылки"));
        rig.telegram("/routes", TG_OTHER, TG_OTHER, 2);
        assertTrue(rig.lastTelegram().contains("нет пересылок"));

        rig.telegram("urgently", TG_OWNER, TG_GROUP, 3);
        rig.platforms.telegramMembers.remove(TG_OWNER);
        rig.telegram("urgent", TG_OTHER, TG_GROUP, 4);
        rig.platforms.telegramMembers.add(TG_OWNER);
        rig.platforms.maxMembers.get(MAX_GROUP).remove(MAX_OWNER);
        rig.telegram("urgent", TG_OWNER, TG_GROUP, 5);
        assertTrue(rig.platforms.to(MAX_GROUP).isEmpty());
        rig.platforms.maxMembers.get(MAX_GROUP).add(MAX_OWNER);

        rig.telegram("/pause " + id, TG_OWNER, TG_OWNER, 6);
        rig.telegram("urgent", TG_OWNER, TG_GROUP, 7);
        assertTrue(rig.platforms.to(MAX_GROUP).isEmpty());
        rig.telegram("/resume " + id, TG_OWNER, TG_OWNER, 8);
        rig.telegram("urgent", TG_OWNER, TG_GROUP, 9);
        assertEquals(1, rig.platforms.to(MAX_GROUP).size());
        rig.telegram("/remove " + id, TG_OWNER, TG_OWNER, 10);
        rig.telegram("urgent", TG_OWNER, TG_GROUP, 11);
        assertEquals(1, rig.platforms.to(MAX_GROUP).size());
    }

    @Test
    void checksChannelRightsAndProtectedContent() throws Exception {
        rig = new Rig(temp);
        rig.telegram("/start src_" + TG_GROUP, TG_OWNER, TG_OWNER, 1);
        rig.link(TG_OWNER, MAX_OWNER);
        rig.send("max", object("update_type", "bot_added", "chat_id", MAX_CHANNEL,
                "is_channel", true, "timestamp", 10));
        rig.telegram("/destinations", TG_OWNER, TG_OWNER, 2);
        assertTrue(rig.lastTelegram().contains("(канал)  " + MAX_CHANNEL));
        rig.platforms.channelWrite = false;
        rig.telegram("/route " + MAX_CHANNEL + " exclude blocked, internal only", TG_OWNER, TG_OWNER, 3);
        assertTrue(rig.lastTelegram().contains("Выберите группу или канал MAX"));
        rig.platforms.channelWrite = true;
        rig.telegram("/route " + MAX_CHANNEL + " exclude blocked, internal only", TG_OWNER, TG_OWNER, 4);

        rig.telegram("INTERNAL ONLY", TG_OWNER, TG_GROUP, 5);
        rig.telegram("blocked!", TG_OWNER, TG_GROUP, 6);
        var protectedEvent = rig.telegramEvent("allowed", TG_OWNER, TG_GROUP, 7);
        ((ObjectNode) protectedEvent.path("message")).put("has_protected_content", true);
        rig.send("telegram", protectedEvent);
        rig.platforms.protectedSource = true;
        rig.telegram("allowed", TG_OWNER, TG_GROUP, 8);
        assertTrue(rig.platforms.to(MAX_CHANNEL).isEmpty());
        rig.platforms.protectedSource = false;
        var anonymousPost = rig.telegramEvent("allowed", TG_OWNER, TG_GROUP, 9);
        var message = (ObjectNode) anonymousPost.path("message");
        message.put("date", Instant.parse("2026-11-23T21:04:08Z").getEpochSecond());
        message.set("sender_chat", object("id", TG_GROUP, "type", "supergroup", "title", "Диспетчерская"));
        ((ObjectNode) message.path("from")).put("username", "GroupAnonymousBot").put("is_bot", true);
        rig.send("telegram", anonymousPost);
        var forwarded = rig.platforms.to(MAX_CHANNEL);
        assertEquals(1, forwarded.size());
        assertTrue(text(forwarded.getFirst().body(), "text").startsWith(
                "Диспетчерская (ник не указан, 24.11.2026 00:04:08 МСК) · Telegram"));
    }

    @Test
    void maxSourceGroupButtonStartsTheReverseDirection() throws Exception {
        rig = new Rig(temp);
        rig.max("/bridge", MAX_OWNER, MAX_SOURCE, "chat", null);
        String url = text(maxButtons(rig.platforms.to(MAX_SOURCE).getFirst().body()).path(0).path(0), "url");
        assertTrue(url.endsWith("start=src_" + MAX_SOURCE));
        rig.send("max", object("update_type", "bot_started", "timestamp", 12,
                "user", object("user_id", MAX_OWNER), "payload", "src_" + MAX_SOURCE));
        assertTrue(rig.maxButton("Открыть Telegram").contains("start=link_"));
        var draft = rig.store.menu("max", Long.toString(MAX_OWNER)).draft();
        assertEquals("telegram", draft.to());
        assertEquals(Long.toString(MAX_SOURCE), draft.source());
    }

    @Test
    void keepsRetryOrderAndDoesNotResendSuccessfulDestination() throws Exception {
        rig = new Rig(temp);
        rig.route(TG_OWNER, MAX_OWNER, TG_GROUP, MAX_GROUP, "all");
        rig.telegram("/route " + MAX_CHANNEL + " all", TG_OWNER, TG_OWNER, 1);
        rig.telegram("/source " + TG_GROUP_TWO, TG_OWNER, TG_OWNER, 2);
        rig.telegram("/route " + MAX_GROUP + " all", TG_OWNER, TG_OWNER, 3);

        rig.platforms.failOnce.add(MAX_CHANNEL);
        rig.post("telegram", rig.telegramEvent("first delivery", TG_OWNER, TG_GROUP, 4), true);
        await(() -> rig.platforms.failedPosts.get() == 1);
        rig.post("telegram", rig.telegramEvent("later from same chat", TG_OWNER, TG_GROUP, 5), true);
        rig.post("telegram", rig.telegramEvent("independent chat", TG_OWNER, TG_GROUP_TWO, 6), true);
        await(() -> rig.platforms.has(MAX_GROUP, "independent chat"));
        assertFalse(rig.platforms.has(MAX_GROUP, "later from same chat"));
        rig.awaitIdle();

        assertEquals(3, rig.platforms.to(MAX_GROUP).size());
        assertEquals(2, rig.platforms.to(MAX_CHANNEL).size());
        assertEquals(1, rig.platforms.to(MAX_GROUP).stream()
                .filter(post -> text(post.body(), "text").contains("first delivery")).count());
    }

    @Test
    void slowSendDoesNotBlockAnotherChatAndEarlyReplyWaitsForItsMapping() throws Exception {
        rig = new Rig(temp);
        rig.route(TG_OWNER, MAX_OWNER, TG_GROUP, MAX_GROUP, "all");
        rig.telegram("/source " + TG_GROUP_TWO, TG_OWNER, TG_OWNER, 1);
        rig.telegram("/route " + MAX_CHANNEL + " all", TG_OWNER, TG_OWNER, 2);

        rig.platforms.blockOn = "held delivery";
        try {
            rig.post("telegram", rig.telegramEvent("held delivery", TG_OWNER, TG_GROUP, 79), true);
            assertTrue(rig.platforms.blockEntered.await(4, TimeUnit.SECONDS));
            String maxMid = rig.platforms.to(MAX_GROUP).getFirst().mid();
            rig.post("telegram", rig.telegramEvent("same chat next", TG_OWNER, TG_GROUP, 80), true);
            rig.post("max", rig.maxEvent("early reply", MAX_OTHER, MAX_GROUP, "chat", maxMid), true);
            rig.post("telegram", rig.telegramEvent("free chat", TG_OWNER, TG_GROUP_TWO, 81), true);

            await(() -> rig.platforms.has(MAX_CHANNEL, "free chat"));
            assertFalse(rig.platforms.has(MAX_GROUP, "same chat next"));
            assertFalse(rig.platforms.telegramSends.stream().anyMatch(body -> body.has("reply_parameters")));
        } finally {
            rig.platforms.blockRelease.countDown();
        }
        rig.awaitIdle();
        assertTrue(rig.platforms.has(MAX_GROUP, "same chat next"));
        assertEquals(79, rig.platforms.telegramSends.stream().filter(body -> body.has("reply_parameters")).findFirst()
                .orElseThrow().path("reply_parameters").path("message_id").asInt());
    }

    @Test
    void boundsConcurrentDeliveriesWithManyActiveChats() throws Exception {
        var firstWave = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(12);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        try (var store = new Store(temp.resolve("capacity.sqlite3"));
             var queue = new DeliveryQueue(store, 3, job -> {
                 peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                 firstWave.countDown();
                 try {
                     assertTrue(release.await(5, TimeUnit.SECONDS));
                 } catch (InterruptedException error) {
                     Thread.currentThread().interrupt();
                 } finally {
                     active.decrementAndGet();
                     completed.countDown();
                 }
             })) {
            for (int index = 1; index <= 12; index++) {
                store.enqueue(Update.from("telegram", telegramEvent(index, "queued", TG_OWNER, -index, 1)));
            }
            queue.start();
            try {
                assertTrue(firstWave.await(4, TimeUnit.SECONDS));
                assertEquals(3, active.get());
            } finally {
                release.countDown();
            }
            assertTrue(completed.await(4, TimeUnit.SECONDS));
            assertEquals(3, peak.get());
        }
    }

    @Test
    void rejectsForgedWebhooksAndInvalidJson() throws Exception {
        rig = new Rig(temp);
        assertEquals(403, rig.httpPost("telegram", object("update_id", 1).toString(), false));
        assertEquals(400, rig.httpPost("telegram", "not JSON", true));
        assertEquals(400, rig.httpPost("telegram", "", true));
        assertEquals(413, rig.httpPost("telegram", "x".repeat(1_048_577), true));
        assertEquals(0, rig.pending());
    }

    @Test
    void onlySourceMembersCanSelectItAndAccountCodesAreSingleUse() throws Exception {
        rig = new Rig(temp);
        rig.platforms.telegramMembers.remove(TG_OTHER);
        rig.telegram("/start src_" + TG_GROUP, TG_OTHER, TG_OTHER, 1);
        assertNull(rig.store.selectedSource("telegram", Long.toString(TG_OTHER)));
        String code = rig.link(TG_OWNER, MAX_OWNER);
        rig.send("max", object("update_type", "bot_started", "chat_id", 514,
                "timestamp", 700, "user", object("user_id", MAX_OTHER), "payload", code));
        assertTrue(text(rig.platforms.to(MAX_OTHER).getFirst().body(), "text").contains("истекла или уже использована"));
        assertEquals(Long.toString(MAX_OWNER), rig.store.maxUser(Long.toString(TG_OWNER)));
    }

    @Test
    void ignoresOwnPostsAndUnlinkPausesRoutes() throws Exception {
        rig = new Rig(temp);
        rig.route(TG_OWNER, MAX_OWNER, TG_GROUP, MAX_GROUP, "all");
        rig.telegram("Own post", TG_BOT, TG_GROUP, 15);
        var otherBot = rig.telegramEvent("Other bot post", 701, TG_GROUP, 16);
        ((ObjectNode) otherBot.path("message").path("from")).put("is_bot", true);
        rig.send("telegram", otherBot);
        assertEquals(1, rig.platforms.to(MAX_GROUP).size());
        assertTrue(rig.platforms.has(MAX_GROUP, "Other bot post"));

        rig.telegram("/unlink", TG_OWNER, TG_OWNER, 17);
        assertNull(rig.store.maxUser(Long.toString(TG_OWNER)));
        assertFalse(rig.store.routes("telegram", Long.toString(TG_OWNER), 0, 8).getFirst().active());
        rig.telegram("after unlink", TG_OWNER, TG_GROUP, 18);
        assertEquals(1, rig.platforms.to(MAX_GROUP).size());
    }

    @Test
    void preservesMediaCaptionsAndTransfersAttachmentTypes() throws Exception {
        rig = new Rig(temp);
        rig.route(TG_OWNER, MAX_OWNER, TG_GROUP, MAX_GROUP, "include report");
        var photo = rig.telegramEvent(null, TG_OWNER, TG_GROUP, 20);
        ((ObjectNode) photo.path("message")).putArray("photo").add(object("file_id", "photo-id", "file_size", 100));
        rig.send("telegram", photo);
        assertTrue(rig.platforms.to(MAX_GROUP).isEmpty());

        var report = rig.telegramEvent(null, TG_OWNER, TG_GROUP, 21);
        ((ObjectNode) report.path("message")).put("caption", "Report").putArray("photo")
                .add(object("file_id", "photo-id", "file_size", Transport.MEDIA_LIMIT + 1));
        rig.send("telegram", report);
        assertTrue(rig.platforms.has(MAX_GROUP, "Report"));
        assertTrue(rig.platforms.has(MAX_GROUP, "вложение больше 20 МБ"));

        rig.telegram("/route " + MAX_GROUP + " all", TG_OWNER, TG_OWNER, 22);
        var location = rig.telegramEvent(null, TG_OWNER, TG_GROUP, 23);
        ((ObjectNode) location.path("message")).set("location", object("latitude", 1.25, "longitude", -2.5));
        rig.send("telegram", location);
        var attachment = rig.platforms.to(MAX_GROUP).getLast().body().path("attachments").path(0);
        assertEquals("location", text(attachment, "type"));
        assertEquals(1.25, attachment.path("latitude").asDouble());
    }

    @Test
    void capsDownloadedMediaAndSendsTelegramDocumentsWithReplyMetadata() throws Exception {
        rig = new Rig(temp);
        assertArrayEquals(new byte[] {1, 2, 3}, rig.telegram.download("sample"));
        assertThrows(Transport.MediaFailure.class, () -> rig.telegram.download("oversize"));
        rig.telegram.send(Long.toString(TG_GROUP), null, "12", "17",
                new Transport.Upload("document", "sample.txt", "sample body".getBytes(StandardCharsets.UTF_8)));
        assertTrue(rig.platforms.telegramMultipart.contains("name=\"reply_parameters\""));
        assertTrue(rig.platforms.telegramMultipart.contains("\"message_id\":12"));
        assertTrue(rig.platforms.telegramMultipart.contains("name=\"document\"; filename=\"sample.txt\""));
        assertTrue(rig.platforms.telegramMultipart.contains("sample body"));
    }

    @Test
    void migratesExistingDatabaseAndResumesLegacyPartialDelivery() throws Exception {
        Path db = temp.resolve("bridge.sqlite3");
        var persisted = telegramEvent(400, "Legacy caption", TG_OWNER, TG_GROUP, 40);
        ((ObjectNode) persisted.path("message")).putArray("photo").add(object("file_id", "legacy-photo"));
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (var schema = Store.class.getResourceAsStream("/schema.sql");
                 var statement = conn.createStatement()) {
                for (String sql : new String(schema.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
            execute(conn.prepareStatement("INSERT INTO profiles VALUES('telegram', ?, ?)"), TG_OWNER + "", TG_GROUP + "");
            execute(conn.prepareStatement("INSERT INTO account_links VALUES (?, ?)"), TG_OWNER + "", MAX_OWNER + "");
            for (long target : List.of(MAX_GROUP, MAX_CHANNEL)) {
                execute(conn.prepareStatement("""
                        INSERT INTO routes(platform, owner, source, target, mode, terms, active, enabled_since)
                        VALUES ('telegram', ?, ?, ?, 'include', '["legacy"]', 1, 1)
                        """), TG_OWNER + "", TG_GROUP + "", target + "");
            }
            execute(conn.prepareStatement("""
                    INSERT INTO inbox(seq, platform, event, data, received) VALUES (4, 'telegram', '400', ?, ?)
                    """), persisted.toString(), Double.toString(System.currentTimeMillis() / 1000.0));
            execute(conn.prepareStatement("INSERT INTO steps VALUES (4, ?, ?)"), "prepare-upload",
                    "[{\"type\": \"image\", \"payload\": {\"token\": \"previous-token\"}}, \"\"]");
            execute(conn.prepareStatement("INSERT INTO steps VALUES (4, ?, ?)"), "forward:" + MAX_GROUP + ":0",
                    "\"prior-mid\"");
            execute(conn.prepareStatement("""
                    INSERT INTO message_links VALUES (?, 'prior-mid', 'telegram', ?, '39', '17')
                    """), MAX_GROUP + "", TG_GROUP + "");
        }
        rig = new Rig(temp);
        rig.awaitIdle();
        assertEquals(Long.toString(TG_GROUP), rig.store.selectedSource("telegram", Long.toString(TG_OWNER)));
        assertEquals(Long.toString(MAX_OWNER), rig.store.maxUser(Long.toString(TG_OWNER)));
        assertTrue(rig.platforms.to(MAX_GROUP).isEmpty());
        assertEquals("previous-token", rig.platforms.to(MAX_CHANNEL).getFirst().body()
                .path("attachments").path(0).path("payload").path("token").asString());
        rig.max("Reply to a Python forward", MAX_OTHER, MAX_GROUP, "chat", "prior-mid");
        var reply = rig.platforms.telegramSends.getLast();
        assertEquals(39, reply.path("reply_parameters").path("message_id").asInt());
        assertEquals(17, reply.path("message_thread_id").asInt());
    }

    @Test
    void registersBothWebhooksUsingOriginalEndpointsAndHeaders() throws Exception {
        rig = new Rig(temp);
        Main.register(rig.telegram, rig.max, new Main.Secrets(TG_SECRET, MAX_SECRET), "https://bridge.example.com/");
        assertEquals("https://bridge.example.com/webhook/telegram", text(rig.platforms.telegramRegistration, "url"));
        assertEquals(TG_SECRET, text(rig.platforms.telegramRegistration, "secret_token"));
        assertEquals(1, rig.platforms.telegramRegistration.path("max_connections").asInt());
        assertEquals("https://bridge.example.com/webhook/max", text(rig.platforms.maxRegistration, "url"));
        assertEquals(MAX_SECRET, text(rig.platforms.maxRegistration, "secret"));
        var maxTypes = Set.copyOf(Json.strings(rig.platforms.maxRegistration.path("update_types")));
        assertEquals(Update.MAX_TYPES, maxTypes);
        assertTrue(maxTypes.contains("comment_created"));
    }

    @Test
    void telegramMenuAndNextTapProceedWhileCallbackAnswerIsStalled() throws Exception {
        rig = new Rig(temp);
        rig.telegram("/start", TG_OWNER, TG_OWNER, 1);
        rig.platforms.blockedTelegramCallback = "click." + (rig.updateId.get() + 1);
        try {
            rig.telegramClick(TG_OWNER, rig.telegramButton("Новый профиль"));
            assertTrue(rig.platforms.telegramCallbackEntered.await(3, TimeUnit.SECONDS));
            assertTrue(rig.lastTelegram().contains("Выберите направление"));
            rig.telegramClick(TG_OWNER, rig.telegramButton("Главное меню"));
            assertTrue(rig.lastTelegram().contains("Создайте первый профиль"));
            assertEquals(0, rig.pending());
        } finally {
            rig.platforms.telegramCallbackRelease.countDown();
        }
    }

    @Test
    void maxMenuUpdatesInOneAnswerAndFallsBackWhenCallbackExpires() throws Exception {
        rig = new Rig(temp);
        rig.send("max", object("update_type", "bot_started", "user", object("user_id", MAX_OWNER)));
        String mid = rig.platforms.to(MAX_OWNER).getLast().mid();
        rig.maxClick(rig.maxButton("Новый профиль"));
        assertTrue(text(rig.platforms.maxAnswers.getLast().path("message"), "text").contains("Выберите направление"));
        assertEquals(0, rig.platforms.maxMenuEdits.get());
        assertEquals(mid, rig.platforms.to(MAX_OWNER).getLast().mid());

        rig.platforms.expireNextMaxCallback = true;
        rig.maxClick(rig.maxButton("Главное меню"));
        assertEquals(1, rig.platforms.maxMenuEdits.get());
        assertEquals(mid, rig.platforms.to(MAX_OWNER).getLast().mid());
        assertTrue(text(rig.platforms.to(MAX_OWNER).getLast().body(), "text").contains("Создайте первый профиль"));
    }

    @Test
    void chatChoicesCheckMembershipInParallelAndExcludeInaccessibleChats() throws Exception {
        rig = new Rig(temp);
        rig.link(TG_OWNER, MAX_OWNER);
        rig.send("max", object("update_type", "bot_added", "chat_id", MAX_GROUP));
        rig.send("max", object("update_type", "bot_added", "chat_id", MAX_SOURCE));
        rig.platforms.maxMembers.get(MAX_GROUP).remove(MAX_OWNER);
        rig.send("max", object("update_type", "bot_started", "user", object("user_id", MAX_OWNER)));
        rig.maxClick(rig.maxButton("Новый профиль"));
        rig.platforms.membershipRequestsTogether = new CountDownLatch(2);
        rig.maxClick(rig.maxButton("MAX → Telegram"));
        assertEquals(0, rig.platforms.membershipRequestsTogether.getCount());
        var buttons = maxButtons(rig.platforms.to(MAX_OWNER).getLast().body());
        assertFalse(buttons.toString().contains("Room " + MAX_GROUP));
        assertTrue(buttons.toString().contains("Room " + MAX_SOURCE));
    }

    @Test
    void russianTelegramWizardLinksMaxAndReusesSavedDraftAcrossRestart() throws Exception {
        rig = new Rig(temp);
        rig.send("telegram", object("update_id", rig.updateId.incrementAndGet(), "my_chat_member",
                object("chat", object("id", TG_GROUP, "type", "supergroup"),
                        "new_chat_member", object("status", "administrator"))));
        rig.send("max", object("update_type", "bot_added", "chat_id", MAX_GROUP, "timestamp", 1));
        rig.send("max", object("update_type", "bot_added", "chat_id", MAX_CHANNEL, "timestamp", 1));
        rig.telegram("/start", TG_OWNER, TG_OWNER, 1);

        String initial = rig.telegramButton("Новый профиль");
        rig.telegramClick(TG_OWNER, initial.replace(":new", ":save"));
        await(() -> !rig.platforms.telegramAnswers.isEmpty());
        assertTrue(text(rig.platforms.telegramAnswers.getLast(), "text").contains("устарело"));
        assertTrue(rig.platforms.telegramSends.stream().anyMatch(value ->
                value.path("reply_markup").path("is_persistent").asBoolean(false)));
        rig.telegramClick(TG_OWNER, initial);
        rig.telegramClick(TG_OWNER, rig.telegramButton("Telegram → MAX"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Dispatch"));
        String link = rig.telegramButton("Открыть MAX");
        String code = link.substring(link.indexOf("start=") + 6);
        assertTrue(code.startsWith("link_"));
        rig.send("max", object("update_type", "bot_started", "chat_id", 513, "timestamp", 2,
                "user", object("user_id", MAX_OWNER), "payload", code));
        var maxScreen = rig.platforms.to(MAX_OWNER).getLast().body();
        assertTrue(text(maxScreen, "text").contains("Аккаунты связаны"));
        assertTrue(buttonValue(maxButtons(maxScreen), "Продолжить · Telegram", "payload").endsWith("start=resume"));

        rig.reopen();
        rig.telegram("/start resume", TG_OWNER, TG_OWNER, 2);
        rig.telegramClick(TG_OWNER, rig.telegramButton("Room " + MAX_GROUP));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Только с нужными словами"));
        rig.telegram("заявка, срочный ремонт", TG_OWNER, TG_OWNER, 3);
        assertTrue(rig.lastTelegram().contains("Проверьте профиль"));
        assertTrue(rig.store.routes("telegram", Long.toString(TG_OWNER), 0, 8).isEmpty());
        rig.telegramClick(TG_OWNER, rig.telegramButton("Создать и запустить"));
        var saved = rig.store.routes("telegram", Long.toString(TG_OWNER), 0, 8).getFirst();
        assertEquals(List.of("заявка", "срочный ремонт"), saved.terms());
        assertEquals(Long.toString(MAX_GROUP), saved.target());

        rig.telegramClick(TG_OWNER, rig.telegramButton("Главное меню"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Мои профили"));
        rig.telegramClick(TG_OWNER, rig.telegramButtonPrefix("Dispatch → "));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Приостановить"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Изменить фильтр"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Все сообщения"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Сохранить"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Изменить чат назначения"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Room " + MAX_CHANNEL + " · канал"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Сохранить"));
        var edited = rig.store.routes("telegram", Long.toString(TG_OWNER), 0, 8).getFirst();
        assertEquals(saved.id(), edited.id());
        assertEquals("all", edited.mode());
        assertEquals(Long.toString(MAX_CHANNEL), edited.target());
        assertFalse(edited.active());
        rig.telegramClick(TG_OWNER, rig.telegramButton("Возобновить"));
        assertTrue(rig.store.route("telegram", Long.toString(TG_OWNER), saved.id()).active());
        rig.telegramClick(TG_OWNER, rig.telegramButton("Удалить профиль"));
        assertNotNull(rig.store.route("telegram", Long.toString(TG_OWNER), saved.id()));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Удалить"));
        assertNull(rig.store.route("telegram", Long.toString(TG_OWNER), saved.id()));
    }

    @Test
    void maxWizardLinksTelegramCreatesReverseProfileAndReturnsReplies() throws Exception {
        rig = new Rig(temp);
        rig.send("max", object("update_type", "bot_added", "chat_id", MAX_SOURCE, "timestamp", 1));
        for (long id : List.of(TG_GROUP, TG_CHANNEL)) {
            rig.send("telegram", object("update_id", rig.updateId.incrementAndGet(), "my_chat_member",
                    object("chat", object("id", id, "type", id == TG_CHANNEL ? "channel" : "supergroup"),
                            "new_chat_member", object("status", "administrator"))));
        }
        rig.send("max", object("update_type", "bot_started", "chat_id", 73,
                "timestamp", 5, "user", object("user_id", MAX_OWNER), "payload", ""));
        String start = rig.maxButton("Новый профиль");
        rig.maxClick(start);
        rig.maxClick(start);
        assertTrue(text(rig.platforms.maxAnswers.getLast(), "notification").contains("устарело"));
        rig.maxClick(rig.maxButton("MAX → Telegram"));
        rig.maxClick(rig.maxButton("Room " + MAX_SOURCE));
        String link = rig.maxButton("Открыть Telegram");
        String code = link.substring(link.indexOf("start=") + 6);
        rig.telegram("/start " + code, TG_OWNER, TG_OWNER, 1);
        assertTrue(rig.lastTelegram().contains("Аккаунты связаны"));
        assertTrue(rig.telegramButton("Продолжить · MAX").endsWith("start=resume"));
        rig.send("max", object("update_type", "bot_started", "chat_id", 73,
                "timestamp", 6, "user", object("user_id", MAX_OWNER), "payload", "resume"));
        assertFalse(maxButtons(rig.platforms.to(MAX_OWNER).getLast().body()).toString().contains("Новости"));
        rig.platforms.telegramChannelWrite = true;
        rig.maxClick(rig.maxButton("Обновить список"));
        assertTrue(maxButtons(rig.platforms.to(MAX_OWNER).getLast().body()).toString().contains("Новости"));
        rig.maxClick(rig.maxButton("Dispatch"));
        rig.maxClick(rig.maxButton("Все, кроме сообщений со стоп-словами"));
        rig.max(",,", MAX_OWNER, 73, "dialog", null);
        assertTrue(text(rig.platforms.to(MAX_OWNER).getLast().body(), "text").contains("Введите от 1 до 30"));
        rig.max("реклама\nспам", MAX_OWNER, 73, "dialog", null);
        rig.maxClick(rig.maxButton("Дать название"));
        rig.max("Рабочие заявки", MAX_OWNER, 73, "dialog", null);
        rig.maxClick(rig.maxButton("Создать и запустить"));
        var profile = rig.store.userProfiles("max", Long.toString(MAX_OWNER), 0, 8).getFirst();
        assertEquals("telegram", profile.targetPlatform());
        assertEquals("Рабочие заявки", profile.name());
        assertEquals("exclude", profile.mode());
        assertEquals(List.of("реклама", "спам"), profile.terms());
        assertTrue(rig.store.userProfiles("telegram", Long.toString(TG_OTHER), 0, 8).isEmpty());
        rig.telegram("Мои профили", TG_OWNER, TG_OWNER, 2);
        rig.telegramClick(TG_OWNER, rig.telegramButton("Рабочие заявки"));
        assertTrue(rig.lastTelegram().contains("MAX → Telegram"));

        rig.max("Это реклама", MAX_OWNER, MAX_SOURCE, "chat", null);
        var source = rig.maxEvent("Новая заявка", MAX_OWNER, MAX_SOURCE, "chat", null);
        source.put("timestamp", Instant.parse("2026-11-23T21:05:09Z").toEpochMilli());
        var message = (ObjectNode) source.path("message");
        message.put("timestamp", Instant.parse("2026-11-23T21:04:08.987Z").toEpochMilli());
        ((ObjectNode) message.path("sender")).put("first_name", "Елена").put("last_name", "Соколова")
                .put("username", "elena_sokolova");
        rig.send("max", source);
        var delivered = rig.platforms.telegramSends.stream().filter(value -> value.path("chat_id").asLong() == TG_GROUP).toList();
        assertEquals(1, delivered.size());
        assertEquals("Елена Соколова (@elena_sokolova, 24.11.2026 00:04:08 МСК) · MAX / Room " + MAX_SOURCE
                + "\n\nНовая заявка", text(delivered.getFirst(), "text"));
        int forwardedId = rig.platforms.telegramMessageId;
        var reply = rig.telegramEvent("Принято", TG_OTHER, TG_GROUP, 8);
        ((ObjectNode) reply.path("message")).set("reply_to_message", object("message_id", forwardedId,
                "from", object("id", TG_BOT, "is_bot", true)));
        rig.send("telegram", reply);
        var relayed = rig.platforms.to(MAX_SOURCE).getLast().body();
        assertTrue(text(relayed, "text").contains("Принято"));
        assertEquals(object("type", "reply", "mid", text(source.path("message").path("body"), "mid")), relayed.path("link"));

        String oldMaxPause = rig.maxButton("Приостановить");
        rig.telegramClick(TG_OWNER, rig.telegramButton("Изменить чат назначения"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Новости · канал"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Сохранить"));
        rig.maxClick(oldMaxPause);
        assertTrue(text(rig.platforms.to(MAX_OWNER).getLast().body(), "text").contains("Профиль уже изменён"));
        assertTrue(rig.store.userProfile("telegram", Long.toString(TG_OWNER), profile.id()).active());
        rig.max("Заявка для канала", MAX_OWNER, MAX_SOURCE, "chat", null);
        assertTrue(rig.platforms.telegramSends.stream().anyMatch(value -> value.path("chat_id").asLong() == TG_CHANNEL
                && text(value, "text").contains("Заявка для канала")));

        rig.telegramClick(TG_OWNER, rig.telegramButton("Изменить направление"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Telegram → MAX"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Dispatch"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Room " + MAX_SOURCE));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Сохранить"));
        var changed = rig.store.userProfile("max", Long.toString(MAX_OWNER), profile.id());
        assertEquals("telegram", changed.platform());
        assertEquals("max", changed.targetPlatform());
        assertEquals(profile.terms(), changed.terms());
        assertEquals(profile.name(), changed.name());

        rig.telegram("Новый профиль", TG_OWNER, TG_OWNER, 9);
        rig.telegramClick(TG_OWNER, rig.telegramButton("Telegram → MAX"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Dispatch"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Room " + MAX_SOURCE));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Все сообщения"));
        rig.telegramClick(TG_OWNER, rig.telegramButton("Создать и запустить"));
        var profiles = rig.store.userProfiles("max", Long.toString(MAX_OWNER), 0, 8);
        assertEquals(List.of("exclude", "all"), profiles.stream().map(Store.Route::mode).toList());
        int before = rig.platforms.to(MAX_SOURCE).size();
        rig.telegram("Заявка по двум профилям", TG_OWNER, TG_GROUP, 10);
        assertEquals(before + 1, rig.platforms.to(MAX_SOURCE).size());
    }

    private static JsonNode telegramButtons(JsonNode body) {
        return body.path("reply_markup").path("inline_keyboard");
    }

    private static JsonNode maxButtons(JsonNode body) {
        return body.path("attachments").path(0).path("payload").path("buttons");
    }

    private static String buttonValue(JsonNode rows, String label, String callbackField) {
        for (var row : rows) {
            for (var button : row) {
                if (text(button, "text").equals(label)) {
                    return Json.first(text(button, callbackField), text(button, "url"));
                }
            }
        }
        throw new AssertionError("Button missing: " + label + " from " + rows);
    }

    @Test
    void matchesUnicodeWordsAndPhrasesWithLegacyBoundaries() {
        List<String> terms = Content.parseFilter("include", " Straße ,  аварийная заявка, urgent, urgent ");
        assertEquals(List.of("strasse", "аварийная заявка", "urgent"), terms);
        var include = new Store.Route(1, "telegram", "1", "2", "3", "include", terms, true, 0);
        var exclude = new Store.Route(1, "telegram", "1", "2", "3", "exclude", terms, true, 0);
        assertTrue(Content.matches(include, Content.normalize("STRASSE!")));
        assertTrue(Content.matches(include, Content.normalize("АВАРИЙНАЯ\nзаявка.")));
        assertFalse(Content.matches(include, Content.normalize("urgently")));
        assertFalse(Content.matches(include, Content.normalize("ℤurgent")));
        assertTrue(Content.matches(exclude, ""));
        assertFalse(Content.matches(exclude, Content.normalize("urgent")));
        assertThrows(IllegalArgumentException.class, () -> Content.parseFilter("include", "  , "));
    }

    @Test
    void preservesPythonLifecycleDeduplicationKeys() {
        var data = object("update_type", "bot_started", "timestamp", 700, "chat_id", 42,
                "user", object("user_id", MAX_OWNER, "first_name", "Ёлка 🎧\u000B"), "payload", "src_-123");
        assertEquals("bot_started:db6d20b578fbb75fbf86f9813f222eccc902a9ef2044e5bcde71d1365cb7bc15",
                Update.from("max", data).key());
    }

    private static void execute(PreparedStatement statement, String... fields) throws SQLException {
        try (statement) {
            for (int index = 0; index < fields.length; index++) {
                statement.setString(index + 1, fields[index]);
            }
            statement.executeUpdate();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                fail("Timed out waiting for the expected delivery state");
            }
            Thread.sleep(10);
        }
    }

    private static ObjectNode telegramEvent(long update, String text, long user, long chat, long mid) {
        var message = object("message_id", mid, "from", object("id", user, "is_bot", user == TG_BOT, "first_name", "Sasha"),
                "chat", object("id", chat, "type", chat < 0 ? "supergroup" : "private", "title", "Dispatch"));
        if (text != null) {
            message.put("text", text);
        }
        return object("update_id", update, "message", message);
    }

    private static final class Rig implements AutoCloseable {
        final Platforms platforms = new Platforms();
        final Transport telegramTransport = new Transport(1000, null);
        final Transport maxTransport = new Transport(1000, null);
        final HttpClient webhookClient = HttpClient.newHttpClient();
        final AtomicLong updateId = new AtomicLong();
        final Path db;
        final Telegram telegram;
        final Max max;
        Store store;
        DeliveryQueue queue;
        Webhooks webhooks;

        Rig(Path temp) throws Exception {
            db = temp.resolve("bridge.sqlite3");
            telegram = new Telegram(telegramTransport, platforms.base(), "telegram-token");
            max = new Max(maxTransport, platforms.base(), "max-token");
            open();
        }

        void open() throws Exception {
            store = new Store(db);
            var bridge = new Bridge(store, telegram, max, object("id", TG_BOT, "username", "telegram_bot"),
                    object("user_id", MAX_BOT, "username", "max_bot"), 2);
            queue = new DeliveryQueue(store, 6, bridge::handle);
            webhooks = new Webhooks(new InetSocketAddress("127.0.0.1", 0), store, queue, TG_SECRET, MAX_SECRET);
            queue.start();
            webhooks.start();
        }

        void reopen() throws Exception {
            closeStore();
            open();
        }

        private void closeStore() {
            webhooks.close();
            queue.close();
            store.close();
        }

        @Override
        public void close() {
            platforms.blockRelease.countDown();
            closeStore();
            webhookClient.shutdownNow();
            telegramTransport.close();
            maxTransport.close();
            platforms.close();
        }

        ObjectNode telegramEvent(String text, long user, long chat, long mid) {
            return BridgeTest.telegramEvent(updateId.incrementAndGet(), text, user, chat, mid);
        }

        ObjectNode maxEvent(String text, long user, long chat, String type, String reply) {
            var message = object("sender", object("user_id", user, "is_bot", user == MAX_BOT, "first_name", "Lena"),
                    "recipient", object("chat_id", chat, "chat_type", type),
                    "body", object("mid", "incoming." + updateId.incrementAndGet(), "text", text));
            if (reply != null) {
                message.set("link", object("type", "reply", "message", object("mid", reply),
                        "sender", object("user_id", MAX_BOT)));
            }
            return object("update_type", "message_created", "message", message);
        }

        ObjectNode maxComment(String text, long user, String post) {
            return object("update_type", "comment_created", "message",
                    object("sender", object("user_id", user, "first_name", "Lena"),
                            "recipient", object("chat_id", MAX_CHANNEL, "chat_type", "channel", "post_id", post),
                            "body", object("mid", "comment." + updateId.incrementAndGet(), "text", text)));
        }

        void telegram(String text, long user, long chat, long id) throws Exception {
            send("telegram", telegramEvent(text, user, chat, id));
        }

        String lastTelegram() {
            return text(platforms.telegramSends.getLast(), "text");
        }

        String telegramButton(String label) {
            return buttonValue(telegramButtons(privateTelegramMenu()), label, "callback_data");
        }

        String telegramButtonPrefix(String label) {
            var rows = telegramButtons(privateTelegramMenu());
            for (var row : rows) {
                for (var button : row) {
                    if (text(button, "text").startsWith(label)) {
                        return text(button, "callback_data");
                    }
                }
            }
            throw new AssertionError("Button missing: " + label);
        }

        JsonNode privateTelegramMenu() {
            return platforms.telegramSends.stream()
                    .filter(item -> item.path("chat_id").asLong() == TG_OWNER && telegramButtons(item).isArray())
                    .reduce((earlier, later) -> later).orElseThrow();
        }

        String maxButton(String label) {
            return buttonValue(maxButtons(platforms.to(MAX_OWNER).getLast().body()), label, "payload");
        }

        void telegramClick(long user, String data) throws Exception {
            long id = updateId.incrementAndGet();
            send("telegram", object("update_id", id, "callback_query", object(
                    "id", "click." + id, "from", object("id", user), "data", data,
                    "message", object("message_id", 2, "chat", object("id", user, "type", "private")))));
        }

        void maxClick(String data) throws Exception {
            long id = updateId.incrementAndGet();
            String mid = platforms.to(MAX_OWNER).getLast().mid();
            platforms.maxCallbackMessages.put("click." + id, mid);
            send("max", object("update_type", "message_callback",
                    "callback", object("callback_id", "click." + id, "payload", data, "user", object("user_id", MAX_OWNER)),
                    "message", object("recipient", object("chat_type", "dialog"), "body",
                            object("mid", mid))));
        }

        void max(String text, long user, long chat, String kind, String reply) throws Exception {
            send("max", maxEvent(text, user, chat, kind, reply));
        }

        void route(long telegram, long max, long source, long target, String filter) throws Exception {
            telegram("/bridge@telegram_bot", telegram, source, 1);
            assertTrue(text(telegramButtons(platforms.telegramSends.getLast()).path(0).path(0), "url")
                    .endsWith("start=src_" + source));
            telegram("/start src_" + source, telegram, telegram, 1);
            link(telegram, max);
            telegram("/route " + target + " " + filter, telegram, telegram, 1);
            assertTrue(lastTelegram().contains("включена"));
        }

        String link(long telegram, long max) throws Exception {
            telegram("/link", telegram, telegram, 1);
            var code = Pattern.compile("start=(link_[A-Za-z0-9_-]+)").matcher(lastTelegram());
            assertTrue(code.find());
            String result = code.group(1);
            send("max", object("update_type", "bot_started", "chat_id", 513, "timestamp", updateId.incrementAndGet(),
                    "user", object("user_id", max), "payload", result));
            assertEquals(Long.toString(max), store.maxUser(Long.toString(telegram)));
            return result;
        }

        void send(String platform, JsonNode data) throws Exception {
            post(platform, data, true);
            awaitIdle();
        }

        void post(String platform, JsonNode data, boolean authorized) throws Exception {
            assertEquals(200, httpPost(platform, data.toString(), authorized));
        }

        int httpPost(String platform, String text, boolean authorized) throws Exception {
            boolean tg = platform.equals("telegram");
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webhooks.address().getPort()
                            + "/webhook/" + platform))
                    .header(tg ? "X-Telegram-Bot-Api-Secret-Token" : "X-Max-Bot-Api-Secret",
                            authorized ? (tg ? TG_SECRET : MAX_SECRET) : "wrong")
                    .timeout(Duration.ofSeconds(4)).POST(HttpRequest.BodyPublishers.ofString(text)).build();
            return webhookClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }

        void awaitIdle() throws InterruptedException {
            await(() -> pending() == 0);
        }

        int pending() {
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                 var statement = conn.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM inbox WHERE data IS NOT NULL")) {
                return result.getInt(1);
            } catch (SQLException error) {
                throw new AssertionError(error);
            }
        }
    }

    private record Sent(long target, JsonNode body, String mid) {}

    private static final class Platforms implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
        final List<JsonNode> telegramSends = new CopyOnWriteArrayList<>();
        final List<JsonNode> telegramAnswers = new CopyOnWriteArrayList<>();
        final List<JsonNode> maxAnswers = new CopyOnWriteArrayList<>();
        final List<Sent> maxSends = new CopyOnWriteArrayList<>();
        final Map<String, String> maxCallbackMessages = new ConcurrentHashMap<>();
        final Set<Long> telegramMembers = ConcurrentHashMap.newKeySet();
        final Map<Long, Set<Long>> maxMembers = new ConcurrentHashMap<>();
        final Set<Long> failOnce = ConcurrentHashMap.newKeySet();
        final CountDownLatch blockEntered = new CountDownLatch(1);
        final CountDownLatch blockRelease = new CountDownLatch(1);
        final CountDownLatch telegramCallbackEntered = new CountDownLatch(1);
        final CountDownLatch telegramCallbackRelease = new CountDownLatch(1);
        final AtomicInteger failedPosts = new AtomicInteger();
        final AtomicInteger maxMenuEdits = new AtomicInteger();
        private final AtomicLong nextMid = new AtomicLong();
        volatile JsonNode telegramRegistration;
        volatile JsonNode maxRegistration;
        volatile String telegramMultipart;
        volatile boolean protectedSource;
        volatile boolean channelWrite = true;
        volatile boolean telegramChannelWrite;
        volatile int telegramMessageId;
        volatile String blockOn;
        volatile String blockedTelegramCallback;
        volatile boolean expireNextMaxCallback;
        volatile CountDownLatch membershipRequestsTogether;

        Platforms() throws IOException {
            telegramMembers.addAll(List.of(TG_OWNER, TG_OTHER));
            for (long chat : List.of(MAX_GROUP, MAX_CHANNEL, MAX_SOURCE)) {
                Set<Long> members = ConcurrentHashMap.newKeySet();
                members.add(MAX_OWNER);
                if (chat == MAX_GROUP) {
                    members.add(MAX_OTHER);
                }
                maxMembers.put(chat, members);
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
            server.setExecutor(tasks);
            server.createContext("/", this::handle);
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<Sent> to(long chat) {
            return maxSends.stream().filter(sent -> sent.target() == chat).toList();
        }

        boolean has(long chat, String text) {
            return to(chat).stream().anyMatch(sent -> Json.text(sent.body(), "text").contains(text));
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                byte[] raw = exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/file/bottelegram-token/")) {
                    writeFile(exchange, path.endsWith("/oversize"));
                    return;
                }
                if (path.equals("/bottelegram-token/sendDocument")) {
                    telegramMultipart = new String(raw, StandardCharsets.UTF_8);
                    reply(exchange, 200, object("ok", true, "result", object("message_id", nextMid.incrementAndGet())));
                    return;
                }
                JsonNode body = raw.length == 0 ? object() : Json.MAPPER.readTree(raw);
                if (path.startsWith("/bottelegram-token/")) {
                    if (path.endsWith("/answerCallbackQuery")
                            && text(body, "callback_query_id").equals(blockedTelegramCallback)) {
                        telegramCallbackEntered.countDown();
                        waitFor(telegramCallbackRelease);
                        reply(exchange, 503, object("ok", false));
                        return;
                    }
                    var result = telegram(path.substring(path.lastIndexOf('/') + 1), body);
                    reply(exchange, 200, object("ok", true, "result", result));
                } else if ("max-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    max(exchange, path, body);
                } else {
                    reply(exchange, 401, object("code", "bad.token"));
                }
            }
        }

        private Object telegram(String method, JsonNode body) {
            return switch (method) {
                case "getChatMember" -> {
                    long user = body.path("user_id").asLong();
                    yield object("status", user == TG_BOT ? "administrator" :
                            telegramMembers.contains(user) ? "member" : "left",
                            "can_post_messages", telegramChannelWrite);
                }
                case "getChat" -> object("id", body.path("chat_id"), "type",
                        body.path("chat_id").asLong() == TG_CHANNEL ? "channel" : "supergroup", "title",
                        body.path("chat_id").asLong() == TG_CHANNEL ? "Новости" : "Dispatch",
                        "has_protected_content", protectedSource);
                case "getFile" -> object("file_path", text(body, "file_id"));
                case "sendMessage", "editMessageText" -> {
                    telegramSends.add(body);
                    int messageId = Math.toIntExact(nextMid.incrementAndGet());
                    if (body.path("chat_id").asLong() == TG_GROUP) telegramMessageId = messageId;
                    yield object("message_id", messageId);
                }
                case "answerCallbackQuery" -> {
                    telegramAnswers.add(body);
                    yield true;
                }
                case "setMyCommands" -> true;
                case "setWebhook" -> {
                    telegramRegistration = body;
                    yield true;
                }
                default -> throw new AssertionError("Unexpected Telegram request " + method);
            };
        }

        private void max(HttpExchange exchange, String path, JsonNode body) throws IOException {
            if (path.equals("/answers")) {
                maxAnswers.add(body);
                if (expireNextMaxCallback) {
                    expireNextMaxCallback = false;
                    reply(exchange, 404, object("code", "callback.not.found"));
                    return;
                }
                if (body.has("message")) {
                    String mid = maxCallbackMessages.get(query(exchange, "callback_id"));
                    var previous = maxSends.stream().filter(sent -> sent.mid().equals(mid)).findFirst().orElseThrow();
                    maxSends.add(new Sent(previous.target(), body.path("message"), mid));
                }
                reply(exchange, 200, object("success", true));
                return;
            }
            if (path.equals("/me/commands")) {
                reply(exchange, 200, object("success", true));
                return;
            }
            if (path.equals("/messages")) {
                if (exchange.getRequestMethod().equals("PUT")) {
                    maxMenuEdits.incrementAndGet();
                    String mid = query(exchange, "message_id");
                    var previous = maxSends.stream().filter(sent -> sent.mid().equals(mid)).findFirst().orElseThrow();
                    maxSends.add(new Sent(previous.target(), body, mid));
                    reply(exchange, 200, object("success", true));
                    return;
                }
                String target = query(exchange, "chat_id");
                long chat = Long.parseLong(target == null ? query(exchange, "user_id") : target);
                if (failOnce.remove(chat)) {
                    failedPosts.incrementAndGet();
                    reply(exchange, 429, object("code", "rate.limit"));
                    return;
                }
                String mid = "mid." + nextMid.incrementAndGet();
                maxSends.add(new Sent(chat, body, mid));
                if (blockOn != null && text(body, "text").contains(blockOn)) {
                    blockEntered.countDown();
                    try {
                        assertTrue(blockRelease.await(8, TimeUnit.SECONDS));
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                reply(exchange, 200, object("message", object("body", object("mid", mid))));
                return;
            }
            if (path.equals("/subscriptions")) {
                maxRegistration = body;
                reply(exchange, 200, object("success", true));
                return;
            }
            String[] parts = path.split("/");
            long chat = Long.parseLong(parts[2]);
            if (!maxMembers.containsKey(chat)) {
                reply(exchange, 404, object("code", "chat.not.found"));
            } else if (path.endsWith("/members/me")) {
                List<String> permissions = chat == MAX_CHANNEL && !channelWrite ? List.of("read_all_messages")
                        : List.of("read_all_messages", "write");
                reply(exchange, 200, object("is_admin", true, "permissions", permissions));
            } else if (path.endsWith("/members")) {
                var together = membershipRequestsTogether;
                if (together != null) {
                    together.countDown();
                    waitFor(together);
                }
                List<JsonNode> members = new ArrayList<>();
                for (String user : query(exchange, "user_ids").split(",")) {
                    if (maxMembers.get(chat).contains(Long.parseLong(user))) {
                        members.add(object("user_id", Long.parseLong(user)));
                    }
                }
                reply(exchange, 200, object("members", members));
            } else {
                reply(exchange, 200, object("chat_id", chat, "type", chat == MAX_CHANNEL ? "channel" : "chat",
                        "status", "active", "title", "Room " + chat));
            }
        }

        private static void waitFor(CountDownLatch latch) throws IOException {
            try {
                if (!latch.await(6, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for the test request");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            }
        }

        private static String query(HttpExchange exchange, String field) {
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw != null) {
                for (String query : raw.split("&")) {
                    String[] parts = query.split("=", 2);
                    if (parts[0].equals(field)) {
                        return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                    }
                }
            }
            return null;
        }

        private static void reply(HttpExchange exchange, int status, JsonNode data) throws IOException {
            byte[] raw = data.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, raw.length);
            exchange.getResponseBody().write(raw);
        }

        private static void writeFile(HttpExchange exchange, boolean oversize) throws IOException {
            if (!oversize) {
                exchange.sendResponseHeaders(200, 3);
                exchange.getResponseBody().write(new byte[] {1, 2, 3});
                return;
            }
            exchange.sendResponseHeaders(200, 0);
            var bytes = new byte[8192];
            for (int count = 0; count <= Transport.MEDIA_LIMIT / bytes.length; count++) {
                exchange.getResponseBody().write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
            tasks.shutdownNow();
        }
    }
}
