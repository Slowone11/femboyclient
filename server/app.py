"""Femboy Client website API.

A small FastAPI app backed by SQLite. It receives what the mod's trackers see,
stores it, and shows it on the page.

Endpoints:
    POST /register       link an account (needs X-Enroll-Secret), returns its API key
    POST /heartbeat      "this account is online"       (needs X-API-Key)
    GET  /players        every linked account with its live status
    POST /tracker/snapshot   players seen in a lobby (needs X-API-Key)
    GET  /tracker/search     find a player and every time you met them
    GET  /tracker/overview   the worlds and how many players are in each familiarity tier
    GET  /tracker/players    one page of the players in a tier (sightings under 4 hours apart are one meeting)
    GET  /tracker/live       server-sent events: players as they are seen
    GET  /tracker/worlds     the SkyBlock worlds seen so far, with player counts
    GET  /networth       is a third-party networth lookup available?
    GET  /health         quick check that the server is up

Run it with:  .venv\\Scripts\\python.exe -m uvicorn app:app
"""

import asyncio
import gzip
import hashlib
import hmac
import ipaddress
import json
import math
import os
import base64
import re
import secrets
import shutil
import socket
import sqlite3
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid as uuidlib
from collections import deque
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, List, Optional

from fastapi import FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import HTMLResponse, PlainTextResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from starlette.middleware.gzip import GZipMiddleware
from pydantic import BaseModel, Field

BASE = Path(__file__).parent
# The two paths can be overridden with environment variables, which is how the
# tests run against a throwaway database instead of your real one.
DB_PATH = Path(os.environ.get("FEMBOYCLIENT_DB", BASE / "femboyclient.db"))
CONFIG_PATH = Path(os.environ.get("FEMBOYCLIENT_CONFIG", BASE / "config.json"))
STATIC_DIR = BASE / "static"

# The mod sends a heartbeat every 15 seconds. An account counts as online while
# its last contact is newer than three missed heartbeats.
ONLINE_WINDOW_MS = 45_000

TRACKER_MAX_PLAYERS = 500     # names in one snapshot
SEARCH_CHAT_LIMIT = 300       # messages shown per player
SEARCH_DEFAULT_LIMIT = 20
SEARCH_MAX_LIMIT = 100



@asynccontextmanager
async def lifespan(_app):
    announce()
    yield


app = FastAPI(title="Femboy Client API", lifespan=lifespan)


# ------------------------------------------------------------------ helpers

def now_ms() -> int:
    return int(time.time() * 1000)


def normalise_uuid(value: str) -> str:
    """Return a UUID in canonical lowercase form, or raise a 422."""
    try:
        return str(uuidlib.UUID(value.strip()))
    except (ValueError, AttributeError):
        raise HTTPException(status_code=422, detail="invalid player_uuid: " + repr(value))


def _json_response(request: Request, data) -> Response:
    """A big answer as JSON, gzipped when the browser accepts that.

    FastAPI's own conversion walks every value in Python first, which took longer than the queries for a big page
    (5000 players). Plain json.dumps is the same JSON, made in C. Gzip turns a page of about 1.4 MB into a small fraction of
    that, which matters when the page is opened from another PC or a phone.
    """
    body = json.dumps(data, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    headers = {"Vary": "Accept-Encoding"}
    if len(body) > 4096 and "gzip" in request.headers.get("accept-encoding", "").lower():
        body = gzip.compress(body, compresslevel=5)
        headers["Content-Encoding"] = "gzip"
    return Response(content=body, media_type="application/json", headers=headers)


# ------------------------------------------------------------------ config

CONFIG_LOCK = threading.Lock()


def _read_config() -> dict:
    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    config.setdefault("api_keys", {})
    return config


def _write_config(config: dict) -> None:
    # Write to a temp file and swap it in, so a crash cannot leave half a file.
    temp = CONFIG_PATH.with_name("%s.%d.tmp" % (CONFIG_PATH.name, threading.get_ident()))
    temp.write_text(json.dumps(config, indent=2), encoding="utf-8")
    os.replace(temp, CONFIG_PATH)


def ensure_config() -> dict:
    """Create config.json if it is missing, and add an enrol secret if it has none."""
    with CONFIG_LOCK:
        if CONFIG_PATH.exists():
            config = _read_config()
        else:
            config = {"api_keys": {}}

        changed = not CONFIG_PATH.exists()
        if not config.get("enroll_secret"):
            config["enroll_secret"] = secrets.token_urlsafe(24)
            changed = True
        if not config.get("admin_token"):
            config["admin_token"] = secrets.token_urlsafe(24)       # lets you send commands to your games from the page
            changed = True

        config["_comment"] = (
            "enroll_secret lets a new PC link an account without copying keys around. "
            "Keep it private. api_keys holds one key per account; the server adds "
            "entries itself when a mod registers. admin_token is the password for sending "
            "commands (quit, chat) to your games from the page: keep it private too."
        )
        if changed:
            _write_config(config)
        return config


def require_key(key: Optional[str]) -> dict:
    """Look the API key up in config.json. Rejects anything unknown."""
    keys = _read_config().get("api_keys", {})
    if not key or key not in keys:
        raise HTTPException(status_code=401, detail="missing or unknown X-API-Key")
    return keys[key]


def linked_account(key: Optional[str]) -> dict:
    """require_key, and the key must also be linked to a Minecraft account."""
    account = require_key(key)
    if not account.get("player_uuid"):
        raise HTTPException(status_code=403, detail="this API key is not linked to an account")
    return account


def check_bound(player: dict, player_uuid: str) -> None:
    """A key locked to one account may not write data for anyone else."""
    bound = player.get("player_uuid")
    if bound and bound != player_uuid:
        raise HTTPException(
            status_code=403,
            detail="this API key may only be used for " + bound,
        )


# ---------------------------------------------------------------- database

@contextmanager
def db():
    """Open a connection, commit on success, and always close it."""
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA synchronous=NORMAL")           # safe with WAL, and commits are much cheaper
    conn.execute("PRAGMA temp_store=MEMORY")
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def init_db() -> None:
    with db() as conn:
        # WAL lets the SSE readers and the uploader write at the same time
        # without "database is locked" errors.
        conn.execute("PRAGMA journal_mode=WAL")

        # One row per linked account, so the page can show who is online.
        conn.execute(
            "CREATE TABLE IF NOT EXISTS players ("
            " player_uuid TEXT PRIMARY KEY,"
            " player_name TEXT NOT NULL,"
            " status TEXT NOT NULL DEFAULT 'offline',"
            " in_skyblock INTEGER NOT NULL DEFAULT 0,"
            " last_seen_ms INTEGER NOT NULL,"
            " registered_ms INTEGER NOT NULL,"
            " scanner TEXT NOT NULL DEFAULT '')"
        )

        # Older databases get the scanner column added.
        if "scanner" not in {r["name"] for r in conn.execute("PRAGMA table_info(players)")}:
            conn.execute("ALTER TABLE players ADD COLUMN scanner TEXT NOT NULL DEFAULT ''")

        # ---- player tracker. All times here are UTC, as text like 2026-09-21T19:41:03.123Z,
        # so text order is time order. No server, lobby or address is stored anywhere.

        # Every username ever seen, once, whatever its capitalisation.
        conn.execute(
            "CREATE TABLE IF NOT EXISTS tracker_players ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " username TEXT NOT NULL,"
            " username_lower TEXT NOT NULL UNIQUE,"
            " first_seen TEXT NOT NULL,"
            " last_seen TEXT NOT NULL,"
            " times_seen INTEGER NOT NULL DEFAULT 0)"
        )

        # One row per lobby visit. A visit is just the moment an account entered a
        # lobby. The pair (account, start time) is unique, so a message the mod
        # sends twice cannot create a second visit.
        conn.execute(
            "CREATE TABLE IF NOT EXISTS tracker_visits ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " owner_uuid TEXT NOT NULL,"
            " started_at TEXT NOT NULL,"
            " world TEXT NOT NULL DEFAULT 'Unknown',"
            " UNIQUE(owner_uuid, started_at))"
        )

        # Visits recorded before worlds existed become "Unknown". The name of the
        # SkyBlock world (for example "Hub") is all that is kept, never a server code.
        if "world" not in {r["name"] for r in conn.execute("PRAGMA table_info(tracker_visits)")}:
            conn.execute("ALTER TABLE tracker_visits ADD COLUMN world TEXT NOT NULL DEFAULT 'Unknown'")

        # One row each time a player is met in a visit, never twice for the same visit.
        conn.execute(
            "CREATE TABLE IF NOT EXISTS tracker_sightings ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " player_id INTEGER NOT NULL REFERENCES tracker_players(id),"
            " visit_id INTEGER NOT NULL REFERENCES tracker_visits(id),"
            " seen_at TEXT NOT NULL,"
            " UNIQUE(player_id, visit_id))"
        )

        # One row each time a player was confirmed to be in your party. Never an invite or a
        # request: the mod only reports players who actually joined. Unique per account,
        # player and time, so a message the mod sends twice cannot count twice.
        conn.execute(
            "CREATE TABLE IF NOT EXISTS tracker_parties ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " owner_uuid TEXT NOT NULL,"
            " player_id INTEGER NOT NULL REFERENCES tracker_players(id),"
            " joined_at TEXT NOT NULL,"
            " UNIQUE(owner_uuid, player_id, joined_at))"
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_tracker_parties_player ON tracker_parties(player_id)")

        # A friendly or enemy tag on a player, plus a free-text note on why. Shared across every
        # account, like the rest of the tracker database. At most one tag per player.
        conn.execute(
            "CREATE TABLE IF NOT EXISTS tracker_frienemies ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " player_id INTEGER NOT NULL UNIQUE REFERENCES tracker_players(id),"
            " tag TEXT NOT NULL CHECK(tag IN ('friendly', 'enemy')),"
            " notes TEXT NOT NULL DEFAULT '',"
            " updated_at TEXT NOT NULL)"
        )

        # What players said, when, and where. The player must be a real username; the text is tidied
        # (see _tidy_chat). Unique per account, player, time and text, so a message the mod sends
        # twice is stored once.
        conn.execute(
            "CREATE TABLE IF NOT EXISTS tracker_chats ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " owner_uuid TEXT NOT NULL,"
            " player_id INTEGER NOT NULL REFERENCES tracker_players(id),"
            " said_at TEXT NOT NULL,"
            " channel TEXT NOT NULL DEFAULT 'Public',"
            " world TEXT NOT NULL DEFAULT 'Unknown',"
            " message TEXT NOT NULL,"
            " UNIQUE(owner_uuid, player_id, said_at, message))"
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_tracker_chats_player ON tracker_chats(player_id, said_at)")

        # What the account owner said themselves. It is shown on the owner's own node (the middle one), not
        # as a player, so it lives apart from other players' chat. Same tidying and the same one-copy rule.
        conn.execute(
            "CREATE TABLE IF NOT EXISTS tracker_my_chats ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " owner_uuid TEXT NOT NULL,"
            " said_at TEXT NOT NULL,"
            " channel TEXT NOT NULL DEFAULT 'Public',"
            " world TEXT NOT NULL DEFAULT 'Unknown',"
            " message TEXT NOT NULL,"
            " UNIQUE(owner_uuid, said_at, message))"
        )

        # The Minecraft UUID of a player, looked up once from their name (see _resolve_uuid) and
        # saved, because Hypixel's status endpoint takes a UUID, not a username.
        player_columns = {r["name"] for r in conn.execute("PRAGMA table_info(tracker_players)")}
        if "uuid" not in player_columns:
            conn.execute("ALTER TABLE tracker_players ADD COLUMN uuid TEXT")
        if "uuid_checked_at" not in player_columns:
            conn.execute("ALTER TABLE tracker_players ADD COLUMN uuid_checked_at TEXT")

        conn.execute("CREATE INDEX IF NOT EXISTS idx_tracker_players_lower ON tracker_players(username_lower)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_tracker_players_uuid ON tracker_players(uuid)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_tracker_sightings_player ON tracker_sightings(player_id)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_tracker_sightings_seen ON tracker_sightings(seen_at)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_tracker_sightings_visit ON tracker_sightings(visit_id)")
        # One profile (you) that all your accounts belong to. Nothing needs more than one, so it is just a name and a link.
        conn.execute("CREATE TABLE IF NOT EXISTS profiles (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        conn.execute("INSERT OR IGNORE INTO profiles (id, name) VALUES (1, 'Me')")
        if "profile_id" not in {r["name"] for r in conn.execute("PRAGMA table_info(players)")}:
            conn.execute("ALTER TABLE players ADD COLUMN profile_id INTEGER NOT NULL DEFAULT 1 REFERENCES profiles(id)")

        # Every tracker row already says which account it came from by UUID (owner_uuid). It now also keeps the name, so a row is
        # readable on its own. Old rows get the name of their account. Nothing is moved or deleted: the data was already in these
        # shared tables, and only the pages looked at one account at a time.
        for table in ("tracker_visits", "tracker_parties", "tracker_chats", "tracker_my_chats"):
            if "owner_name" not in {r["name"] for r in conn.execute("PRAGMA table_info(" + table + ")")}:
                conn.execute("ALTER TABLE " + table + " ADD COLUMN owner_name TEXT NOT NULL DEFAULT ''")
            conn.execute(
                "UPDATE " + table + " SET owner_name = COALESCE("
                "(SELECT p.player_name FROM players p WHERE p.player_uuid = " + table + ".owner_uuid), '') WHERE owner_name = ''")

        # Commands you send to a game from the page (quit, chat). The game asks for its own account's commands, does them and
        # says how it went. Times are UTC. A command that is not picked up or answered in time is failed (see _expire_commands).
        conn.execute(
            "CREATE TABLE IF NOT EXISTS commands ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " target_uuid TEXT NOT NULL,"
            " target_name TEXT NOT NULL,"
            " type TEXT NOT NULL CHECK (type IN ('QUIT', 'CHAT')),"
            " payload TEXT,"
            " status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'FAILED')),"
            " reason TEXT,"
            " created_at TEXT NOT NULL,"
            " taken_at TEXT,"
            " finished_at TEXT)"
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_commands_target ON commands(target_uuid, status, id)")

        conn.execute("CREATE INDEX IF NOT EXISTS idx_tracker_visits_owner ON tracker_visits(owner_uuid, id)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_tracker_visits_world ON tracker_visits(world)")


def touch_player(conn, player_uuid: str, player_name: str, *, status: str = "online",
                 in_skyblock: Optional[bool] = None) -> None:
    """Record that an account was just heard from."""
    now = now_ms()
    conn.execute(
        "INSERT INTO players (player_uuid, player_name, status, in_skyblock,"
        " last_seen_ms, registered_ms) VALUES (?, ?, ?, ?, ?, ?)"
        " ON CONFLICT(player_uuid) DO UPDATE SET"
        "  player_name = excluded.player_name,"
        "  status = excluded.status,"
        "  last_seen_ms = excluded.last_seen_ms",
        (player_uuid, player_name, status, int(bool(in_skyblock)), now, now),
    )
    if in_skyblock is not None:
        conn.execute(
            "UPDATE players SET in_skyblock = ? WHERE player_uuid = ?",
            (int(in_skyblock), player_uuid),
        )


ensure_config()
init_db()


# ------------------------------------------------------------------ models

class RegisterIn(BaseModel):
    player_uuid: str
    player_name: str


class HeartbeatIn(BaseModel):
    player_uuid: str
    player_name: str
    in_skyblock: bool = False
    online: bool = True  # the mod sends false once, when the game closes
    scanner: Optional[str] = None  # e.g. "ON (world: Hub)" or "OFF", shown on the Player Tracker tab


# --------------------------------------------------------------- endpoints

@app.post("/register")
def register(body: RegisterIn, x_enroll_secret: Optional[str] = Header(default=None)):
    """Link an account and hand back its API key.

    Calling it again for an account that is already linked returns the same key,
    so installing the mod on a second PC just works. That is why the enrol secret
    must be kept private: it is the only thing protecting this endpoint.
    """
    expected = ensure_config().get("enroll_secret", "")
    if not x_enroll_secret or not secrets.compare_digest(x_enroll_secret, expected):
        raise HTTPException(status_code=401, detail="missing or wrong X-Enroll-Secret")

    player_uuid = normalise_uuid(body.player_uuid)
    player_name = body.player_name.strip()[:32] or "unknown"

    with CONFIG_LOCK:
        config = _read_config()
        found = next((k for k, v in config["api_keys"].items()
                      if v.get("player_uuid") == player_uuid), None)
        created = found is None
        key = found or secrets.token_urlsafe(24)
        config["api_keys"][key] = {"name": player_name, "player_uuid": player_uuid}
        _write_config(config)

    with db() as conn:
        touch_player(conn, player_uuid, player_name)

    return {"api_key": key, "created": created}


@app.post("/heartbeat")
def heartbeat(body: HeartbeatIn, x_api_key: Optional[str] = Header(default=None)):
    """Tell the website an account is online, and whether it is in SkyBlock."""
    player = require_key(x_api_key)
    player_uuid = normalise_uuid(body.player_uuid)
    check_bound(player, player_uuid)

    with db() as conn:
        touch_player(conn, player_uuid, body.player_name.strip()[:32] or "unknown",
                     status="online" if body.online else "offline",
                     in_skyblock=body.in_skyblock and body.online)
        if body.scanner is not None:
            # Keep only a short, printable line; it is displayed as text.
            status = "".join(c for c in body.scanner if c.isprintable())[:60]
            conn.execute("UPDATE players SET scanner = ? WHERE player_uuid = ?", (status, player_uuid))
    return {"ok": True}


@app.get("/players")
def get_players():
    """Every linked account, with whether it is online and in SkyBlock."""
    now = now_ms()
    result = []

    with db() as conn:
        for p in conn.execute("SELECT * FROM players ORDER BY last_seen_ms DESC").fetchall():
            online = p["status"] == "online" and now - p["last_seen_ms"] <= ONLINE_WINDOW_MS
            result.append({
                "player_uuid": p["player_uuid"],
                "player_name": p["player_name"],
                "online": online,
                "in_skyblock": online and bool(p["in_skyblock"]),
                "last_seen_ms": p["last_seen_ms"],
                "scanner": p["scanner"],
                "profile_id": p["profile_id"],
            })
    return result


# ----------------------------------------------------------- player tracker
#
# Who was in the lobby with you, and when. Only usernames and times are stored.
# Nothing here knows or records which server or lobby it was: a visit is just the
# moment you entered a lobby, and a sighting is one player seen during one visit.

_USERNAME = re.compile(r"[A-Za-z0-9_]{3,16}")


def utc_iso(text: str) -> str:
    """Normalise an ISO 8601 timestamp to UTC, e.g. 2026-09-21T19:41:03.123Z.

    Everything the tracker stores has this one shape, so text order is time order.
    The website converts to America/Toronto only when it shows a time.
    """
    try:
        parsed = datetime.fromisoformat(text)
    except (ValueError, TypeError):
        raise HTTPException(status_code=422, detail="bad timestamp: " + repr(text))
    if parsed.tzinfo is None:
        raise HTTPException(status_code=422, detail="timestamp needs a timezone: " + text)
    return parsed.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class TrackerSnapshotIn(BaseModel):
    """What the mod sends when it sees players in a lobby."""

    me: str = ""
    # The SkyBlock world, for example "Hub". Missing or not a plain name means "Unknown".
    world: Optional[str] = None
    players: List[str]
    timestamp: str
    # When this lobby visit began. It is the same in every message of one visit.
    visit_started: Optional[str] = None
    # A simpler way to say "this starts a new visit", used when visit_started is absent.
    new_visit: bool = False


# A world is a plain name such as "Hub" or "Spider's Den": letters, spaces, apostrophes
# and hyphens only. Anything with a digit is refused, which is what stops a server or
# lobby code (for example "mini264C") ever being stored as a world.
_WORLD = re.compile(r"[A-Za-z][A-Za-z' -]{1,31}")

UNKNOWN_WORLD = "Unknown"


def clean_world(text: Optional[str]) -> str:
    """The world name to store, or "Unknown" if what was sent is not a plain world name."""
    if not text:
        return UNKNOWN_WORLD
    name = " ".join(text.split())
    return name if _WORLD.fullmatch(name) else UNKNOWN_WORLD


def _world_filter(worlds: List[str]) -> List[str]:
    """Lower-case, de-duplicated world names from a query, at most 20."""
    seen = []
    for name in worlds:
        lowered = " ".join(name.split()).lower()
        if lowered and lowered not in seen:
            seen.append(lowered)
    if len(seen) > 20:
        raise HTTPException(status_code=422, detail="at most 20 worlds")
    return seen


def _clean_usernames(raw_names: List[str], me: str, account: dict):
    """Valid usernames from what the mod sent, one each whatever the capitalisation, without your own.

    Cleaned again here, so a modified or older mod cannot store rubbish or your own name.
    Returns ({lowercase: name}, how many were ignored).
    """
    own = {me.strip().lower(), str(account.get("name", "")).strip().lower()} - {""}
    names: dict = {}
    ignored = 0
    for raw in raw_names:
        name = raw.strip()
        if not _USERNAME.fullmatch(name):
            ignored += 1
        elif name.lower() not in own:
            names.setdefault(name.lower(), name)
    return names, ignored


ALL_ACCOUNTS = "all"


def _scope_owner(owner: Optional[str]) -> Optional[str]:
    """The account a request is about: its UUID, or None for every account of the profile (owner missing, empty or "all")."""
    if owner is None or not owner.strip() or owner.strip().lower() == ALL_ACCOUNTS:
        return None
    return normalise_uuid(owner)


def _account_name(conn, owner: str, account: dict) -> str:
    """The name of an account, for the owner_name column: the linked account's own name, else the one on its API key."""
    row = conn.execute("SELECT player_name FROM players WHERE player_uuid = ?", (owner,)).fetchone()
    return (row["player_name"] if row else "") or str(account.get("name", "")) or ""


def _party_scope(conn, owner: Optional[str]):
    """The WHERE part and its values for "who has been in a party with this account": never the account itself, and for every
    account together, never any of your own accounts."""
    if owner:
        return "t.owner_uuid = ? AND p.username_lower != ?", [owner, _own_name(conn, owner)]
    return "p.username_lower NOT IN (SELECT lower(player_name) FROM players)", []


def _tracker_visit(conn, owner: str, visit_time: Optional[str], seen_at: str, world: str, owner_name: str = "") -> int:
    """Find or create the visit a snapshot belongs to."""
    if visit_time is None:
        # No visit given: carry on with the account's latest one, or start the first.
        latest = conn.execute(
            "SELECT id FROM tracker_visits WHERE owner_uuid = ? ORDER BY id DESC LIMIT 1", (owner,)
        ).fetchone()
        if latest:
            return latest["id"]
        visit_time = seen_at

    # Use the spelling already stored for this world, so "hub" and "Hub" are one world.
    known = conn.execute("SELECT world FROM tracker_visits WHERE lower(world) = lower(?) LIMIT 1", (world,)).fetchone()
    if known:
        world = known["world"]

    conn.execute("INSERT OR IGNORE INTO tracker_visits (owner_uuid, owner_name, started_at, world) VALUES (?, ?, ?, ?)",
                 (owner, owner_name, visit_time, world))
    visit = conn.execute(
        "SELECT id, world FROM tracker_visits WHERE owner_uuid = ? AND started_at = ?", (owner, visit_time)
    ).fetchone()

    # A visit first sent without a world (an older mod) learns it from a later message.
    if visit["world"] == UNKNOWN_WORLD and world != UNKNOWN_WORLD:
        conn.execute("UPDATE tracker_visits SET world = ? WHERE id = ?", (world, visit["id"]))
    return visit["id"]


@app.post("/tracker/snapshot")
def tracker_snapshot(body: TrackerSnapshotIn, x_api_key: Optional[str] = Header(default=None)):
    """Record the players the mod saw in a lobby.

    Returns which players were new to you and which you had met before.
    """
    account = linked_account(x_api_key)
    owner = account["player_uuid"]

    if len(body.players) > TRACKER_MAX_PLAYERS:
        raise HTTPException(status_code=413, detail="at most %d players per request" % TRACKER_MAX_PLAYERS)

    seen_at = utc_iso(body.timestamp)
    if body.visit_started:
        visit_time = utc_iso(body.visit_started)
    else:
        visit_time = seen_at if body.new_visit else None
    world = clean_world(body.world)

    names, ignored = _clean_usernames(body.players, body.me, account)

    result = {"received": len(body.players), "world": world, "new": [], "returning": [],
              "repeated": 0, "ignored": ignored}
    if not names:
        return result   # nothing to record, and an empty lobby must not create a visit

    with db() as conn:
        visit_id = _tracker_visit(conn, owner, visit_time, seen_at, world, _account_name(conn, owner, account))

        for lower, name in sorted(names.items()):
            player = conn.execute("SELECT id FROM tracker_players WHERE username_lower = ?", (lower,)).fetchone()
            existed = player is not None
            if existed:
                player_id = player["id"]
            else:
                player_id = conn.execute(
                    "INSERT INTO tracker_players (username, username_lower, first_seen, last_seen, times_seen)"
                    " VALUES (?, ?, ?, ?, 0)", (name, lower, seen_at, seen_at)).lastrowid

            added = conn.execute(
                "INSERT OR IGNORE INTO tracker_sightings (player_id, visit_id, seen_at) VALUES (?, ?, ?)",
                (player_id, visit_id, seen_at)).rowcount
            if not added:
                result["repeated"] += 1     # already met in this visit
                continue

            conn.execute(
                "UPDATE tracker_players SET username = ?, times_seen = times_seen + 1,"
                " first_seen = MIN(first_seen, ?), last_seen = MAX(last_seen, ?) WHERE id = ?",
                (name, seen_at, seen_at, player_id))
            result["returning" if existed else "new"].append(name)

    return result


# ------------------------------------------------------------------ player status (Hypixel)
#
# Only ever called when someone clicks a player on the page. The key lives in config.json (or the
# HYPIXEL_API_KEY environment variable) and is only used here, on the server: it is never sent to
# the page, never logged, and never part of any answer.
#
# Hypixel's limit is 300 requests per 5 minutes per key (it says so in the ratelimit-* headers).
# Every answer is cached for STATUS_TTL_SECONDS, a refresh is allowed at most every
# STATUS_MIN_REFRESH_SECONDS, and this server also keeps its own count so it stays well under the limit.

HYPIXEL_STATUS_URL = "https://api.hypixel.net/v2/status"
HYPIXEL_PLAYER_URL = "https://api.hypixel.net/v2/player"
HYPIXEL_PROFILES_URL = "https://api.hypixel.net/v2/skyblock/profiles"
HYPIXEL_MUSEUM_URL = "https://api.hypixel.net/v2/skyblock/museum"
MOJANG_PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/"
STATUS_TTL_SECONDS = 45              # a result is reused for this long
STATUS_MIN_REFRESH_SECONDS = 10      # a Refresh cannot ask Hypixel again sooner than this
STATUS_TIMEOUT_SECONDS = 6
STATUS_BUDGET = 200                  # our own cap on Hypixel calls per STATUS_WINDOW_SECONDS (their limit is 300)
STATUS_WINDOW_SECONDS = 300
UUID_MAX_AGE_DAYS = 7                # a saved UUID is looked up again after this long, in case a name changed hands
NAME_MISS_SECONDS = 60               # a name that does not exist is not looked up again for this long
BAD_KEY_SECONDS = 60                 # after Hypixel refuses the key, do not ask again for this long

# SkyBlock's "mode" values, in the world names the scanner uses (see SkyBlockWorlds.java).
SKYBLOCK_MODES = {
    "hub": "Hub", "dungeon_hub": "Dungeon Hub", "dungeon": "Dungeons", "crystal_hollows": "Crystal Hollows",
    "mining_3": "Dwarven Mines", "combat_3": "The End", "crimson_isle": "Crimson Isle", "combat_1": "Spider's Den",
    "foraging_1": "The Park", "farming_1": "The Farming Islands", "garden": "Garden", "dynamic": "Private Island",
    "rift": "The Rift", "mining_2": "Deep Caverns", "mining_1": "Gold Mine", "combat_2": "Blazing Fortress",
    "winter": "Jerry's Workshop", "foraging_2": "Galatea",
}

GAME_NAMES = {
    "SKYBLOCK": "SkyBlock", "BEDWARS": "Bed Wars", "SKYWARS": "SkyWars", "DUELS": "Duels", "MAIN": "Main lobby",
    "PIT": "The Pit", "BUILD_BATTLE": "Build Battle", "MURDER_MYSTERY": "Murder Mystery", "ARCADE": "Arcade",
    "LEGACY": "Classic games", "HOUSING": "Housing", "TNTGAMES": "TNT Games", "UHC": "UHC", "SPEED_UHC": "Speed UHC",
    "PROTOTYPE": "Prototype", "SURVIVAL_GAMES": "Blitz SG", "WALLS3": "Mega Walls", "MCGO": "Cops and Crims",
    "BATTLEGROUND": "Warlords", "SUPER_SMASH": "Smash Heroes", "GINGERBREAD": "Turbo Kart Racers",
    "WOOL_GAMES": "Wool Games", "REPLAY": "Replay", "SMP": "SMP",
}


class _Unreachable(Exception):
    """The other service did not answer (timeout or no connection)."""


def _download_limits(url: str, timeout: float):
    """(most bytes to read, timeout) for a URL. A status answer is tiny, but a player's Hypixel data can be a few hundred
    kilobytes and a SkyBlock profile several megabytes, and a cut-off answer is not valid JSON."""
    if url.startswith(HYPIXEL_PROFILES_URL):
        return 60_000_000, max(timeout, 20.0)
    if url.startswith(HYPIXEL_MUSEUM_URL):
        return 20_000_000, max(timeout, 15.0)
    if url.startswith(HYPIXEL_PLAYER_URL):
        return 5_000_000, max(timeout, 10.0)
    return 200_000, timeout


def _http_json(url: str, headers: Optional[dict] = None, timeout: float = STATUS_TIMEOUT_SECONDS):
    """GET a URL and return (status code, parsed JSON or None, lower-case headers).

    Raises _Unreachable on a timeout or a network failure. The message is deliberately dropped,
    so nothing about the request (which could hold the key) can end up in a log or an answer.
    """
    request = urllib.request.Request(url, headers={"User-Agent": "FemboyClient/1.0", **(headers or {})})
    limit, timeout = _download_limits(url, timeout)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status, raw, got = response.status, response.read(limit), response.headers
    except urllib.error.HTTPError as error:
        status, raw, got = error.code, error.read(limit), error.headers
    except (urllib.error.URLError, TimeoutError, OSError):
        raise _Unreachable()

    try:
        body = json.loads(raw.decode("utf-8")) if raw else None
    except ValueError:
        body = None
    return status, body, {k.lower(): v for k, v in got.items()}


def _hypixel_key() -> str:
    """The Hypixel API key, from the environment or config.json. Empty if there is none."""
    key = os.environ.get("HYPIXEL_API_KEY", "").strip()
    if not key:
        try:
            key = str(_read_config().get("hypixel_api_key", "")).strip()
        except (OSError, ValueError):
            key = ""
    return key


_status_cache: dict = {}                 # uuid -> {"at": monotonic seconds, "checked": ISO time, "session": dict}
_status_locks: dict = {}                 # uuid -> Lock, so one player is never looked up twice at once
_status_guard = threading.Lock()
_hypixel_calls_lock = threading.Lock()
_hypixel_calls: deque = deque()          # when each Hypixel call was made (monotonic seconds)
_hypixel_blocked_until = 0.0             # do not call Hypixel before this (rate limit or bad key)
_hypixel_block_reason: dict = {}         # the error to give while blocked
_name_misses: dict = {}                  # lower-case name -> when we may look it up again
_uuid_memory: dict = {}                  # lower-case name -> (uuid, name, when), for players not in the database
UUID_MEMORY_SECONDS = 3600
_mojang_blocked_until = 0.0


def _error(code: str, message: str, retry_after: Optional[int] = None) -> dict:
    return {"code": code, "message": message, "retry_after": retry_after}


def _resolve_uuid(name: str):
    """The UUID for a username: (uuid, username as saved, None), or (None, None, error).

    It comes from our own database if it was looked up before (and not too long ago), so a name is
    asked of Mojang once. A name that does not exist is remembered for a minute.
    """
    global _mojang_blocked_until
    lower = name.lower()

    with db() as conn:
        row = conn.execute("SELECT username, uuid, uuid_checked_at FROM tracker_players WHERE username_lower = ?",
                           (lower,)).fetchone()

    saved_uuid = row["uuid"] if row and row["uuid"] else None
    if saved_uuid and row["uuid_checked_at"]:
        try:
            age = datetime.now(timezone.utc) - datetime.fromisoformat(row["uuid_checked_at"].replace("Z", "+00:00"))
            if age.days < UUID_MAX_AGE_DAYS:
                return saved_uuid, row["username"], None
        except ValueError:
            pass

    now = time.monotonic()
    remembered = _uuid_memory.get(lower)
    if remembered and not row and now - remembered[2] < UUID_MEMORY_SECONDS:
        return remembered[0], remembered[1], None       # not in the database, so it was kept in memory
    if _name_misses.get(lower, 0) > now:
        return None, None, _error("not_found", "No Minecraft account has that username.")
    if now < _mojang_blocked_until:
        wait = max(1, math.ceil(_mojang_blocked_until - now))
        return None, None, _error("rate_limited", "Mojang is busy. Try again in %d seconds." % wait, wait)

    try:
        status, body, _ = _http_json(MOJANG_PROFILE_URL + urllib.parse.quote(name))
    except _Unreachable:
        if saved_uuid:
            return saved_uuid, row["username"], None      # an old UUID is better than none
        return None, None, _error("timeout", "Could not reach Mojang to find that player.")

    if status == 200 and isinstance(body, dict) and re.fullmatch(r"[0-9a-fA-F]{32}", str(body.get("id", ""))):
        uuid = str(uuidlib.UUID(body["id"]))
        with db() as conn:
            conn.execute("UPDATE tracker_players SET uuid = ?, uuid_checked_at = ? WHERE username_lower = ?",
                         (uuid, datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z"), lower))
        shown = row["username"] if row else str(body.get("name") or name)
        if not row:
            _uuid_memory[lower] = (uuid, shown, now)
        return uuid, shown, None

    if status in (204, 400, 404):
        _name_misses[lower] = now + NAME_MISS_SECONDS
        return None, None, _error("not_found", "No Minecraft account has that username.")
    if status == 429:
        _mojang_blocked_until = now + 30
        return None, None, _error("rate_limited", "Mojang is busy. Try again in 30 seconds.", 30)
    return None, None, _error("unavailable", "Mojang could not look that player up (HTTP %d)." % status)


def _hypixel_call(url: str, uuid: str, param: str = "uuid"):
    """Ask one Hypixel endpoint about one player: (answer dict, None) or (None, error). Never raises.

    The status and player lookups both go through here, so they share the key, the rate limit and the call count.
    """
    global _hypixel_blocked_until, _hypixel_block_reason
    key = _hypixel_key()
    if not key:
        return None, _error("no_key", "The website has no Hypixel API key set.")

    now = time.monotonic()
    if now < _hypixel_blocked_until:
        wait = max(1, math.ceil(_hypixel_blocked_until - now))
        reason = dict(_hypixel_block_reason or _error("rate_limited", "Rate limited."))
        if reason["code"] == "rate_limited":
            reason.update(message="Rate limited by Hypixel. Try again in %d seconds." % wait, retry_after=wait)
        return None, reason

    with _hypixel_calls_lock:                           # sync routes run on many threads at once
        while _hypixel_calls and now - _hypixel_calls[0] > STATUS_WINDOW_SECONDS:
            _hypixel_calls.popleft()
        if len(_hypixel_calls) >= STATUS_BUDGET:
            wait = max(1, math.ceil(STATUS_WINDOW_SECONDS - (now - _hypixel_calls[0])))
            return None, _error("rate_limited", "Too many lookups just now. Try again in %d seconds." % wait, wait)
        _hypixel_calls.append(now)
    try:
        status, body, headers = _http_json(url + "?" + param + "=" + urllib.parse.quote(uuid), {"API-Key": key})
    except _Unreachable:
        return None, _error("timeout", "Hypixel took too long to answer.")

    # Hypixel says how many calls are left. Stop early rather than run into the limit.
    try:
        if int(headers.get("ratelimit-remaining", "99")) <= 3:
            _hypixel_blocked_until = time.monotonic() + max(1, int(headers.get("ratelimit-reset", "30")))
            _hypixel_block_reason = _error("rate_limited", "Rate limited by Hypixel.")
    except ValueError:
        pass

    if status == 200 and isinstance(body, dict) and body.get("success"):
        return body, None
    if status == 403:
        _hypixel_blocked_until = time.monotonic() + BAD_KEY_SECONDS
        _hypixel_block_reason = _error("invalid_key", "Hypixel refused the website's API key.")
        return None, dict(_hypixel_block_reason)
    if status == 429:
        try:
            wait = max(1, int(headers.get("retry-after") or headers.get("ratelimit-reset") or 30))
        except ValueError:
            wait = 30
        _hypixel_blocked_until = time.monotonic() + wait
        _hypixel_block_reason = _error("rate_limited", "Rate limited by Hypixel.")
        return None, _error("rate_limited", "Rate limited by Hypixel. Try again in %d seconds." % wait, wait)
    return None, _error("unavailable", "Hypixel could not answer (HTTP %d)." % status)


def _fetch_session(uuid: str):
    """Ask Hypixel whether one player is online: (session dict, None) or (None, error)."""
    body, error = _hypixel_call(HYPIXEL_STATUS_URL, uuid)
    if error:
        return None, error
    session = body.get("session")
    return (session if isinstance(session, dict) else {}), None


def _friendly_game(game_type: Optional[str]) -> Optional[str]:
    if not game_type:
        return None
    return GAME_NAMES.get(game_type, str(game_type).replace("_", " ").title())


def _status_reply(name: str, *, session: Optional[dict] = None, checked: Optional[str] = None, age: float = 0.0,
                  cached: bool = False, stale: bool = False, error: Optional[dict] = None) -> dict:
    """The answer to the page. Never holds the API key or the player's UUID."""
    reply = {"name": name, "state": "error" if session is None else "unknown", "online": None, "game": None,
             "game_type": None, "mode": None, "map": None, "world": None, "skyblock": False,
             "checked_at": checked, "age_seconds": int(age), "cached": cached, "stale": stale,
             "refresh_in": max(0, math.ceil(STATUS_MIN_REFRESH_SECONDS - age)) if session is not None else 0,
             "error": error}
    if session is None:
        return reply

    online = bool(session.get("online"))
    game_type, mode = session.get("gameType"), session.get("mode")
    reply.update(
        # Hypixel reports a player who hides their status as offline, so "offline" can only ever
        # mean "offline or hidden". It is never a definite no.
        state="online" if online else "offline_or_hidden", online=online,
        game=_friendly_game(game_type) if online else None,
        game_type=game_type if online else None, mode=mode if online else None,
        map=session.get("map") if online else None,
        skyblock=online and game_type == "SKYBLOCK")
    if reply["skyblock"] and mode:
        reply["world"] = SKYBLOCK_MODES.get(str(mode).lower())
    return reply


@app.get("/tracker/status")
def tracker_status(name: str = Query(min_length=1, max_length=32), refresh: bool = False):
    """Whether a player is online on Hypixel right now, in which game, and (in SkyBlock) which world.

    Answers 200 with state "online" or "offline_or_hidden", or state "error" and an error with a
    short code (not_found, invalid_key, timeout, rate_limited, no_key, unavailable) and message.
    Results are cached for STATUS_TTL_SECONDS. refresh=true asks again, but not sooner than
    STATUS_MIN_REFRESH_SECONDS after the last look. The page only calls this when you click a player.
    """
    name = name.strip()
    if not _USERNAME.fullmatch(name):
        raise HTTPException(status_code=422, detail="that is not a valid Minecraft username")

    uuid, saved_name, error = _resolve_uuid(name)
    if error:
        return _status_reply(name, error=error)

    with _status_guard:
        lock = _status_locks.setdefault(uuid, threading.Lock())

    with lock:          # two clicks at once share one lookup
        cached = _status_cache.get(uuid)
        now = time.monotonic()
        if cached:
            age = now - cached["at"]
            if age < STATUS_TTL_SECONDS and (not refresh or age < STATUS_MIN_REFRESH_SECONDS):
                return _status_reply(saved_name, session=cached["session"], checked=cached["checked"], age=age, cached=True)

        session, error = _fetch_session(uuid)
        if session is not None:
            checked = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
            _status_cache[uuid] = {"at": time.monotonic(), "checked": checked, "session": session}
            return _status_reply(saved_name, session=session, checked=checked, age=0.0)

        if cached:      # the lookup failed, but an older answer is better than none
            return _status_reply(saved_name, session=cached["session"], checked=cached["checked"],
                                 age=now - cached["at"], cached=True, stale=True, error=error)
        return _status_reply(saved_name, error=error)


# ------------------------------------------------------------------ linked Discord
#
# A player can link a Discord name to their Hypixel account. SkyCrypt shows it from the same place: Hypixel's
# player data (socialMedia.links.DISCORD). SkyCrypt's own API refuses scripts, so this asks Hypixel directly with
# the same key and the same limits as the status lookup. It is only asked when you click a player, kept in
# memory for DISCORD_TTL_SECONDS and never written to the database.

DISCORD_TTL_SECONDS = 3600
DISCORD_MAX_LENGTH = 64
_discord_cache: dict = {}                # uuid -> {"at": monotonic seconds, "checked": ISO time, "discord": str or None, "rank": str}
_discord_locks: dict = {}


def _linked_discord(player) -> Optional[str]:
    """The Discord name in Hypixel's player data, tidied, or None if there is none."""
    media = player.get("socialMedia") if isinstance(player, dict) else None
    if not isinstance(media, dict):
        return None
    links = media.get("links")
    found = (links.get("DISCORD") if isinstance(links, dict) else None) or media.get("DISCORD")
    if not isinstance(found, str):
        return None
    tidy = "".join(c for c in found if c.isprintable()).strip()
    return tidy[:DISCORD_MAX_LENGTH] or None


STAFF_RANKS = {"ADMIN", "MODERATOR", "HELPER", "YOUTUBER"}
PACKAGE_RANKS = {"MVP_PLUS": "MVP+", "MVP": "MVP", "VIP_PLUS": "VIP+", "VIP": "VIP"}


def _hypixel_rank(player) -> str:
    """The rank shown before a player's name, worked out from Hypixel's player data in this order:
    staff and YouTube rank, then MVP++ (a SUPERSTAR monthly rank), then the package rank, otherwise "none".
    """
    if not isinstance(player, dict):
        return "none"
    staff = str(player.get("rank") or "").upper()
    if staff in STAFF_RANKS:
        return staff
    if player.get("monthlyPackageRank") == "SUPERSTAR":
        return "MVP++"
    return PACKAGE_RANKS.get(str(player.get("newPackageRank") or ""), "none")


def _discord_reply(name: str, *, discord: Optional[str] = None, checked: Optional[str] = None, age: float = 0.0,
                   cached: bool = False, error: Optional[dict] = None, rank: Optional[str] = None) -> dict:
    """The answer to the page. Never holds the API key or the player's UUID. rank is None when it is not known."""
    return {"name": name, "state": "error" if error else ("linked" if discord else "none"), "discord": discord,
            "rank": rank, "checked_at": checked, "age_seconds": int(age), "cached": cached, "error": error}


@app.get("/tracker/discord")
def tracker_discord(name: str = Query(min_length=1, max_length=32)):
    """The Discord a player has linked on Hypixel: state "linked" (with discord), "none" or "error".

    "none" also covers a player who has not linked one. The page only calls this when you click a player.
    """
    name = name.strip()
    if not _USERNAME.fullmatch(name):
        raise HTTPException(status_code=422, detail="that is not a valid Minecraft username")

    uuid, saved_name, error = _resolve_uuid(name)
    if error:
        return _discord_reply(name, error=error)
    return _discord_answer(uuid, saved_name)


def _discord_answer(uuid: str, saved_name: str) -> dict:
    """The Discord answer for a known UUID, from the cache or from Hypixel."""
    with _status_guard:
        lock = _discord_locks.setdefault(uuid, threading.Lock())

    with lock:          # two clicks at once share one lookup
        cached = _discord_cache.get(uuid)
        now = time.monotonic()
        if cached and now - cached["at"] < DISCORD_TTL_SECONDS:
            return _discord_reply(saved_name, discord=cached["discord"], checked=cached["checked"],
                                  age=now - cached["at"], cached=True, rank=cached["rank"])

        body, error = _hypixel_call(HYPIXEL_PLAYER_URL, uuid)
        if error:
            if cached:      # an older answer is better than none
                return _discord_reply(saved_name, discord=cached["discord"], checked=cached["checked"],
                                      age=now - cached["at"], cached=True, rank=cached["rank"])
            return _discord_reply(saved_name, error=error)

        discord = _linked_discord(body.get("player"))
        rank = _hypixel_rank(body.get("player"))
        checked = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
        _discord_cache[uuid] = {"at": time.monotonic(), "checked": checked, "discord": discord, "rank": rank}
        return _discord_reply(saved_name, discord=discord, checked=checked, rank=rank)


# ------------------------------------------------------------------ SkyBlock networth
#
# Hypixel has no networth endpoint. The website downloads the selected SkyBlock profile (and the museum) with its own key
# and hands the data to the SkyHelper-Networth library (MIT, npm), which server/networth/networth.js wraps. The key never
# leaves this process. The result is kept for NETWORTH_TTL_SECONDS. It is only worked out when you click a player.

NETWORTH_TTL_SECONDS = 300
NETWORTH_TIMEOUT_SECONDS = 25
NETWORTH_SCRIPT = BASE / "networth" / "networth.js"
_networth_results: dict = {}             # uuid -> {"at": monotonic seconds, "value": int or None, "reason": str or None}
_networth_locks: dict = {}


def _node_path() -> str:
    """The node program: node_path in config.json, else the one on PATH."""
    return str(_read_config().get("node_path") or shutil.which("node") or "node")


def _compute_networth(uuid: str):
    """(networth, None) or (None, reason). Reasons: api_off, no_profile, or a short error text. Never raises."""
    plain = uuid.replace("-", "")
    body, error = _hypixel_call(HYPIXEL_PROFILES_URL, uuid)
    if error:
        return None, error["message"]
    profiles = [p for p in (body.get("profiles") or []) if isinstance(p, dict)]
    if not profiles:
        return None, "no_profile"

    # The profile the player has selected in game, otherwise the one saved most recently.
    profile = next((p for p in profiles if p.get("selected")), None) or max(
        profiles, key=lambda p: (p.get("members", {}).get(plain, {}) or {}).get("last_save", 0) if isinstance(p.get("members"), dict) else 0)
    members = profile.get("members")
    member = members.get(plain) if isinstance(members, dict) else None
    if not isinstance(member, dict):
        return None, "no_profile"
    if not isinstance(member.get("inventory"), dict):
        return None, "api_off"            # a player who switched their inventory API off has no inventory data

    bank = ((profile.get("banking") or {}).get("balance", 0)) if isinstance(profile.get("banking"), dict) else 0
    museum = None
    if profile.get("profile_id"):
        museum_body, _ = _hypixel_call(HYPIXEL_MUSEUM_URL, str(profile["profile_id"]), param="profile")
        if museum_body and isinstance(museum_body.get("members"), dict):
            museum = museum_body["members"].get(plain)     # a missing museum only makes the number a little lower

    try:
        done = subprocess.run([_node_path(), str(NETWORTH_SCRIPT)], cwd=str(NETWORTH_SCRIPT.parent),
                              input=json.dumps({"member": member, "museum": museum, "bank": bank}),
                              capture_output=True, text=True, timeout=NETWORTH_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        return None, "the networth calculation took too long"
    except OSError:
        return None, "node is not installed on the website's PC"

    try:
        result = json.loads(done.stdout)
    except ValueError:
        return None, "the networth calculator failed"
    if isinstance(result, dict) and isinstance(result.get("networth"), (int, float)):
        return int(result["networth"]), None
    return None, str((result or {}).get("error") or "the networth calculator failed")[:120]


def _networth_for(uuid: str):
    """(networth or None, reason or None) for a player, from the 5 minute cache or a fresh calculation."""
    with _status_guard:
        lock = _networth_locks.setdefault(uuid, threading.Lock())

    with lock:          # two clicks at once share one calculation
        kept = _networth_results.get(uuid)
        now = time.monotonic()
        if kept and now - kept["at"] < NETWORTH_TTL_SECONDS:
            return kept["value"], kept["reason"]

        value, reason = _compute_networth(uuid)
        if value is not None or reason in ("api_off", "no_profile"):      # steady answers are kept; errors are retried next click
            _networth_results[uuid] = {"at": time.monotonic(), "value": value, "reason": reason}
        return value, reason


# ------------------------------------------------------------------ one player, for the in-game screen

@app.get("/api/player/{player_uuid}")
def api_player(player_uuid: str, name: Optional[str] = Query(default=None, max_length=32),
               x_api_key: Optional[str] = Header(default=None)):
    """Everything the in-game screen shows about one player, for the account that owns the API key.

    Returns found (is the player in your database), timesMet, chatLogs (newest first), discord
    (null if none is linked, with discordError set if the lookup failed), rank (ADMIN, MODERATOR, HELPER, YOUTUBER,
    MVP++, MVP+, MVP, VIP+, VIP, "none", or null if unknown) and networth (SkyBlock networth in coins; null with a
    networthNote of "api_off", "no_profile" or an error text). The player is matched by
    UUID, or by the optional name if the database has not stored their UUID yet.
    """
    owner = linked_account(x_api_key)["player_uuid"]
    uuid = normalise_uuid(player_uuid)
    name = (name or "").strip()
    if name and not _USERNAME.fullmatch(name):
        raise HTTPException(status_code=422, detail="that is not a valid Minecraft username")

    with db() as conn:
        row = conn.execute("SELECT id, username FROM tracker_players WHERE uuid = ?", (uuid,)).fetchone()
        if not row and name:
            row = conn.execute("SELECT id, username FROM tracker_players WHERE username_lower = ?", (name.lower(),)).fetchone()

        times_met, chat_logs = 0, []
        if row:
            times_met = conn.execute(
                "SELECT COUNT(*) FROM tracker_sightings s JOIN tracker_visits v ON v.id = s.visit_id"
                " WHERE s.player_id = ? AND v.owner_uuid = ?", (row["id"], owner)).fetchone()[0]
            chat_logs = [dict(r) for r in conn.execute(
                "SELECT said_at AS at, channel, world, message FROM tracker_chats WHERE player_id = ? AND owner_uuid = ?"
                " ORDER BY said_at DESC, id DESC LIMIT ?", (row["id"], owner, SEARCH_CHAT_LIMIT)).fetchall()]

    discord = _discord_answer(uuid, row["username"] if row else (name or uuid))
    networth, networth_reason = _networth_for(uuid)
    return {"found": row is not None, "name": row["username"] if row else (name or None),
            "discord": discord["discord"], "discordError": discord["error"]["message"] if discord["error"] else None,
            "rank": discord["rank"], "networth": networth, "networthNote": networth_reason,
            "timesMet": times_met, "chatLogs": chat_logs}


# ------------------------------------------------------------------ player finder
#
# The Player Finder tab tells the mod to hop lobbies until a player is in the tab list. The website
# only holds the state and passes it on: the mod asks GET /finder/command every couple of seconds and
# reports back with POST /finder/report. The website never sends the mod a command to run. It sends a
# target name and a world name, and the mod turns the world into a fixed /warp command from its own table.
#
# Starting and stopping is only allowed from the PC the website runs on, like Clear data, because it
# makes the game type commands. State is kept in memory: a restart of the website forgets a search.

# World name -> the /warp name. The mod has the same table in FinderWarps.java (a test keeps them equal).
# Every name is in SkyHanni's warp list (constants/Warps.json in hannibal002/SkyHanni-REPO); not tested in this game by me.
FINDER_WARPS = {
    "Hub": "hub", "Dungeon Hub": "dhub", "Spider's Den": "spider", "The End": "end", "The Park": "park",
    "The Farming Islands": "barn", "Crimson Isle": "nether", "Deep Caverns": "deep", "Gold Mine": "gold",
    "Dwarven Mines": "mines", "Garden": "garden", "Galatea": "galatea",
}
FINDER_MAX_HOPS = 30
FINDER_LOST_SECONDS = 30      # a search whose game has not asked for 30 seconds is ended

FINDER_REASONS = {
    "limit": "attempt limit (%d hops)" % FINDER_MAX_HOPS,
    "toggle_off": "toggle off",
    "disconnected": "disconnected",
    "not_in_skyblock": "not in SkyBlock",
    "website_unreachable": "the game lost contact with the website",
    "game_not_responding": "the game is not responding",
    "no_warp": "no warp command for that world",
    "warp_failed": "the warp did not take you anywhere (is it unlocked?)",
    "bad_target": "not a valid username",
    "error": "the mod hit an error",
}

_finder: dict = {}
_finder_lock = threading.Lock()


def _finder_new() -> dict:
    return {"active": False, "run": 0, "target": None, "world": None, "hops": 0, "started": None,
            "last_poll": None, "result": None}


def _finder_end(state: dict, outcome: str, reason: Optional[str], hops: int, world: Optional[str] = None,
                elapsed: Optional[int] = None) -> None:
    """Ends the search and keeps the result, which stays until the next search starts."""
    state["active"] = False
    state["result"] = {
        "outcome": outcome, "target": state["target"], "world": world or state["world"], "hops": hops,
        "elapsed_seconds": elapsed if elapsed is not None else
        (int(time.time() - state["started"]) if state["started"] else 0),
        "reason": reason, "reason_text": FINDER_REASONS.get(reason or "", reason),
        "finished_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z"),
    }
    state["hops"] = hops


def _finder_view(state: dict) -> dict:
    """What the page is told. The game asking for its command is how we know it is alive."""
    now = time.time()
    if state["active"] and now - max(state["started"] or 0, state["last_poll"] or 0) > FINDER_LOST_SECONDS:
        _finder_end(state, "not_found", "game_not_responding", state["hops"])

    waiting = bool(state["active"] and (state["last_poll"] is None or state["last_poll"] < state["started"]))
    return {
        "active": state["active"], "target": state["target"], "world": state["world"], "run": state["run"],
        "hops": state["hops"], "max_hops": FINDER_MAX_HOPS, "waiting_for_game": waiting,
        "elapsed_seconds": int(now - state["started"]) if state["active"] and state["started"] else 0,
        "warp": ("/warp " + FINDER_WARPS[state["world"]]) if state["world"] in FINDER_WARPS else None,
        "result": state["result"],
    }


class FinderStartIn(BaseModel):
    owner: str
    target: str
    world: str


class FinderStopIn(BaseModel):
    owner: str


class FinderReportIn(BaseModel):
    target: str = ""
    run: int = 0
    status: str
    hops: int = Field(default=0, ge=0, le=1000)
    world: Optional[str] = None
    elapsedSeconds: Optional[int] = Field(default=None, ge=0, le=86400)
    reason: Optional[str] = Field(default=None, max_length=40)


@app.get("/finder/worlds")
def finder_worlds():
    """The worlds the finder can hop to, with the warp command for each."""
    return {"worlds": {world: "/warp " + name for world, name in FINDER_WARPS.items()}, "max_hops": FINDER_MAX_HOPS}


@app.post("/finder/start")
def finder_start(body: FinderStartIn, request: Request):
    """Start a search: hop lobbies in one world until this player shows up in the tab list."""
    if not _is_local(request):
        raise HTTPException(status_code=403, detail="the finder can only be started from the PC running the website")

    owner = normalise_uuid(body.owner)
    target = body.target.strip()
    if not _USERNAME.fullmatch(target):
        raise HTTPException(status_code=422, detail="that is not a valid Minecraft username")
    if body.world not in FINDER_WARPS:
        raise HTTPException(status_code=422, detail="there is no warp command for that world")

    with _finder_lock:
        state = _finder.setdefault(owner, _finder_new())
        state.update(active=True, run=int(time.time() * 1000), target=target, world=body.world, hops=0,
                     started=time.time(), result=None)      # a new search clears the last result
        return _finder_view(state)


@app.post("/finder/stop")
def finder_stop(body: FinderStopIn, request: Request):
    """Turn the search off. The mod sees the target disappear and stops on its next check."""
    if not _is_local(request):
        raise HTTPException(status_code=403, detail="the finder can only be stopped from the PC running the website")

    owner = normalise_uuid(body.owner)
    with _finder_lock:
        state = _finder.setdefault(owner, _finder_new())
        if state["active"]:
            _finder_end(state, "not_found", "toggle_off", state["hops"])
        return _finder_view(state)


@app.get("/finder/state")
def finder_state(owner: str):
    """The search as the page shows it: hops so far, whether it is running, and the last result."""
    owner = normalise_uuid(owner)
    with _finder_lock:
        return _finder_view(_finder.setdefault(owner, _finder_new()))


@app.get("/finder/command")
def finder_command(x_api_key: Optional[str] = Header(default=None)):
    """What the mod should be doing: {target, world, run}, or {target: null} for nothing."""
    owner = linked_account(x_api_key)["player_uuid"]

    with _finder_lock:
        state = _finder.setdefault(owner, _finder_new())
        state["last_poll"] = time.time()
        _finder_view(state)                      # ends a search that has gone stale
        if not state["active"]:
            return {"target": None}
        return {"target": state["target"], "world": state["world"], "run": state["run"]}


@app.post("/finder/report")
def finder_report(body: FinderReportIn, x_api_key: Optional[str] = Header(default=None)):
    """The mod says how the search is going: searching, found, not_found or stopped."""
    account = linked_account(x_api_key)
    owner = account["player_uuid"]
    if body.status not in ("searching", "found", "not_found", "stopped"):
        raise HTTPException(status_code=422, detail="unknown status")

    with _finder_lock:
        state = _finder.setdefault(owner, _finder_new())
        state["last_poll"] = time.time()

        if body.run != state["run"]:
            return {"ok": False, "ignored": "an older search"}      # a late message from a search that was replaced

        if not state["active"]:
            # The page already ended it (toggle off). A player found in that same moment still counts as found.
            if body.status == "found" and state["result"] and state["result"]["reason"] == "toggle_off":
                world = clean_world(body.world) if body.world else state["world"]
                _finder_end(state, "found", None, max(body.hops, state["hops"]), world, body.elapsedSeconds)
                return {"ok": True}
            # Otherwise keep the hop count if the mod counted more.
            if state["result"] and body.hops > state["result"]["hops"]:
                state["result"]["hops"] = state["hops"] = body.hops
            return {"ok": True, "ignored": "already ended"}

        state["hops"] = max(state["hops"], body.hops)
        if body.status == "searching":
            return {"ok": True}

        if body.status == "found":
            world = clean_world(body.world) if body.world else state["world"]
            _finder_end(state, "found", None, body.hops, world, body.elapsedSeconds)
        else:
            reason = body.reason or ("toggle_off" if body.status == "stopped" else None)
            _finder_end(state, "not_found", reason, body.hops, None, body.elapsedSeconds)
        return {"ok": True}


def _own_name(conn, owner: str) -> str:
    """The lower-case name of an account. It is never listed as one of its own party members."""
    row = conn.execute("SELECT player_name FROM players WHERE player_uuid = ?", (owner,)).fetchone()
    return (row["player_name"] if row else "").strip().lower()


class TrackerPartyIn(BaseModel):
    """What the mod sends when players are confirmed to be in your party."""

    me: str = ""
    players: List[str]
    timestamp: str


@app.post("/tracker/party")
def tracker_party(body: TrackerPartyIn, x_api_key: Optional[str] = Header(default=None)):
    """Record players who are in your party. Each one is added to the player list if new.

    Returns which of them you had never partied with (first_time) and which you had (again).
    """
    account = linked_account(x_api_key)
    owner = account["player_uuid"]

    if len(body.players) > TRACKER_MAX_PLAYERS:
        raise HTTPException(status_code=413, detail="at most %d players per request" % TRACKER_MAX_PLAYERS)

    joined_at = utc_iso(body.timestamp)
    names, ignored = _clean_usernames(body.players, body.me, account)
    result = {"received": len(body.players), "first_time": [], "again": [], "repeated": 0, "ignored": ignored}

    with db() as conn:
        owner_name = _account_name(conn, owner, account)
        for lower, name in sorted(names.items()):
            player = conn.execute("SELECT id FROM tracker_players WHERE username_lower = ?", (lower,)).fetchone()
            if player:
                player_id = player["id"]
            else:
                # Someone met only in a party is still a player the website knows about.
                player_id = conn.execute(
                    "INSERT INTO tracker_players (username, username_lower, first_seen, last_seen, times_seen)"
                    " VALUES (?, ?, ?, ?, 0)", (name, lower, joined_at, joined_at)).lastrowid

            before = conn.execute("SELECT COUNT(*) FROM tracker_parties WHERE player_id = ?", (player_id,)).fetchone()[0]
            added = conn.execute(
                "INSERT OR IGNORE INTO tracker_parties (owner_uuid, owner_name, player_id, joined_at) VALUES (?, ?, ?, ?)",
                (owner, owner_name, player_id, joined_at)).rowcount
            if not added:
                result["repeated"] += 1
                continue

            conn.execute("UPDATE tracker_players SET username = ? WHERE id = ?", (name, player_id))
            result["again" if before else "first_time"].append(name)

    return result


# ------------------------------------------------------------------ frienemies
#
# A friendly or enemy tag on a player, with a free-text note on why. Shared across every account, like
# the rest of the tracker database, and open like /tracker/search (no X-API-Key: nothing here is scoped
# to one account). The website's Frienemys tab manages the full list; the mod's Frienemys module shows
# any tagged player who is in your current lobby.

FRIENEMY_MAX_NOTES = 500     # longest note kept, in characters


class FrienemyIn(BaseModel):
    """Tag a player friendly or enemy. uuid is optional (the mod knows it from the tab list; the website
    usually does not); when given it is used to find or link the player instead of just their name."""

    name: str
    uuid: Optional[str] = None
    tag: str
    notes: str = ""


def _frienemy_dict(row: sqlite3.Row) -> dict:
    # "id" is the tracker_players id (same id /tracker/search uses), not the tracker_frienemies row's own
    # id, so the mod and website can key off the one player id everywhere, including for DELETE.
    return {"id": row["player_id"], "uuid": row["uuid"], "name": row["username"], "tag": row["tag"],
            "notes": row["notes"], "updated_at": row["updated_at"]}


_FRIENEMY_SELECT = ("SELECT f.player_id, f.tag, f.notes, f.updated_at, p.username, p.uuid"
                     " FROM tracker_frienemies f JOIN tracker_players p ON p.id = f.player_id")


@app.get("/tracker/frienemies")
def tracker_frienemies_list():
    """Every player tagged friendly or enemy, most recently tagged first."""
    with db() as conn:
        rows = conn.execute(_FRIENEMY_SELECT + " ORDER BY f.updated_at DESC, f.id DESC").fetchall()
    return [_frienemy_dict(r) for r in rows]


@app.post("/tracker/frienemies")
def tracker_frienemies_set(body: FrienemyIn):
    """Tags a player friendly or enemy, replacing any tag and note they already had. Adds them to the
    player database first if they are not in it yet."""
    name = body.name.strip()
    if not _USERNAME.fullmatch(name):
        raise HTTPException(status_code=422, detail="that is not a valid Minecraft username")
    tag = body.tag.strip().lower()
    if tag not in ("friendly", "enemy"):
        raise HTTPException(status_code=422, detail="tag must be 'friendly' or 'enemy'")
    notes = body.notes.strip()
    if len(notes) > FRIENEMY_MAX_NOTES:
        raise HTTPException(status_code=422, detail="notes are too long (max %d characters)" % FRIENEMY_MAX_NOTES)
    uuid = normalise_uuid(body.uuid) if body.uuid else None
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    lower = name.lower()

    with db() as conn:
        row = conn.execute("SELECT id FROM tracker_players WHERE uuid = ?", (uuid,)).fetchone() if uuid else None
        if not row:
            row = conn.execute("SELECT id FROM tracker_players WHERE username_lower = ?", (lower,)).fetchone()

        if row:
            player_id = row["id"]
            if uuid:
                conn.execute("UPDATE tracker_players SET username = ?, uuid = ? WHERE id = ?", (name, uuid, player_id))
            else:
                conn.execute("UPDATE tracker_players SET username = ? WHERE id = ?", (name, player_id))
        else:
            player_id = conn.execute(
                "INSERT INTO tracker_players (username, username_lower, first_seen, last_seen, times_seen, uuid)"
                " VALUES (?, ?, ?, ?, 0, ?)", (name, lower, now, now, uuid)).lastrowid

        conn.execute(
            "INSERT INTO tracker_frienemies (player_id, tag, notes, updated_at) VALUES (?, ?, ?, ?)"
            " ON CONFLICT(player_id) DO UPDATE SET tag = excluded.tag, notes = excluded.notes, updated_at = excluded.updated_at",
            (player_id, tag, notes, now))

        saved = conn.execute(_FRIENEMY_SELECT + " WHERE f.player_id = ?", (player_id,)).fetchone()

    return _frienemy_dict(saved)


@app.delete("/tracker/frienemies/{player_id}")
def tracker_frienemies_delete(player_id: int):
    """Removes a player's friendly/enemy tag and note. The player stays in the database."""
    with db() as conn:
        removed = conn.execute("DELETE FROM tracker_frienemies WHERE player_id = ?", (player_id,)).rowcount
    if not removed:
        raise HTTPException(status_code=404, detail="no tag for that player")
    return {"deleted": True}


# The chat types the mod sends and the Chat Logs tab filters by, and the label each one is stored as in the channel column.
# The column has always held these labels (old rows already say Public, Party, ...), so no new column is needed. "Private"
# is what was always stored for a message someone sent you, so it reads as PRIVATE_FROM.
CHAT_TYPES = {"PUBLIC": "Public", "PARTY": "Party", "GUILD": "Guild", "COOP": "Co-op",
              "PRIVATE_FROM": "Private", "PRIVATE_TO": "Private To"}
CHAT_TYPE_OF_LABEL = {label: chat_type for chat_type, label in CHAT_TYPES.items()}
# What older versions of the mod sent in "channel".
CHAT_CHANNELS = {"public": "Public", "party": "Party", "guild": "Guild", "co-op": "Co-op", "private": "Private",
                 "private to": "Private To"}
CHAT_MAX_TEXT = 300           # longest message kept, in characters
TRACKER_MAX_CHATS = 200       # messages in one request


def _tidy_chat(text: str) -> str:
    """A chat message as it is stored: no control characters, single spaces, cut to CHAT_MAX_TEXT."""
    words = "".join(" " if (c.isspace() or ord(c) < 32 or ord(c) == 127) else c for c in text).split()
    clean = " ".join(words)
    return clean if len(clean) <= CHAT_MAX_TEXT else clean[:CHAT_MAX_TEXT - 1] + "\u2026"


class ChatMessageIn(BaseModel):
    """One thing a player said. type is PUBLIC, PARTY, GUILD, COOP, PRIVATE_FROM or PRIVATE_TO; a missing type is PUBLIC.
    (channel is what older versions of the mod sent instead.) For PRIVATE_TO, player is who the message was sent to."""

    player: str
    type: Optional[str] = None
    channel: str = "Public"
    text: str
    timestamp: str


def _chat_label(message: ChatMessageIn) -> str:
    """The label stored in the channel column for a message. An unknown type refuses the request."""
    if message.type is not None and message.type.strip():
        label = CHAT_TYPES.get(message.type.strip().upper())
        if label is None:
            raise HTTPException(status_code=422, detail="unknown chat type: " + repr(message.type[:30]))
        return label
    return CHAT_CHANNELS.get(message.channel.strip().lower(), "Public")


class TrackerChatIn(BaseModel):
    """What the mod sends when players talk in chat."""

    me: str = ""
    world: Optional[str] = None
    messages: List[ChatMessageIn]


@app.post("/tracker/chat")
def tracker_chat(body: TrackerChatIn, x_api_key: Optional[str] = Header(default=None)):
    """Record what players said. The sender is added to the player list if new.

    Only usernames, the tidied text, the channel, the world and the time are kept. What the account
    owner said goes in their own chat list (shown on their node) instead of the player list.
    """
    account = linked_account(x_api_key)
    owner = account["player_uuid"]

    if len(body.messages) > TRACKER_MAX_CHATS:
        raise HTTPException(status_code=413, detail="at most %d messages per request" % TRACKER_MAX_CHATS)

    own = {body.me.strip().lower(), str(account.get("name", "")).strip().lower()} - {""}
    world = clean_world(body.world)
    result = {"received": len(body.messages), "saved": 0, "repeated": 0, "ignored": 0}

    # Check every timestamp and type before writing anything, so a bad one refuses the whole request.
    parsed = [(m, utc_iso(m.timestamp), _chat_label(m)) for m in body.messages]

    with db() as conn:
        owner_name = _account_name(conn, owner, account)
        known = conn.execute("SELECT world FROM tracker_visits WHERE lower(world) = lower(?) LIMIT 1", (world,)).fetchone()
        if known:
            world = known["world"]

        for m, said_at, label in parsed:
            name = m.player.strip()
            text = _tidy_chat(m.text)
            if not _USERNAME.fullmatch(name) or not text:
                result["ignored"] += 1
                continue

            if name.lower() in own:          # the account owner: kept for their own node, never added as a player
                added = conn.execute(
                    "INSERT OR IGNORE INTO tracker_my_chats (owner_uuid, owner_name, said_at, channel, world, message)"
                    " VALUES (?, ?, ?, ?, ?, ?)",
                    (owner, owner_name, said_at, label, world, text)).rowcount
                result["saved" if added else "repeated"] += 1
                continue

            player = conn.execute("SELECT id FROM tracker_players WHERE username_lower = ?", (name.lower(),)).fetchone()
            if player:
                player_id = player["id"]
            else:
                # Someone heard only in chat is still a player the website knows about.
                player_id = conn.execute(
                    "INSERT INTO tracker_players (username, username_lower, first_seen, last_seen, times_seen)"
                    " VALUES (?, ?, ?, ?, 0)", (name, name.lower(), said_at, said_at)).lastrowid

            added = conn.execute(
                "INSERT OR IGNORE INTO tracker_chats (owner_uuid, owner_name, player_id, said_at, channel, world, message)"
                " VALUES (?, ?, ?, ?, ?, ?, ?)",
                (owner, owner_name, player_id, said_at, label, world, text)).rowcount
            result["saved" if added else "repeated"] += 1

    return result


CHATS_PAGE = 100              # chats in one page of the Chat Logs tab
CHATS_MAX_PAGE = 200
_CHAT_CURSOR = re.compile(r"^(\d{4}-\d\d-\d\dT[0-9:.]+Z)\|([cm])\|(\d+)$")


_CHAT_BOUND = re.compile(r"^\d{4}-\d\d-\d\dT[0-9:.]+Z$")


def _chat_labels(chat_type: str) -> list:
    """The stored channel labels a type filter stands for. Empty means every type."""
    wanted = chat_type.strip().upper()
    if wanted in ("", "ALL"):
        return []
    if wanted == "PRIVATE":
        return [CHAT_TYPES["PRIVATE_FROM"], CHAT_TYPES["PRIVATE_TO"]]
    if wanted in CHAT_TYPES:
        return [CHAT_TYPES[wanted]]
    raise HTTPException(status_code=422, detail="unknown chat type: " + repr(chat_type[:20]))


def _chat_bound(value: str, name: str) -> Optional[str]:
    value = value.strip()
    if not value:
        return None
    if not _CHAT_BOUND.match(value):
        raise HTTPException(status_code=422, detail="that is not a valid %s value" % name)
    return value


def _chat_filtered(owner: Optional[str], q: str, labels: list, player: str,
                   since: Optional[str] = None, until: Optional[str] = None):
    """The deduplicated chat list behind the Chat Logs tab: (subquery, where clauses, params) for one account or all.

    Other players' chat and your own are two tables; together they are one list. Each row says which account it is from.
    A message heard by two accounts is in the tables twice; only the first of such copies is kept (see "ranked").
    """
    name_of = "COALESCE(NULLIF({t}.owner_name, ''), (SELECT a.player_name FROM players a WHERE a.player_uuid = {t}.owner_uuid), '')"
    source = ("SELECT c.said_at AS said_at, c.channel AS channel, c.world AS world, c.message AS message,"
              " p.username AS player, 'c' AS src, c.id AS rid, c.owner_uuid AS account_uuid, " + name_of.format(t="c") + " AS account"
              " FROM tracker_chats c JOIN tracker_players p ON p.id = c.player_id" + (" WHERE c.owner_uuid = ?" if owner else "") +
              " UNION ALL "
              "SELECT m.said_at, m.channel, m.world, m.message, " + name_of.format(t="m") + ", 'm', m.id, m.owner_uuid, " +
              name_of.format(t="m") + " FROM tracker_my_chats m" + (" WHERE m.owner_uuid = ?" if owner else ""))
    params: list = [owner, owner] if owner else []
    where = ["(prev_at IS NULL OR (julianday(said_at) - julianday(prev_at)) * 86400.0 > 2.0)"]
    if q.strip():
        pattern = "%" + _like_escape(q.strip().lower()) + "%"
        where.append("(lower(message) LIKE ? ESCAPE '\\' OR lower(player) LIKE ? ESCAPE '\\')")
        params += [pattern, pattern]
    if labels:
        where.append("channel IN (" + ",".join("?" * len(labels)) + ")")
        params += labels
    if player.strip():
        where.append("lower(player) = ?")
        params.append(player.strip().lower())
    if since:
        where.append("said_at >= ?")
        params.append(since)
    if until:
        where.append("said_at < ?")
        params.append(until)
    ranked = ("SELECT *, LAG(said_at) OVER (PARTITION BY lower(player), channel, message ORDER BY said_at, src, rid) AS prev_at"
              " FROM (" + source + ")")
    return ranked, where, params


@app.get("/tracker/chats")
def tracker_chats(owner: str = ALL_ACCOUNTS, q: str = Query(default="", max_length=100),
                  chat_type: str = Query(default="", alias="type", max_length=20),
                  player: str = Query(default="", max_length=32), before: str = Query(default="", max_length=64),
                  since: str = Query(default="", max_length=32), until: str = Query(default="", max_length=32),
                  limit: int = Query(default=CHATS_PAGE, ge=1, le=CHATS_MAX_PAGE)):
    """The newest chats an account has recorded, newest first: what players said to you or around you, the private
    messages you sent, and what you said yourself. Without owner (or with owner=all) it is every account together, and
    a message that two of your accounts both heard (the same player, type and text within 2 seconds) is listed once.

    q searches the message and the player's name (any capitals, a % or _ means itself). type is ALL (or empty), PUBLIC,
    PARTY, GUILD, COOP, PRIVATE_FROM, PRIVATE_TO or PRIVATE (both private kinds). player is one exact username. since and
    until (UTC timestamps) keep only chats from since up to but not including until: one day's folder. before is the
    next_before of the previous page: it loads the chats older than that one. Answers {chats, has_more, next_before}, and each
    chat is {at, type, player, message, world, mine, account, account_uuid}.
    """
    owner = _scope_owner(owner)
    labels = _chat_labels(chat_type)
    since_at, until_at = _chat_bound(since, "since"), _chat_bound(until, "until")

    cursor = None
    if before.strip():
        cursor = _CHAT_CURSOR.match(before.strip())
        if not cursor:
            raise HTTPException(status_code=422, detail="that is not a valid before value")

    with db() as conn:
        ranked, where, params = _chat_filtered(owner, q, labels, player, since_at, until_at)
        if cursor:
            at, src, rid = cursor.group(1), cursor.group(2), int(cursor.group(3))
            where.append("(said_at < ? OR (said_at = ? AND (src < ? OR (src = ? AND rid < ?))))")
            params += [at, at, src, src, rid]
        sql = ("SELECT * FROM (" + ranked + ") WHERE " + " AND ".join(where) +
               " ORDER BY said_at DESC, src DESC, rid DESC LIMIT ?")
        rows = conn.execute(sql, params + [limit + 1]).fetchall()

    has_more = len(rows) > limit
    rows = rows[:limit]
    return {
        "chats": [{"at": r["said_at"], "type": CHAT_TYPE_OF_LABEL.get(r["channel"], "PUBLIC"), "player": r["player"],
                   "message": r["message"], "world": r["world"], "mine": r["src"] == "m",
                   "account": r["account"], "account_uuid": r["account_uuid"]} for r in rows],
        "has_more": has_more,
        "next_before": "%s|%s|%d" % (rows[-1]["said_at"], rows[-1]["src"], rows[-1]["rid"]) if rows else None,
    }


@app.get("/tracker/chats/days")
def tracker_chat_days(owner: str = ALL_ACCOUNTS, q: str = Query(default="", max_length=100),
                      chat_type: str = Query(default="", alias="type", max_length=20),
                      player: str = Query(default="", max_length=32)):
    """How many chats fall in each UTC hour, newest first: the data behind the Chat Logs folders.

    The page groups these hours into days in its own time zone (this server has no time zone database), and opens a day
    by asking /tracker/chats with since and until. The filters (q, type, player) are the same as /tracker/chats, so a
    day with no matching chats is not listed. Answers {hours: [{hour: "2026-09-21T15", count}]}.
    """
    owner = _scope_owner(owner)
    labels = _chat_labels(chat_type)
    with db() as conn:
        ranked, where, params = _chat_filtered(owner, q, labels, player)
        sql = ("SELECT substr(said_at, 1, 13) AS hour, COUNT(*) AS count FROM (" + ranked + ") WHERE " +
               " AND ".join(where) + " GROUP BY hour ORDER BY hour DESC")
        rows = conn.execute(sql, params).fetchall()
    return {"hours": [{"hour": r["hour"], "count": r["count"]} for r in rows]}


MY_CHAT_SHOWN = 300           # newest messages sent to the page


@app.get("/tracker/mychat")
def tracker_my_chat(owner: str = ALL_ACCOUNTS):
    """What the account owner said: the newest MY_CHAT_SHOWN messages, newest first, and the total.

    For one account, or for all of them together (owner missing or "all"), each message saying which account said it.
    """
    owner = _scope_owner(owner)
    where, params = ("WHERE owner_uuid = ?", [owner]) if owner else ("", [])
    with db() as conn:
        count = conn.execute("SELECT COUNT(*) FROM tracker_my_chats " + where, params).fetchone()[0]
        rows = conn.execute(
            "SELECT said_at, channel, world, message, owner_name AS account FROM tracker_my_chats " + where +
            " ORDER BY said_at DESC, id DESC LIMIT ?", params + [MY_CHAT_SHOWN]).fetchall()
    return {"count": count, "chats": [dict(r) for r in rows]}


@app.get("/tracker/party")
def tracker_party_list(owner: Optional[str] = None):
    """Everyone an account has ever partied with, and how many times.

    Pass owner for one account. Without it (or with owner=all) every account is counted together, and none of your own
    accounts is listed as someone you partied with.
    """
    sql = ("SELECT p.id, p.username, COUNT(*) AS times, MIN(t.joined_at) AS first, MAX(t.joined_at) AS last"
           " FROM tracker_parties t JOIN tracker_players p ON p.id = t.player_id")
    with db() as conn:
        clause, params = _party_scope(conn, _scope_owner(owner))
        sql += " WHERE " + clause
        sql += " GROUP BY p.id ORDER BY last DESC, p.id"
        return {"players": [dict(r) for r in conn.execute(sql, params).fetchall()]}


def _like_escape(text: str) -> str:
    """Make text safe to use inside a LIKE pattern, so a search for % matches only a real %."""
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


@app.get("/tracker/search")
def tracker_search(q: str = Query(min_length=1, max_length=32),
                   exact: bool = False,
                   sightings: bool = True,
                   world: List[str] = Query(default=[]),
                   owner: Optional[str] = None,
                   limit: int = Query(default=SEARCH_DEFAULT_LIMIT, ge=1, le=SEARCH_MAX_LIMIT)):
    """Find players by name, matching any part of it and ignoring case.

    Each match comes with every time you met them (sightings), newest first, each with the
    SkyBlock world it was in, and by_world: how many times you met them in each world.
    meetings counts the sightings that match, with sightings under 4 hours apart as one meeting,
    and meeting_list (when sightings are asked for) groups them that way, newest first.
    Use sightings=false for a light list (for suggestions while typing) and
    exact=true to match the whole name only. Pass world (once or several times) to
    keep only players met in those worlds, and only those encounters. Pass owner and
    the party information (party_times, parties) covers only that account's parties.
    """
    party_owner = _scope_owner(owner)
    own_lower = ""
    if party_owner:
        with db() as conn:
            own_lower = _own_name(conn, party_owner)
    needle = q.strip().lower()
    if not needle:
        raise HTTPException(status_code=422, detail="q must not be blank")
    worlds = _world_filter(world)
    marks = ",".join("?" * len(worlds))

    if exact:
        where = "username_lower = ?"
        params: list = [needle]
        order = " ORDER BY last_seen DESC, id"
    else:
        where = "username_lower LIKE ? ESCAPE '\\'"
        params = ["%" + _like_escape(needle) + "%"]
        order = (" ORDER BY (username_lower = ?) DESC, (username_lower LIKE ? ESCAPE '\\') DESC,"
                 " last_seen DESC, id")

    if worlds:
        where += (" AND EXISTS (SELECT 1 FROM tracker_sightings s JOIN tracker_visits v ON v.id = s.visit_id"
                  " WHERE s.player_id = tracker_players.id AND lower(v.world) IN (" + marks + "))")
        params += worlds
    if not exact:
        params += [needle, _like_escape(needle) + "%"]

    with db() as conn:
        found = conn.execute("SELECT * FROM tracker_players WHERE " + where + order + " LIMIT ?",
                             params + [limit]).fetchall()

        accounts = {r["player_uuid"]: r["player_name"]
                    for r in conn.execute("SELECT player_uuid, player_name FROM players").fetchall()}

        results = []
        for p in found:
            item = {"id": p["id"], "username": p["username"], "first_seen": p["first_seen"],
                    "last_seen": p["last_seen"], "times_seen": p["times_seen"]}

            # How many times, in each world, whatever the filter, so the page can offer them.
            item["by_world"] = {r["world"]: r["n"] for r in conn.execute(
                "SELECT v.world AS world, COUNT(*) AS n FROM tracker_sightings s"
                " JOIN tracker_visits v ON v.id = s.visit_id WHERE s.player_id = ?"
                " GROUP BY v.world ORDER BY n DESC, v.world", (p["id"],)).fetchall()}

            query = ("FROM tracker_sightings s"
                     " JOIN tracker_visits v ON v.id = s.visit_id WHERE s.player_id = ?")
            args: list = [p["id"]]
            if worlds:
                query += " AND lower(v.world) IN (" + marks + ")"
                args += worlds

            if sightings:
                # Need the rows themselves, so fetch them once and take the count from that.
                rows = conn.execute(
                    "SELECT s.seen_at, v.started_at, v.owner_uuid, v.world " + query +
                    " ORDER BY s.seen_at DESC, s.id DESC", args).fetchall()
                item["matching"] = len(rows)   # encounters that pass the world filter
                item["meetings"] = _count_meetings([r["seen_at"] for r in reversed(rows)])
            else:
                # Suggestions only need the count: a COUNT(*) skips pulling every sighting row for a
                # player who has been seen thousands of times, which is what made typing feel laggy
                # once the tracker had a lot of players on file.
                rows = None
                times = [r[0] for r in conn.execute("SELECT s.seen_at " + query + " ORDER BY s.seen_at, s.id", args)]
                item["matching"] = len(times)
                item["meetings"] = _count_meetings(times)

            # Have you ever been in a party with them, and how many times.
            party_sql = "FROM tracker_parties WHERE player_id = ?"
            party_args: list = [p["id"]]
            if party_owner:
                party_sql += " AND owner_uuid = ?"
                party_args.append(party_owner)
            no_self_party = party_owner and p["username_lower"] == own_lower   # you are never in your own party list
            if no_self_party:
                parties = []
                item["party_times"] = 0
            elif sightings:
                parties = conn.execute(
                    "SELECT joined_at, owner_uuid " + party_sql + " ORDER BY joined_at DESC, id DESC",
                    party_args).fetchall()
                item["party_times"] = len(parties)
            else:
                parties = None
                item["party_times"] = conn.execute("SELECT COUNT(*) " + party_sql, party_args).fetchone()[0]

            # What they have said, newest first (at most SEARCH_CHAT_LIMIT), and how many messages there are.
            chat_sql = " FROM tracker_chats WHERE player_id = ?"
            chat_args: list = [p["id"]]
            if party_owner:
                chat_sql += " AND owner_uuid = ?"
                chat_args.append(party_owner)
            item["chat_count"] = conn.execute("SELECT COUNT(*)" + chat_sql, chat_args).fetchone()[0]
            if sightings:
                item["chats"] = [
                    {"said_at": r["said_at"], "channel": r["channel"], "world": r["world"], "message": r["message"],
                     "account": accounts.get(r["owner_uuid"], "unknown")}
                    for r in conn.execute(
                        "SELECT said_at, channel, world, message, owner_uuid" + chat_sql +
                        " ORDER BY said_at DESC, id DESC LIMIT ?", chat_args + [SEARCH_CHAT_LIMIT]).fetchall()]
            if sightings:
                item["parties"] = [{"joined_at": r["joined_at"], "account": accounts.get(r["owner_uuid"], "unknown")}
                                   for r in parties]
            if sightings:
                item["sightings"] = [
                    {"seen_at": r["seen_at"], "visit_started_at": r["started_at"], "world": r["world"],
                     "account": accounts.get(r["owner_uuid"], "unknown")}
                    for r in rows]
                item["meeting_list"] = _meetings_json(item["sightings"])
                tag = conn.execute("SELECT tag, notes, updated_at FROM tracker_frienemies WHERE player_id = ?",
                                   (p["id"],)).fetchone()
                item["tag"] = dict(tag) if tag else None
            results.append(item)

    return {"query": q, "worlds": worlds, "players": results}


@app.get("/tracker/worlds")
def tracker_worlds(owner: Optional[str] = None):
    """The SkyBlock worlds seen so far, with how many different players and visits each has.

    Also gives total_players, the number of different players met in any world (a
    player met in two worlds counts once). Pass owner to count only that account's visits; without it, or with owner=all,
    every account is counted together.
    """
    owner = _scope_owner(owner)
    sql = ("SELECT v.world AS world, COUNT(DISTINCT s.player_id) AS players, COUNT(DISTINCT v.id) AS visits"
           " FROM tracker_visits v LEFT JOIN tracker_sightings s ON s.visit_id = v.id")
    params: list = []
    if owner:
        sql += " WHERE v.owner_uuid = ?"
        params.append(owner)
    sql += " GROUP BY v.world ORDER BY players DESC, v.world"

    total_sql = ("SELECT COUNT(DISTINCT s.player_id) FROM tracker_sightings s"
                 " JOIN tracker_visits v ON v.id = s.visit_id")
    if owner:
        total_sql += " WHERE v.owner_uuid = ?"

    with db() as conn:
        worlds = [dict(r) for r in conn.execute(sql, params).fetchall()]
        total = conn.execute(total_sql, params).fetchone()[0]
        clause, clause_params = _party_scope(conn, owner)
        partied = conn.execute(
            "SELECT COUNT(DISTINCT t.player_id) FROM tracker_parties t JOIN tracker_players p ON p.id = t.player_id"
            " WHERE " + clause, clause_params).fetchone()[0]
    return {"worlds": worlds, "total_players": total, "party_players": partied}


# ------------------------------------------------------------------ meetings and familiarity tiers
#
# A sighting is one lobby visit in which a player was seen. Hopping lobbies makes many sightings a few minutes apart, so
# they are folded into meetings: a meeting starts at a sighting, and every sighting less than MEET_WINDOW after that
# start belongs to it. The next sighting at or after MEET_WINDOW starts a new meeting (a fixed window, not a rolling gap).
# Nothing is deleted: the sightings stay as they are and the meetings are worked out from them when asked for.

MEET_WINDOW = timedelta(hours=4)

# The familiarity tiers of the Players tab, by meetings. "close" (partied with you or tagged) is a separate group that
# overlaps these.
TIERS = ("once", "twice", "recurring", "regular")
PLAYERS_DEFAULT_LIMIT = 50
PLAYERS_MAX_LIMIT = 500


def _tier(meetings: int) -> str:
    """The tier for a number of meetings: 1 once, 2 twice, 3-4 recurring, 5+ regular."""
    if meetings >= 5:
        return "regular"
    if meetings >= 3:
        return "recurring"
    return "twice" if meetings == 2 else "once"


def _utc(text: str) -> datetime:
    """A stored UTC time ("2026-09-21T19:41:03.123Z") as a datetime."""
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def _meeting_groups(sightings: list) -> list:
    """Sightings (dicts with seen_at), oldest first, as meetings: lists of sightings, oldest first."""
    groups: list = []
    start = None
    for s in sightings:
        when = _utc(s["seen_at"])
        if start is None or when - start >= MEET_WINDOW:
            groups.append([])
            start = when
        groups[-1].append(s)
    return groups


def _count_meetings(times: list) -> int:
    """How many meetings a list of seen_at texts, oldest first, makes."""
    count, start = 0, None
    for text in times:
        when = _utc(text)
        if start is None or when - start >= MEET_WINDOW:
            count += 1
            start = when
    return count


def _meetings_json(sightings: list) -> list:
    """A player's sightings (newest first, as /tracker/search lists them) as meetings, newest first. Each meeting has its
    start and end, the worlds and accounts in it, and its sightings, newest first."""
    out = []
    for group in reversed(_meeting_groups(list(reversed(sightings)))):
        worlds, owners = [], []
        for s in group:
            if s["world"] not in worlds:
                worlds.append(s["world"])
            if s["account"] not in owners:
                owners.append(s["account"])
        out.append({"start": group[0]["seen_at"], "end": group[-1]["seen_at"], "count": len(group),
                    "worlds": worlds, "accounts": owners, "sightings": list(reversed(group))})
    return out


_familiar_cache: dict = {}
_familiar_lock = threading.Lock()


def _familiarity(conn, owner: Optional[str], world: Optional[str]) -> dict:
    """Every player met by this account (or every account) in this world (or every world), with their meetings.

    Returns player id -> {meetings, sightings, first, last, last_world, worlds: {world: meetings}}. Kept until a sighting
    is added or removed, so paging through a list does not work it all out again.
    """
    sql = (" FROM tracker_sightings s JOIN tracker_visits v ON v.id = s.visit_id WHERE 1 = 1" +
           (" AND v.owner_uuid = ?" if owner else "") + (" AND lower(v.world) = ?" if world else ""))
    params = ([owner] if owner else []) + ([world] if world else [])
    stamp = tuple(conn.execute("SELECT COUNT(*), COALESCE(MAX(s.id), 0)" + sql, params).fetchone())
    key = (str(DB_PATH), owner, world)
    with _familiar_lock:
        kept = _familiar_cache.get(key)
        if kept and kept[0] == stamp:
            return kept[1]

    players: dict = {}
    starts: dict = {}           # (player id, world) -> the start of the meeting in that world, for the per-world count
    for r in conn.execute("SELECT s.player_id, s.seen_at, v.world" + sql + " ORDER BY s.player_id, s.seen_at, s.id", params):
        pid, seen, where = r[0], r[1], r[2]
        when = _utc(seen)
        p = players.get(pid)
        if p is None:
            p = players[pid] = {"meetings": 0, "sightings": 0, "first": seen, "last": seen, "last_world": where,
                                "worlds": {}, "_start": None}
        p["sightings"] += 1
        p["last"], p["last_world"] = seen, where
        if p["_start"] is None or when - p["_start"] >= MEET_WINDOW:
            p["meetings"] += 1
            p["_start"] = when
        here = starts.get((pid, where))
        if here is None or when - here >= MEET_WINDOW:
            p["worlds"][where] = p["worlds"].get(where, 0) + 1
            starts[(pid, where)] = when
    for p in players.values():
        del p["_start"]

    with _familiar_lock:
        if len(_familiar_cache) > 64:
            _familiar_cache.clear()
        _familiar_cache[key] = (stamp, players)
    return players


def _close_ids(conn, owner: Optional[str], met: dict, world: Optional[str]) -> set:
    """Players partied with (by this account) or tagged friendly or enemy. With a world, only those met in it."""
    clause, params = _party_scope(conn, owner)
    close = {r[0] for r in conn.execute(
        "SELECT DISTINCT t.player_id FROM tracker_parties t JOIN tracker_players p ON p.id = t.player_id WHERE " + clause,
        params)}
    close |= {r[0] for r in conn.execute("SELECT player_id FROM tracker_frienemies")}
    if world:
        close &= set(met)
    return close


def _one_world(world: Optional[str]) -> Optional[str]:
    """One world name from a query, lower-case, or None for every world."""
    if world is None:
        return None
    cleaned = _world_filter([world])
    return cleaned[0] if cleaned else None


@app.get("/tracker/overview")
def tracker_overview(owner: Optional[str] = None, world: Optional[str] = Query(default=None, max_length=64)):
    """What the Players tab opens on: the worlds (for the map) and how many players are in each familiarity tier.

    The worlds and totals are those of /tracker/worlds, and latest_event_id is where /tracker/live continues from. tiers counts the players met in `world` (or in any world) by
    meetings: once (1), twice (2), recurring (3-4) and regular (5 or more), plus close: partied with or tagged. A player
    in close is also counted in their tier. Pass owner for one account; without it, or with owner=all, every account.
    """
    scope = _scope_owner(owner)
    place = _one_world(world)
    with db() as conn:
        # Read the live cursor first: a sighting that lands while the rest of this runs may then be counted twice by the
        # page (it refreshes again) but is never missed.
        latest_event_id = conn.execute("SELECT COALESCE(MAX(id), 0) FROM tracker_sightings").fetchone()[0]
    answer = tracker_worlds(owner)
    answer["latest_event_id"] = latest_event_id
    with db() as conn:
        met = _familiarity(conn, scope, place)
        close = _close_ids(conn, scope, met, place)
    tiers = {t: 0 for t in TIERS}
    for p in met.values():
        tiers[_tier(p["meetings"])] += 1
    tiers["close"] = len(close)
    answer.update({"world": place, "players": len(met), "meetings": sum(p["meetings"] for p in met.values()),
                   "tiers": tiers})
    return answer


@app.get("/tracker/players")
def tracker_players(request: Request,
                    owner: Optional[str] = None,
                    world: Optional[str] = Query(default=None, max_length=64),
                    tier: str = Query(default="all", pattern="^(all|once|twice|recurring|regular|close)$"),
                    sort: str = Query(default="meetings", pattern="^(meetings|last|first|name)$"),
                    offset: int = Query(default=0, ge=0),
                    limit: int = Query(default=PLAYERS_DEFAULT_LIMIT, ge=1, le=PLAYERS_MAX_LIMIT)):
    """One page of the players in a familiarity tier, for the Players tab's list.

    Each player has their meetings and sightings in the chosen world (or every world), when you first and last met them
    there, the world of the latest sighting, meetings per world, how many times they partied with you, and their
    friendly/enemy tag. sort is meetings (most first), last (most recently met first), first (most recently first met
    first) or name. total is how many players the whole tier has.
    """
    scope = _scope_owner(owner)
    place = _one_world(world)
    with db() as conn:
        met = _familiarity(conn, scope, place)
        if tier == "close":
            ids = list(_close_ids(conn, scope, met, place))
        elif tier == "all":
            ids = list(met)
        else:
            ids = [pid for pid, p in met.items() if _tier(p["meetings"]) == tier]

        empty = {"meetings": 0, "sightings": 0, "first": "", "last": "", "last_world": "", "worlds": {}}
        if sort == "name":
            names = {r[0]: r[1] for r in conn.execute("SELECT id, username_lower FROM tracker_players")}
            ids.sort(key=lambda pid: (names.get(pid, ""), pid))
        elif sort == "last":
            ids.sort(key=lambda pid: (met.get(pid, empty)["last"], pid), reverse=True)
        elif sort == "first":
            ids.sort(key=lambda pid: (met.get(pid, empty)["first"], pid), reverse=True)
        else:
            ids.sort(key=lambda pid: (met.get(pid, empty)["meetings"], met.get(pid, empty)["last"], pid), reverse=True)

        page = ids[offset:offset + limit]
        rows, parties, tags = {}, {}, {}
        if page:
            marks = ",".join("?" * len(page))
            rows = {r["id"]: r for r in conn.execute(
                "SELECT id, username, first_seen, last_seen FROM tracker_players WHERE id IN (" + marks + ")", page)}
            clause, params = _party_scope(conn, scope)
            parties = {r[0]: r[1] for r in conn.execute(
                "SELECT t.player_id, COUNT(*) FROM tracker_parties t JOIN tracker_players p ON p.id = t.player_id"
                " WHERE " + clause + " AND t.player_id IN (" + marks + ") GROUP BY t.player_id", params + page)}
            tags = {r[0]: {"tag": r[1], "notes": r[2]} for r in conn.execute(
                "SELECT player_id, tag, notes FROM tracker_frienemies WHERE player_id IN (" + marks + ")", page)}

    players = []
    for pid in page:
        row = rows.get(pid)
        if row is None:
            continue
        p = met.get(pid, empty)
        players.append({"id": pid, "username": row["username"], "meetings": p["meetings"], "sightings": p["sightings"],
                        "first_seen": p["first"] or row["first_seen"], "last_seen": p["last"] or row["last_seen"],
                        "last_world": p["last_world"], "worlds": p["worlds"], "party_times": parties.get(pid, 0),
                        "tag": tags.get(pid)})
    return _json_response(request, {"world": place, "tier": tier, "sort": sort, "total": len(ids),
                                    "offset": offset, "players": players})


class TrackerClearIn(BaseModel):
    """Which tracker data to delete."""

    owner: str
    # One SkyBlock world (for example "Hub"). Missing means every world.
    world: Optional[str] = None


def _is_local(request: Request) -> bool:
    """True if the request came from the PC the website runs on."""
    return request.client is not None and request.client.host in ("127.0.0.1", "::1", "localhost")


@app.post("/tracker/clear")
def tracker_clear(body: TrackerClearIn, request: Request):
    """Delete an account's tracker data: every world, or just one.

    This cannot be undone, so it only works from the PC the website runs on. Anyone
    else on the network (LAN mode) can read the data but not wipe it. Players no one
    has met any more are removed, and the counts of the rest are worked out again.
    """
    if not _is_local(request):
        raise HTTPException(status_code=403, detail="clearing is only allowed from the PC running the website")

    owner = _scope_owner(body.owner)
    if owner is None:
        raise HTTPException(status_code=422, detail="choose one account to clear: clearing never touches every account at once")
    worlds = _world_filter([body.world]) if body.world else []

    with db() as conn:
        # Clearing every world also forgets this account's parties. One world leaves them alone.
        parties_removed = 0
        if not worlds:
            parties_removed = conn.execute("DELETE FROM tracker_parties WHERE owner_uuid = ?", (owner,)).rowcount

        # Chat belongs to a world too: clear it with that world, or all of it with everything.
        if worlds:
            chats_removed = conn.execute("DELETE FROM tracker_chats WHERE owner_uuid = ? AND lower(world) = ?",
                                         (owner, worlds[0])).rowcount
            chats_removed += conn.execute("DELETE FROM tracker_my_chats WHERE owner_uuid = ? AND lower(world) = ?",
                                          (owner, worlds[0])).rowcount
        else:
            chats_removed = conn.execute("DELETE FROM tracker_chats WHERE owner_uuid = ?", (owner,)).rowcount
            chats_removed += conn.execute("DELETE FROM tracker_my_chats WHERE owner_uuid = ?", (owner,)).rowcount

        sql = "SELECT id FROM tracker_visits WHERE owner_uuid = ?"
        params: list = [owner]
        if worlds:
            sql += " AND lower(world) = ?"
            params.append(worlds[0])
        visit_ids = [r["id"] for r in conn.execute(sql, params).fetchall()]

        players_removed = sightings_removed = 0
        # SQLite allows about 999 values in one statement, so work in chunks.
        for start in range(0, len(visit_ids), 500):
            chunk = visit_ids[start:start + 500]
            marks = ",".join("?" * len(chunk))
            affected = [r["player_id"] for r in conn.execute(
                "SELECT DISTINCT player_id FROM tracker_sightings WHERE visit_id IN (" + marks + ")", chunk).fetchall()]

            sightings_removed += conn.execute(
                "DELETE FROM tracker_sightings WHERE visit_id IN (" + marks + ")", chunk).rowcount
            conn.execute("DELETE FROM tracker_visits WHERE id IN (" + marks + ")", chunk)

            for player_id in affected:
                left = conn.execute(
                    "SELECT COUNT(*) AS n, MIN(seen_at) AS first, MAX(seen_at) AS last"
                    " FROM tracker_sightings WHERE player_id = ?", (player_id,)).fetchone()
                partied = conn.execute("SELECT COUNT(*) FROM tracker_parties WHERE player_id = ?", (player_id,)).fetchone()[0]
                partied += conn.execute("SELECT COUNT(*) FROM tracker_chats WHERE player_id = ?", (player_id,)).fetchone()[0]
                if left["n"] == 0 and partied:
                    conn.execute("UPDATE tracker_players SET times_seen = 0 WHERE id = ?", (player_id,))   # kept: partied or chatted
                elif left["n"] == 0:
                    conn.execute("DELETE FROM tracker_players WHERE id = ?", (player_id,))
                    players_removed += 1
                else:
                    conn.execute("UPDATE tracker_players SET times_seen = ?, first_seen = ?, last_seen = ? WHERE id = ?",
                                 (left["n"], left["first"], left["last"], player_id))

        # Players only ever met in a party by this account, and no longer in any party, go too.
        players_removed += conn.execute(
            "DELETE FROM tracker_players WHERE id NOT IN (SELECT player_id FROM tracker_sightings)"
            " AND id NOT IN (SELECT player_id FROM tracker_parties)"
            " AND id NOT IN (SELECT player_id FROM tracker_chats)").rowcount

    return {"visits_removed": len(visit_ids), "sightings_removed": sightings_removed,
            "players_removed": players_removed, "parties_removed": parties_removed,
            "chats_removed": chats_removed, "world": body.world or None}


def _tracker_events(last_id: int, owner: Optional[str]) -> list:
    """Sightings newer than last_id for one account (or every account), as live events."""
    with db() as conn:
        rows = conn.execute(
            "SELECT s.id, s.visit_id, s.seen_at, v.started_at AS visit_started_at, v.world AS world,"
            " p.id AS player_id, p.username, p.times_seen,"
            " (SELECT COUNT(*) FROM tracker_parties tp WHERE tp.player_id = s.player_id"
            + ("   AND tp.owner_uuid = v.owner_uuid" if owner else "") + ") AS party_times,"
            " (SELECT MAX(o.seen_at) FROM tracker_sightings o"
            "   WHERE o.player_id = s.player_id AND o.id < s.id) AS previous_seen_at,"
            " (SELECT v2.world FROM tracker_sightings o JOIN tracker_visits v2 ON v2.id = o.visit_id"
            "   WHERE o.player_id = s.player_id AND o.id < s.id"
            "   ORDER BY o.seen_at DESC, o.id DESC LIMIT 1) AS previous_world,"
            " (SELECT group_concat(DISTINCT v3.world) FROM tracker_sightings o3"
            "   JOIN tracker_visits v3 ON v3.id = o3.visit_id"
            "   WHERE o3.player_id = s.player_id AND o3.id < s.id) AS previous_worlds"
            " FROM tracker_sightings s"
            " JOIN tracker_visits v ON v.id = s.visit_id"
            " JOIN tracker_players p ON p.id = s.player_id"
            " WHERE s.id > ?" + (" AND v.owner_uuid = ?" if owner else "") + " ORDER BY s.id LIMIT 1000",
            [last_id] + ([owner] if owner else [])).fetchall()

    events = []
    for r in rows:
        event = dict(r)
        # Met before means there is an earlier sighting, which can only be from another visit.
        event["returning"] = r["previous_seen_at"] is not None
        # Every world this player was met in before, so the page can tell "same world" from "another world".
        event["previous_worlds"] = sorted(r["previous_worlds"].split(",")) if r["previous_worlds"] else []
        events.append(event)
    return events


def _party_events(last_id: int, owner: Optional[str]) -> list:
    """One account's (or every account's) party confirmations newer than last_id, as live events."""
    with db() as conn:
        clause, clause_params = _party_scope(conn, owner)
        rows = conn.execute(
            "SELECT t.id, t.player_id, t.joined_at, p.username,"
            " (SELECT COUNT(*) FROM tracker_parties o WHERE o.player_id = t.player_id"
            + ("   AND o.owner_uuid = t.owner_uuid" if owner else "") + "   AND o.id <= t.id) AS party_times"
            " FROM tracker_parties t JOIN tracker_players p ON p.id = t.player_id"
            " WHERE t.id > ? AND " + clause + " ORDER BY t.id LIMIT 1000", [last_id] + clause_params).fetchall()
    return [dict(r, kind="party") for r in rows]


def _chat_events(last_id: int, owner: Optional[str]) -> list:
    """One account's (or every account's) chat messages newer than last_id, as live events."""
    with db() as conn:
        rows = conn.execute(
            "SELECT c.id, c.player_id, c.said_at, c.channel, c.world, c.message, p.username, c.owner_name AS account"
            " FROM tracker_chats c JOIN tracker_players p ON p.id = c.player_id"
            " WHERE c.id > ?" + (" AND c.owner_uuid = ?" if owner else "") + " ORDER BY c.id LIMIT 1000",
            [last_id] + ([owner] if owner else [])).fetchall()
    return [dict(r, kind="chat") for r in rows]


def _my_chat_events(last_id: int, owner: Optional[str]) -> list:
    """The account owner's own messages (of one account, or every account) newer than last_id, as live events."""
    with db() as conn:
        rows = conn.execute(
            "SELECT id, said_at, channel, world, message, owner_name AS account FROM tracker_my_chats"
            " WHERE id > ?" + (" AND owner_uuid = ?" if owner else "") + " ORDER BY id LIMIT 1000",
            [last_id] + ([owner] if owner else [])).fetchall()
    return [dict(r, kind="mychat") for r in rows]


def _newest_my_chat_id() -> int:
    with db() as conn:
        return conn.execute("SELECT COALESCE(MAX(id), 0) FROM tracker_my_chats").fetchone()[0]


def _newest_chat_id() -> int:
    with db() as conn:
        return conn.execute("SELECT COALESCE(MAX(id), 0) FROM tracker_chats").fetchone()[0]


def _newest_party_id() -> int:
    with db() as conn:
        return conn.execute("SELECT COALESCE(MAX(id), 0) FROM tracker_parties").fetchone()[0]


def _newest_sighting_id() -> int:
    with db() as conn:
        return conn.execute("SELECT COALESCE(MAX(id), 0) FROM tracker_sightings").fetchone()[0]


@app.get("/tracker/live")
async def tracker_live(owner: str = ALL_ACCOUNTS, since: Optional[int] = Query(default=None, ge=0)):
    """Stream players as they are seen, as server-sent events.

    Each event is one sighting, and says whether you had met that player before
    (returning) and when you last saw them (previous_seen_at). By default it starts
    from now; pass ?since=<latest_event_id from /tracker/overview> to continue from there.
    """
    account = _scope_owner(owner)   # checked before streaming starts, so a bad value is a plain 422. None: every account

    async def event_stream():
        last_id = await asyncio.to_thread(_newest_sighting_id) if since is None else since
        last_party_id = await asyncio.to_thread(_newest_party_id)
        last_chat_id = await asyncio.to_thread(_newest_chat_id)
        last_my_chat_id = await asyncio.to_thread(_newest_my_chat_id)

        while True:
            events = await asyncio.to_thread(_tracker_events, last_id, account)
            for event in events:
                last_id = event["id"]
                yield "data: " + json.dumps(event) + "\n\n"

            # Party confirmations carry kind "party"; sightings have no kind.
            parties = await asyncio.to_thread(_party_events, last_party_id, account)
            for event in parties:
                last_party_id = event["id"]
                yield "data: " + json.dumps(event) + "\n\n"

            # Chat messages carry kind "chat".
            chats = await asyncio.to_thread(_chat_events, last_chat_id, account)
            for event in chats:
                last_chat_id = event["id"]
                yield "data: " + json.dumps(event) + "\n\n"

            # The account owner's own messages carry kind "mychat".
            mine = await asyncio.to_thread(_my_chat_events, last_my_chat_id, account)
            for event in mine:
                last_my_chat_id = event["id"]
                yield "data: " + json.dumps(event) + "\n\n"

            if not events and not parties and not chats and not mine:
                yield ": keepalive\n\n"

            await asyncio.sleep(1)

    return StreamingResponse(event_stream(), media_type="text/event-stream")


# ------------------------------------------------------------------ remote commands
#
# From the page you can tell one of your games to quit or to say something in chat. The page proves it is you with the admin token
# (config.json, "admin_token"); without it nothing can be queued. The game asks for its own account's commands with its API key,
# does them on its main thread and reports back. A chat command is a plain message: it can never start with "/", so this cannot
# be used to make the game run commands.

COMMAND_TYPES = ("QUIT", "CHAT")
COMMAND_CHAT_MAX = 256
COMMAND_WAIT_SECONDS = 60         # a command the game has not picked up by then is dropped, so it can never fire hours later
COMMAND_ANSWER_SECONDS = 30       # the game picked it up but never said how it went
COMMAND_MAX_PENDING = 3           # per account


def _require_admin(token: Optional[str]) -> None:
    """Only the holder of the admin token may send commands. Compared in constant time."""
    expected = str(_read_config().get("admin_token", ""))
    if not expected or not token or not hmac.compare_digest(token.encode("utf-8"), expected.encode("utf-8")):
        raise HTTPException(status_code=401, detail="missing or wrong X-Admin-Token")


def _utc_stamp(seconds_ago: float = 0.0) -> str:
    return (datetime.now(timezone.utc) - timedelta(seconds=seconds_ago)).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def _expire_commands(conn) -> None:
    """Fails commands nobody picked up, or that the game took but never answered."""
    now = _utc_stamp()
    conn.execute("UPDATE commands SET status = 'FAILED', reason = 'expired: the game did not pick it up in time', finished_at = ?"
                 " WHERE status = 'PENDING' AND taken_at IS NULL AND created_at < ?", (now, _utc_stamp(COMMAND_WAIT_SECONDS)))
    conn.execute("UPDATE commands SET status = 'FAILED', reason = 'no answer from the game', finished_at = ?"
                 " WHERE status = 'PENDING' AND taken_at IS NOT NULL AND taken_at < ?", (now, _utc_stamp(COMMAND_ANSWER_SECONDS)))


def _command_target(conn, account: str):
    """The linked account a path names, by UUID or by name."""
    row = None
    try:
        row = conn.execute("SELECT * FROM players WHERE player_uuid = ?", (normalise_uuid(account),)).fetchone()
    except HTTPException:
        pass
    if row is None:
        row = conn.execute("SELECT * FROM players WHERE lower(player_name) = lower(?)", (account.strip(),)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="no such account")
    return row


def _command_row(row) -> dict:
    return {"id": row["id"], "type": row["type"], "payload": row["payload"], "status": row["status"], "reason": row["reason"],
            "created_at": row["created_at"], "finished_at": row["finished_at"]}


class CommandIn(BaseModel):
    """A command for a game. type is QUIT or CHAT; payload is the message for CHAT."""

    type: str
    payload: Optional[str] = None


@app.post("/api/accounts/{account}/command")
def create_command(account: str, body: CommandIn, x_admin_token: Optional[str] = Header(default=None)):
    """Queue a command for one account's game. Needs the admin token. The account can be its UUID or its name."""
    _require_admin(x_admin_token)

    kind = body.type.strip().upper()
    if kind not in COMMAND_TYPES:
        raise HTTPException(status_code=422, detail="type must be QUIT or CHAT")

    payload = None
    if kind == "CHAT":
        text = " ".join("".join(" " if (c.isspace() or ord(c) < 32 or ord(c) == 127) else c for c in (body.payload or "")).split())
        if not text:
            raise HTTPException(status_code=422, detail="a chat command needs a message")
        if len(text) > COMMAND_CHAT_MAX:
            raise HTTPException(status_code=422, detail="a message is at most %d characters" % COMMAND_CHAT_MAX)
        if text.startswith("/"):
            raise HTTPException(status_code=422, detail="this sends chat messages only, not commands (it cannot start with /)")
        payload = text

    with db() as conn:
        target = _command_target(conn, account)
        online = target["status"] == "online" and now_ms() - target["last_seen_ms"] <= ONLINE_WINDOW_MS
        if not online:
            raise HTTPException(status_code=409, detail=target["player_name"] + " is not online, so the game cannot get the command")

        _expire_commands(conn)
        waiting = conn.execute("SELECT COUNT(*) FROM commands WHERE target_uuid = ? AND status = 'PENDING'", (target["player_uuid"],)).fetchone()[0]
        if waiting >= COMMAND_MAX_PENDING:
            raise HTTPException(status_code=429, detail="that account already has %d commands waiting" % COMMAND_MAX_PENDING)

        new_id = conn.execute(
            "INSERT INTO commands (target_uuid, target_name, type, payload, status, created_at) VALUES (?, ?, ?, ?, 'PENDING', ?)",
            (target["player_uuid"], target["player_name"], kind, payload, _utc_stamp())).lastrowid
    return {"id": new_id, "status": "PENDING", "type": kind, "target": target["player_name"]}


@app.get("/api/accounts/{account}/commands")
def list_commands(account: str, limit: int = Query(default=10, ge=1, le=50), x_admin_token: Optional[str] = Header(default=None)):
    """The newest commands sent to an account, newest first, with how each went. Needs the admin token."""
    _require_admin(x_admin_token)
    with db() as conn:
        target = _command_target(conn, account)
        _expire_commands(conn)
        rows = conn.execute("SELECT * FROM commands WHERE target_uuid = ? ORDER BY id DESC LIMIT ?",
                            (target["player_uuid"], limit)).fetchall()
    return {"account": target["player_name"], "commands": [_command_row(r) for r in rows]}


@app.get("/commands/next")
def next_command(x_api_key: Optional[str] = Header(default=None)):
    """The game asks for its own account's oldest waiting command: {command: {id, type, payload}} or {command: null}.

    A command is handed out once. Whatever the game does with it, it should answer with POST /commands/{id}/result.
    """
    owner = linked_account(x_api_key)["player_uuid"]

    with db() as conn:
        _expire_commands(conn)
        row = conn.execute("SELECT * FROM commands WHERE target_uuid = ? AND status = 'PENDING' AND taken_at IS NULL"
                           " ORDER BY id LIMIT 1", (owner,)).fetchone()
        if row is None:
            return {"command": None}
        conn.execute("UPDATE commands SET taken_at = ? WHERE id = ?", (_utc_stamp(), row["id"]))
    return {"command": {"id": row["id"], "type": row["type"], "payload": row["payload"]}}


class CommandResultIn(BaseModel):
    """How a command went. status is DONE or FAILED; reason says why it failed."""

    status: str
    reason: Optional[str] = None


@app.post("/commands/{command_id}/result")
def command_result(command_id: int, body: CommandResultIn, x_api_key: Optional[str] = Header(default=None)):
    """The game reports how a command it was given went. Only the account the command was for can answer it, once."""
    account = linked_account(x_api_key)
    owner = account["player_uuid"]

    status = body.status.strip().upper()
    if status not in ("DONE", "FAILED"):
        raise HTTPException(status_code=422, detail="status must be DONE or FAILED")
    reason = " ".join((body.reason or "").split())[:200] or None
    if status == "DONE":
        reason = None

    with db() as conn:
        changed = conn.execute(
            "UPDATE commands SET status = ?, reason = ?, finished_at = ?"
            " WHERE id = ? AND target_uuid = ? AND status = 'PENDING' AND taken_at IS NOT NULL",
            (status, reason, _utc_stamp(), command_id, owner)).rowcount
    if not changed:
        raise HTTPException(status_code=404, detail="no such command waiting for an answer")
    return {"ok": True}


# ------------------------------------------------------------------- live view
#
# Each game sends a small picture of its screen a few times a second (POST /live/frame, with its API key). The newest picture of
# every account is kept in memory only: nothing is written to the disk and nothing is kept for later. The pictures can be looked at
# only from the PC the website runs on (see _is_this_pc): another PC on the network, or a friend over Tailscale, gets a refusal.
# The game is told whether the page is being watched right now, and sends about 3 pictures a second while it is, one every 10
# seconds when it is not.

LIVE_MAX_BYTES = 400_000            # a 640 pixel wide JPEG is about 40 KB; anything much bigger is not from the mod
LIVE_STALE_S = 40                   # a picture older than this is not shown any more (the game closed, or stopped sending)
LIVE_WATCH_S = 6                    # the page counts as watching for this long after it last asked for a picture
_LIVE_UUID = re.compile(r"^[0-9a-fA-F-]{32,36}$")
_live_frames: Dict[str, dict] = {}
_live_input_on: Dict[str, tuple] = {}         # player_uuid -> (does the game take input, when it said so)
_live_input: Dict[str, deque] = {}            # player_uuid -> input waiting for the game to fetch it
_live_state: Dict[str, dict] = {}             # player_uuid -> what the game says about itself with each picture
_live_seq = 0                                 # counts pictures, so the page can skip one it already has
LIVE_INPUT_TTL_S = 5                          # an input the game has not fetched by then is dropped, never run late
LIVE_INPUT_MAX = 40
_live_watched: Dict[str, float] = {}
_live_lock = threading.Lock()


THIS_PC_CACHE_S = 60
_this_pc_cache: dict = {}                       # ip -> (when checked, is it this PC's own address)


def _is_this_pc(host: Optional[str]) -> bool:
    """True when a request comes from the PC the website runs on: loopback, or one of this PC's own addresses.

    A browser on this PC that opens the website by its LAN or Tailscale address comes in from that address, not from 127.0.0.1, so
    an address counts when this PC can bind a socket to it (which only works for an address that belongs to this PC).
    """
    if not host:
        return False
    try:
        ip = ipaddress.ip_address(host.split("%")[0])
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    if ip.is_loopback:
        return True
    now = time.monotonic()
    cached = _this_pc_cache.get(ip)
    if cached and now - cached[0] < THIS_PC_CACHE_S:
        return cached[1]
    family = socket.AF_INET6 if ip.version == 6 else socket.AF_INET
    try:
        with socket.socket(family, socket.SOCK_STREAM) as probe:
            probe.bind((str(ip), 0))
        mine = True
    except OSError:
        mine = False
    if len(_this_pc_cache) > 256:
        _this_pc_cache.clear()                          # remote addresses are unbounded; this is only a speed-up
    _this_pc_cache[ip] = (now, mine)
    return mine


def _live_clean(raw: Optional[str], legacy_input: Optional[str]) -> dict:
    """What the game says about itself (the URL-encoded JSON in X-Live-State), checked field by field: the held slot (0 to 8), the
    held item's name, whether a menu is open, where the player stands, and whether the game takes input. Anything odd is dropped."""
    state: dict = {"input": legacy_input == "1", "slot": None, "held": "", "menu": False, "pos": None}
    if not raw:
        return state
    try:
        data = json.loads(urllib.parse.unquote(raw))
    except ValueError:
        return state
    if not isinstance(data, dict):
        return state
    state["input"] = data.get("input") is True
    slot = data.get("slot")
    if isinstance(slot, int) and not isinstance(slot, bool) and 0 <= slot <= 8:
        state["slot"] = slot
    held = data.get("held")
    if isinstance(held, str):
        state["held"] = "".join(c for c in held if c.isprintable())[:60]
    state["menu"] = data.get("menu") is True
    pos = data.get("pos")
    if (isinstance(pos, list) and len(pos) == 3
            and all(isinstance(v, (int, float)) and not isinstance(v, bool) and abs(v) < 3e7 for v in pos)):
        state["pos"] = [int(round(v)) for v in pos]
    return state


def _live_input_off() -> set:
    """Accounts whose input is switched off on the website (kept in config.json so a restart does not switch it back on)."""
    return {str(u) for u in _read_config().get("live_input_off", [])}


def _require_this_pc(request: Request) -> None:
    host = request.client.host if request.client else None
    if not _is_this_pc(host):
        raise HTTPException(status_code=403, detail="the live view can only be opened on the PC the website runs on")


@app.post("/live/frame")
async def live_frame(request: Request, player_uuid: str = Query(...), x_api_key: Optional[str] = Header(default=None),
                     x_live_input: Optional[str] = Header(default=None), x_live_state: Optional[str] = Header(default=None)):
    """The game's newest screen picture (a JPEG in the body). Answers with whether the page is watching right now."""
    player = require_key(x_api_key)
    if not _LIVE_UUID.match(player_uuid):
        raise HTTPException(status_code=422, detail="bad player_uuid")
    check_bound(player, player_uuid)

    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > LIVE_MAX_BYTES:
        raise HTTPException(status_code=413, detail="picture too big")
    body = await request.body()
    if len(body) > LIVE_MAX_BYTES:
        raise HTTPException(status_code=413, detail="picture too big")
    if not body.startswith(b"\xff\xd8\xff"):
        raise HTTPException(status_code=422, detail="not a JPEG")

    now = time.time()
    state = _live_clean(x_live_state, x_live_input)
    global _live_seq
    with _live_lock:
        _live_seq += 1
        _live_frames[player_uuid] = {"jpeg": body, "at": now, "seq": _live_seq}
        _live_input_on[player_uuid] = (state["input"], now)
        _live_state[player_uuid] = state
        for key in [k for k, v in _live_frames.items() if now - v["at"] > 300]:
            del _live_frames[key]
        watching = now - _live_watched.get(player_uuid, 0) <= LIVE_WATCH_S
    return {"viewers": 1 if watching else 0}


@app.get("/liveview/status")
def live_status(request: Request):
    """Which accounts have a picture right now, and how old it is. Only from the PC the website runs on."""
    _require_this_pc(request)
    now = time.time()
    with _live_lock:
        fresh = {k: v for k, v in _live_frames.items() if now - v["at"] <= LIVE_STALE_S}
        return {
            "frames": {k: round(now - v["at"], 1) for k, v in fresh.items()},
            "input": {k: bool(on) for k, (on, at) in _live_input_on.items() if now - at <= LIVE_STALE_S},
            "accounts": {k: {"age": round(now - v["at"], 1), "seq": v["seq"], "bytes": len(v["jpeg"]), **_live_state.get(k, {})}
                         for k, v in fresh.items()},
            "disabled": sorted(_live_input_off()),
        }


@app.get("/liveview/{player_uuid}/frame.jpg")
def live_picture(player_uuid: str, request: Request, since: Optional[int] = Query(default=None)):
    """The newest picture of one account. Asking for it is what tells the game to send faster. With since=N (the number of the
    picture the page already has) an unchanged screen answers 204 with no body, so nothing is sent twice."""
    _require_this_pc(request)
    now = time.time()
    with _live_lock:
        _live_watched[player_uuid] = now
        frame = _live_frames.get(player_uuid)
    if frame is None or now - frame["at"] > LIVE_STALE_S:
        raise HTTPException(status_code=404, detail="no picture from this account right now")
    headers = {"Cache-Control": "no-store", "X-Frame-Seq": str(frame["seq"]), "X-Frame-Age": str(round(now - frame["at"], 2))}
    if since is not None and since == frame["seq"]:
        return Response(status_code=204, headers=headers)
    return Response(content=frame["jpeg"], media_type="image/jpeg", headers=headers)


class LiveInputIn(BaseModel):
    type: str
    x: Optional[float] = Field(default=None, ge=0, le=1)
    y: Optional[float] = Field(default=None, ge=0, le=1)
    button: int = Field(default=0, ge=0, le=2)
    shift: bool = False
    n: Optional[int] = Field(default=None, ge=1, le=9)
    dy: float = Field(default=0, ge=-10, le=10)
    key: Optional[str] = None
    text: Optional[str] = Field(default=None, max_length=64)


LIVE_KEYS = ("escape", "enter", "backspace")


def _live_event(body: LiveInputIn) -> dict:
    """The input as the game will get it, or a 422 when it lacks what its kind needs."""
    kind = body.type
    if kind in ("click", "scroll"):
        if body.x is None or body.y is None:
            raise HTTPException(status_code=422, detail=kind + " needs x and y")
        if kind == "click":
            return {"type": "click", "x": body.x, "y": body.y, "button": body.button, "shift": body.shift}
        return {"type": "scroll", "x": body.x, "y": body.y, "dy": body.dy}
    if kind == "slot":
        if body.n is None:
            raise HTTPException(status_code=422, detail="slot needs n (1 to 9)")
        return {"type": "slot", "n": body.n}
    if kind == "key":
        if body.key not in LIVE_KEYS:
            raise HTTPException(status_code=422, detail="key must be one of " + ", ".join(LIVE_KEYS))
        return {"type": "key", "key": body.key}
    if kind == "text":
        if not body.text or any(ord(c) < 32 for c in body.text):
            raise HTTPException(status_code=422, detail="text needs printable characters")
        return {"type": "text", "text": body.text}
    raise HTTPException(status_code=422, detail="unknown input type")


@app.post("/liveview/{player_uuid}/input")
def live_send_input(player_uuid: str, body: LiveInputIn, request: Request, x_admin_token: Optional[str] = Header(default=None)):
    """A click, hotbar slot, scroll, key or typed text for one account's game. Needs this PC and the admin token, and the game must
    have input switched on (/liveinput on): the game says so with every picture."""
    _require_this_pc(request)
    _require_admin(x_admin_token)
    if not _LIVE_UUID.match(player_uuid):
        raise HTTPException(status_code=422, detail="bad player_uuid")
    event = _live_event(body)
    if player_uuid in _live_input_off():
        raise HTTPException(status_code=409, detail="input is switched off for this account: turn it on in the Live tab")
    now = time.time()
    with _live_lock:
        _live_watched[player_uuid] = now
        on, at = _live_input_on.get(player_uuid, (False, 0))
        if not on or now - at > LIVE_STALE_S:
            raise HTTPException(status_code=409, detail="that game does not take input: type /liveinput on in it")
        queue = _live_input.setdefault(player_uuid, deque(maxlen=LIVE_INPUT_MAX))
        event["at"] = now
        queue.append(event)
        return {"queued": len(queue)}


@app.get("/live/input")
def live_take_input(player_uuid: str = Query(...), x_api_key: Optional[str] = Header(default=None)):
    """The game fetches what was sent to it since it last asked. Only what is younger than a few seconds; the rest is dropped."""
    player = require_key(x_api_key)
    if not _LIVE_UUID.match(player_uuid):
        raise HTTPException(status_code=422, detail="bad player_uuid")
    check_bound(player, player_uuid)
    now = time.time()
    with _live_lock:
        queue = _live_input.get(player_uuid)
        events = [{k: v for k, v in e.items() if k != "at"} for e in (queue or ()) if now - e["at"] <= LIVE_INPUT_TTL_S]
        if queue is not None:
            queue.clear()
        watching = now - _live_watched.get(player_uuid, 0) <= LIVE_WATCH_S
    return {"events": events, "viewers": 1 if watching else 0}


class LiveSwitchIn(BaseModel):
    enabled: bool


@app.post("/liveview/{player_uuid}/input-enabled")
def live_input_switch(player_uuid: str, body: LiveSwitchIn, request: Request, x_admin_token: Optional[str] = Header(default=None)):
    """Switches input on or off for one account, from the Live tab. On unless switched off, and remembered across restarts."""
    _require_this_pc(request)
    _require_admin(x_admin_token)
    if not _LIVE_UUID.match(player_uuid):
        raise HTTPException(status_code=422, detail="bad player_uuid")
    if not body.enabled:
        with _live_lock:
            _live_input.pop(player_uuid, None)          # nothing already waiting may run after it was switched off
    with CONFIG_LOCK:                                   # same lock as register(), so neither loses the other's write
        config = _read_config()
        off = {str(u) for u in config.get("live_input_off", [])}
        if body.enabled:
            off.discard(player_uuid)
        else:
            off.add(player_uuid)
        config["live_input_off"] = sorted(off)
        _write_config(config)
    return {"enabled": body.enabled}


# ------------------------------------------------------------ mod updates
#
# The newest mod jar (the one built in the mod folder beside this one, or the file named by "mod_jar" in config.json) is offered to
# every linked game, which downloads it when it differs from the jar it runs and installs it once the game is closed. Only a game
# with a valid API key can get it.

_mod_cache: dict = {"key": None, "name": None, "data": b"", "sha": ""}
_mod_lock = threading.Lock()


def _mod_jar_path() -> Optional[Path]:
    configured = _read_config().get("mod_jar")
    if configured:
        path = Path(configured)
        return path if path.is_file() else None
    libs = BASE.parent / "mod" / "build" / "libs"
    jars = [p for p in libs.glob("femboyclient-*.jar") if not re.search(r"sources|dev|javadoc", p.name)]
    return max(jars, key=lambda p: p.stat().st_mtime) if jars else None


def _mod_latest() -> Optional[dict]:
    """The newest jar's name, bytes and SHA-256, read again only when the file changed."""
    path = _mod_jar_path()
    if path is None:
        return None
    stat = path.stat()
    key = (str(path), stat.st_mtime_ns, stat.st_size)
    with _mod_lock:
        if _mod_cache["key"] != key:
            data = path.read_bytes()
            _mod_cache.update(key=key, name=path.name, data=data, sha=hashlib.sha256(data).hexdigest())
        return dict(_mod_cache)


_mod_b64_cache: dict = {"sha": None, "text": ""}


def _mod_b64(latest: dict) -> str:
    """The jar as base64 text, encoded again only when the jar changed."""
    with _mod_lock:
        if _mod_b64_cache["sha"] != latest["sha"]:
            _mod_b64_cache.update(sha=latest["sha"], text=base64.b64encode(latest["data"]).decode("ascii"))
        return _mod_b64_cache["text"]


@app.get("/mod/latest.json")
def mod_latest_info(x_api_key: Optional[str] = Header(default=None)):
    """What the newest mod jar is, so a game can tell whether it is out of date."""
    require_key(x_api_key)
    latest = _mod_latest()
    if latest is None:
        raise HTTPException(status_code=404, detail="no mod jar is available on this website")
    return {"name": latest["name"], "sha256": latest["sha"], "size": len(latest["data"])}


@app.get("/mod/latest.b64")
def mod_latest_download(x_api_key: Optional[str] = Header(default=None)):
    """The newest mod jar, as base64 text."""
    require_key(x_api_key)
    latest = _mod_latest()
    if latest is None:
        raise HTTPException(status_code=404, detail="no mod jar is available on this website")
    return PlainTextResponse(_mod_b64(latest), headers={"X-Sha256": latest["sha"]})


# ------------------------------------------------------------------- pages

# The website's page, script (app.js) and styles (style.css).
class FreshStaticFiles(StaticFiles):
    """Static files that the browser must re-check every time, so an updated page is never stale."""

    async def get_response(self, path, scope):
        response = await super().get_response(path, scope)
        response.headers["Cache-Control"] = "no-cache"
        return response


app.mount("/static", GZipMiddleware(FreshStaticFiles(directory=STATIC_DIR), minimum_size=1024), name="static")   # compressed here only: SSE routes must stay unbuffered


@app.get("/")
def index(request: Request):
    """Serve the page.

    Its script and stylesheet links get a version (the time they last changed), so a
    browser can never keep running an old copy of them after an update.

    The Live tab (the game screen, and the chat and quit controls) is written between THISPC markers in the page. A request from
    any PC but the one the website runs on gets the page with everything between the markers cut out, so it is not just hidden.
    """
    files = [STATIC_DIR / "index.html", STATIC_DIR / "style.css", *STATIC_DIR.glob("*.js")]
    version = str(int(max(f.stat().st_mtime for f in files)))
    html = (STATIC_DIR / "index.html").read_text(encoding="utf-8")
    if _is_this_pc(request.client.host if request.client else None):
        html = html.replace("<!--THISPC-->", "").replace("<!--/THISPC-->", "")
    else:
        html = re.sub(r"<!--THISPC-->.*?<!--/THISPC-->", "", html, flags=re.S)
    html = re.sub(r'(/static/[\w.-]+\.(?:js|css))"', lambda m: m.group(1) + "?v=" + version + '"', html)
    return HTMLResponse(html, headers={"Cache-Control": "no-cache"})


@app.get("/health")
def health():
    with db() as conn:
        players = conn.execute("SELECT COUNT(*) AS c FROM players").fetchone()["c"]
    return {"status": "ok", "players": players}


def _lan_address() -> Optional[str]:
    """This PC's address on the local network, for linking a second PC."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))  # no packet is sent
            return s.getsockname()[0]
    except OSError:
        return None


def announce() -> None:
    """Print the details you need to link a browser or another PC."""
    config = ensure_config()
    lan = _lan_address()
    print("=" * 66)
    print("Femboy Client server is running.")
    print("  Website         : http://127.0.0.1:8000/")
    print("  Enrol secret    : in config.json (\"enroll_secret\"), not printed so it stays out of server.log")
    if lan:
        print("  Another PC uses : http://%s:8000   (start with --host 0.0.0.0)" % lan)
    print("=" * 66)
