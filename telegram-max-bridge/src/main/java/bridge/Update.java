package bridge;

import java.util.Set;
import tools.jackson.databind.JsonNode;

import static bridge.Json.text;
import static bridge.Json.value;

record Update(String platform, String key, String lane, JsonNode data) {
    static final Set<String> TELEGRAM_TYPES = Set.of("message", "callback_query", "my_chat_member");
    static final Set<String> MAX_TYPES = Set.of(
            "message_created", "comment_created", "message_callback", "bot_started", "bot_added", "bot_removed",
            "bot_admin_permissions_changed");

    static Update from(String platform, JsonNode data) {
        if (platform.equals("telegram")) {
            var id = data.path("update_id");
            if (!id.isIntegralNumber()) {
                return null;
            }
            String chat = Json.first(value(data.path("callback_query").path("from"), "id"),
                    value(data.path("message").path("chat"), "id"),
                    value(data.path("my_chat_member").path("chat"), "id"), "unknown");
            return new Update(platform, id.asString(), "telegram:" + chat, data);
        }
        String type = text(data, "update_type");
        if (!MAX_TYPES.contains(type)) {
            return null;
        }
        if (type.equals("message_callback")) {
            var callback = data.path("callback");
            String id = text(callback, "callback_id");
            if (id.isEmpty()) {
                return null;
            }
            return new Update(platform, type + ":" + id,
                    "max:user:" + value(callback.path("user"), "user_id"), data);
        }
        if (type.equals("comment_created")) {
            var message = data.path("message");
            var recipient = message.path("recipient");
            String chat = text(recipient, "chat_id");
            String mid = text(message.path("body"), "mid");
            if (chat.isEmpty() || mid.isEmpty() || text(recipient, "post_id").isEmpty()) {
                return null;
            }
            return new Update(platform, type + ":" + chat + ":" + mid, "max:chat:" + chat, data);
        }
        if (type.equals("message_created")) {
            var message = data.path("message");
            if (!message.isObject()) {
                return null;
            }
            var recipient = message.path("recipient");
            String kind = value(recipient, "chat_type");
            boolean direct = "dialog".equals(kind);
            String chat = direct ? value(message.path("sender"), "user_id") : value(recipient, "chat_id");
            String lane = direct ? "max:user:" + chat : "max:chat:" + chat;
            String mid = value(message.path("body"), "mid");
            if (mid != null && !mid.isEmpty()) {
                String key = type + ":" + (kind == null ? "None" : kind) + ":" + (chat == null ? "None" : chat)
                        + ":" + mid;
                return new Update(platform, key, lane, data);
            }
            return new Update(platform, digest(type, data), lane, data);
        }
        String lane = type.equals("bot_started")
                ? "max:user:" + value(data.path("user"), "user_id") : "max:chat:" + value(data, "chat_id");
        return new Update(platform, digest(type, data), lane, data);
    }

    private static String digest(String type, JsonNode data) {
        return type + ":" + Json.sha256(Json.canonical(data));
    }
}
