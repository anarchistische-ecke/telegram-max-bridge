# Telegram to MAX bridge

Forward new messages from Telegram or MAX groups to a MAX group or channel. A reply made directly to a forwarded copy in a MAX group goes back as a reply to the original message. Telegram forum replies stay in the original topic.

Each member configures their own routes in a private bot chat. A route applies to messages from the whole source group and has one destination and one filter. Members can create routes to multiple destinations. If several profiles match the same message and destination, the bridge posts a single copy there.

## Run the bridge

Build with JDK 25 and Maven 3.9 or newer. The service uses the JDK HTTP server and client, SQLite JDBC for storage, Jackson for JSON, and ICU4J for Unicode keyword matching. Maven packages the dependencies into one runnable JAR.

```sh
mvn verify
```

The executable is `target/telegram-max-bridge.jar`.

1. Create a Telegram bot through BotFather and a MAX bot through the MAX partner platform. Get the two bot tokens. [Telegram setup](https://core.telegram.org/bots/features#creating-a-new-bot) · [MAX setup](https://dev.max.ru/docs/chatbots/bots-coding/prepare)
2. Copy `.env.example` to `.env`. Set both tokens and `PUBLIC_WEBHOOK_URL` to your public HTTPS origin. Run `openssl rand -hex 32` separately for each webhook secret. If the MAX API certificate needs an additional CA on your host, set `MAX_CA_FILE` to the trusted CA file in PEM format. [MAX API connection requirements](https://dev.max.ru/docs-api/changelog-api)
3. Point your HTTPS reverse proxy on port 443 at the local service on `127.0.0.1:8787`. Preserve the request body and secret headers on both `/webhook/telegram` and `/webhook/max`. A GET to `/healthz` checks whether the worker is running. [Telegram webhook setup](https://core.telegram.org/bots/api#setwebhook) · [MAX webhook setup](https://dev.max.ru/docs-api/methods/POST/subscriptions)
4. From this directory, load the environment, check the credentials, and start the service.

   ```sh
   set -a
   . ./.env
   set +a
   java -jar target/telegram-max-bridge.jar check
   java -jar target/telegram-max-bridge.jar serve
   ```

5. With the service running, load the same environment in a second shell and run `java -jar target/telegram-max-bridge.jar register-webhooks`. Use dedicated bots for the bridge. Registering replaces the Telegram bot’s webhook and adds a MAX subscription.
6. Promote the Telegram bot to administrator in the source groups. In MAX, add the bot as administrator with `read_all_messages` in every connected group and grant `write` in each destination channel. Add MAX chats after registering the webhooks so the bridge can discover them. An existing MAX group becomes discoverable when the bot receives a new group message. For an existing channel, change the bot’s admin permissions to generate an update, or use the chat ID directly if you know it. [Telegram group visibility](https://core.telegram.org/bots/faq#what-messages-will-my-bot-get) · [MAX rights](https://dev.max.ru/docs-api/methods/POST/chats/-chatId-/members/admins) · [MAX chat IDs](https://dev.max.ru/docs-api/methods/GET/chats)

Keep one service process per bot pair. Persist the SQLite database and retain its message-ID mappings across deployments so existing MAX replies can still reach their source. Run the service under your process supervisor in production. The listener binds to localhost by default.

### Upgrade an existing Python deployment

Build the JAR, stop the Python service, and back up the SQLite database. Start the JAR with the existing environment and the same `BRIDGE_DB` file. Use the same working directory if the database path is relative. Java updates the database on first start and resumes pending updates, including deliveries with some destinations already completed. Profiles, linked accounts, routes, and reply mappings are reused. Existing webhook registrations work with the JAR when the public endpoints and secrets stay the same.

## Configure a personal profile

1. In the source group, send `/bridge` and open its link to the private bot chat. In Telegram groups with several bots, you can address it as `/bridge@YOUR_BOT`. If the source link opens an existing bot chat without selecting the group, send `/source GROUP_ID` privately. The group ID is the numeric part of the link after `src_`.
2. For a Telegram source, send `/link` to the Telegram bot and open the resulting MAX link. You can also copy the `/link CODE` command to the private MAX bot chat. This lets the bridge check which MAX chats you belong to.
3. In the private bot chat where you selected the source, send `/destinations`. It lists MAX groups and channels you can use, with their IDs. Continue with `/destinations 2` when another page is listed.
4. Set a route, substituting the chosen MAX chat ID for `MAX_CHAT_ID`.

| Command | Result |
| --- | --- |
| `/route MAX_CHAT_ID all` | Send every supported new message, including media without a caption. |
| `/route MAX_CHAT_ID include urgent, срочно, new order` | Send messages that contain any listed word or phrase. |
| `/route MAX_CHAT_ID exclude spam, internal only` | Send messages unless they contain any listed word or phrase. |
| `/routes` | View your active and paused routes. |
| `/pause ROUTE_ID` and `/resume ROUTE_ID` | Stop or restart new forwards for a route. |
| `/remove ROUTE_ID` | Delete a route from your profile. |

Keywords are case-insensitive. Commas separate entries, spaces within an entry form a phrase, and whole-word matching means `urgent` matches `urgent!` but misses `urgently`. Filters check message text and media captions. A replacement `/route` command for the same source and destination updates the existing route and activates it. Changing the selected source only changes which source the next `/route` command applies to. Telegram `/unlink` disconnects the MAX account and pauses the member’s Telegram routes.

Before each delivery, the bridge checks that at least one matching profile still belongs to both the source group and the destination and that the bots have the relevant rights. Existing copies in MAX groups can still receive direct replies after the profile owner pauses or deletes a route. Any current member of that MAX destination group can reply.

## Delivery behavior

- The bridge picks up new messages after activation and ignores its own posts to prevent loops. Edits, deletions, older history, and service events do not sync. Messages from other bots are eligible when the platform delivers them to the bridge. [Telegram bot-to-bot updates](https://core.telegram.org/api/bots/bot-to-bot)
- MAX-to-MAX forwarding uses the native forward operation. Telegram-to-MAX messages include source and sender attribution. Photos, videos, audio, and files can cross platforms up to 20 MB. Some specialized message types appear as text or a placeholder. Media albums arrive as individual Telegram updates. Rich text is reduced to plain text with embedded link URLs retained. [Telegram files](https://core.telegram.org/bots/api#getfile) · [MAX media](https://dev.max.ru/docs-api/use-cases/sending-messages/media/)
- MAX groups support return replies. MAX channels receive posts only. Direct replies to a forwarded copy are associated with its exact original message, so the original must still exist. Telegram content protection is respected. [Telegram reply parameters](https://core.telegram.org/bots/api#replyparameters) · [MAX message links](https://dev.max.ru/docs-api/objects/NewMessageBody)
- Webhooks are acknowledged after SQLite commits the update. Delivery retries temporary failures and records each successful send. In the rare case where a send succeeds remotely but the API acknowledgement or local write is interrupted, the platform can show a duplicate. The service clears processed webhook bodies and keeps recent event IDs for deduplication and message IDs for replies.

## Delivery capacity

The Java service runs up to 24 chat jobs concurrently by default using virtual threads. Each chat and private profile processes its queued updates in the order SQLite accepted them. A slow delivery or an update waiting to retry holds back later work for that chat while other chats can use available slots. SQLite indexes the next update for each chat, so the scheduler can select work without reading the messages behind a stalled update. [Java virtual-thread executor](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Executors.html#newVirtualThreadPerTaskExecutor()) · [SQLite write-ahead logging](https://sqlite.org/wal.html)

| Setting | Default | Effect |
| --- | --- | --- |
| `BRIDGE_WORKERS` | 24 | Maximum chat jobs in flight. Supports 1–256. |
| `BRIDGE_MEDIA_WORKERS` | Up to 4 | Maximum simultaneous media transfers. Supports 1 through the worker count. |

The bridge spaces API calls to 25 requests per second per platform and honors rate-limit delays. Membership checks and sends count towards that budget, so a worker count of 24 does not imply 24 messages per second. Jobs and retries stay in SQLite across restarts. [MAX API request limit](https://dev.max.ru/docs-api/methods/POST/uploads#%D0%9E%D0%B1%D1%80%D0%B0%D0%B1%D0%BE%D1%82%D0%BA%D0%B0%20%D0%BC%D0%B5%D0%B4%D0%B8%D0%B0%D1%84%D0%B0%D0%B9%D0%BB%D0%BE%D0%B2)

## Verify

```sh
mvn test
```

The tests use local Telegram and MAX API fixtures. They cover per-chat delivery order, progress across chats during a slow send or retry, and migration of pending Python deliveries. To verify a live installation, create an include route, send one matching and one nonmatching message, and check that MAX receives the match. Reply to the copy and verify the reply appears on the exact source message.
