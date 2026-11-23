package bridge;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static bridge.Content.safeTitle;
import static bridge.Json.first;
import static bridge.Json.text;

final class Commands {
    record Command(String name, String argument) {}

    private static final Pattern COMMAND = Pattern.compile("^/([a-z_]+)(?:@([A-Za-z0-9_]+))?(?:\\s|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> NAMES = Set.of("start", "help", "bridge", "source", "link", "unlink",
            "destinations", "route", "routes", "pause", "resume", "remove");
    private static final int PAGE_SIZE = 8;
    private static final String HELP = "Откройте /start, чтобы выбрать действие в меню.";

    private final Store store;
    private final ChatAccess access;
    private final String telegramUsername;
    private final String maxUsername;

    Commands(Store store, ChatAccess access, String telegramUsername, String maxUsername) {
        this.store = store;
        this.access = access;
        this.telegramUsername = telegramUsername;
        this.maxUsername = maxUsername;
    }

    Command parse(String platform, String text) {
        var matcher = COMMAND.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String name = matcher.group(1).toLowerCase(Locale.ROOT);
        String username = matcher.group(2);
        String bot = platform.equals("telegram") ? telegramUsername : maxUsername;
        if (!NAMES.contains(name) || username != null && !bot.equalsIgnoreCase(username)) {
            return null;
        }
        return new Command(name, text.substring(matcher.end()).strip());
    }

    String privateCommand(Store.Job job, String user, Command command) {
        String platform = job.platform();
        String name = command.name();
        String arg = command.argument();
        try {
            return switch (name) {
                case "source" -> selectSource(platform, user, chatId(arg));
                case "link" -> linkAccount(job, user);
                case "unlink" -> unlink(platform, user);
                case "destinations" -> listDestinations(platform, user, page(arg));
                case "routes" -> listRoutes(platform, user, page(arg));
                case "route" -> configureRoute(job, user, arg);
                case "pause", "resume", "remove" -> changeRoute(job, user, name, arg);
                default -> HELP;
            };
        } catch (IllegalArgumentException error) {
            return error.getMessage();
        }
    }

    private String selectSource(String platform, String user, String source) {
        var info = access.source(platform, source, user);
        if (info == null) {
            return "Выберите группу, в которой вы состоите и бот назначен администратором.";
        }
        store.selectSource(platform, user, source);
        String next = targetUser(platform, user) == null ? "/link" : "/destinations";
        return "Группа выбрана: " + safeTitle(first(text(info, "title"), source)) + " (" + source + ").\nДалее откройте "
                + next + ".\n\n" + HELP;
    }

    private String linkAccount(Store.Job job, String user) {
        String token = store.issueLink(user, job.seq());
        return "Откройте MAX, чтобы связать аккаунты. Ссылка действует 10 минут.\n" + privateLink("max", "link_" + token)
                + "\nИли отправьте /link " + token + " в личном чате с ботом MAX.";
    }

    private String unlink(String platform, String user) {
        if (!platform.equals("telegram")) {
            return HELP;
        }
        store.unlink(user);
        return "Аккаунт MAX отключён. Пересылки из Telegram приостановлены.";
    }

    private String listDestinations(String platform, String user, int page) {
        String maxUser = targetUser(platform, user);
        if (maxUser == null) {
            return "Подключите аккаунт MAX через /start.";
        }
        var chats = store.maxChats(page, PAGE_SIZE);
        var visible = new ArrayList<String>();
        for (String chat : chats.subList(0, Math.min(chats.size(), PAGE_SIZE))) {
            var info = access.target(chat, maxUser);
            if (info != null) {
                String kind = text(info, "type").equals("channel") ? "канал" : "группа";
                visible.add(safeTitle(first(text(info, "title"), chat)) + " (" + kind + ")  " + chat);
            }
        }
        if (visible.isEmpty()) {
            visible.add("На этой странице нет доступных групп или каналов MAX.");
        }
        if (chats.size() > PAGE_SIZE) {
            visible.add("Следующая страница  /destinations " + (page + 2));
        }
        visible.add("Откройте /start для настройки пересылки.");
        return String.join("\n", visible);
    }

    private String listRoutes(String platform, String user, int page) {
        var routes = store.routes(platform, user, page, PAGE_SIZE);
        if (routes.isEmpty()) {
            return "На этой странице нет пересылок. Добавьте пересылку через /start.";
        }
        var lines = new ArrayList<String>();
        for (var route : routes.subList(0, Math.min(routes.size(), PAGE_SIZE))) {
            String terms = String.join(", ", route.terms());
            if (Content.size(terms) > 240) {
                terms = Content.truncate(terms, 239) + "…";
            }
            String status = route.active() ? "включена" : "на паузе";
            lines.add("#" + route.id() + "  " + route.source() + " → " + route.target() + "  " + status + "\n"
                    + Content.join(" ", Menu.filterLabel(route.mode()), terms));
        }
        if (routes.size() > PAGE_SIZE) {
            lines.add("Следующая страница  /routes " + (page + 2));
        }
        return String.join("\n", lines);
    }

    private String configureRoute(Store.Job job, String user, String arg) {
        String platform = job.platform();
        String[] parts = arg.split("(?U)\\s+", 3);
        if (parts.length < 2) {
            return "Настройте пересылку через /start или укажите чат и режим в команде /route.";
        }
        String target = chatId(parts[0]);
        String mode = parts[1].toLowerCase(Locale.ROOT);
        var terms = Content.parseFilter(mode, parts.length == 3 ? parts[2] : "");
        String source = store.selectedSource(platform, user);
        if (source == null) {
            return "Сначала выберите группу-источник через /start.";
        }
        if (platform.equals("max") && source.equals(target)) {
            return "Выберите для пересылки другой чат.";
        }
        String maxUser = targetUser(platform, user);
        if (maxUser == null) {
            return "Подключите аккаунт MAX через /start.";
        }
        if (access.source(platform, source, user) == null) {
            return "Группа-источник недоступна. Выберите доступную группу.";
        }
        var targetInfo = access.target(target, maxUser);
        if (targetInfo == null) {
            return "Выберите группу или канал MAX, где вы состоите и бот может читать и публиковать сообщения.";
        }
        store.rememberMaxChat(target);
        long id = store.setRoute(platform, user, source, target, mode, terms, job.seq());
        return "Пересылка №" + id + " включена для " + safeTitle(first(text(targetInfo, "title"), target))
                + ".";
    }

    private String changeRoute(Store.Job job, String user, String name, String arg) {
        String platform = job.platform();
        if (!arg.matches("[1-9][0-9]{0,17}")) {
            return "Укажите номер пересылки или откройте «Мои пересылки» через /start.";
        }
        var saved = store.step(job.seq(), "change-route");
        if (saved != null) {
            return saved.asString();
        }
        long id = Long.parseLong(arg);
        var route = store.route(platform, user, id);
        if (route == null) {
            return "В вашем профиле нет такой пересылки.";
        }
        if (name.equals("resume")) {
            String maxUser = targetUser(platform, user);
            if (maxUser == null || access.source(platform, route.source(), user) == null
                    || access.target(route.target(), maxUser) == null) {
                return "Восстановите доступ к чатам и подключите аккаунт MAX.";
            }
        }
        String status = switch (name) {
            case "pause" -> "приостановлена";
            case "resume" -> "включена";
            default -> "удалена";
        };
        String result = "Пересылка №" + id + " " + status + ".";
        store.changeRoute(route, name, result, job.seq());
        return result;
    }

    String targetUser(String platform, String user) {
        return platform.equals("telegram") ? store.maxUser(user) : user;
    }

    String privateLink(String platform, String payload) {
        String url = platform.equals("telegram") ? "https://t.me/" + telegramUsername : "https://max.ru/" + maxUsername;
        return url + "?start=" + payload;
    }

    private static int page(String arg) {
        if (arg.isEmpty()) {
            return 0;
        }
        if (!arg.matches("[1-9][0-9]{0,5}")) {
            throw new IllegalArgumentException("Укажите номер страницы.");
        }
        return Integer.parseInt(arg) - 1;
    }

    static String chatId(String arg) {
        try {
            if (arg.matches("-?[0-9]{1,19}")) {
                long id = Long.parseLong(arg);
                if (id != 0) {
                    return Long.toString(id);
                }
            }
        } catch (NumberFormatException ignored) {
            // IDs outside the signed 64-bit range are invalid.
        }
        throw new IllegalArgumentException("Укажите числовой ID чата.");
    }
}
