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

    record Draft(String from, String to, String source, String target, String mode, List<String> words,
                 String name, long route, long revision, String editing) {
        static Draft empty() {
            return new Draft(null, null, null, null, null, List.of(), "", 0, 0, "");
        }
        static Draft of(Store.Route route) {
            return new Draft(route.platform(), route.targetPlatform(), route.source(), route.target(),
                    route.mode(), route.terms(), route.name(), route.id(), route.revision(), "");
        }
        Draft direction(String platform) {
            return new Draft(platform, other(platform), null, null, mode, words, name, route, revision, editing);
        }
        Draft source(String id) { return new Draft(from, to, id, target, mode, words, name, route, revision, editing); }
        Draft target(String id) { return new Draft(from, to, source, id, mode, words, name, route, revision, editing); }
        Draft filter(String kind, List<String> terms) {
            return new Draft(from, to, source, target, kind, terms, name, route, revision, editing);
        }
        Draft name(String label) {
            return new Draft(from, to, source, target, mode, words, label, route, revision, editing);
        }
        Draft edit(String field) {
            return new Draft(from, to, source, target, mode, words, name, route, revision, field);
        }
    }

    record State(String revision, String view, Draft draft, List<String> actions) {
        boolean accepts(String payload) {
            String prefix = "m:" + revision + ":";
            return payload.startsWith(prefix) && actions.contains(payload.substring(prefix.length()));
        }
    }

    private static final int PROFILE_PAGE_SIZE = 5;
    private static final int CHAT_PAGE_SIZE = 10;
    static final String HOME_LABEL = "Главное меню";
    static final String PROFILES_LABEL = "Мои профили";
    static final String NEW_LABEL = "Новый профиль";
    private static final Button HOME = button(HOME_LABEL, "home");
    private static final Button PROFILES = button(PROFILES_LABEL, "profiles:0");
    private static final Button NEW = button(NEW_LABEL, "new");
    private static final String STALE = "Это меню устарело. Откройте /start.";
    private static final String CHANGED = "Профиль уже изменён. Проверьте текущие настройки.";
    static final String LINKED = "Аккаунты связаны.";

    private final Store store;
    private final ChatAccess access;
    private final Commands commands;

    Menu(Store store, ChatAccess access, Commands commands) {
        this.store = store;
        this.access = access;
        this.commands = commands;
    }

    Screen group(String platform, String chat) {
        return new Screen("Создайте профиль для этой группы в личном чате с ботом.", "", List.of(
                List.of(link("Настроить профиль", commands.privateLink(platform, "src_" + chat)))));
    }

    Screen entry(Store.Job job, String owner, Commands.Command command) {
        String name = command.name();
        String arg = command.argument();
        if (!List.of("start", "help", "bridge").contains(name)
                && !(name.equals("link") && (!arg.isBlank() || job.platform().equals("max")))) {
            return null;
        }
        var session = new Session(job, owner);
        if (session.previous != null) return session.previous;
        if (name.equals("help")) return session.help();
        if (name.equals("start") && arg.startsWith("src_")) {
            try {
                return session.pickChat(Draft.empty().direction(job.platform()), true, Commands.chatId(arg.substring(4)));
            } catch (IllegalArgumentException error) {
                return session.home("Откройте настройку из нужной группы.");
            }
        }
        if (name.equals("start") && arg.startsWith("link_") || name.equals("link")) {
            String code = name.equals("link") ? arg : arg.substring(5);
            String result = code.matches("[A-Za-z0-9_-]{32}")
                    ? store.redeemLink(job.platform(), code, owner, job.seq())
                    : "Откройте ссылку для привязки в другом приложении.";
            if (LINKED.equals(result)) {
                String previousApp = other(job.platform());
                return session.screen("linked", Draft.empty(), result,
                        link("Продолжить · " + app(previousApp), commands.privateLink(previousApp, "resume")), HOME);
            }
            return session.home(result);
        }
        if (name.equals("start") && arg.equals("resume") && session.state != null
                && session.state.view().startsWith("link:")) {
            return session.continueLink(session.state.draft());
        }
        return session.home("");
    }

    boolean current(Store.Job job, String owner, String payload) {
        if (store.step(job.seq(), "menu-screen") != null) return true;
        var state = store.menu(job.platform(), owner);
        return state != null && state.accepts(payload);
    }

    String staleNotice() { return STALE; }

    Screen callback(Store.Job job, String owner, String payload) {
        var session = new Session(job, owner);
        if (session.previous != null) return session.previous;
        if (session.state == null || !session.state.accepts(payload)) return null;
        String[] parts = payload.split(":", 4);
        return session.action(parts[2], parts.length == 4 ? parts[3] : "");
    }

    Screen input(Store.Job job, String owner, String input) {
        var session = new Session(job, owner);
        if (session.previous != null) return session.previous;
        if (job.platform().equals("telegram")) {
            switch (input.strip()) {
                case HOME_LABEL: return session.home("");
                case PROFILES_LABEL: return session.profiles(0, "");
                case NEW_LABEL: return session.directions(Draft.empty());
                default: break;
            }
        }
        if (session.state == null) return session.home("");
        var draft = session.state.draft();
        if (session.state.view().equals("words")) {
            try {
                return session.review(draft.filter(draft.mode(), Content.parseFilter(draft.mode(), input.replace('\n', ','))));
            } catch (IllegalArgumentException error) {
                return session.words(draft, error.getMessage());
            }
        }
        if (session.state.view().equals("name")) {
            String label = safeTitle(input);
            if (label.isEmpty() || Content.size(label) > 40) {
                return session.name(draft, "Введите название длиной от 1 до 40 символов.");
            }
            return session.review(draft.name(label));
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
            state = store.menu(platform, owner);
            var saved = store.step(job.seq(), "menu-screen");
            previous = saved == null ? null : MAPPER.treeToValue(saved, Screen.class);
        }

        Screen action(String action, String arg) {
            Draft draft = state.draft();
            return switch (action) {
                case "home" -> home("");
                case "help" -> help();
                case "new" -> directions(Draft.empty());
                case "direction" -> choices(draft.direction(arg), true, 0);
                case "sources" -> choices(draft, true, page(arg));
                case "targets" -> choices(draft, false, page(arg));
                case "source" -> pickChat(draft, true, Commands.chatId(arg));
                case "target" -> pickChat(draft, false, Commands.chatId(arg));
                case "filters" -> filters(draft);
                case "mode" -> arg.equals("all") ? review(draft.filter("all", List.of()))
                        : words(draft.filter(arg, draft.words()), "");
                case "keepWords", "review" -> review(draft);
                case "name" -> name(draft, "");
                case "save" -> save(draft);
                case "profiles" -> profiles(page(arg), "");
                case "profile" -> detail(Long.parseLong(arg), "");
                case "editDirection" -> directions(draft.edit("direction"));
                case "editSource" -> choices(draft.edit("source"), true, 0);
                case "editTarget" -> choices(draft.edit("target"), false, 0);
                case "editFilter" -> filters(draft.edit("filter"));
                case "editName" -> name(draft.edit("name"), "");
                case "pause", "resume" -> change(draft, action);
                case "remove" -> screen("confirm-delete", draft, "Удалить этот профиль и остановить пересылку?",
                        button("Удалить", "delete"), back(draft));
                case "delete" -> change(draft, "remove");
                case "account" -> account();
                case "linkReady" -> continueLink(draft);
                case "unlink" -> screen("confirm-unlink", draft,
                        "Разорвать связь аккаунтов? Пересылка между Telegram и MAX будет приостановлена.",
                        button("Разорвать связь", "disconnect"), HOME);
                case "disconnect" -> {
                    store.unlink(platform, owner);
                    yield home("Связь аккаунтов отключена.");
                }
                default -> home(STALE);
            };
        }

        Screen home(String notice) {
            String text = store.userProfiles(platform, owner, 0, 1).isEmpty()
                    ? "Создайте первый профиль, чтобы настроить пересылку между Telegram и MAX."
                    : "Выберите профиль или добавьте новую пересылку.";
            return screen("home", Draft.empty(), Content.join("\n\n", notice, text),
                    NEW, PROFILES, button("Аккаунты", "account"), button("Помощь", "help"));
        }

        Screen help() {
            return screen("help", Draft.empty(), """
                    Профиль — это направление пересылки, группа-источник, чат назначения и фильтр.

                    Добавьте ботов администраторами в выбранные чаты. В каналах дайте им право публиковать сообщения.

                    Нажмите «Новый профиль», выберите направление и нужные чаты. Связать аккаунты Telegram и MAX достаточно один раз.

                    Если группы нет в списке, откройте настройку кнопкой после /bridge в этой группе. Если не видно канала, проверьте права бота и обновите список.

                    Чтобы ответить в исходную группу, нажмите «Ответить» на пересланном сообщении в группе назначения.
                    """.strip(), NEW, HOME);
        }

        Screen directions(Draft draft) {
            return screen("directions", draft, "Выберите направление пересылки.",
                    button("Telegram → MAX", "direction:telegram"),
                    button("MAX → Telegram", "direction:max"), back(draft));
        }

        Screen choices(Draft draft, boolean source, int page) {
            String selectedPlatform = source ? draft.from() : draft.to();
            String member = store.identity(platform, owner).on(selectedPlatform);
            if (member == null) return linkAccounts(draft, source ? "sources" : "targets");
            List<String> ids = selectedPlatform.equals("telegram")
                    ? store.telegramSources(member, page, CHAT_PAGE_SIZE) : store.maxChats(page, CHAT_PAGE_SIZE);
            var buttons = new ArrayList<Button>();
            for (String id : ids.subList(0, Math.min(CHAT_PAGE_SIZE, ids.size()))) {
                var info = access.visibleChat(selectedPlatform, id, member, source);
                if (info != null) {
                    String label = title(info);
                    if (!source && text(info, "type").equals("channel")) label = truncate(label, 49) + " · канал";
                    buttons.add(button(label, (source ? "source:" : "target:") + id));
                }
            }
            String title = source ? "Из какой группы " + app(selectedPlatform) + " пересылать?"
                    : "В какую группу или канал " + app(selectedPlatform) + " пересылать?";
            if (buttons.isEmpty()) {
                title += "\nЗдесь пока нет доступных чатов. Проверьте своё участие и права бота.";
                if (source) title += " Можно открыть настройку через /bridge в нужной группе.";
            }
            String action = source ? "sources:" : "targets:";
            if (page > 0) buttons.add(button("Предыдущая страница", action + (page - 1)));
            if (ids.size() > CHAT_PAGE_SIZE) buttons.add(button("Следующая страница", action + (page + 1)));
            buttons.add(button("Обновить список", action + page));
            buttons.add(draft.route() > 0 ? back(draft)
                    : source ? button("Сменить направление", "new") : button("Назад к группам", "sources:0"));
            return screen(source ? "sources" : "targets", draft, title, buttons);
        }

        Screen pickChat(Draft draft, boolean source, String id) {
            String selectedPlatform = source ? draft.from() : draft.to();
            String member = store.identity(platform, owner).on(selectedPlatform);
            if (member == null) return linkAccounts(draft, source ? "sources" : "targets");
            var info = source ? access.source(selectedPlatform, id, member) : access.target(selectedPlatform, id, member);
            if (info == null) {
                return screen("unavailable", draft, "Чат недоступен. Проверьте своё участие и права бота.",
                        button("Выбрать другой чат", source ? "sources:0" : "targets:0"), back(draft));
            }
            if (source) {
                if (selectedPlatform.equals(platform)) store.selectSource(platform, owner, id);
                var selected = draft.source(id);
                return draft.editing().equals("source") ? review(selected) : choices(selected, false, 0);
            }
            var selected = draft.target(id);
            return draft.mode() == null ? filters(selected) : review(selected);
        }

        Screen filters(Draft draft) {
            return screen("filters", draft, "Какие сообщения пересылать?",
                    button("Все сообщения", "mode:all"), button("Только с нужными словами", "mode:include"),
                    button("Все, кроме сообщений со стоп-словами", "mode:exclude"),
                    draft.route() > 0 ? back(draft) : button("Назад к чатам", "targets:0"));
        }

        Screen words(Draft draft, String notice) {
            String prompt = draft.mode().equals("include") ? "Введите слова или фразы для поиска."
                    : "Введите стоп-слова или фразы. Сообщения с ними будут пропущены.";
            var buttons = new ArrayList<Button>();
            if (!draft.words().isEmpty()) {
                prompt += "\n\nТекущие слова: " + wordList(draft.words());
                buttons.add(button("Оставить текущие слова", "keepWords"));
            }
            buttons.add(button("Назад к фильтрам", "filters"));
            buttons.add(back(draft));
            return screen("words", draft, Content.join("\n\n", notice, prompt,
                    "Разделяйте их запятыми или вводите с новой строки."), buttons);
        }

        Screen name(Draft draft, String notice) {
            return screen("name", draft, Content.join("\n\n", notice,
                            "Введите название профиля. Например, «Заявки на ремонт»."),
                    button("Назад к проверке", "review"), back(draft));
        }

        Screen review(Draft draft) {
            var buttons = new ArrayList<Button>();
            buttons.add(button(draft.route() > 0 ? "Сохранить" : "Создать и запустить", "save"));
            if (draft.route() == 0) buttons.add(button("Дать название", "name"));
            buttons.add(button("Назад", switch (draft.editing()) {
                case "direction", "target" -> "targets:0";
                case "source" -> "sources:0";
                case "name" -> "profile:" + draft.route();
                default -> draft.mode().equals("all") ? "filters" : "mode:" + draft.mode();
            }));
            buttons.add(back(draft));
            return screen("review", draft, "Проверьте профиль\n\n" + summary(draft), buttons);
        }

        Screen save(Draft draft) {
            var prior = store.step(job.seq(), "profile-save");
            if (prior != null) return detail(prior.asLong(), "Профиль сохранён.");
            var user = store.identity(platform, owner);
            if (!user.linked()) return linkAccounts(draft, "review");
            if (access.visibleChat(draft.from(), draft.source(), user.on(draft.from()), true) == null
                    || access.target(draft.to(), draft.target(), user.on(draft.to())) == null) {
                return screen("unavailable", draft, "Не удалось сохранить профиль. Проверьте своё участие в чатах и права ботов.",
                        button("Проверить снова", "save"), back(draft));
            }
            long id = store.saveProfile(job, owner, user, draft);
            if (id == 0) return draft.route() == 0 ? home(CHANGED) : detail(draft.route(), CHANGED);
            return detail(id, draft.route() == 0 ? "Профиль создан." : "Профиль сохранён.");
        }

        Screen profiles(int page, String notice) {
            var profiles = store.userProfiles(platform, owner, page, PROFILE_PAGE_SIZE);
            var buttons = new ArrayList<Button>();
            for (var profile : profiles.subList(0, Math.min(profiles.size(), PROFILE_PAGE_SIZE))) {
                String label = profile.name().isBlank()
                        ? truncate(chatLabel(profile.platform(), profile.source(), true), 21) + " → "
                            + truncate(chatLabel(profile.targetPlatform(), profile.target(), false), 21)
                        : profile.label();
                buttons.add(button((profile.active() ? "" : "⏸ ") + label, "profile:" + profile.id()));
            }
            if (page > 0) buttons.add(button("Предыдущая страница", "profiles:" + (page - 1)));
            if (profiles.size() > PROFILE_PAGE_SIZE) buttons.add(button("Следующая страница", "profiles:" + (page + 1)));
            buttons.add(NEW);
            buttons.add(HOME);
            String title = profiles.isEmpty() && page == 0 ? "У вас пока нет профилей." : PROFILES_LABEL;
            return screen("profiles", Draft.empty(), Content.join("\n\n", notice, title), buttons);
        }

        Screen detail(long id, String notice) {
            var profile = store.userProfile(platform, owner, id);
            if (profile == null) return profiles(0, "Профиль недоступен.");
            var draft = Draft.of(profile);
            String state = profile.active() ? "Пересылка включена" : "Пересылка на паузе";
            return screen("detail", draft, Content.join("\n\n", notice, profile.label() + "\n" + state, summary(draft)),
                    button("Изменить направление", "editDirection"), button("Изменить источник", "editSource"),
                    button("Изменить чат назначения", "editTarget"), button("Изменить фильтр", "editFilter"),
                    button("Переименовать", "editName"),
                    profile.active() ? button("Приостановить", "pause") : button("Возобновить", "resume"),
                    button("Удалить профиль", "remove"), PROFILES, HOME);
        }

        Screen change(Draft draft, String action) {
            if (store.step(job.seq(), "change-route") == null) {
                var profile = store.userProfile(platform, owner, draft.route());
                if (profile == null) return profiles(0, "Профиль недоступен.");
                if (profile.revision() != draft.revision()) return detail(draft.route(), CHANGED);
                if (action.equals("resume")) {
                    var user = store.identity(platform, owner);
                    if (access.visibleChat(draft.from(), draft.source(), user.on(draft.from()), true) == null
                            || access.target(draft.to(), draft.target(), user.on(draft.to())) == null) {
                        return detail(profile.id(), "Для возобновления проверьте доступ к чатам и связь аккаунтов.");
                    }
                }
                if (!store.changeProfile(job, owner, profile, action)) return detail(profile.id(), CHANGED);
            }
            return action.equals("remove") ? profiles(0, "Профиль удалён.") : detail(draft.route(), "");
        }

        Screen account() {
            return store.identity(platform, owner).linked()
                    ? screen("account", Draft.empty(), "Аккаунты Telegram и MAX связаны.",
                        button("Разорвать связь", "unlink"), HOME)
                    : linkAccounts(Draft.empty(), "home");
        }

        Screen linkAccounts(Draft draft, String next) {
            String other = other(platform);
            String token = store.issueLink(platform, owner, job.seq());
            return screen("link:" + next, draft, "Свяжите аккаунты один раз, чтобы выбирать чаты в обоих приложениях.\n\n"
                            + "Откройте " + app(other) + " и нажмите «Продолжить · " + app(platform) + "».",
                    link("Открыть " + app(other), commands.privateLink(other, "link_" + token)),
                    button("Проверить подключение", "linkReady"), back(draft));
        }

        Screen continueLink(Draft draft) {
            String next = state.view().substring("link:".length());
            if (!store.identity(platform, owner).linked()) return linkAccounts(draft, next);
            return switch (next) {
                case "sources" -> choices(draft, true, 0);
                case "targets" -> choices(draft, false, 0);
                case "review" -> review(draft);
                default -> home(LINKED);
            };
        }

        String summary(Draft draft) {
            String name = draft.name().isBlank() ? "" : "Название: " + draft.name() + "\n";
            String words = draft.words().isEmpty() ? "" : "\nСлова: " + wordList(draft.words());
            return name + app(draft.from()) + " → " + app(draft.to())
                    + "\nИсточник: " + chatLabel(draft.from(), draft.source(), true)
                    + "\nНазначение: " + chatLabel(draft.to(), draft.target(), false)
                    + "\nФильтр: " + filterLabel(draft.mode()) + words;
        }

        String chatLabel(String platform, String chat, boolean source) {
            String user = store.identity(this.platform, owner).on(platform);
            var info = access.visibleChat(platform, chat, user, source);
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
        return draft.route() > 0 ? button("К профилю", "profile:" + draft.route()) : HOME;
    }

    private static int page(String arg) { return arg.isEmpty() ? 0 : Integer.parseInt(arg); }
    private static String other(String platform) { return platform.equals("telegram") ? "max" : "telegram"; }
    private static String app(String platform) { return platform.equals("telegram") ? "Telegram" : "MAX"; }
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

    private static Button button(String label, String action) { return new Button(truncate(label, 58), action, null); }
    private static Button link(String label, String url) { return new Button(label, null, url); }
}
