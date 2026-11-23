package bridge;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

import static bridge.Json.object;
import static bridge.Json.text;
import static bridge.Transport.*;

final class Telegram {
    private final Transport http;
    private final String base;
    private final String token;

    Telegram(Transport http, String base, String token) {
        this.http = http;
        this.base = base;
        this.token = token;
    }

    JsonNode call(String method, JsonNode body) {
        return call(method, body, null);
    }

    private JsonNode call(String method, JsonNode body, Upload upload) {
        String operation = "Telegram " + method;
        return result(operation, http.request(operation, uri(base, "/bot" + token + "/" + method, Map.of()),
                "POST", body, null, upload, false, false));
    }

    private static JsonNode result(String operation, byte[] data) {
        JsonNode response = Transport.object(data);
        if (!response.path("ok").asBoolean(false)) {
            throw new ApiFailure(operation, response.path("error_code").asInt(0), "",
                    response.path("parameters").path("retry_after").asInt(0));
        }
        return response.path("result");
    }

    JsonNode member(String chat, String user) {
        return call("getChatMember", object("chat_id", Long.parseLong(chat), "user_id", Long.parseLong(user)));
    }

    JsonNode chat(String chat) {
        return call("getChat", object("chat_id", Long.parseLong(chat)));
    }

    JsonNode send(String chat, String message, String reply, String thread, Upload file) {
        var body = object("chat_id", Long.parseLong(chat));
        if (thread != null) {
            body.put("message_thread_id", Long.parseLong(thread));
        }
        if (reply != null) {
            body.set("reply_parameters", object("message_id", Long.parseLong(reply), "allow_sending_without_reply", false));
        }
        if (file != null) {
            if (message != null && !message.isEmpty()) {
                body.put("caption", message);
            }
            return call("sendDocument", body, file).required("message_id");
        }
        body.put("text", message);
        body.set("link_preview_options", object("is_disabled", true));
        return call("sendMessage", body).required("message_id");
    }

    JsonNode menu(String chat, String mid, String thread, Menu.Screen screen) {
        var body = object("chat_id", Long.parseLong(chat), "text", screen.text(),
                "reply_markup", object("inline_keyboard", screen.buttons("telegram")),
                "link_preview_options", object("is_disabled", true));
        if (mid != null) {
            body.put("message_id", Long.parseLong(mid));
            return call("editMessageText", body);
        }
        if (thread != null) {
            body.put("message_thread_id", Long.parseLong(thread));
        }
        return call("sendMessage", body);
    }

    JsonNode answer(String id, String notice) {
        var body = object("callback_query_id", id);
        if (notice != null) {
            body.put("text", notice);
        }
        String method = "answerCallbackQuery";
        String operation = "Telegram " + method;
        return result(operation, http.request(operation, uri(base, "/bot" + token + "/" + method, Map.of()),
                "POST", body, null, null, false, false, Duration.ofSeconds(3)));
    }

    JsonNode navigation(String chat) {
        var keyboard = java.util.List.of(java.util.List.of(
                        object("text", Menu.HOME_LABEL), object("text", Menu.PROFILES_LABEL)),
                java.util.List.of(object("text", Menu.NEW_LABEL)));
        return call("sendMessage", object("chat_id", Long.parseLong(chat), "text",
                "Кнопки под полем ввода открывают меню и список профилей.",
                "reply_markup", object("keyboard", keyboard, "resize_keyboard", true, "is_persistent", true)));
    }

    byte[] download(String fileId) {
        var file = call("getFile", object("file_id", fileId));
        String path = text(file, "file_path");
        if (path.isEmpty() || file.path("file_size").asLong(0) > MEDIA_LIMIT) {
            throw new MediaFailure("вложение недоступно или превышает лимит Telegram");
        }
        path = Arrays.stream(path.split("/")).map(Transport::encode).collect(Collectors.joining("/"));
        return http.request("Telegram file download", uri(base, "/file/bot" + token + "/" + path, Map.of()),
                "GET", null, null, null, true, false);
    }
}
