CREATE TABLE IF NOT EXISTS profiles (
    platform TEXT NOT NULL,
    owner TEXT NOT NULL,
    source TEXT NOT NULL,
    PRIMARY KEY (platform, owner)
);
CREATE TABLE IF NOT EXISTS account_links (
    telegram TEXT PRIMARY KEY,
    max_user TEXT NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS link_codes (
    digest TEXT PRIMARY KEY,
    telegram TEXT NOT NULL UNIQUE,
    expires REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS max_link_codes (
    digest TEXT PRIMARY KEY,
    owner TEXT NOT NULL UNIQUE,
    expires REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS max_chats (
    id TEXT PRIMARY KEY
);
CREATE TABLE IF NOT EXISTS telegram_chats (
    id TEXT PRIMARY KEY
);
CREATE TABLE IF NOT EXISTS menus (
    platform TEXT NOT NULL,
    owner TEXT NOT NULL,
    state TEXT NOT NULL,
    PRIMARY KEY (platform, owner)
);
CREATE TABLE IF NOT EXISTS routes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    platform TEXT NOT NULL,
    owner TEXT NOT NULL,
    source TEXT NOT NULL,
    target TEXT NOT NULL,
    mode TEXT NOT NULL,
    terms TEXT NOT NULL,
    active INTEGER NOT NULL,
    enabled_since REAL NOT NULL,
    UNIQUE (platform, owner, source, target)
);
CREATE INDEX IF NOT EXISTS routes_by_source ON routes (platform, source, active, target);
CREATE TABLE IF NOT EXISTS inbox (
    seq INTEGER PRIMARY KEY,
    platform TEXT NOT NULL,
    event TEXT NOT NULL,
    data TEXT,
    received REAL NOT NULL,
    available REAL NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    UNIQUE (platform, event)
);
CREATE INDEX IF NOT EXISTS inbox_pending ON inbox (available, seq) WHERE data IS NOT NULL;
CREATE TABLE IF NOT EXISTS steps (
    seq INTEGER NOT NULL REFERENCES inbox (seq) ON DELETE CASCADE,
    action TEXT NOT NULL,
    result TEXT NOT NULL,
    PRIMARY KEY (seq, action)
);
CREATE TABLE IF NOT EXISTS message_links (
    target TEXT NOT NULL,
    target_message TEXT NOT NULL,
    platform TEXT NOT NULL,
    source TEXT NOT NULL,
    source_message TEXT NOT NULL,
    thread TEXT,
    PRIMARY KEY (target, target_message)
);
