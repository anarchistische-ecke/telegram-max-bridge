package bridge;

import java.util.List;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

import static bridge.Json.text;

final class ChatAccess {
    private final Telegram telegram;
    private final Max max;
    private final String telegramBotId;

    ChatAccess(Telegram telegram, Max max, String telegramBotId) {
        this.telegram = telegram;
        this.max = max;
        this.telegramBotId = telegramBotId;
    }

    JsonNode source(String platform, String chat, String user) {
        JsonNode info = source(platform, chat);
        return info != null && isMember(platform, chat, user) ? info : null;
    }

    JsonNode source(String platform, String chat) {
        if (platform.equals("max")) {
            return maxChat(chat, false);
        }
        var bot = available(() -> telegram.member(chat, telegramBotId));
        if (bot == null || !text(bot, "status").equals("administrator")) {
            return null;
        }
        var info = available(() -> telegram.chat(chat));
        if (info == null || !List.of("group", "supergroup").contains(text(info, "type"))
                || info.path("has_protected_content").asBoolean(false)) {
            return null;
        }
        return info;
    }

    JsonNode target(String chat, String maxUser) {
        return target("max", chat, maxUser);
    }

    JsonNode target(String platform, String chat, String user) {
        if (user == null) return null;
        JsonNode info = destination(platform, chat);
        return info != null && isMember(platform, chat, user) ? info : null;
    }

    JsonNode destination(String platform, String chat) {
        if (platform.equals("max")) return maxChat(chat, true);
        var bot = available(() -> telegram.member(chat, telegramBotId));
        if (bot == null || !text(bot, "status").equals("administrator")) return null;
        var info = available(() -> telegram.chat(chat));
        if (info == null) return null;
        String type = text(info, "type");
        return List.of("group", "supergroup").contains(type)
                || type.equals("channel") && bot.path("can_post_messages").asBoolean(false) ? info : null;
    }

    JsonNode visibleChat(String platform, String chat, String user, boolean source) {
        if (user == null || !isMember(platform, chat, user)) {
            return null;
        }
        return source ? source(platform, chat) : destination(platform, chat);
    }

    private JsonNode maxChat(String chat, boolean publishing) {
        var info = available(() -> max.chat(chat));
        if (info == null || !text(info, "status").equals("active")) {
            return null;
        }
        String type = text(info, "type");
        if (!List.of("chat", "channel").contains(type)) {
            return null;
        }
        var bot = available(() -> max.botMember(chat));
        if (bot == null || !bot.path("is_admin").asBoolean(false)) {
            return null;
        }
        var rights = Json.strings(bot.path("permissions"));
        if (!rights.contains("read_all_messages")
                || publishing && type.equals("channel") && !rights.contains("write")
                    && !rights.contains("post_edit_delete_message")) {
            return null;
        }
        return info;
    }

    boolean isMember(String platform, String chat, String user) {
        if (platform.equals("max")) {
            var members = available(() -> max.members(chat, List.of(user)));
            if (members != null) {
                for (var member : members) {
                    if (user.equals(Json.value(member, "user_id"))) {
                        return true;
                    }
                }
            }
            return false;
        }
        var member = available(() -> telegram.member(chat, user));
        if (member == null) {
            return false;
        }
        String status = text(member, "status");
        return List.of("creator", "administrator", "member").contains(status)
                || status.equals("restricted") && member.path("is_member").asBoolean(false);
    }

    private static JsonNode available(Supplier<JsonNode> request) {
        try {
            return request.get();
        } catch (Transport.ApiFailure error) {
            if (error.unavailable()) {
                return null;
            }
            throw error;
        }
    }
}
