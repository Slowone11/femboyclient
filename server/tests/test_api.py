"""Tests for the Femboy Client API."""

import sqlite3

import app as app_module
from conftest import SECRET, UUID_A, UUID_B, iso, make_row, register

import pytest


# ----------------------------------------------------------------- register

def test_register_rejects_a_missing_or_wrong_secret(client):
    body = {"player_uuid": UUID_A, "player_name": "Steve"}
    assert client.post("/register", json=body).status_code == 401
    assert client.post("/register", json=body, headers={"X-Enroll-Secret": "nope"}).status_code == 401


def test_register_creates_a_key_then_returns_the_same_one(client):
    first = client.post("/register", json={"player_uuid": UUID_A, "player_name": "Steve"},
                        headers={"X-Enroll-Secret": SECRET}).json()
    second = client.post("/register", json={"player_uuid": UUID_A.upper(), "player_name": "Steve2"},
                         headers={"X-Enroll-Secret": SECRET}).json()

    assert first["created"] is True
    assert second["created"] is False
    assert first["api_key"] == second["api_key"]  # a second PC gets the same key


def test_register_gives_different_accounts_different_keys(client):
    assert register(client, UUID_A) != register(client, UUID_B, "Alex")


def test_register_rejects_a_bad_uuid(client):
    response = client.post("/register", json={"player_uuid": "not-a-uuid", "player_name": "x"},
                           headers={"X-Enroll-Secret": SECRET})
    assert response.status_code == 422


# ---------------------------------------------------------------------- log

def test_log_requires_a_valid_key(client):
    assert client.post("/log", json=[make_row()]).status_code == 401
    assert client.post("/log", json=[make_row()], headers={"X-API-Key": "wrong"}).status_code == 401


def test_log_stores_rows_and_ignores_duplicates(client):
    key = register(client)
    batch = [make_row(timestamp=iso(2)), make_row(timestamp=iso(1))]

    first = client.post("/log", json=batch, headers={"X-API-Key": key}).json()
    again = client.post("/log", json=batch, headers={"X-API-Key": key}).json()

    assert first["inserted"] == 2
    assert again["inserted"] == 0 and again["duplicates"] == 2


def test_a_key_cannot_write_for_a_different_account(client):
    key = register(client, UUID_A)
    response = client.post("/log", json=[make_row(player_uuid=UUID_B)], headers={"X-API-Key": key})
    assert response.status_code == 403


def test_a_bad_row_rejects_the_whole_batch(client):
    key = register(client)
    batch = [make_row(timestamp=iso(2)), make_row(timestamp="not a time")]

    assert client.post("/log", json=batch, headers={"X-API-Key": key}).status_code == 422
    assert client.get("/health").json()["rows"] == 0  # the good row was not half-stored


def test_a_timestamp_without_a_timezone_is_rejected(client):
    key = register(client)
    response = client.post("/log", json=[make_row(timestamp="2026-09-20T23:28:57")],
                           headers={"X-API-Key": key})
    assert response.status_code == 422


def test_an_oversized_batch_is_refused(client):
    key = register(client)
    too_many = [make_row(timestamp=iso(), session_id="s%d" % i) for i in range(app_module.MAX_BATCH + 1)]
    assert client.post("/log", json=too_many, headers={"X-API-Key": key}).status_code == 413


def test_long_messages_are_truncated_not_rejected(client):
    key = register(client)
    response = client.post("/log", json=[make_row(cause_message="x" * 5000)], headers={"X-API-Key": key})
    assert response.status_code == 200

    rows = client.get("/sessions/session-1").json()
    assert len(rows[0]["cause_message"]) == app_module.MAX_MESSAGE_CHARS


# ------------------------------------------------------------------ privacy

LEAKED = [
    "[371] ✿ [MVP+] CatBoyLangtu_: Ming",
    "[120] ⌬ [VIP+] rogatxa: watchu doing here in the hub",
    "[365] ᛝ [MVP+] DonQuarion: [365] ᛝ [MVP+] DonQuarion: buying items",
    "[211] ☠ [VIP] Sickah: ROYO_VX get a pig pet u noob",
    "[MVP+] Notch: hey",
    "Steve: hello",
    "Party > [VIP] Steve: lets go",
    "Guild > [MVP+] Alex [Officer]: gg",
    "From [MVP++] Bob: hi",
    "To Bob: hi",
]

KEPT = [
    "[Bazaar] Bought 64x Enchanted Bone for 56,026 coins!",
    "[Bazaar] Sold 192x Enchanted Magma Cream for 278,246 coins!",
    "[Bazaar] Executing instant sell...",
    "ALLOWANCE! You earned 50,000 coins!",
    "[MVP+] WeTrollALittle joined the lobby!",
    "Flight Duration: 21:49:48",
    "You have 27 items stashed away!",
    "To be claimed later",
    "From the Bazaar you collected 5,000 coins",
]


@pytest.mark.parametrize("text", LEAKED)
def test_player_chat_is_recognised(text):
    assert app_module.looks_like_player_chat(text) is True


@pytest.mark.parametrize("text", KEPT)
def test_server_messages_are_kept(text):
    assert app_module.looks_like_player_chat(text) is False


def test_the_server_blanks_player_chat_even_if_a_mod_sends_it(client):
    key = register(client)
    rows = [make_row(timestamp=iso(i), cause_message=text) for i, text in enumerate(LEAKED)]

    result = client.post("/log", json=rows, headers={"X-API-Key": key}).json()
    stored = client.get("/sessions/session-1").json()

    assert result["sanitised"] == len(LEAKED)
    assert all(r["cause_message"] == "" and r["cause_label"] == "unknown" for r in stored)
    assert all(r["change"] == 100 for r in stored)  # the coin data itself is kept


def test_player_chat_is_kept_only_when_the_mod_says_the_user_allowed_it(client):
    key = register(client)
    text = LEAKED[0]

    client.post("/log", json=[make_row(timestamp=iso(2), cause_message=text)], headers={"X-API-Key": key})
    client.post("/log", json=[make_row(timestamp=iso(1), cause_message=text)],
                headers={"X-API-Key": key, "X-Player-Chat": "allowed"})

    stored = client.get("/sessions/session-1").json()
    assert [r["cause_message"] for r in stored] == ["", text]


# ------------------------------------------------------------------ sessions

def test_rows_are_ordered_by_real_time_not_by_timezone_text(client):
    key = register(client)
    # The OLDER row is written in UTC+9 (its clock reads 13 hours "later") and the
    # NEWER row in UTC-4. Sorted as text the newer row comes first, which is wrong;
    # sorted as real instants the older row comes first.
    older = make_row(timestamp=iso(10, offset_hours=9), coins=1)
    newer = make_row(timestamp=iso(5, offset_hours=-4), coins=2)
    assert older["timestamp"] > newer["timestamp"]  # guards the test itself: text order really is wrong

    client.post("/log", json=[newer, older], headers={"X-API-Key": key})

    assert [r["coins"] for r in client.get("/sessions/session-1").json()] == [1, 2]


def test_sessions_list_totals_and_newest_first(client):
    key = register(client)
    rows = [
        make_row(session_id="old", timestamp=iso(120), coins=100, change=0, cause_label="session_start", cause_message=""),
        make_row(session_id="old", timestamp=iso(119), coins=150, change=50),
        make_row(session_id="new", timestamp=iso(10), coins=500, change=0, cause_label="session_start", cause_message=""),
        make_row(session_id="new", timestamp=iso(9), coins=300, change=-200),
        make_row(session_id="new", timestamp=iso(8), coins=350, change=50),
    ]
    client.post("/log", json=rows, headers={"X-API-Key": key})

    sessions = client.get("/sessions").json()
    assert [s["session_id"] for s in sessions] == ["new", "old"]
    assert (sessions[0]["gained"], sessions[0]["lost"], sessions[0]["net"]) == (50, 200, -150)


def test_sessions_from_different_timezones_are_still_ordered_by_real_time(client):
    key = register(client)
    # Same trap as above, at session level: the older session is written in UTC+9.
    client.post("/log", json=[
        make_row(session_id="older-tokyo", timestamp=iso(60, offset_hours=9)),
        make_row(session_id="newer-nyc", timestamp=iso(5, offset_hours=-4)),
    ], headers={"X-API-Key": key})

    assert [s["session_id"] for s in client.get("/sessions").json()] == ["newer-nyc", "older-tokyo"]


def test_sessions_can_be_filtered_to_one_player(client):
    key_a, key_b = register(client, UUID_A), register(client, UUID_B, "Alex")
    client.post("/log", json=[make_row(session_id="a1")], headers={"X-API-Key": key_a})
    client.post("/log", json=[make_row(session_id="b1", player_uuid=UUID_B, player_name="Alex")],
                headers={"X-API-Key": key_b})

    only_b = client.get("/sessions", params={"player_uuid": UUID_B}).json()
    assert [s["session_id"] for s in only_b] == ["b1"]


def test_an_unknown_session_is_a_404(client):
    assert client.get("/sessions/nope").status_code == 404


# ---------------------------------------------------------- merged (all sessions)

def test_rows_merges_every_session_in_real_time_order(client):
    key = register(client)
    # Three sessions written in different time zones, posted out of order. As text
    # their timestamps sort wrongly, so this only passes if real instants are used.
    client.post("/log", json=[
        make_row(session_id="c", timestamp=iso(5, offset_hours=-4), coins=3),
        make_row(session_id="a", timestamp=iso(600, offset_hours=9), coins=1),
        make_row(session_id="b", timestamp=iso(60, offset_hours=-7), coins=2),
        make_row(session_id="b", timestamp=iso(59, offset_hours=-7), coins=22),
    ], headers={"X-API-Key": key})

    result = client.get("/rows", params={"player_uuid": UUID_A}).json()

    assert [r["coins"] for r in result["rows"]] == [1, 2, 22, 3]
    assert [r["session_id"] for r in result["rows"]] == ["a", "b", "b", "c"]
    assert result["truncated"] is False


def test_rows_are_only_the_requested_accounts(client):
    key_a, key_b = register(client, UUID_A), register(client, UUID_B, "Alex")
    client.post("/log", json=[make_row(coins=111)], headers={"X-API-Key": key_a})
    client.post("/log", json=[make_row(player_uuid=UUID_B, player_name="Alex", coins=999)],
                headers={"X-API-Key": key_b})

    rows = client.get("/rows", params={"player_uuid": UUID_B}).json()["rows"]
    assert [r["coins"] for r in rows] == [999]


def test_rows_over_the_limit_keep_the_newest_and_say_so(client):
    key = register(client)
    client.post("/log", json=[make_row(timestamp=iso(60 - i), coins=i) for i in range(10)],
                headers={"X-API-Key": key})

    result = client.get("/rows", params={"player_uuid": UUID_A, "limit": 4}).json()

    assert [r["coins"] for r in result["rows"]] == [6, 7, 8, 9]   # the newest four, oldest first
    assert result["truncated"] is True and result["limit"] == 4


def test_rows_exactly_at_the_limit_are_not_marked_truncated(client):
    key = register(client)
    client.post("/log", json=[make_row(timestamp=iso(60 - i), coins=i) for i in range(4)],
                headers={"X-API-Key": key})

    result = client.get("/rows", params={"player_uuid": UUID_A, "limit": 4}).json()
    assert len(result["rows"]) == 4 and result["truncated"] is False


def test_rows_for_an_account_with_no_data_is_empty_not_an_error(client):
    result = client.get("/rows", params={"player_uuid": UUID_B}).json()
    assert result == {"rows": [], "truncated": False, "limit": app_module.DEFAULT_ROWS}


@pytest.mark.parametrize("params", [
    {"player_uuid": "nope"},
    {"player_uuid": UUID_A, "limit": 0},
    {"player_uuid": UUID_A, "limit": -1},
    {"player_uuid": UUID_A, "limit": app_module.MAX_ROWS + 1},
    {"player_uuid": UUID_A, "limit": "many"},
    {"limit": 0},
])
def test_rows_rejects_bad_input(client, params):
    assert client.get("/rows", params=params).status_code == 422


def test_the_live_stream_can_be_limited_to_one_account(client):
    key_a, key_b = register(client, UUID_A), register(client, UUID_B, "Alex")
    client.post("/log", json=[make_row(session_id="a1")], headers={"X-API-Key": key_a})
    client.post("/log", json=[make_row(session_id="b1", player_uuid=UUID_B, player_name="Alex")],
                headers={"X-API-Key": key_b})

    assert {r["session_id"] for r in app_module._fetch_new_rows(0, None, UUID_A)} == {"a1"}
    assert {r["session_id"] for r in app_module._fetch_new_rows(0, None, UUID_B)} == {"b1"}
    assert len(app_module._fetch_new_rows(0, None, None)) == 2
    assert app_module._fetch_new_rows(0, "a1", UUID_B) == []   # both filters apply together


def test_the_live_stream_refuses_a_bad_account_id_up_front(client):
    assert client.get("/live", params={"player_uuid": "nope"}).status_code == 422


# ------------------------------------------------------------------- summary

def test_summary_windows_only_count_rows_inside_them(client):
    key = register(client)
    rows = [
        make_row(timestamp=iso(30), change=1000),        # inside 1h
        make_row(timestamp=iso(40), change=-400),        # inside 1h
        make_row(timestamp=iso(180), change=5000),       # inside 6h only
        make_row(timestamp=iso(60 * 20), change=-7000),  # inside 24h only
        make_row(timestamp=iso(60 * 30), change=99999),  # outside everything
    ]
    client.post("/log", json=rows, headers={"X-API-Key": key})

    result = client.get("/summary", params={"player_uuid": UUID_A, "hours": "1,6,24"}).json()
    by_hours = {w["hours"]: w for w in result["windows"]}

    assert (by_hours[1]["gained"], by_hours[1]["lost"], by_hours[1]["net"]) == (1000, 400, 600)
    assert (by_hours[6]["gained"], by_hours[6]["lost"]) == (6000, 400)
    assert (by_hours[24]["gained"], by_hours[24]["lost"], by_hours[24]["net"]) == (6000, 7400, -1400)


def test_summary_only_counts_the_requested_account(client):
    key_a, key_b = register(client, UUID_A), register(client, UUID_B, "Alex")
    client.post("/log", json=[make_row(change=100)], headers={"X-API-Key": key_a})
    client.post("/log", json=[make_row(player_uuid=UUID_B, change=9999)], headers={"X-API-Key": key_b})

    windows = client.get("/summary", params={"player_uuid": UUID_A, "hours": "1"}).json()["windows"]
    assert windows[0]["gained"] == 100


def test_summary_with_no_data_is_all_zero(client):
    windows = client.get("/summary", params={"player_uuid": UUID_A}).json()["windows"]
    assert [w["hours"] for w in windows] == [1, 6, 24]
    assert all(w["gained"] == 0 and w["lost"] == 0 and w["net"] == 0 for w in windows)


@pytest.mark.parametrize("hours", ["abc", "0", "-3", "1,,x", "999999", "1,2,3,4,5,6,7,8,9", ""])
def test_summary_rejects_bad_hours(client, hours):
    assert client.get("/summary", params={"player_uuid": UUID_A, "hours": hours}).status_code == 422


def test_summary_accepts_spaces_and_decimals(client):
    windows = client.get("/summary", params={"player_uuid": UUID_A, "hours": "0.5 2"}).json()["windows"]
    assert [w["hours"] for w in windows] == [0.5, 2]


# ------------------------------------------------------- heartbeat and players

def beat(client, key, **overrides):
    body = {"player_uuid": UUID_A, "player_name": "WeTrollALittle", "in_skyblock": True, "session_id": "s1"}
    body.update(overrides)
    return client.post("/heartbeat", json=body, headers={"X-API-Key": key})


def test_heartbeat_needs_a_valid_key_for_that_account(client):
    key = register(client, UUID_A)
    assert client.post("/heartbeat", json={"player_uuid": UUID_A, "player_name": "x"}).status_code == 401
    assert beat(client, key, player_uuid=UUID_B).status_code == 403


def test_an_account_is_online_after_a_heartbeat(client):
    key = register(client)
    beat(client, key)

    player = client.get("/players").json()[0]
    assert player["online"] is True and player["in_skyblock"] is True and player["session_id"] == "s1"


def test_leaving_skyblock_clears_the_skyblock_flag(client):
    key = register(client)
    beat(client, key)
    beat(client, key, in_skyblock=False, session_id=None)

    player = client.get("/players").json()[0]
    assert player["online"] is True and player["in_skyblock"] is False


def test_the_goodbye_heartbeat_marks_the_account_offline(client):
    key = register(client)
    beat(client, key)
    beat(client, key, online=False)

    player = client.get("/players").json()[0]
    assert player["online"] is False and player["in_skyblock"] is False


def test_an_account_goes_offline_when_heartbeats_stop(client):
    key = register(client)
    beat(client, key)
    with app_module.db() as conn:
        conn.execute("UPDATE players SET last_seen_ms = last_seen_ms - ?", (app_module.ONLINE_WINDOW_MS + 5000,))

    assert client.get("/players").json()[0]["online"] is False


def test_uploading_rows_counts_as_being_online(client):
    key = register(client)
    with app_module.db() as conn:
        conn.execute("UPDATE players SET status = 'offline'")
    client.post("/log", json=[make_row()], headers={"X-API-Key": key})

    assert client.get("/players").json()[0]["online"] is True


def test_players_lists_every_account_with_latest_coins(client):
    key_a, key_b = register(client, UUID_A), register(client, UUID_B, "Alex")
    client.post("/log", json=[make_row(timestamp=iso(5), coins=111), make_row(timestamp=iso(1), coins=222)],
                headers={"X-API-Key": key_a})
    client.post("/log", json=[make_row(player_uuid=UUID_B, player_name="Alex", coins=999)],
                headers={"X-API-Key": key_b})

    players = {p["player_name"]: p for p in client.get("/players").json()}
    assert players["WeTrollALittle"]["coins"] == 222
    assert players["Alex"]["coins"] == 999
    assert len(players) == 2


def test_a_renamed_account_shows_its_new_name(client):
    register(client, UUID_A, "OldName")
    register(client, UUID_A, "NewName")

    assert [p["player_name"] for p in client.get("/players").json()] == ["NewName"]


# --------------------------------------------------------------------- live

def test_live_helpers_only_return_newer_rows_for_the_session(client):
    key = register(client)
    client.post("/log", json=[make_row(session_id="a", timestamp=iso(3)),
                              make_row(session_id="b", timestamp=iso(2))], headers={"X-API-Key": key})

    newest = app_module._newest_id()
    client.post("/log", json=[make_row(session_id="a", timestamp=iso(1))], headers={"X-API-Key": key})

    assert len(app_module._fetch_new_rows(newest, None)) == 1
    assert len(app_module._fetch_new_rows(newest, "b")) == 0
    assert len(app_module._fetch_new_rows(0, "a")) == 2


# ---------------------------------------------------------------- migration

def test_an_old_database_is_upgraded_and_backfilled(tmp_path, monkeypatch):
    legacy = tmp_path / "legacy.db"
    conn = sqlite3.connect(legacy)
    conn.execute(
        "CREATE TABLE coin_rows (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL,"
        " player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, timestamp TEXT NOT NULL,"
        " coins INTEGER NOT NULL, change INTEGER NOT NULL, cause_label TEXT NOT NULL,"
        " cause_message TEXT NOT NULL DEFAULT '', received_at TEXT NOT NULL,"
        " UNIQUE(session_id, timestamp))")
    conn.execute("INSERT INTO coin_rows (session_id, player_uuid, player_name, timestamp, coins, change,"
                 " cause_label, received_at) VALUES ('s', ?, 'Old', '2026-09-20T23:28:57.9105041-04:00',"
                 " 5, 0, 'session_start', 'x')", (UUID_A,))
    conn.commit()
    conn.close()

    monkeypatch.setattr(app_module, "DB_PATH", legacy)
    app_module.init_db()

    check = sqlite3.connect(legacy)
    assert check.execute("SELECT ts_ms FROM coin_rows").fetchone()[0] == 1789961337910
    assert check.execute("SELECT player_name FROM players").fetchone()[0] == "Old"
    check.close()


def test_the_page_and_its_scripts_are_never_served_from_a_stale_browser_cache(client):
    # The browser may keep them, but must ask the server whether they changed.
    for path in ("/", "/static/app.js", "/static/style.css"):
        assert client.get(path).headers["cache-control"] == "no-cache", path


def test_the_page_links_its_script_and_styles_with_a_version_so_they_are_never_stale(client):
    html = client.get("/").text
    import re
    assert re.search(r'href="/static/style\.css\?v=\d+"', html)
    scripts = re.findall(r'src="/static/(\w+\.js)\?v=\d+"', html)
    assert "app.js" in scripts and "core.js" in scripts
    for name in scripts:
        assert client.get("/static/%s?v=123" % name).status_code == 200, name


def test_the_graph_engine_is_in_the_script_before_the_tracker_and_the_old_library_is_not_needed(client):
    import re
    html = client.get("/").text
    scripts = re.findall(r'src="/static/(\w+\.js)', html)
    assert "window.GraphKit =" in client.get("/static/graph.js").text
    assert scripts.index("graph.js") < scripts.index("tracker.js"), "the engine must load before the tracker"
    assert scripts.index("core.js") < scripts.index("app.js"), "the shared helpers must load first"
    assert "vis-network" not in html and "cdn.jsdelivr.net/npm/vis" not in html

