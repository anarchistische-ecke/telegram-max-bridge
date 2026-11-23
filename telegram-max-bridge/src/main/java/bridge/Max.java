package bridge;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

import static bridge.Json.*;
import static bridge.Transport.*;

final class Max {
    private final Transport http;
    private final String base;
    private final String token;

    Max(Transport http, String base, String token) {
        this.http = http;
        this.base = base;
        this.token = token;
    }

    JsonNode get(String path) {
        return call(path, "GET", null, Map.of());
    }

    JsonNode post(String path, JsonNode body) {
        return call(path, "POST", body, Map.of());
    }

    private JsonNode call(String path, String method, JsonNode body, Map<String, String> query) {
        String operation = "MAX " + method + " /" + path.substring(1).split("/", 2)[0];
        var response = Transport.object(http.request(operation, uri(base, path, query), method, body, token,
                null, false, false));
        if (response.isEmpty() || !response.path("success").asBoolean(true)) {
            throw new ApiFailure(operation, response.isEmpty() ? 0 : 400, text(response, "code"), 0);
        }
        return response;
    }

    JsonNode chat(String chat) {
        return get("/chats/" + Long.parseLong(chat));
    }

    JsonNode botMember(String chat) {
        return get("/chats/" + Long.parseLong(chat) + "/members/me");
    }

    JsonNode members(String chat, Collection<String> users) {
        return call("/chats/" + Long.parseLong(chat) + "/members", "GET", null,
                Map.of("user_ids", String.join(",", users))).required("members");
    }

    JsonNode send(String chat, String user, String text, JsonNode link, JsonNode attachment) {
        var body = Json.object();
        if (text != null && !text.isEmpty()) {
            body.put("text", text);
        }
        if (link != null) {
            body.set("link", link);
        }
        if (attachment != null) {
            body.putArray("attachments").add(attachment);
        }
        return call("/messages", "POST", body, chat == null ? Map.of("user_id", user) : Map.of("chat_id", chat))
                .required("message").required("body").required("mid");
    }

    JsonNode menu(String chat, String user, String mid, Menu.Screen screen) {
        var body = menuBody(screen);
        if (mid != null) {
            return call("/messages", "PUT", body, Map.of("message_id", mid, "disable_link_preview", "true"));
        }
        var query = new java.util.HashMap<String, String>();
        query.put(chat == null ? "user_id" : "chat_id", chat == null ? user : chat);
        query.put("disable_link_preview", "true");
        return call("/messages", "POST", body, query);
    }

    JsonNode answer(String id, String notice, Menu.Screen screen) {
        var body = screen == null ? object("notification", notice) : object("message", menuBody(screen));
        return call("/answers", "POST", body, Map.of("callback_id", id, "disable_link_preview", "true"));
    }

    private static JsonNode menuBody(Menu.Screen screen) {
        return object("text", screen.text(), "attachments", List.of(
                object("type", "inline_keyboard", "payload", object("buttons", screen.buttons("max")))));
    }

    void commands(List<JsonNode> commands) {
        call("/me/commands", "PATCH", object("commands", commands), Map.of());
    }

    JsonNode upload(String kind, String filename, byte[] bytes) {
        var ticket = call("/uploads", "POST", null, Map.of("type", kind));
        byte[] raw = http.request("MAX media upload", mediaUri(ticket.required("url").asString()),
                "POST", null, kind.equals("image") ? token : null, new Upload("data", filename, bytes), false, true);
        JsonNode response = Transport.object(raw);
        String mediaToken = first(value(response, "token"), value(ticket, "token"));
        if (kind.equals("image")) {
            for (JsonNode photo : response.path("photos")) {
                if (!text(photo, "token").isEmpty()) {
                    mediaToken = text(photo, "token");
                    break;
                }
            }
        }
        String rawText = new String(raw, StandardCharsets.UTF_8).strip();
        if (!mediaToken.isEmpty() && (!response.isEmpty() || rawText.equals("<retval>1</retval>") || rawText.isEmpty())) {
            return object("type", kind, "payload", object("token", mediaToken));
        }
        throw new MediaFailure("MAX не принял вложение");
    }

    byte[] download(JsonNode attachment) {
        var payload = attachment.path("payload");
        String url = text(payload, "url");
        if (text(attachment, "type").equals("video") && !text(payload, "token").isEmpty()) {
            var urls = get("/videos/" + encode(text(payload, "token"))).path("urls");
            for (String resolution : List.of("mp4_480", "mp4_360", "mp4_720", "mp4_1080", "mp4_240")) {
                if (!text(urls, resolution).isEmpty()) {
                    url = text(urls, resolution);
                    break;
                }
            }
        }
        if (url.isEmpty()) {
            throw new MediaFailure("вложение недоступно для скачивания");
        }
        return http.request("MAX media download", mediaUri(url), "GET", null, null, null, true, true);
    }
}
