package bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

import static bridge.Content.safeTitle;
import static bridge.Content.truncate;
import static bridge.Json.*;

final class Menu {
    record Button(String label, String action, String url) {}
    record Screen(String text, String revision, List<List<Button>> rows) {
        JsonNode buttons(String platform) {
            var keyboard = MAPPER.createArrayNode();
            for (var row : rows) {
                var buttons = keyboard.addArray();
                for (var button : row) {
                    if (button.url() != null) {
                        var json = object("text", button.label(), "url", button.url());
                        if (platform.equals("max")) {
                            json.put("type", "link");
                        }
                        buttons.add(json);
                    } else {
                        String payload = "m:" + revision + ":" + button.action();
                        buttons.add(platform.equals("max")
                                ? object("type", "callback", "text", button.label(), "payload", payload)
                                : object("text", button.label(), "callback_data", payload));
                    }
                }
            }
            return keyboard;
        }
    }

    record Draft(String source, String target, String mode, List<String> words, long route, String editing) {
        static Draft empty() { return new Draft(null, null, null, List.of(), 0, ""); }
        static Draft of(Store.Route route) {
            return new Draft(route.source(), route.target(), route.mode(), route.terms(), route.id(), "");
        }
        Draft source(String id) { return new Draft(id, target, mode, words, route, editing); }
        Draft target(String id) { return new Draft(source, id, mode, words, route, editing); }
        Draft filter(String mode, List<String> words) { return new Draft(source, target, mode, words, route, editing); }
        Draft edit(String field) { return new Draft(source, target, mode, words, route, field); }
    }

    record State(String revision, String view, Draft draft, List<String> actions) {
        boolean accepts(String payload) {
            String prefix = "m:" + revision + ":";
            return payload.startsWith(prefix) && actions.contains(payload.substring(prefix.length()));
        }
    }
    private static final int PAGE_SIZE = 5;
    private static final int CHAT_PAGE_SIZE = 10;
    private static final Button HOME = button("Главное меню", "home");
    private static final Button ROUTES = button("Мои пересылки", "routes");
    private static final String STALE = "Это меню устарело. Откройте /start.";
    static final String LINKED = "Аккаунты связаны. Вернитесь в Telegram, чтобы продолжить.";
    private final Store store;
    private final ChatAccess access;
    private final Commands commands;

    Menu(Store store, ChatAccess access, Commands commands) {
        this.store = store;
        this.access = access;
        this.commands = commands;
    }

    Screen group(String platform, String chat) {
        return new Screen("Настройте пересылку из этой группы в личном чате с ботом.", "", List.of(
                List.of(link("Настроить пересылку", commands.privateLink(platform, "src_" + chat)))));
    }

    Screen entry(Store.Job job, String owner, Commands.Command command) {
        String name = command.name();
        String arg = command.argument();
        if (!List.of("start", "help", "bridge").contains(name) && !(job.platform().equals("max") && name.equals("link"))) {
            return null;
        }
        var session = new Session(job, owner);
        if (session.previous != null) {
            return session.previous;
        }
        if (name.equals("help")) {
            return session.help();
        }
        if (arg.startsWith("src_") && name.equals("start")) {
            try {
                return session.pickSource(Draft.empty(), Commands.chatId(arg.substring(4)));
            } catch (IllegalArgumentException error) {
                return session.home("Откройте настройку из нужной группы.");
            }
        }
        if (job.platform().equals("max") && (arg.startsWith("link_") && name.equals("start") || name.equals("link"))) {
            String code = name.equals("link") ? arg : arg.substring(5);
            String result = code.matches("[A-Za-z0-9_-]{32}") ? store.redeemLink(code, owner, job.seq())
                    : "Откройте ссылку «Подключить MAX» в боте Telegram.";
            if (LINKED.equals(result)) {
                return session.screen("linked", Draft.empty(), result,
                        link("Продолжить в Telegram", commands.privateLink("telegram", "resume")), HOME);
            }
            return session.home(result);
        }
        if (job.platform().equals("telegram") && name.equals("start") && arg.equals("resume")
                && session.state != null && session.state.view().equals("link")) {
            return session.continueLink(session.state.draft());
        }
        return session.home("");
    }

    boolean current(Store.Job job, String owner, String payload) {
        var saved = store.step(job.seq(), "menu-screen");
        if (saved != null) {
            return true;
        }
        var state = store.menu(job.platform(), owner);
        return state != null && state.accepts(payload);
    }

    String staleNotice() { return STALE; }

    Screen callback(Store.Job job, String owner, String payload) {
        var session = new Session(job, owner);
        if (session.previous != null) {
            return session.previous;
        }
        if (session.state == null || !session.state.accepts(payload)) {
            return null;
        }
        String[] parts = payload.split(":", 4);
        try {
            return session.action(parts[2], parts.length == 4 ? parts[3] : "");
        } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
            return session.home(STALE);
        }
    }

    Screen input(Store.Job job, String owner, String input) {
        var session = new Session(job, owner);
        if (session.previous != null) {
            return session.previous;
        }
        if (session.state == null) {
            return session.home("");
        }
        if (session.state.view().equals("words")) {
            var draft = session.state.draft();
            try {
                return session.review(draft.filter(draft.mode(), Content.parseFilter(draft.mode(), input.replace('\n', ','))));
            } catch (IllegalArgumentException error) {
                return session.words(draft, "Введите от 1 до 30 слов или фраз через запятую. До 100 символов в каждом.");
            }
        }
        return new Screen("Продолжите с помощью кнопок в меню.", "", List.of(
                List.of(link("Открыть главное меню", commands.privateLink(job.platform(), "menu")))));
    }

    private final class Session {
        final Store.Job job;
        final String platform;
        final String owner;
        final State state;
        final Screen previous;

        Session(Store.Job job, String owner) {
            this.job = job;
            this.platform = job.platform();
            this.owner = owner;
            this.state = store.menu(platform, owner);
            var saved = store.step(job.seq(), "menu-screen");
            previous = saved == null ? null : MAPPER.treeToValue(saved, Screen.class);
        }

        Screen action(String action, String arg) {
            Draft draft = state.draft();
            return switch (action) {
                case "home" -> home("");
                case "help" -> help();
                case "new" -> choices(Draft.empty(), true, 0);
                case "sources" -> choices(draft, true, page(arg));
                case "targets" -> targets(draft, page(arg));
                case "source" -> pickSource(draft, Commands.chatId(arg));
                case "target" -> pickTarget(draft, Commands.chatId(arg));
                case "filters" -> filters(draft);
                case "mode" -> switch (arg) {
                    case "all" -> review(draft.filter("all", List.of()));
                    case "include", "exclude" -> words(draft.filter(arg, draft.words()), "");
                    default -> filters(draft);
                };
                case "keepWords" -> review(draft);
                case "save" -> save(draft);
                case "routes" -> routes(page(arg), "");
                case "route" -> detail(Long.parseLong(arg), "");
                case "editSource" -> choices(draft.edit("source"), true, 0);
                case "editTarget" -> targets(draft.edit("target"), 0);
                case "editFilter" -> filters(draft.edit("filter"));
                case "pause", "resume" -> change(draft, action);
                case "remove" -> screen("confirm-delete", draft, "Удалить эту пересылку?",
                        button("Удалить", "delete"), back(draft));
                case "delete" -> change(draft, "remove");
                case "account" -> account();
                case "linkReady" -> continueLink(draft);
                case "unlink" -> screen("confirm-unlink", draft,
                        "Отключить MAX? Пересылки из Telegram будут приостановлены.",
                        button("Отключить MAX", "disconnect"), HOME);
                case "disconnect" -> {
                    store.unlink(owner);
                    yield home("Аккаунт MAX отключён.");
                }
                default -> home(STALE);
            };
        }

        Screen home(String notice) {
            var buttons = new ArrayList<Button>(List.of(button("Настроить пересылку", "new"),
                    ROUTES, button("Помощь", "help")));
            if (platform.equals("telegram")) {
                buttons.add(button("Аккаунт MAX", "account"));
            }
            return screen("home", Draft.empty(), Content.join("\n\n", notice,
                    "Пересылка сообщений в MAX\nВыберите, что хотите сделать."), buttons);
        }

        Screen help() {
            return screen("help", Draft.empty(), """
                    Добавьте бота администратором в группу-источник и в группу или канал MAX, куда нужна пересылка.

                    В главном меню нажмите «Настроить пересылку». Выберите источник, место назначения и фильтр. Для пересылки из Telegram бот предложит подключить аккаунт MAX.

                    Если группы ещё нет в списке, напишите /bridge в группе-источнике или дождитесь нового сообщения. Для канала MAX проверьте права бота на чтение и публикацию.

                    Чтобы ответить в исходную группу, нажмите «Ответить» на пересланном сообщении в группе MAX.
                    """.strip(), button("Настроить пересылку", "new"), HOME);
        }

        Screen choices(Draft draft, boolean sources, int page) {
            List<String> ids = sources && platform.equals("telegram")
                    ? store.telegramSources(owner, page, CHAT_PAGE_SIZE) : store.maxChats(page, CHAT_PAGE_SIZE);
            String memberPlatform = sources ? platform : "max";
            String member = sources ? owner : commands.targetUser(platform, owner);
            var buttons = new ArrayList<Button>();
            for (String id : ids.subList(0, Math.min(CHAT_PAGE_SIZE, ids.size()))) {
                if (platform.equals("max") && id.equals(sources ? draft.target() : draft.source())) {
                    continue;
                }
                var info = access.visibleChat(memberPlatform, id, member, sources);
                if (info != null) {
                    String label = title(info);
                    if (!sources && text(info, "type").equals("channel")) {
                        label += " · канал";
                    }
                    buttons.add(button(label, (sources ? "source:" : "target:") + id));
                }
            }
            String title = sources ? "Откуда пересылать сообщения?" : "Куда пересылать в MAX?";
            if (buttons.isEmpty()) {
                title += sources
                        ? "\nНа этой странице нет доступных групп. Добавьте бота администратором в нужную группу. В группе можно открыть настройку через /bridge."
                        : "\nНа этой странице нет доступных чатов. Добавьте бота администратором в группу или канал MAX и проверьте, что вы состоите в чате.";
            }
            String action = sources ? "sources:" : "targets:";
            if (page > 0) buttons.add(button("Предыдущая страница", action + (page - 1)));
            if (ids.size() > CHAT_PAGE_SIZE) buttons.add(button("Следующая страница", action + (page + 1)));
            buttons.add(button("Обновить список", action + page));
            buttons.add(draft.route() > 0 ? back(draft) : sources ? HOME : button("Назад к группам", "sources:0"));
            return screen(sources ? "sources" : "targets", draft, title, buttons);
        }

        Screen pickSource(Draft draft, String id) {
            if (access.source(platform, id, owner) == null || platform.equals("max") && id.equals(draft.target())) {
                return screen("unavailable", draft, "Эта группа недоступна. Проверьте, что вы состоите в ней, а бот может читать сообщения.",
                        button("Выбрать группу", "sources:0"), back(draft));
            }
            store.selectSource(platform, owner, id);
            var selected = draft.source(id);
            return draft.editing().equals("source") ? review(selected) : targets(selected, 0);
        }

        Screen targets(Draft draft, int page) {
            return commands.targetUser(platform, owner) == null ? linkMax(draft) : choices(draft, false, page);
        }

        Screen pickTarget(Draft draft, String id) {
            String maxUser = commands.targetUser(platform, owner);
            if (maxUser == null) return linkMax(draft);
            if (access.target(id, maxUser) == null || platform.equals("max") && id.equals(draft.source())) {
                return screen("unavailable", draft, "Этот чат MAX недоступен. Проверьте своё участие и права бота.",
                        button("Выбрать другой чат", "targets:0"), back(draft));
            }
            var selected = draft.target(id);
            return draft.editing().equals("target") ? review(selected) : filters(selected);
        }

        Screen filters(Draft draft) {
            return screen("filters", draft, "Какие сообщения пересылать?",
                    button("Все сообщения", "mode:all"),
                    button("Только с нужными словами", "mode:include"),
                    button("Все, кроме сообщений со стоп-словами", "mode:exclude"),
                    draft.route() > 0 ? back(draft) : button("Назад к чатам MAX", "targets:0"));
        }

        Screen words(Draft draft, String notice) {
            String prompt = draft.mode().equals("include")
                    ? "Напишите слова или фразы, которые хотите находить."
                    : "Напишите стоп-слова или фразы. Сообщения с ними будут пропускаться.";
            var buttons = new ArrayList<Button>();
            if (!draft.words().isEmpty()) {
                prompt += "\n\nТекущие слова: " + wordList(draft.words());
                buttons.add(button("Оставить текущие слова", "keepWords"));
            }
            buttons.add(button("Назад к фильтрам", "filters"));
            buttons.add(back(draft));
            return screen("words", draft, Content.join("\n\n", notice, prompt,
                            "Разделяйте их запятыми. Например, заявка, срочный ремонт"),
                    buttons);
        }

        Screen review(Draft draft) {
            return screen("review", draft, "Проверьте настройки\n\n" + summary(draft),
                    button(draft.route() > 0 ? "Сохранить" : "Запустить пересылку", "save"),
                    button("Назад", switch (draft.editing()) {
                        case "source" -> "sources:0";
                        case "target" -> "targets:0";
                        default -> draft.mode().equals("all") ? "filters" : "mode:" + draft.mode();
                    }), back(draft));
        }

        Screen save(Draft draft) {
            var saved = store.step(job.seq(), "set-route");
            if (saved != null) {
                return detail(saved.asLong(), "Пересылка настроена.");
            }
            if (store.step(job.seq(), "menu-edit") != null) {
                return detail(draft.route(), "Настройки сохранены.");
            }
            String maxUser = commands.targetUser(platform, owner);
            if (maxUser == null) return linkMax(draft);
            if (access.source(platform, draft.source(), owner) == null || access.target(draft.target(), maxUser) == null) {
                return screen("unavailable", draft, "Не удалось сохранить пересылку. Проверьте участие в чатах и права бота.",
                        button("Проверить снова", "save"), back(draft));
            }
            var duplicate = store.routeFor(platform, owner, draft.source(), draft.target());
            if (duplicate != null && duplicate.id() != draft.route()) {
                return detail(duplicate.id(), "Для этих чатов уже есть пересылка. Её можно изменить здесь.");
            }
            if (draft.route() == 0) {
                long id = store.setRoute(platform, owner, draft.source(), draft.target(), draft.mode(), draft.words(), job.seq());
                return detail(id, "Пересылка настроена.");
            }
            var existing = store.route(platform, owner, draft.route());
            if (existing == null) return routes(0, "Пересылка уже удалена.");
            store.editRoute(existing, draft.source(), draft.target(), draft.mode(), draft.words(), job.seq());
            return detail(existing.id(), "Настройки сохранены.");
        }

        Screen routes(int page, String notice) {
            var routes = store.routes(platform, owner, page, PAGE_SIZE);
            var buttons = new ArrayList<Button>();
            for (var route : routes.subList(0, Math.min(routes.size(), PAGE_SIZE))) {
                String from = truncate(chatLabel(platform, route.source(), false), 24);
                String to = truncate(chatLabel("max", route.target(), true), 24);
                buttons.add(button((route.active() ? "" : "⏸ ") + from + " → " + to, "route:" + route.id()));
            }
            if (page > 0) buttons.add(button("Предыдущая страница", "routes:" + (page - 1)));
            if (routes.size() > PAGE_SIZE) buttons.add(button("Следующая страница", "routes:" + (page + 1)));
            buttons.add(button("Добавить пересылку", "new"));
            buttons.add(HOME);
            String title = routes.isEmpty() && page == 0 ? "У вас пока нет пересылок." : "Мои пересылки";
            return screen("routes", Draft.empty(), Content.join("\n\n", notice, title), buttons);
        }

        Screen detail(long id, String notice) {
            var route = store.route(platform, owner, id);
            if (route == null) return routes(0, "Эта пересылка уже удалена.");
            var draft = Draft.of(route);
            String state = route.active() ? "Пересылка включена" : "Пересылка на паузе";
            return screen("detail", draft, Content.join("\n\n", notice, state, summary(draft)),
                    button("Изменить фильтр", "editFilter"), button("Изменить источник", "editSource"),
                    button("Изменить чат MAX", "editTarget"),
                    route.active() ? button("Приостановить", "pause") : button("Возобновить", "resume"),
                    button("Удалить пересылку", "remove"), ROUTES, HOME);
        }

        Screen change(Draft draft, String action) {
            if (store.step(job.seq(), "change-route") == null) {
                var route = store.route(platform, owner, draft.route());
                if (route == null) return routes(0, "Пересылка уже удалена.");
                if (action.equals("resume")) {
                    String maxUser = commands.targetUser(platform, owner);
                    if (maxUser == null || access.source(platform, route.source(), owner) == null
                            || access.target(route.target(), maxUser) == null) {
                        return detail(route.id(), "Восстановите доступ к чатам, чтобы возобновить пересылку.");
                    }
                }
                store.changeRoute(route, action, action, job.seq());
            }
            return action.equals("remove") ? routes(0, "Пересылка удалена.") : detail(draft.route(), "");
        }

        Screen account() {
            return commands.targetUser(platform, owner) == null ? linkMax(Draft.empty())
                    : screen("account", Draft.empty(), "Аккаунт MAX подключён.",
                    button("Отключить аккаунт", "unlink"), HOME);
        }

        Screen linkMax(Draft draft) {
            if (!platform.equals("telegram")) return home("");
            String token = store.issueLink(owner, job.seq());
            return screen("link", draft, """
                    Подключите аккаунт MAX один раз, чтобы бот показал доступные вам группы и каналы.

                    Нажмите «Открыть MAX», затем «Продолжить в Telegram».
                    """.strip(), link("Открыть MAX", commands.privateLink("max", "link_" + token)),
                    button("Проверить подключение", "linkReady"), back(draft));
        }

        Screen continueLink(Draft draft) {
            if (commands.targetUser(platform, owner) == null) return linkMax(draft);
            if (draft.source() == null) return home("Аккаунт MAX подключён.");
            if (state.view().equals("link") && draft.target() != null && draft.mode() != null
                    && draft.editing().equals("filter")) return review(draft);
            return targets(draft, 0);
        }

        String summary(Draft draft) {
            String words = draft.words().isEmpty() ? "" : "\nСлова: " + wordList(draft.words());
            return "Откуда: " + chatLabel(platform, draft.source(), false)
                    + "\nКуда: " + chatLabel("max", draft.target(), true)
                    + "\nФильтр: " + filterLabel(draft.mode()) + words;
        }

        String chatLabel(String platform, String chat, boolean destination) {
            String maxUser = commands.targetUser(this.platform, owner);
            var info = destination ? maxUser == null ? null : access.target(chat, maxUser)
                    : access.source(platform, chat, owner);
            return info == null ? "Чат недоступен" : title(info);
        }

        Screen screen(String view, Draft draft, String text, Button... buttons) {
            return screen(view, draft, text, List.of(buttons));
        }

        Screen screen(String view, Draft draft, String text, List<Button> buttons) {
            String revision = UUID.randomUUID().toString().substring(0, 12);
            var screen = new Screen(text, revision, buttons.stream().map(List::of).toList());
            var actions = buttons.stream().map(Button::action).filter(java.util.Objects::nonNull).toList();
            store.saveMenu(job, owner, new State(revision, view, draft, actions), screen);
            return screen;
        }
    }

    private static Button back(Draft draft) {
        return draft.route() > 0 ? button("К настройкам пересылки", "route:" + draft.route()) : HOME;
    }

    private static int page(String arg) {
        return arg.isEmpty() ? 0 : Integer.parseInt(arg);
    }

    private static String title(JsonNode chat) { return safeTitle(first(text(chat, "title"), "Чат без названия")); }

    static String filterLabel(String mode) {
        return switch (mode) {
            case "include" -> "Только с нужными словами";
            case "exclude" -> "Сообщения без стоп-слов";
            default -> "Все сообщения";
        };
    }

    private static String wordList(List<String> words) {
        String list = String.join(", ", words);
        String preview = truncate(list, 700);
        return preview.equals(list) ? list : preview + "… (всего " + words.size() + ")";
    }

    private static Button button(String label, String action) {
        return new Button(truncate(label, 58), action, null);
    }

    private static Button link(String label, String url) { return new Button(label, null, url); }
}
