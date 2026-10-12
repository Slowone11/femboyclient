"""Tests for the player tracker: snapshot, search, tree and live events."""

import json
import sqlite3
import time

import app as app_module
from conftest import SECRET, UUID_A, UUID_B, register

import pytest

ME = "WeTrollALittle"

V1 = "2026-09-20T19:00:00Z"   # the start of the first visit
V2 = "2026-09-21T15:41:00Z"
V3 = "2026-09-21T16:10:00Z"


def snap(client, key, players, when, visit=None, me=ME, **extra):
    body = {"me": me, "players": players, "timestamp": when}
    if visit:
        body["visit_started"] = visit
    body.update(extra)
    return client.post("/tracker/snapshot", json=body, headers={"X-API-Key": key})


def count(table):
    with app_module.db() as conn:
        return conn.execute("SELECT COUNT(*) FROM " + table).fetchone()[0]


def search(client, q, **params):
    return client.get("/tracker/search", params={"q": q, **params}).json()["players"]


# ------------------------------------------------------------------------ auth

def test_a_snapshot_needs_a_valid_key(client):
    body = {"me": ME, "players": ["Alice"], "timestamp": V1}
    assert client.post("/tracker/snapshot", json=body).status_code == 401
    assert client.post("/tracker/snapshot", json=body, headers={"X-API-Key": "nope"}).status_code == 401


def test_a_key_that_is_not_linked_to_an_account_is_refused(client):
    app_module.CONFIG_PATH.write_text(json.dumps(
        {"enroll_secret": SECRET, "api_keys": {"loose-key": {"name": "x", "player_uuid": None}}}), encoding="utf-8")

    assert snap(client, "loose-key", ["Alice"], V1).status_code == 403
    assert count("tracker_players") == 0


# ------------------------------------------- the two-snapshot scenario from the spec

def test_two_snapshots_with_overlapping_names_make_no_duplicates_and_correct_counts(client):
    key = register(client)

    first = snap(client, key, ["Alice", "Bob", "Carol"], "2026-09-20T19:00:10Z", visit=V1).json()
    second = snap(client, key, ["bob", "Carol", "Dave"], "2026-09-21T15:41:10Z", visit=V2).json()

    assert first["new"] == ["Alice", "Bob", "Carol"] and first["returning"] == []
    assert second["new"] == ["Dave"]
    assert sorted(n.lower() for n in second["returning"]) == ["bob", "carol"]

    assert count("tracker_players") == 4, "Bob and bob are one player"
    assert count("tracker_visits") == 2
    assert count("tracker_sightings") == 6

    with app_module.db() as conn:
        lowers = [r[0] for r in conn.execute("SELECT username_lower FROM tracker_players")]
    assert len(lowers) == len(set(lowers))

    bob = search(client, "bob", exact="true")[0]
    assert bob["times_seen"] == 2
    assert bob["first_seen"] == "2026-09-20T19:00:10.000Z"
    assert bob["last_seen"] == "2026-09-21T15:41:10.000Z"
    assert [s["seen_at"] for s in bob["sightings"]] == ["2026-09-21T15:41:10.000Z", "2026-09-20T19:00:10.000Z"]

    assert search(client, "alice", exact="true")[0]["times_seen"] == 1
    assert search(client, "dave", exact="true")[0]["times_seen"] == 1


# ---------------------------------------------------- retries and one visit

def test_sending_the_same_message_again_changes_nothing(client):
    key = register(client)
    snap(client, key, ["Alice", "Bob"], "2026-09-20T19:00:10Z", visit=V1)

    again = snap(client, key, ["Alice", "Bob"], "2026-09-20T19:00:10Z", visit=V1).json()

    assert again["new"] == [] and again["returning"] == [] and again["repeated"] == 2
    assert count("tracker_visits") == 1 and count("tracker_sightings") == 2
    assert search(client, "alice", exact="true")[0]["times_seen"] == 1


def test_players_who_join_later_in_the_same_visit_join_that_visit(client):
    key = register(client)
    snap(client, key, ["Alice", "Bob"], "2026-09-20T19:00:10Z", visit=V1)
    snap(client, key, ["Carol"], "2026-09-20T19:02:00Z", visit=V1)
    again = snap(client, key, ["Alice", "Carol", "Dave"], "2026-09-20T19:03:00Z", visit=V1).json()

    assert count("tracker_visits") == 1
    assert count("tracker_sightings") == 4
    assert again["new"] == ["Dave"] and again["repeated"] == 2


def test_a_player_is_met_once_per_visit_even_after_leaving_and_returning(client):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-20T19:00:10Z", visit=V1)
    snap(client, key, ["Alice"], "2026-09-20T19:05:00Z", visit=V1)   # left and came back

    assert search(client, "alice", exact="true")[0]["times_seen"] == 1


def test_without_a_visit_time_a_message_joins_the_latest_visit_or_starts_one(client):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-20T19:00:00Z")                     # no visit yet, so one is made
    snap(client, key, ["Bob"], "2026-09-20T19:01:00Z")                       # joins it
    assert count("tracker_visits") == 1

    snap(client, key, ["Carol"], "2026-09-20T20:00:00Z", new_visit=True)     # the simple way to say new visit
    assert count("tracker_visits") == 2


def test_a_message_naming_an_older_visit_lands_in_that_visit(client):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-20T19:00:10Z", visit=V1)
    snap(client, key, ["Bob"], "2026-09-21T15:41:10Z", visit=V2)
    snap(client, key, ["Carol"], "2026-09-20T19:00:20Z", visit=V1)          # a delayed message from the first visit

    with app_module.db() as conn:
        first = conn.execute("SELECT id FROM tracker_visits WHERE started_at = '2026-09-20T19:00:00.000Z'").fetchone()
        assert conn.execute("SELECT COUNT(*) FROM tracker_sightings WHERE visit_id = ?", (first["id"],)).fetchone()[0] == 2


# ------------------------------------------------------------- what is accepted

def test_invalid_names_are_ignored_and_the_rest_are_kept(client):
    key = register(client)
    too_long = "x" * 17

    result = snap(client, key, ["", "!A-a", "ab", too_long, "Good_Name", "no spaces", "§aBad"], V1, visit=V1).json()

    assert result["new"] == ["Good_Name"]
    assert result["ignored"] == 6
    assert count("tracker_players") == 1


def test_your_own_name_is_never_recorded(client):
    key = register(client, UUID_A, ME)

    # Both the name sent in "me" and the name of the linked account are skipped, in any case.
    result = snap(client, key, ["wetrollalittle", "OtherName", "Alice"], V1, visit=V1, me="OtherName").json()

    assert result["new"] == ["Alice"]


def test_an_empty_or_all_invalid_lobby_creates_no_visit(client):
    key = register(client)

    assert snap(client, key, [], V1, visit=V1).json()["new"] == []
    assert snap(client, key, ["!A-a", ""], V1, visit=V1).json()["ignored"] == 2

    assert count("tracker_visits") == 0 and count("tracker_players") == 0


def test_too_many_players_in_one_message_are_refused(client):
    key = register(client)
    names = ["Player%d" % i for i in range(app_module.TRACKER_MAX_PLAYERS + 1)]
    assert snap(client, key, names, V1, visit=V1).status_code == 413
    assert count("tracker_players") == 0


def test_a_lobby_of_a_few_hundred_players_is_recorded(client):
    key = register(client)
    names = ["Player%d" % i for i in range(app_module.TRACKER_MAX_PLAYERS)]

    result = snap(client, key, names, V1, visit=V1).json()

    assert len(result["new"]) == app_module.TRACKER_MAX_PLAYERS
    assert count("tracker_sightings") == app_module.TRACKER_MAX_PLAYERS


# -------------------------------------------------------------------- timestamps

def test_times_are_stored_in_utc_whatever_zone_they_arrive_in(client):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-21T15:41:10.5-04:00", visit="2026-09-21T15:40:00-04:00")

    alice = search(client, "alice", exact="true")[0]
    assert alice["first_seen"] == "2026-09-21T19:41:10.500Z"
    assert alice["sightings"][0]["visit_started_at"] == "2026-09-21T19:40:00.000Z"


def test_the_mods_nanosecond_timestamps_are_understood(client):
    key = register(client)
    assert snap(client, key, ["Alice"], "2026-09-21T19:41:03.123456789Z", visit="2026-09-21T19:40:55.987654321Z").status_code == 200


@pytest.mark.parametrize("bad", ["", "yesterday", "2026-09-21T19:41:03", "2026-13-40T99:99:99Z"])
def test_a_bad_or_zoneless_timestamp_is_rejected(client, bad):
    key = register(client)
    assert snap(client, key, ["Alice"], bad).status_code == 422
    assert count("tracker_players") == 0


@pytest.mark.parametrize("bad", ["yesterday", "2026-09-21T19:41:03", "2026-13-40T99:99:99Z"])
def test_a_bad_or_zoneless_visit_time_is_rejected(client, bad):
    key = register(client)
    assert snap(client, key, ["Alice"], V1, visit=bad).status_code == 422
    assert count("tracker_players") == 0


# ------------------------------------------------------------------ privacy

def test_server_details_in_a_message_are_never_stored(client):
    key = register(client)
    snap(client, key, ["Alice"], V1, visit=V1, server="mc.hypixel.net", ip="203.0.113.7",
         lobby="mini264C", address="play.example.com:25565")

    with app_module.db() as conn:
        everything = ""
        for table in ("tracker_players", "tracker_visits", "tracker_sightings"):
            everything += json.dumps([list(r) for r in conn.execute("SELECT * FROM " + table).fetchall()])

    for secret in ("hypixel", "203.0.113.7", "mini264C", "example.com"):
        assert secret not in everything


def test_the_tracker_tables_have_no_server_or_lobby_columns(client):
    banned = ("server", "lobby", "address", "host", "ip", "port")
    with app_module.db() as conn:
        for table in ("tracker_players", "tracker_visits", "tracker_sightings"):
            for column in conn.execute("PRAGMA table_info(" + table + ")").fetchall():
                assert not any(word in column["name"].lower() for word in banned), (table, column["name"])


# -------------------------------------------------------------------------- search

def test_search_matches_part_of_a_name_ignoring_case(client):
    key = register(client)
    snap(client, key, ["Steve", "Steven", "Stevie", "Alex"], V1, visit=V1)

    assert sorted(p["username"] for p in search(client, "STEV")) == ["Steve", "Steven", "Stevie"]
    assert [p["username"] for p in search(client, "lex")] == ["Alex"]
    assert search(client, "nobody") == []


def test_an_exact_match_comes_first_then_prefix_matches(client):
    key = register(client)
    snap(client, key, ["ABsteve", "Steven", "Steve"], V1, visit=V1)

    assert [p["username"] for p in search(client, "steve")][:2] == ["Steve", "Steven"]


def test_search_wildcards_are_taken_literally(client):
    key = register(client)
    snap(client, key, ["A_B", "AxB", "Plain"], V1, visit=V1)

    assert [p["username"] for p in search(client, "a_b")] == ["A_B"], "an underscore is not a wildcard"
    assert search(client, "%") == [], "a percent sign is not a wildcard"


def test_search_lists_every_encounter_newest_first_with_the_account(client):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-20T19:00:10Z", visit=V1)
    snap(client, key, ["Alice"], "2026-09-21T15:41:10Z", visit=V2)
    snap(client, key, ["Alice"], "2026-09-21T16:10:10Z", visit=V3)

    sightings = search(client, "alice")[0]["sightings"]

    assert [s["seen_at"] for s in sightings] == [
        "2026-09-21T16:10:10.000Z", "2026-09-21T15:41:10.000Z", "2026-09-20T19:00:10.000Z"]
    assert sightings[0]["account"] == ME
    assert sightings[0]["visit_started_at"] == "2026-09-21T16:10:00.000Z"


def test_search_can_leave_out_the_encounters_for_a_light_list(client):
    key = register(client)
    snap(client, key, ["Alice"], V1, visit=V1)

    light = search(client, "ali", sightings="false")[0]
    assert "sightings" not in light and light["times_seen"] == 1


def test_exact_search_matches_only_the_whole_name(client):
    key = register(client)
    snap(client, key, ["Steve", "Steven"], V1, visit=V1)

    assert search(client, "stev", exact="true") == []
    assert [p["username"] for p in search(client, "STEVE", exact="true")] == ["Steve"]


def test_search_respects_the_limit(client):
    key = register(client)
    snap(client, key, ["Player%d" % i for i in range(30)], V1, visit=V1)

    assert len(search(client, "player", limit=5)) == 5
    assert len(search(client, "player")) == app_module.SEARCH_DEFAULT_LIMIT


@pytest.mark.parametrize("params", [
    {}, {"q": ""}, {"q": "   "}, {"q": "x" * 33}, {"q": "a", "limit": 0}, {"q": "a", "limit": 101},
])
def test_search_rejects_bad_input(client, params):
    assert client.get("/tracker/search", params=params).status_code == 422


def test_search_covers_every_account_you_play(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    snap(client, key_a, ["Alice"], "2026-09-20T19:00:10Z", visit=V1)
    snap(client, key_b, ["Alice"], "2026-09-21T15:41:10Z", visit=V2, me="AltAccount")

    alice = search(client, "alice", exact="true")[0]
    assert alice["times_seen"] == 2
    assert [s["account"] for s in alice["sightings"]] == ["AltAccount", ME]
    assert count("tracker_players") == 1


# ---------------------------------------------------------------------------- tree

def tree(client, **params):
    return client.get("/tracker/tree", params={"owner": UUID_A, **params}).json()


def test_the_tree_has_you_in_the_centre_a_node_per_world_and_the_players_around_it(client):
    key = register(client)
    snap(client, key, ["Shared", "OnlyFirst"], "2026-09-20T19:00:10Z", visit=V1, world="Hub")
    snap(client, key, ["Shared", "OnlySecond"], "2026-09-21T15:41:10Z", visit=V2, world="Dwarven Mines")

    result = tree(client)
    by_id = {n["id"]: n for n in result["nodes"]}
    world_ids = {n["world"]: n["id"] for n in result["nodes"] if n["type"] == "world"}
    player = {n["label"]: n["id"] for n in result["nodes"] if n["type"] == "player"}

    assert by_id["me"]["label"] == ME and by_id["me"]["type"] == "me"
    assert set(world_ids) == {"Hub", "Dwarven Mines"} and len(player) == 3
    assert not any(n["type"] == "visit" for n in result["nodes"]), "dates are not nodes"

    links = {(l["source"], l["target"]) for l in result["links"]}
    assert all(("me", w) in links for w in world_ids.values())
    assert {s for (s, t) in links if t == player["Shared"]} == set(world_ids.values()), "a player met in two worlds joins both"
    assert {s for (s, t) in links if t == player["OnlyFirst"]} == {world_ids["Hub"]}
    assert by_id[player["Shared"]]["times_seen"] == 2
    assert by_id[player["Shared"]]["worlds"] == {"Hub": 1, "Dwarven Mines": 1}
    assert result["has_more"] is False


def test_a_player_met_in_two_visits_of_the_same_world_has_one_link_with_a_count(client):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-20T19:00:10Z", visit=V1, world="Hub")
    snap(client, key, ["Alice"], "2026-09-21T15:41:10Z", visit=V2, world="Hub")

    result = tree(client)
    alice = next(n["id"] for n in result["nodes"] if n.get("label") == "Alice")
    links = [l for l in result["links"] if l["target"] == alice]

    assert links == [{"source": "w:Hub", "target": alice, "times": 2}]
    hub = next(n for n in result["nodes"] if n["type"] == "world")
    assert (hub["players"], hub["visits"]) == (1, 2)


def test_world_nodes_count_their_players_and_visits(client):
    key = register(client)
    snap(client, key, ["Alice", "Bob"], "2026-09-20T19:00:10Z", visit=V1, world="Hub")
    snap(client, key, ["Bob", "Carol"], "2026-09-21T15:41:10Z", visit=V2, world="Hub")
    snap(client, key, ["Dave"], "2026-09-21T16:41:10Z", visit="2026-09-21T16:41:00Z", world="The End")

    worlds = {n["world"]: (n["players"], n["visits"]) for n in tree(client)["nodes"] if n["type"] == "world"}
    assert worlds == {"Hub": (3, 2), "The End": (1, 1)}


def test_the_tree_pages_backwards_through_players(client):
    key = register(client)
    for hour in range(5):
        start = "2026-09-20T%02d:00:00Z" % (10 + hour)
        snap(client, key, ["Player%d" % hour], "2026-09-20T%02d:00:10Z" % (10 + hour), visit=start)

    page1 = tree(client, players=2)
    page2 = tree(client, players=2, before=page1["next_before"])
    page3 = tree(client, players=2, before=page2["next_before"])

    names = lambda page: [n["label"] for n in page["nodes"] if n["type"] == "player"]
    assert (names(page1), page1["has_more"]) == (["Player4", "Player3"], True)
    assert (names(page2), page2["has_more"]) == (["Player2", "Player1"], True)
    assert (names(page3), page3["has_more"]) == (["Player0"], False)


def test_a_player_met_again_moves_to_the_newest_page(client):
    key = register(client)
    snap(client, key, ["Old"], "2026-09-20T10:00:10Z", visit="2026-09-20T10:00:00Z")
    snap(client, key, ["Mid"], "2026-09-20T11:00:10Z", visit="2026-09-20T11:00:00Z")
    snap(client, key, ["Old"], "2026-09-20T12:00:10Z", visit="2026-09-20T12:00:00Z")

    first = tree(client, players=1)
    assert [n["label"] for n in first["nodes"] if n["type"] == "player"] == ["Old"]


def test_each_account_has_its_own_tree(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    snap(client, key_a, ["Shared", "OnlyMain"], V1, visit=V1)
    snap(client, key_b, ["Shared", "OnlyAlt"], V2, visit=V2, me="AltAccount")

    main = tree(client)
    alt = client.get("/tracker/tree", params={"owner": UUID_B}).json()

    assert sorted(n["label"] for n in main["nodes"] if n["type"] == "player") == ["OnlyMain", "Shared"]
    assert sorted(n["label"] for n in alt["nodes"] if n["type"] == "player") == ["OnlyAlt", "Shared"]
    assert alt["nodes"][0]["label"] == "AltAccount"


def test_an_account_with_no_history_has_an_empty_tree(client):
    result = tree(client)

    assert [n["type"] for n in result["nodes"]] == ["me"]
    assert result["links"] == [] and result["has_more"] is False
    assert result["next_before"] is None


@pytest.mark.parametrize("params", [{"owner": "nope"}, {"players": 0},
                                     {"players": app_module.TREE_MAX_PLAYERS + 1}, {"before": 0}])
def test_the_tree_rejects_bad_input(client, params):
    params = {"owner": UUID_A, **params}
    assert client.get("/tracker/tree", params=params).status_code == 422


def test_the_tree_gives_a_cursor_for_the_live_feed(client):
    key = register(client)
    before = app_module._newest_sighting_id()
    snap(client, key, ["Alice", "Bob"], V1, visit=V1)

    latest = tree(client)["latest_event_id"]
    assert latest == app_module._newest_sighting_id()
    assert latest > before, "the cursor moves forward when players are recorded"
    assert [e["username"] for e in app_module._tracker_events(before, UUID_A)] == ["Alice", "Bob"]


def test_a_large_history_is_fast(client):
    key = register(client)
    for visit in range(60):
        start = "2026-09-%02dT10:%02d:00Z" % (1 + visit // 30, visit % 30)
        snap(client, key, ["Player%d_%d" % (visit, i) for i in range(100)], start, visit=start)

    started = time.time()
    page = tree(client)
    took = time.time() - started

    assert page["has_more"] is True
    assert sum(1 for n in page["nodes"] if n["type"] == "player") == app_module.TREE_DEFAULT_PLAYERS
    assert took < 3, "took %.2fs" % took


# --------------------------------------------------------------------- live events

def test_live_events_say_whether_you_had_met_the_player_before(client):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-20T19:00:10Z", visit=V1)
    snap(client, key, ["Alice", "Bob"], "2026-09-21T15:41:10Z", visit=V2)

    events = app_module._tracker_events(0, UUID_A)

    assert [(e["username"], e["returning"]) for e in events] == [("Alice", False), ("Alice", True), ("Bob", False)]
    assert events[1]["previous_seen_at"] == "2026-09-20T19:00:10.000Z"
    assert events[0]["previous_seen_at"] is None
    assert events[2]["visit_started_at"] == "2026-09-21T15:41:00.000Z"


def test_the_previous_time_is_the_most_recent_earlier_meeting(client):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-20T19:00:10Z", visit=V1)
    snap(client, key, ["Alice"], "2026-09-21T15:41:10Z", visit=V2)
    snap(client, key, ["Alice"], "2026-09-21T16:10:10Z", visit=V3)

    last = app_module._tracker_events(0, UUID_A)[-1]

    assert last["returning"] is True and last["previous_seen_at"] == "2026-09-21T15:41:10.000Z"


def test_live_events_only_include_newer_sightings_for_that_account(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    snap(client, key_a, ["Alice"], V1, visit=V1)
    cursor = app_module._newest_sighting_id()
    snap(client, key_a, ["Bob"], V2, visit=V2)
    snap(client, key_b, ["Carol"], V3, visit=V3, me="AltAccount")

    assert [e["username"] for e in app_module._tracker_events(cursor, UUID_A)] == ["Bob"]
    assert [e["username"] for e in app_module._tracker_events(cursor, UUID_B)] == ["Carol"]
    assert app_module._tracker_events(app_module._newest_sighting_id(), UUID_A) == []


def test_the_live_stream_refuses_a_bad_account_up_front(client):
    assert client.get("/tracker/live", params={"owner": "nope"}).status_code == 422
    assert client.get("/tracker/live", params={"owner": UUID_A, "since": -1}).status_code == 422


# ----------------------------------------------------------------- upgrading

def test_the_tracker_is_added_to_an_existing_database_without_touching_coin_data(tmp_path, monkeypatch):
    legacy = tmp_path / "legacy.db"
    conn = sqlite3.connect(legacy)
    conn.execute(
        "CREATE TABLE coin_rows (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL,"
        " player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, timestamp TEXT NOT NULL,"
        " coins INTEGER NOT NULL, change INTEGER NOT NULL, cause_label TEXT NOT NULL,"
        " cause_message TEXT NOT NULL DEFAULT '', received_at TEXT NOT NULL, UNIQUE(session_id, timestamp))")
    conn.execute("INSERT INTO coin_rows (session_id, player_uuid, player_name, timestamp, coins, change,"
                 " cause_label, received_at) VALUES ('s', ?, 'Old', '2026-09-20T23:28:57-04:00', 5, 0, 'x', 'x')",
                 (UUID_A,))
    conn.commit()
    conn.close()

    monkeypatch.setattr(app_module, "DB_PATH", legacy)
    app_module.init_db()
    app_module.init_db()   # running it twice must be harmless

    check = sqlite3.connect(legacy)
    tables = {r[0] for r in check.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
    indexes = {r[0] for r in check.execute("SELECT name FROM sqlite_master WHERE type = 'index'")}
    assert {"tracker_players", "tracker_visits", "tracker_sightings"} <= tables
    assert {"idx_tracker_players_lower", "idx_tracker_sightings_player", "idx_tracker_sightings_seen"} <= indexes
    assert check.execute("SELECT COUNT(*) FROM coin_rows").fetchone()[0] == 1
    check.close()
