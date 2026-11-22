package bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class Json {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {}

    static ObjectNode object(Object... fields) {
        var result = MAPPER.createObjectNode();
        for (int index = 0; index < fields.length; index += 2) {
            result.set((String) fields[index], MAPPER.valueToTree(fields[index + 1]));
        }
        return result;
    }

    static String value(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    static String text(JsonNode node, String field) {
        return node.path(field).asString("");
    }

    static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    static List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        node.forEach(item -> result.add(item.asString()));
        return result;
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    // Match the previous webhook hashes for lifecycle events already recorded by the Python service.
    static String canonical(JsonNode node) {
        if (node.isObject()) {
            List<String> keys = new ArrayList<>(node.propertyNames());
            Collections.sort(keys);
            List<String> fields = new ArrayList<>();
            for (String key : keys) {
                fields.add(asciiString(key) + ":" + canonical(node.path(key)));
            }
            return "{" + String.join(",", fields) + "}";
        }
        if (node.isArray()) {
            List<String> fields = new ArrayList<>();
            node.forEach(item -> fields.add(canonical(item)));
            return "[" + String.join(",", fields) + "]";
        }
        return node.isString() ? asciiString(node.asString()) : node.toString();
    }

    private static String asciiString(String value) {
        StringBuilder ascii = new StringBuilder(value.length() + 2).append('"');
        for (int offset = 0; offset < value.length(); offset++) {
            char character = value.charAt(offset);
            switch (character) {
                case '"' -> ascii.append("\\\"");
                case '\\' -> ascii.append("\\\\");
                case '\b' -> ascii.append("\\b");
                case '\f' -> ascii.append("\\f");
                case '\n' -> ascii.append("\\n");
                case '\r' -> ascii.append("\\r");
                case '\t' -> ascii.append("\\t");
                default -> {
                    if (character < 32 || character >= 127) {
                        ascii.append("\\u").append(HexFormat.of().toHexDigits(character));
                    } else {
                        ascii.append(character);
                    }
                }
            }
        }
        return ascii.append('"').toString();
    }
}
