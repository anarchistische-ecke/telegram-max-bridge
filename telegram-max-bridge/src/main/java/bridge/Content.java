package bridge;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.Normalizer2;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

import static bridge.Json.*;

final class Content {
    record TelegramMessage(String rendered, String filterText, Media media, JsonNode attachment) {}
    record Media(String kind, String name, String fileId, long length) {}
    private record MediaType(String field, String kind, String name) {}
    private record Link(int end, String url) {}

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\x1c-\\x1f]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final DateTimeFormatter MOSCOW_TIME = DateTimeFormatter
            .ofPattern("dd.MM.uuuu HH:mm:ss 'МСК'", Locale.ROOT).withZone(ZoneId.of("Europe/Moscow"));
    private static final Set<String> MODES = Set.of("all", "include", "exclude");
    private static final List<MediaType> TELEGRAM_MEDIA = List.of(
            new MediaType("video", "video", "video.mp4"),
            new MediaType("video_note", "video", "video-note.mp4"),
            new MediaType("animation", "video", "animation.mp4"),
            new MediaType("audio", "audio", "audio.mp3"),
            new MediaType("voice", "audio", "voice.ogg"),
            new MediaType("document", "file", "document"),
            new MediaType("sticker", "file", "sticker")
    );
    private static final List<String> TELEGRAM_SPECIAL = List.of(
            "paid_media", "story", "game", "checklist", "invoice", "giveaway", "giveaway_winners");
    static final Map<String, String> MAX_FILENAMES = Map.of(
            "image", "photo.jpg", "video", "video.mp4", "audio", "audio.mp3", "file", "document");

    private Content() {}

    static String normalize(String text) {
        return WHITESPACE.matcher(UCharacter.foldCase(Normalizer2.getNFKCInstance().normalize(text), true))
                .replaceAll(" ").strip();
    }

    static List<String> parseFilter(String mode, String raw) {
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("Выберите режим пересылки в меню.");
        }
        if (mode.equals("all")) {
            if (!normalize(raw).isEmpty()) {
                throw new IllegalArgumentException("В режиме «Все сообщения» слова указывать не нужно.");
            }
            return List.of();
        }
        List<String> terms = Arrays.stream(raw.split(",")).map(Content::normalize)
                .filter(term -> !term.isEmpty()).distinct().toList();
        if (terms.isEmpty() || terms.size() > 30 || terms.stream().anyMatch(term -> size(term) > 100)) {
            throw new IllegalArgumentException("Введите от 1 до 30 слов или фраз через запятую. До 100 символов в каждом.");
        }
        return terms;
    }

    static boolean matches(Store.Route route, String normalized) {
        if (route.mode().equals("all")) {
            return true;
        }
        boolean found = route.terms().stream().anyMatch(term -> containsWord(normalized, term));
        return route.mode().equals("include") == found;
    }

    private static boolean containsWord(String text, String term) {
        if (term.isEmpty()) {
            return false;
        }
        for (int start = text.indexOf(term); start >= 0; start = text.indexOf(term, start + 1)) {
            int end = start + term.length();
            if ((start == 0 || !wordCharacter(text.codePointBefore(start)))
                    && (end == text.length() || !wordCharacter(text.codePointAt(end)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean wordCharacter(int character) {
        int type = Character.getType(character);
        return character == '_' || Character.isLetterOrDigit(character)
                || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER;
    }

    static String displayName(JsonNode user) {
        return first(join(" ", text(user, "first_name"), text(user, "last_name")),
                value(user, "title"), value(user, "name"), "Неизвестный отправитель");
    }

    static String telegramAttribution(JsonNode message, JsonNode author) {
        long seconds = message.path("date").asLong(0);
        return attribution(displayName(author), text(author, "username"),
                seconds > 0 ? Instant.ofEpochSecond(seconds) : null);
    }

    static String maxHeader(JsonNode message, String author, JsonNode chat) {
        long milliseconds = message.path("timestamp").asLong(0);
        String title = first(safeTitle(text(chat, "title")), text(message.path("recipient"), "chat_id"));
        return attribution(author, text(message.path("sender"), "username"),
                milliseconds > 0 ? Instant.ofEpochMilli(milliseconds) : null) + " · MAX / " + title;
    }

    private static String attribution(String author, String username, Instant time) {
        String handle = safeTitle(username);
        String nickname = handle.isEmpty() ? "ник не указан" : "@" + handle;
        String published = time == null ? "время не указано" : MOSCOW_TIME.format(time);
        return safeTitle(author) + " (" + nickname + ", " + published + ")";
    }

    static String safeTitle(String value) {
        return truncate(WHITESPACE.matcher(value).replaceAll(" ").strip(), 120);
    }

    static String truncate(String value, int limit) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(limit, size(value))));
    }

    static int size(String value) {
        return value.codePointCount(0, value.length());
    }

    static String join(String separator, String... parts) {
        return Arrays.stream(parts).filter(part -> part != null && !part.isEmpty()).collect(Collectors.joining(separator));
    }

    static List<String> splitText(String value, int limit) {
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < value.length();) {
            int end = Math.min(start + limit, value.length());
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) {
                end--;
            }
            parts.add(value.substring(start, end));
            start = end;
        }
        return parts;
    }

    static String showLinkUrls(String text, JsonNode entities) {
        List<Link> links = new ArrayList<>();
        for (JsonNode entity : entities) {
            String type = text(entity, "type");
            String url = text(entity, "url");
            if ((type.equals("link") || type.equals("text_link")) && !url.isEmpty()) {
                int offset = entity.path("offset").asInt(entity.path("from").asInt(-1));
                int length = entity.path("length").asInt(-1);
                long end = (long) offset + length;
                if (offset >= 0 && length > 0 && end <= text.length()
                        && (end == text.length() || !Character.isLowSurrogate(text.charAt((int) end)))) {
                    links.add(new Link((int) end, url));
                }
            }
        }
        links.sort(Comparator.comparingInt(Link::end).reversed());
        StringBuilder result = new StringBuilder(text);
        for (Link link : links) {
            result.insert(link.end(), " (" + link.url() + ")");
        }
        return result.toString();
    }

    static TelegramMessage telegramMessage(JsonNode message) {
        String text = first(value(message, "text"), value(message, "caption"));
        JsonNode entities = message.path("entities");
        String rendered = showLinkUrls(text, entities.isEmpty() ? message.path("caption_entities") : entities);
        JsonNode photos = message.path("photo");
        if (photos.isArray() && !photos.isEmpty()) {
            return telegramMedia(rendered, text, "image", "photo.jpg", photos.get(photos.size() - 1));
        }
        for (MediaType type : TELEGRAM_MEDIA) {
            var item = message.path(type.field());
            if (item.isObject()) {
                String name = type.name();
                if (type.field().equals("sticker")) {
                    name += item.path("is_video").asBoolean(false) ? ".webm"
                            : item.path("is_animated").asBoolean(false) ? ".tgs" : ".webp";
                }
                return telegramMedia(rendered, text, type.kind(), name, item);
            }
        }
        var venue = message.path("venue");
        var point = message.path("location");
        if (point.isObject() || venue.isObject()) {
            if (!point.isObject()) {
                point = venue.path("location");
            }
            String note = join("\n", text(venue, "title"), text(venue, "address"));
            return new TelegramMessage(first(note, "Местоположение"), note, null,
                    object("type", "location", "latitude", point.path("latitude"), "longitude", point.path("longitude")));
        }
        var poll = message.path("poll");
        if (poll.isObject()) {
            List<String> lines = new ArrayList<>();
            lines.add(text(poll, "question"));
            poll.path("options").forEach(option -> lines.add("• " + text(option, "text")));
            String note = String.join("\n", lines);
            return new TelegramMessage(note, note, null, null);
        }
        var contact = message.path("contact");
        if (contact.isObject()) {
            String name = displayName(contact);
            return new TelegramMessage(join("\n", name, text(contact, "phone_number")), name, null, null);
        }
        var dice = message.path("dice");
        if (dice.isObject()) {
            return new TelegramMessage(join(" ", text(dice, "emoji"), text(dice, "value")), "", null, null);
        }
        for (String key : TELEGRAM_SPECIAL) {
            if (message.has(key)) {
                var item = message.path(key);
                List<String> lines = new ArrayList<>();
                lines.add(text(item, "title"));
                if (key.equals("checklist")) {
                    item.path("tasks").forEach(task -> lines.add(text(task, "text")));
                }
                String note = String.join("\n", lines);
                return new TelegramMessage(join("\n", first(rendered, note), "[Telegram " + key.replace('_', ' ') + "]"),
                        first(text, note), null, null);
            }
        }
        return rendered.isEmpty() ? null : new TelegramMessage(rendered, text, null, null);
    }

    private static TelegramMessage telegramMedia(String rendered, String text, String kind, String name, JsonNode item) {
        return new TelegramMessage(rendered, text,
                new Media(kind, first(value(item, "file_name"), name), text(item, "file_id"),
                        item.path("file_size").asLong(0)), null);
    }

    static String maxFilterText(JsonNode message) {
        var body = message.path("body");
        var link = message.path("link");
        String forwarded = text(link, "type").equals("forward") ? text(link.path("message"), "text") : "";
        return join("\n", text(body, "text"), forwarded);
    }

    static JsonNode reusableMaxAttachment(JsonNode attachment) {
        String kind = text(attachment, "type");
        String token = text(attachment.path("payload"), "token");
        if (MAX_FILENAMES.containsKey(kind) && !token.isEmpty()) {
            return object("type", kind, "payload", object("token", token));
        }
        if (kind.equals("location") && attachment.has("latitude") && attachment.has("longitude")) {
            return object("type", "location", "latitude", attachment.path("latitude"), "longitude", attachment.path("longitude"));
        }
        return null;
    }
}
