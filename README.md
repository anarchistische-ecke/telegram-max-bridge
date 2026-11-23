# Telegram to MAX bridge

Forward new messages from Telegram groups to MAX or from MAX groups and channels to Telegram. Destinations can be groups or channels. Direct replies in destination groups and replies or comments on forwarded posts in MAX channels go back to the original message. Replies in Telegram forum sources stay in the original topic.

Each member configures their own profiles in a private bot chat. A profile has a direction, a source, a destination, and its own filter. Linked accounts can manage the same profiles in either bot.

## Как настроить профиль

1. Добавьте ботов администраторами в источник и чат назначения. В MAX дайте боту право читать сообщения. Для публикации в канале дайте боту право публиковать.
2. Откройте личный чат с ботом в Telegram или MAX, нажмите «Старт» и выберите **«Новый профиль»**. Выберите направление **Telegram → MAX** или **MAX → Telegram**.
3. Выберите источник и чат назначения по названию. В Telegram источником может быть группа, в MAX — группа или канал. При первой настройке бот предложит связать аккаунты. Откройте второе приложение по кнопке и вернитесь по кнопке «Продолжить». После этого список профилей доступен в обоих ботах.
4. Выберите фильтр, при желании дайте профилю название и нажмите **«Создать и запустить»**.

| Фильтр | Какие сообщения пересылаются |
| --- | --- |
| Все сообщения | Все поддерживаемые сообщения, в том числе файлы без подписи |
| Только с нужными словами | Сообщения, где встречается хотя бы одно из указанных слов или фраз |
| Все, кроме сообщений со стоп-словами | Сообщения, где отсутствуют все указанные слова и фразы |

Слова и фразы можно ввести через запятую или с новой строки. Бот учитывает текст и подписи к вложениям. Регистр букв значения не имеет.

У каждого профиля свой фильтр. Если одному сообщению соответствуют несколько профилей с одним и тем же назначением, бот перешлёт его туда один раз.

В начале пересылки из Telegram указаны имя отправителя, его ник и время публикации в исходной группе с точностью до секунды по Москве.

**Чтобы изменить настройки**, откройте **«Мои профили»**. Можно сменить направление, источник, назначение, фильтр или название, а также приостановить, возобновить или удалить профиль. В Telegram есть кнопки под полем ввода, в MAX — кнопки под сообщением бота.

Если группа-источник ещё не появилась в списке, отправьте в ней `/bridge` и нажмите кнопку в ответе бота. Если бот уже был добавлен в канал до установки этой версии, обновите его права, чтобы платформа прислала сведения о канале. Чтобы ответить в исходную группу, используйте «Ответить» на пересланном сообщении. В канале MAX также можно оставить комментарий под пересланным постом.

Пересылки из предыдущей версии Telegram → MAX доступны как профили. Старые пересылки MAX → MAX приостановлены.

## Run the bridge

Build with JDK 25 and Maven 3.9 or newer. The service uses the JDK HTTP server and client, SQLite JDBC for storage, Jackson for JSON, and ICU4J for Unicode keyword matching. Maven packages the dependencies into one runnable JAR.

From the repository root, enter the Java project directory and build the JAR.

```sh
cd telegram-max-bridge
mvn verify
```

The executable is `target/telegram-max-bridge.jar` within the Java project directory. Run the setup and service commands from that directory.

1. Create a Telegram bot through BotFather and a MAX bot through the MAX partner platform. Get the two bot tokens. [Telegram setup](https://core.telegram.org/bots/features#creating-a-new-bot) · [MAX setup](https://dev.max.ru/docs/chatbots/bots-coding/prepare)
2. Copy the [environment template](telegram-max-bridge/.env.example) to `.env` in the Java project directory. Set both tokens and `PUBLIC_WEBHOOK_URL` to your public HTTPS origin. Run `openssl rand -hex 32` separately for each webhook secret. If the MAX API certificate needs an additional CA on your host, set `MAX_CA_FILE` to the trusted CA file in PEM format. [MAX API connection requirements](https://dev.max.ru/docs-api/changelog-api)
3. Point your HTTPS reverse proxy on port 443 at the local service on `127.0.0.1:8787`. Preserve the request body and secret headers on both `/webhook/telegram` and `/webhook/max`. A GET to `/healthz` checks whether the worker is running. [Telegram webhook setup](https://core.telegram.org/bots/api#setwebhook) · [MAX webhook setup](https://dev.max.ru/docs-api/methods/POST/subscriptions)
4. Load the environment, check the credentials, and start the service.

   ```sh
   set -a
   . ./.env
   set +a
   java -jar target/telegram-max-bridge.jar check
   java -jar target/telegram-max-bridge.jar serve
   ```

5. With the service running, load the same environment in a second shell and run `java -jar target/telegram-max-bridge.jar register-webhooks`. Use dedicated bots for the bridge. Registering replaces the Telegram bot’s webhook and adds a MAX subscription.
6. Promote the Telegram bot to administrator in the source groups. In MAX, add the bot as administrator with `read_all_messages` in every connected group or channel. Grant `write` to publish in a MAX channel. Add MAX chats after registering the webhooks so the bridge can discover them. An existing MAX group or channel becomes discoverable when the bot receives a new message or post. Changing the bot’s admin permissions also makes the chat discoverable. [Telegram group visibility](https://core.telegram.org/bots/faq#what-messages-will-my-bot-get) · [MAX rights](https://dev.max.ru/docs-api/methods/POST/chats/-chatId-/members/admins) · [MAX chat IDs](https://dev.max.ru/docs-api/methods/GET/chats)

Keep one service process per bot pair. Persist the SQLite database and retain its message-ID mappings across deployments so existing MAX replies can still reach their source. Run the service under your process supervisor in production. The listener binds to localhost by default.

### Upgrade an existing Python deployment

Build the JAR, stop the Python service, and back up the SQLite database. Start the JAR with the existing environment and the same `BRIDGE_DB` file. Use the same working directory if the database path is relative. Java updates the database on first start and resumes pending updates, including deliveries with some destinations already completed. Profiles, linked accounts, routes, and reply mappings are reused. Existing webhook registrations work with the JAR when the public endpoints and secrets stay the same.

## Delivery behavior

- The bridge picks up new messages after activation and ignores its own posts to prevent loops. Edits, deletions, older history, and service events do not sync. Messages from other bots are eligible when the platform delivers them to the bridge. [Telegram bot-to-bot updates](https://core.telegram.org/api/bots/bot-to-bot)
- Forwarded messages include the source chat, sender's name, available username, and publication time in Moscow time to the second. MAX generally supplies usernames only for bots. Photos, videos, audio, and files can cross platforms up to 20 MB. Some specialized message types appear as text or a placeholder. Media albums arrive as individual Telegram updates. Rich text is reduced to plain text with embedded link URLs retained. [MAX message metadata](https://dev.max.ru/docs-api/objects/Message/) · [Telegram files](https://core.telegram.org/bots/api#getfile) · [MAX media](https://dev.max.ru/docs-api/use-cases/sending-messages/media/)
- Direct replies in destination groups and replies or comments under forwarded MAX channel posts are attached to the exact original message. The original must still exist. Telegram content protection is respected. [Telegram reply parameters](https://core.telegram.org/bots/api#replyparameters) · [MAX channel comments](https://dev.max.ru/docs-api/objects/Update/)
- Webhooks are acknowledged after SQLite commits the update. Delivery retries temporary failures and records each successful send. In the rare case where a send succeeds remotely but the API acknowledgement or local write is interrupted, the platform can show a duplicate. The service clears processed webhook bodies and keeps recent event IDs for deduplication and message IDs for replies.

## Delivery capacity

The Java service runs up to 24 chat jobs concurrently by default using virtual threads. Each chat and private profile processes its queued updates in the order SQLite accepted them. A slow delivery or an update waiting to retry holds back later work for that chat while other chats can use available slots. SQLite indexes the next update for each chat, so the scheduler can select work without reading the messages behind a stalled update. [Java virtual-thread executor](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Executors.html#newVirtualThreadPerTaskExecutor()) · [SQLite write-ahead logging](https://sqlite.org/wal.html)

| Setting | Default | Effect |
| --- | --- | --- |
| `BRIDGE_WORKERS` | 24 | Maximum chat jobs in flight. Supports 1–256. |
| `BRIDGE_MEDIA_WORKERS` | Up to 4 | Maximum simultaneous media transfers. Supports 1 through the worker count. |

The bridge spaces API calls to 25 requests per second per platform and honors rate-limit delays. Membership checks and sends count towards that budget, so a worker count of 24 does not imply 24 messages per second. Jobs and retries stay in SQLite across restarts. [MAX API request limit](https://dev.max.ru/docs-api/methods/POST/uploads#%D0%9E%D0%B1%D1%80%D0%B0%D0%B1%D0%BE%D1%82%D0%BA%D0%B0%20%D0%BC%D0%B5%D0%B4%D0%B8%D0%B0%D1%84%D0%B0%D0%B9%D0%BB%D0%BE%D0%B2)

## Verify

From the Java project directory, run the tests.

```sh
mvn test
```

The tests use local Telegram and MAX API fixtures. They cover per-chat delivery order, progress across chats during a slow send or retry, and migration of pending Python deliveries. To verify a live installation, create an include route, send one matching and one nonmatching message, and check that MAX receives the match. Reply to the copy and verify the reply appears on the exact source message.
