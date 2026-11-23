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

import bridge.Store.Destination;

final class Bridge {
    private static final Logger LOG = Logger.getLogger(Bridge.class.getName());
    private final Store store;
    private final Telegram telegram;
    private final Max max;
    private final ChatAccess access;
    private final Commands commands;
    private final Menu menu;
    private final String telegramBotId;
    private final String maxBotId;
    private final Semaphore media;
    private final Map<Destination, Integer> forwardsInFlight = new ConcurrentHashMap<>();

    Bridge(Store store, Telegram telegram, Max max, JsonNode telegramBot, JsonNode maxBot, int concurrentMedia) {
        this.store = store;
        this.telegram = telegram;
        this.max = max;
        telegramBotId = telegramBot.required("id").asString();
        maxBotId = maxBot.required("user_id").asString();
        access = new ChatAccess(telegram, max, telegramBotId);
        commands = new Commands(store, access, telegramBot.required("username").asString(),
                maxBot.required("username").asString());
        menu = new Menu(store, access, commands);
        media = new Semaphore(concurrentMedia);
    }

    void handle(Store.Job job) {
        JsonNode update = MAPPER.readTree(job.data());
        if (job.platform().equals("telegram")) {
            if (update.has("callback_query")) {
                var callback = update.path("callback_query");
                var message = callback.path("message");
                var chat = message.path("chat");
                String user = value(callback.path("from"), "id");
                if (user != null && user.equals(value(chat, "id")) && text(chat, "type").equals("private")) {
                    menuCallback(job, user, value(message, "message_id"), text(callback, "id"), text(callback, "data"));
                }
            } else if (update.has("my_chat_member")) {
                var membership = update.path("my_chat_member");
                var chat = membership.path("chat");
                if (List.of("group", "supergroup", "channel").contains(text(chat, "type")) && chat.hasNonNull("id")) {
                    String id = text(chat, "id");
                    if (text(membership.path("new_chat_member"), "status").equals("administrator")) {
                        store.rememberTelegramChat(id);
                    } else {
                        store.forgetTelegramChat(id);
                    }
                }
            } else {
                telegramUpdate(job, update.path("message"));
            }
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
        if (group) {
            store.rememberTelegramChat(id);
        }
        if (group && !message.path("has_protected_content").asBoolean(false)
                && !chat.path("has_protected_content").asBoolean(false)
                && handleTelegramReply(job, id, senderId, message)) {
            return;
        }
        var command = sender.path("is_bot").asBoolean(false) ? null : commands.parse("telegram", text(message, "text"));
        if (command != null) {
            if (group && anonymous.isEmpty() && command.name().equals("bridge")) {
                String thread = telegramThread(message);
                once(job, "group-setup", () -> telegram.menu(id, null, thread, menu.group("telegram", id)));
            } else if (text(chat, "type").equals("private") && id.equals(senderId)) {
                privateCommand(job, id, command);
            }
            return;
        }
        if (text(chat, "type").equals("private") && id.equals(senderId) && !sender.path("is_bot").asBoolean(false)) {
            showMenu(job, id, null, menu.input(job, id, text(message, "text")));
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
        deliverRoutes(job.platform(), id, routes, target -> sendTelegramContentToMax(job, target.id(), null, header, content, "forward:" + target.id(),
                new Store.Origin(target, "telegram", id, mid, thread)));
    }

    private static String telegramThread(JsonNode message) {
        return message.path("is_topic_message").asBoolean(false) ? value(message, "message_thread_id") : null;
    }

    private void maxUpdate(Store.Job job, JsonNode update) {
        switch (text(update, "update_type")) {
            case "message_created" -> maxMessage(job, update.path("message"));
            case "message_callback" -> {
                var callback = update.path("callback");
                var message = update.path("message");
                String user = value(callback.path("user"), "user_id");
                if (user != null && text(message.path("recipient"), "chat_type").equals("dialog")) {
                    menuCallback(job, user, value(message.path("body"), "mid"),
                            text(callback, "callback_id"), text(callback, "payload"));
                }
            }
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
        if (kind.equals("dialog")) {
            if (command != null) {
                privateCommand(job, user, command);
            } else if (!sender.path("is_bot").asBoolean(false)) {
                showMenu(job, user, null, menu.input(job, user, text(body, "text")));
            }
            return;
        }
        if (!kind.equals("chat") || chat == null) {
            return;
        }
        if (handleMaxReply(job, chat, user, message)) {
            return;
        }
        if (command != null) {
            if (List.of("bridge", "start").contains(command.name())) {
                once(job, "group-setup", () -> max.menu(chat, null, null, menu.group("max", chat)));
            }
            return;
        }
        var routes = matchingRoutes(job, chat, maxFilterText(message));
        var source = routes.isEmpty() ? null : access.source("max", chat);
        if (source == null) {
            return;
        }
        String title = safeTitle(first(text(source, "title"), chat));
        String header = safeTitle(displayName(sender)) + " · MAX / " + title;
        deliverRoutes("max", chat, routes, target -> {
            var origin = new Store.Origin(target, "max", chat, mid, null);
            sendMaxContentToTelegram(job, message, target.id(), null, null, header,
                    "forward:telegram:" + target.id(), origin);
        });
    }

    private boolean handleMaxReply(Store.Job job, String chat, String user, JsonNode message) {
        var link = message.path("link");
        String parent = text(link.path("message"), "mid");
        if (!text(link, "type").equals("reply") || parent.isEmpty()) {
            return false;
        }
        var destination = new Destination("max", chat);
        boolean pendingForward = forwardsInFlight.containsKey(destination);
        var origin = store.origin(destination, parent);
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

    private boolean handleTelegramReply(Store.Job job, String chat, String user, JsonNode message) {
        var reply = message.path("reply_to_message");
        String parent = value(reply, "message_id");
        if (parent == null) return false;
        var destination = new Destination("telegram", chat);
        boolean pending = forwardsInFlight.containsKey(destination);
        var origin = store.origin(destination, parent);
        if (origin == null) {
            if (pending && telegramBotId.equals(value(reply.path("from"), "id"))
                    && Store.now() - job.received() < 30) {
                throw new DeliveryQueue.Defer();
            }
            return false;
        }
        if (user != null && access.target("telegram", chat, user) != null
                && access.source(origin.platform(), origin.source()) != null) {
            var content = telegramMessage(message);
            if (content != null) {
                String header = safeTitle(displayName(message.path("from"))) + " · Telegram";
                sendTelegramContentToMax(job, origin.source(), object("type", "reply", "mid", origin.message()),
                        header, content, "return:telegram", null);
            }
        }
        return true;
    }

    private void privateCommand(Store.Job job, String user, Commands.Command command) {
        if (job.platform().equals("telegram") && command.name().equals("start")
                && (command.argument().isEmpty() || store.menu("telegram", user) == null)) {
            once(job, "telegram-navigation", () -> telegram.navigation(user));
        }
        var screen = menu.entry(job, user, command);
        if (screen != null) {
            showMenu(job, user, null, screen);
            return;
        }
        String result = commands.privateCommand(job, user, command);
        int index = 0;
        for (String part : splitText(result, 4000)) {
            once(job, "command:" + index++, () -> job.platform().equals("telegram")
                    ? telegram.send(user, part, null, null, null) : max.send(null, user, part, null, null));
        }
    }

    private void menuCallback(Store.Job job, String user, String mid, String id, String payload) {
        if (id.isEmpty() || !payload.startsWith("m:")) {
            return;
        }
        boolean current = menu.current(job, user, payload);
        once(job, "menu-acknowledged", () -> {
            try {
                String notice = current ? null : menu.staleNotice();
                return job.platform().equals("telegram") ? telegram.answer(id, notice) : max.answer(id, notice);
            } catch (ApiFailure error) {
                if (error.unavailable()) {
                    return object();
                }
                throw error;
            }
        });
        if (current) {
            showMenu(job, user, mid, menu.callback(job, user, payload));
        }
    }

    private void showMenu(Store.Job job, String user, String mid, Menu.Screen screen) {
        if (screen == null) {
            return;
        }
        once(job, "menu-delivered", () -> {
            boolean inTelegram = job.platform().equals("telegram");
            if (mid != null) {
                try {
                    return inTelegram ? telegram.menu(user, mid, null, screen) : max.menu(null, user, mid, screen);
                } catch (ApiFailure error) {
                    if (!error.unavailable() || error.status == 403) {
                        throw error;
                    }
                }
            }
            return inTelegram ? telegram.menu(user, null, null, screen) : max.menu(null, user, null, screen);
        });
    }

    private Map<Destination, List<Store.Route>> matchingRoutes(Store.Job job, String source, String text) {
        var routes = store.activeRoutes(job.platform(), source, job.received());
        Map<Destination, List<Store.Route>> targets = new LinkedHashMap<>();
        if (!routes.isEmpty()) {
            String normalized = normalize(text);
            for (var route : routes) {
                if (matches(route, normalized)) {
                    targets.computeIfAbsent(new Destination(route.targetPlatform(), route.target()), key -> new ArrayList<>()).add(route);
                }
            }
        }
        return targets;
    }

    private void deliverRoutes(String platform, String source, Map<Destination, List<Store.Route>> routes,
                               Consumer<Destination> send) {
        ApiFailure retry = null;
        Map<String, Boolean> sourceMembership = new HashMap<>();
        for (var target : routes.entrySet()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                if (access.destination(target.getKey().platform(), target.getKey().id()) != null
                        && hasEligibleOwner(platform, source, target.getKey(), target.getValue(), sourceMembership)) {
                    send.accept(target.getKey());
                }
            } catch (ApiFailure error) {
                if (error.retryable()) {
                    retry = later(retry, error);
                } else {
                    LOG.warning(() -> "Forwarding to chat " + target.getKey() + " failed (" + error.getMessage() + ")");
                }
            }
        }
        if (retry != null) {
            throw retry;
        }
    }

    private boolean hasEligibleOwner(String platform, String source, Destination target, List<Store.Route> routes,
                                     Map<String, Boolean> sourceMembership) {
        ApiFailure retry = null;
        for (var route : routes) {
            String user = route.owner();
            try {
                String targetUser = store.identity(platform, user).on(target.platform());
                if (targetUser == null || !sourceMembership.computeIfAbsent(user,
                        key -> access.isMember(platform, source, key)) || !access.isMember(target.platform(), target.id(), targetUser)) {
                    continue;
                }
                var current = store.route(platform, user, route.id());
                if (route.equals(current) && targetUser.equals(store.identity(platform, user).on(target.platform()))) {
                    return true;
                }
            } catch (ApiFailure error) {
                if (error.retryable()) {
                    retry = later(retry, error);
                } else {
                    LOG.warning(() -> "Could not check membership for chat " + target + " (" + error.getMessage() + ")");
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

    private void sendTelegramContentToMax(Store.Job job, String target, JsonNode link, String header,
                                          TelegramMessage content, String action, Store.Origin origin) {
        JsonNode prepared = content.media() == null ? object("attachment", content.attachment())
                : once(job, "prepare-upload", () -> prepareUpload(content.media()));
        // Older queue entries can carry uploads as a pair of attachment and fallback text.
        JsonNode attachment = prepared.isArray() ? prepared.path(0) : prepared.path("attachment");
        String note = prepared.isArray() ? prepared.path(1).asString("") : text(prepared, "note");
        String message = join("\n\n", header, content.rendered(), note);
        int index = 0;
        for (String part : splitText(message, 3900)) {
            JsonNode useAttachment = index == 0 && attachment.isObject() ? attachment : null;
            once(job, action + ":" + index++, () -> sendToMax(target, part, link, useAttachment), origin);
        }
    }

    private JsonNode sendToMax(String target, String part, JsonNode link, JsonNode attachment) {
        try {
            return max.send(target, null, part, link, attachment);
        } catch (ApiFailure error) {
            if (attachment == null || error.retryable() || !List.of(400, 413, 415).contains(error.status)) {
                throw error;
            }
            return max.send(target, null, part + "\n\n[MAX не принял это вложение]", link, null);
        }
    }

    private JsonNode prepareUpload(Media item) {
        try {
            if (item.length() > MEDIA_LIMIT) {
                throw new MediaFailure("вложение больше 20 МБ");
            }
            JsonNode attachment = withMediaSlot(() -> max.upload(item.kind(), item.name(), telegram.download(item.fileId())));
            return object("attachment", attachment);
        } catch (MediaFailure error) {
            return object("note", mediaNote(item.name(), error.getMessage()));
        } catch (ApiFailure error) {
            if (!error.unavailable() && !List.of(413, 415).contains(error.status)) {
                throw error;
            }
            return object("note", mediaNote(item.name(), "не удалось переслать вложение"));
        }
    }

    private void returnReply(Store.Job job, JsonNode message, Store.Origin origin) {
        if (access.source(origin.platform(), origin.source()) == null) {
            return;
        }
        String header = safeTitle(displayName(message.path("sender"))) + " · MAX";
        var body = message.path("body");
        String text = join("\n\n", header, showLinkUrls(text(body, "text"), body.path("markup")));
        if (origin.platform().equals("telegram")) {
            sendMaxContentToTelegram(job, message, origin.source(), origin.message(), origin.thread(), header, "return", null);
            return;
        }
        JsonNode link = object("type", "reply", "mid", origin.message());
        int index = 0;
        for (String part : splitText(text, 4000)) {
            once(job, "return:text:" + index++, () -> max.send(origin.source(), null, part, link, null));
        }
        index = 0;
        for (JsonNode attachment : body.path("attachments")) {
            if (!text(attachment, "type").equals("inline_keyboard")) {
                once(job, "return:media:" + index++, () -> replyMediaToMax(origin.source(), link, attachment));
            }
        }
    }

    private void sendMaxContentToTelegram(Store.Job job, JsonNode message, String chat, String reply, String thread,
                                          String header, String action, Store.Origin origin) {
        var body = message.path("body");
        var forwarded = text(message.path("link"), "type").equals("forward")
                ? message.path("link").path("message") : null;
        String content = join("\n\n", header, showLinkUrls(text(body, "text"), body.path("markup")),
                forwarded == null ? null : showLinkUrls(text(forwarded, "text"), forwarded.path("markup")));
        int index = 0;
        for (String part : splitText(content, 4000)) {
            once(job, action + ":text:" + index++, () -> telegram.send(chat, part, reply, thread, null), origin);
        }
        var attachments = new ArrayList<JsonNode>();
        body.path("attachments").forEach(attachments::add);
        if (forwarded != null) forwarded.path("attachments").forEach(attachments::add);
        index = 0;
        for (JsonNode attachment : attachments) {
            if (!text(attachment, "type").equals("inline_keyboard")) {
                once(job, action + ":media:" + index++, () -> sendMaxMediaToTelegram(chat, reply, thread, attachment), origin);
            }
        }
    }

    private JsonNode sendMaxMediaToTelegram(String chat, String reply, String thread, JsonNode attachment) {
        String kind = text(attachment, "type");
        var payload = attachment.path("payload");
        String filename = first(value(payload, "filename"), value(payload, "name"),
                MAX_FILENAMES.getOrDefault(kind, "attachment"));
        String note;
        try {
            if (kind.equals("location") && attachment.has("latitude") && attachment.has("longitude")) {
                note = "Местоположение: " + text(attachment, "latitude") + ", " + text(attachment, "longitude");
            } else if (MAX_FILENAMES.containsKey(kind)) {
                return withMediaSlot(() -> telegram.send(chat, null, reply, thread,
                        new Upload("document", filename, max.download(attachment))));
            } else {
                note = "[Вложение MAX]";
            }
        } catch (MediaFailure error) {
            note = mediaNote(filename, error.getMessage());
        } catch (ApiFailure error) {
            if (!error.unavailable() && !List.of(413, 415).contains(error.status)) {
                throw error;
            }
            note = mediaNote(filename, "не удалось переслать вложение");
        }
        return telegram.send(chat, note, reply, thread, null);
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
        return max.send(chat, null, "[Вложение MAX]", link, null);
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
            forwardsInFlight.merge(origin.destination(), 1, Integer::sum);
        }
        try {
            JsonNode result = execute.get();
            store.saveStep(job.seq(), action, result, origin);
            return result;
        } finally {
            if (origin != null) {
                forwardsInFlight.compute(origin.destination(), (chat, count) -> count == 1 ? null : count - 1);
            }
        }
    }
}
