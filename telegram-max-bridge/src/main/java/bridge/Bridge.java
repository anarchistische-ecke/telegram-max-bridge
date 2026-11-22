package bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import tools.jackson.databind.JsonNode;

import static bridge.Content.*;
import static bridge.Json.*;
import static bridge.Transport.*;

final class Bridge {
    private static final Logger LOG = Logger.getLogger(Bridge.class.getName());
    private final Store store;
    private final Telegram telegram;
    private final Max max;
    private final ChatAccess access;
    private final Commands commands;
    private final String telegramBotId;
    private final String maxBotId;
    private final Semaphore media;
    private final Map<String, Integer> forwardsInFlight = new ConcurrentHashMap<>();

    Bridge(Store store, Telegram telegram, Max max, JsonNode telegramBot, JsonNode maxBot, int concurrentMedia) {
        this.store = store;
        this.telegram = telegram;
        this.max = max;
        telegramBotId = telegramBot.required("id").asString();
        maxBotId = maxBot.required("user_id").asString();
        access = new ChatAccess(telegram, max, telegramBotId);
        commands = new Commands(store, access, telegramBot.required("username").asString(),
                maxBot.required("username").asString());
        media = new Semaphore(concurrentMedia);
    }

    void handle(Store.Job job) {
        JsonNode update = MAPPER.readTree(job.data());
        if (job.platform().equals("telegram")) {
            telegramUpdate(job, update.path("message"));
        } else {
            maxUpdate(job, update);
        }
    }

    private void telegramUpdate(Store.Job job, JsonNode message) {
        var chat = message.path("chat");
        var sender = message.path("from");
        var anonymous = message.path("sender_chat");
        String id = value(chat, "id");
        String senderId = value(sender, "id");
        if (id == null || telegramBotId.equals(senderId) || sender.isEmpty() && anonymous.isEmpty()) {
            return;
        }
        boolean group = List.of("group", "supergroup").contains(text(chat, "type"));
        var command = sender.path("is_bot").asBoolean(false) ? null : commands.parse("telegram", text(message, "text"));
        if (command != null) {
            if (group && anonymous.isEmpty() && command.name().equals("bridge")) {
                String thread = telegramThread(message);
                once(job, "group-setup", () -> telegram.send(id, commands.group("telegram", id), null, thread, null));
            } else if (text(chat, "type").equals("private") && id.equals(senderId)) {
                privateCommand(job, id, command);
            }
            return;
        }
        if (!group || chat.path("has_protected_content").asBoolean(false)
                || message.path("has_protected_content").asBoolean(false)) {
            return;
        }
        TelegramMessage content = telegramMessage(message);
        if (content == null) {
            return;
        }
        var routes = matchingRoutes(job, id, content.filterText());
        if (routes.isEmpty() || access.source("telegram", id) == null) {
            return;
        }
        String senderName = safeTitle(displayName(anonymous.isEmpty() ? sender : anonymous));
        String header = senderName + " · Telegram / " + safeTitle(first(text(chat, "title"), id));
        String mid = text(message, "message_id");
        String thread = telegramThread(message);
        deliverRoutes(job.platform(), id, routes, target -> forwardTelegram(job, id, mid, thread, target, header, content));
    }

    private static String telegramThread(JsonNode message) {
        return message.path("is_topic_message").asBoolean(false) ? value(message, "message_thread_id") : null;
    }

    private void maxUpdate(Store.Job job, JsonNode update) {
        switch (text(update, "update_type")) {
            case "message_created" -> maxMessage(job, update.path("message"));
            case "bot_started" -> {
                var sender = update.path("user");
                var user = value(sender, "user_id");
                if (user != null && !sender.path("is_bot").asBoolean(false)) {
                    privateCommand(job, user, new Commands.Command("start", text(update, "payload")));
                }
            }
            case "bot_added", "bot_admin_permissions_changed", "bot_removed" -> {
                String chat = value(update, "chat_id");
                if (chat != null) {
                    if (text(update, "update_type").equals("bot_removed")) {
                        store.forgetMaxChat(chat);
                    } else {
                        store.rememberMaxChat(chat);
                    }
                }
            }
            default -> { }
        }
    }

    private void maxMessage(Store.Job job, JsonNode message) {
        var recipient = message.path("recipient");
        String chat = value(recipient, "chat_id");
        String kind = text(recipient, "chat_type");
        if (chat != null && List.of("chat", "channel").contains(kind)) {
            store.rememberMaxChat(chat);
        }
        var sender = message.path("sender");
        String user = value(sender, "user_id");
        var body = message.path("body");
        String mid = text(body, "mid");
        if (user == null || user.equals(maxBotId) || mid.isEmpty()) {
            return;
        }
        var command = sender.path("is_bot").asBoolean(false) ? null : commands.parse("max", text(body, "text"));
        if (kind.equals("dialog") && command != null) {
            privateCommand(job, user, command);
            return;
        }
        if (!kind.equals("chat") || chat == null) {
            return;
        }
        if (handleMaxReply(job, chat, user, message)) {
            return;
        }
        if (command != null) {
            if (command.name().equals("bridge")) {
                once(job, "group-setup", () -> max.send(chat, null, commands.group("max", chat), null, null));
            }
            return;
        }
        var routes = matchingRoutes(job, chat, maxFilterText(message));
        var source = routes.isEmpty() ? null : access.source("max", chat);
        if (source == null) {
            return;
        }
        String title = safeTitle(first(text(source, "title"), chat));
        deliverRoutes("max", chat, routes, target -> once(job, "forward:" + target,
                () -> max.send(target, null, "From " + title, object("type", "forward", "mid", mid), null),
                new Store.Origin(target, "max", chat, mid, null)));
    }

    private boolean handleMaxReply(Store.Job job, String chat, String user, JsonNode message) {
        var link = message.path("link");
        String parent = text(link.path("message"), "mid");
        if (!text(link, "type").equals("reply") || parent.isEmpty()) {
            return false;
        }
        boolean pendingForward = forwardsInFlight.containsKey(chat);
        var origin = store.origin(chat, parent);
        if (origin == null) {
            String parentSender = value(link.path("sender"), "user_id");
            if (pendingForward && (parentSender == null || parentSender.equals(maxBotId))
                    && Store.now() - job.received() < 30) {
                throw new DeliveryQueue.Defer();
            }
            return false;
        }
        if (access.target(chat, user) != null) {
            returnReply(job, message, origin);
        }
        return true;
    }

    private void privateCommand(Store.Job job, String user, Commands.Command command) {
        String result = commands.privateCommand(job, user, command);
        int index = 0;
        for (String part : splitText(result, 4000)) {
            once(job, "command:" + index++, () -> job.platform().equals("telegram")
                    ? telegram.send(user, part, null, null, null) : max.send(null, user, part, null, null));
        }
    }

    private Map<String, List<Store.Route>> matchingRoutes(Store.Job job, String source, String text) {
        var routes = store.activeRoutes(job.platform(), source, job.received());
        Map<String, List<Store.Route>> targets = new LinkedHashMap<>();
        if (!routes.isEmpty()) {
            String normalized = normalize(text);
            for (var route : routes) {
                if (matches(route, normalized)) {
                    targets.computeIfAbsent(route.target(), key -> new ArrayList<>()).add(route);
                }
            }
        }
        return targets;
    }

    private void deliverRoutes(String platform, String source, Map<String, List<Store.Route>> routes,
                               Consumer<String> send) {
        ApiFailure retry = null;
        Map<String, Boolean> sourceMembership = new HashMap<>();
        for (var target : routes.entrySet()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                if (access.maxChat(target.getKey(), null) != null
                        && hasEligibleOwner(platform, source, target.getKey(), target.getValue(), sourceMembership)) {
                    send.accept(target.getKey());
                }
            } catch (ApiFailure error) {
                if (error.retryable()) {
                    retry = later(retry, error);
                } else {
                    LOG.warning(() -> "Forwarding to MAX chat " + target.getKey() + " failed (" + error.getMessage() + ")");
                }
            }
        }
        if (retry != null) {
            throw retry;
        }
    }

    private boolean hasEligibleOwner(String platform, String source, String target, List<Store.Route> routes,
                                     Map<String, Boolean> sourceMembership) {
        ApiFailure retry = null;
        for (var route : routes) {
            String user = route.owner();
            try {
                String maxUser = commands.targetUser(platform, user);
                if (maxUser == null || !sourceMembership.computeIfAbsent(user,
                        key -> access.isMember(platform, source, key)) || !access.isMember("max", target, maxUser)) {
                    continue;
                }
                var current = store.route(platform, user, route.id());
                if (route.equals(current) && maxUser.equals(commands.targetUser(platform, user))) {
                    return true;
                }
            } catch (ApiFailure error) {
                if (error.retryable()) {
                    retry = later(retry, error);
                } else {
                    LOG.warning(() -> "Could not check membership for MAX chat " + target + " (" + error.getMessage() + ")");
                }
            }
        }
        if (retry != null) {
            throw retry;
        }
        return false;
    }

    private static ApiFailure later(ApiFailure current, ApiFailure next) {
        return current == null || next.retryAfter > current.retryAfter ? next : current;
    }

    private void forwardTelegram(Store.Job job, String chat, String mid, String thread, String target, String header,
                                 TelegramMessage content) {
        JsonNode prepared = content.media() == null ? object("attachment", content.attachment())
                : once(job, "prepare-upload", () -> prepareUpload(content.media()));
        // Python persisted uploads as a pair of attachment and fallback text.
        JsonNode attachment = prepared.isArray() ? prepared.path(0) : prepared.path("attachment");
        String note = prepared.isArray() ? prepared.path(1).asString("") : text(prepared, "note");
        String message = join("\n\n", header, content.rendered(), note);
        var origin = new Store.Origin(target, "telegram", chat, mid, thread);
        int index = 0;
        for (String part : splitText(message, 3900)) {
            JsonNode useAttachment = index == 0 && attachment.isObject() ? attachment : null;
            once(job, "forward:" + target + ":" + index++, () -> sendToMax(target, part, useAttachment), origin);
        }
    }

    private JsonNode sendToMax(String target, String part, JsonNode attachment) {
        try {
            return max.send(target, null, part, null, attachment);
        } catch (ApiFailure error) {
            if (attachment == null || error.retryable() || !List.of(400, 413, 415).contains(error.status)) {
                throw error;
            }
            return max.send(target, null, part + "\n\n[MAX rejected this attachment]", null, null);
        }
    }

    private JsonNode prepareUpload(Media item) {
        try {
            if (item.length() > MEDIA_LIMIT) {
                throw new MediaFailure("attachment exceeds 20 MB");
            }
            JsonNode attachment = withMediaSlot(() -> max.upload(item.kind(), item.name(), telegram.download(item.fileId())));
            return object("attachment", attachment);
        } catch (MediaFailure error) {
            return object("note", mediaNote(item.name(), error.getMessage()));
        } catch (ApiFailure error) {
            if (!error.unavailable() && !List.of(413, 415).contains(error.status)) {
                throw error;
            }
            return object("note", mediaNote(item.name(), "attachment could not be transferred"));
        }
    }

    private void returnReply(Store.Job job, JsonNode message, Store.Origin origin) {
        if (access.source(origin.platform(), origin.source()) == null) {
            return;
        }
        String header = safeTitle(displayName(message.path("sender"))) + " · MAX";
        var body = message.path("body");
        String text = join("\n\n", header, showLinkUrls(text(body, "text"), body.path("markup")));
        boolean toTelegram = origin.platform().equals("telegram");
        JsonNode link = object("type", "reply", "mid", origin.message());
        int index = 0;
        for (String part : splitText(text, 4000)) {
            once(job, "return:text:" + index++, () -> toTelegram
                    ? telegram.send(origin.source(), part, origin.message(), origin.thread(), null)
                    : max.send(origin.source(), null, part, link, null));
        }
        index = 0;
        for (JsonNode attachment : body.path("attachments")) {
            if (!text(attachment, "type").equals("inline_keyboard")) {
                once(job, "return:media:" + index++, () -> toTelegram
                        ? replyMediaToTelegram(origin, attachment) : replyMediaToMax(origin.source(), link, attachment));
            }
        }
    }

    private JsonNode replyMediaToTelegram(Store.Origin origin, JsonNode attachment) {
        String kind = text(attachment, "type");
        var payload = attachment.path("payload");
        String filename = first(value(payload, "filename"), value(payload, "name"),
                MAX_FILENAMES.getOrDefault(kind, "attachment"));
        String note;
        try {
            if (kind.equals("location") && attachment.has("latitude") && attachment.has("longitude")) {
                note = "Location: " + text(attachment, "latitude") + ", " + text(attachment, "longitude");
            } else if (MAX_FILENAMES.containsKey(kind)) {
                return withMediaSlot(() -> telegram.send(origin.source(), null, origin.message(), origin.thread(),
                        new Upload("document", filename, max.download(attachment))));
            } else {
                note = "[MAX " + first(kind, "attachment") + "]";
            }
        } catch (MediaFailure error) {
            note = mediaNote(filename, error.getMessage());
        } catch (ApiFailure error) {
            if (!error.unavailable() && !List.of(413, 415).contains(error.status)) {
                throw error;
            }
            note = mediaNote(filename, "attachment could not be transferred");
        }
        return telegram.send(origin.source(), note, origin.message(), origin.thread(), null);
    }

    private JsonNode replyMediaToMax(String chat, JsonNode link, JsonNode attachment) {
        JsonNode reused = reusableMaxAttachment(attachment);
        if (reused != null) {
            try {
                return max.send(chat, null, null, link, reused);
            } catch (ApiFailure error) {
                if (!error.unavailable()) {
                    throw error;
                }
            }
        }
        return max.send(chat, null, "[MAX " + first(text(attachment, "type"), "attachment") + "]", link, null);
    }

    private JsonNode withMediaSlot(Supplier<JsonNode> transfer) {
        if (!media.tryAcquire()) {
            throw new DeliveryQueue.Defer();
        }
        try {
            return transfer.get();
        } finally {
            media.release();
        }
    }

    private static String mediaNote(String name, String reason) {
        return "[" + safeTitle(name) + " — " + reason + "]";
    }

    private JsonNode once(Store.Job job, String action, Supplier<JsonNode> execute) {
        return once(job, action, execute, null);
    }

    private JsonNode once(Store.Job job, String action, Supplier<JsonNode> execute, Store.Origin origin) {
        JsonNode saved = store.step(job.seq(), action);
        if (saved != null) {
            return saved;
        }
        if (origin != null) {
            forwardsInFlight.merge(origin.target(), 1, Integer::sum);
        }
        try {
            JsonNode result = execute.get();
            store.saveStep(job.seq(), action, result, origin);
            return result;
        } finally {
            if (origin != null) {
                forwardsInFlight.compute(origin.target(), (chat, count) -> count == 1 ? null : count - 1);
            }
        }
    }
}
