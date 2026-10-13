"""Tests for SkyBlock worlds in the player tracker."""

import json
import sqlite3

import app as app_module
from conftest import SECRET, UUID_A, UUID_B, register

import pytest

ME = "WeTrollALittle"

# Three lobby visits, at different times, in different worlds.
HUB_AT = "2026-09-20T19:00:00Z"
HOLLOWS_AT = "2026-09-21T15:00:00Z"
DHUB_AT = "2026-09-21T16:00:00Z"


def snap(client, key, players, visit, world="Hub", me=ME, when=None, **extra):
    body = {"me": me, "players": players, "timestamp": when or visit.replace("00:00Z", "00:10Z"),
            "visit_started": visit}
    if world is not None:
        body["world"] = world
    body.update(extra)
    return client.post("/tracker/snapshot", json=body, headers={"X-API-Key": key})


def count(table):
    with app_module.db() as conn:
        return conn.execute("SELECT COUNT(*) FROM " + table).fetchone()[0]


def listed(client, world=None, owner=UUID_A, **params):
    """The Players tab's list for one world (or every world), as {username: row}."""
    query = {"owner": owner, "sort": "name", "limit": 500, **params}
    if world is not None:
        query["world"] = world
    return {p["username"]: p for p in client.get("/tracker/players", params=query).json()["players"]}


def search(client, q, *worlds, **params):
    query = [("q", q)] + [("world", w) for w in worlds] + list(params.items())
    return client.get("/tracker/search", params=query).json()["players"]


@pytest.fixture()
def three_worlds(client):
    """Steve is met in three worlds (once in Crystal Hollows); other players are met in one each."""
    key = register(client)
    snap(client, key, ["Steve", "HubOnly", "HubAlso"], HUB_AT, world="Hub")
    snap(client, key, ["Steve", "HollowsOnly"], HOLLOWS_AT, world="Crystal Hollows")
    snap(client, key, ["Steve", "DungeonHubOnly"], DHUB_AT, world="Dungeon Hub")
    snap(client, key, ["Steve", "HubOnly"], "2026-09-22T10:00:00Z", world="Hub")   # Steve and HubOnly again in the Hub
    return key


# ---------------------------------------------------------------------- saving

def test_a_snapshot_saves_its_world_and_says_so(client):
    key = register(client)

    result = snap(client, key, ["Alice"], HUB_AT, world="Crystal Hollows").json()

    assert result["world"] == "Crystal Hollows"
    with app_module.db() as conn:
        assert conn.execute("SELECT world FROM tracker_visits").fetchone()[0] == "Crystal Hollows"


@pytest.mark.parametrize("world", [None, "", "   ", "Unknown"])
def test_a_missing_world_is_unknown(client, world):
    key = register(client)
    assert snap(client, key, ["Alice"], HUB_AT, world=world).json()["world"] == "Unknown"


@pytest.mark.parametrize("bad", [
    "mini264C",              # a server code
    "m28CW",
    "Hub 12",                # a number
    "Hub-1",
    "x" * 33,                # too long
    "a",                     # too short
    "Hub!",
    "<b>Hub</b>",
    "Hub;drop table",
    "ハブ",
])
def test_anything_that_is_not_a_plain_world_name_is_stored_as_unknown(client, bad):
    key = register(client)

    assert snap(client, key, ["Alice"], HUB_AT, world=bad).json()["world"] == "Unknown"
    with app_module.db() as conn:
        assert [r[0] for r in conn.execute("SELECT world FROM tracker_visits")] == ["Unknown"]


@pytest.mark.parametrize("good", ["Hub", "Dungeon Hub", "Crystal Hollows", "Spider's Den", "The Farming Islands", "The Rift"])
def test_the_real_world_names_are_kept(client, good):
    key = register(client)
    assert snap(client, key, ["Alice"], HUB_AT, world=good).json()["world"] == good


def test_spacing_is_tidied(client):
    key = register(client)
    assert snap(client, key, ["Alice"], HUB_AT, world="  Crystal    Hollows ").json()["world"] == "Crystal Hollows"


def test_a_world_keeps_one_spelling_whatever_the_case_sent(client):
    key = register(client)
    snap(client, key, ["Alice"], HUB_AT, world="Crystal Hollows")
    snap(client, key, ["Bob"], HOLLOWS_AT, world="crystal hollows")

    with app_module.db() as conn:
        assert {r[0] for r in conn.execute("SELECT world FROM tracker_visits")} == {"Crystal Hollows"}


def test_a_later_message_can_fill_in_a_world_an_older_mod_left_out(client):
    key = register(client)
    snap(client, key, ["Alice"], HUB_AT, world=None)
    snap(client, key, ["Bob"], HUB_AT, world="Hub")

    with app_module.db() as conn:
        assert [r[0] for r in conn.execute("SELECT world FROM tracker_visits")] == ["Hub"]


def test_a_visit_keeps_the_world_it_started_with(client):
    key = register(client)
    snap(client, key, ["Alice"], HUB_AT, world="Hub")
    snap(client, key, ["Bob"], HUB_AT, world="Crystal Hollows")   # a stray later message

    with app_module.db() as conn:
        assert [r[0] for r in conn.execute("SELECT world FROM tracker_visits")] == ["Hub"]


def test_no_server_details_are_stored_when_a_server_code_is_sent_as_the_world(client):
    key = register(client)
    snap(client, key, ["Alice"], HUB_AT, world="mini264C", server="mc.hypixel.net", lobby="m28CW")

    with app_module.db() as conn:
        dump = json.dumps([list(r) for t in ("tracker_players", "tracker_visits", "tracker_sightings")
                           for r in conn.execute("SELECT * FROM " + t).fetchall()])
    for secret in ("mini264C", "hypixel", "m28CW"):
        assert secret not in dump


# ------------------------------------------------- the player list in one world

def test_one_world_lists_only_the_players_met_there(client, three_worlds):
    assert list(listed(client, "Crystal Hollows")) == ["HollowsOnly", "Steve"]


def test_in_one_world_meetings_and_worlds_count_only_that_world(client, three_worlds):
    steve_all = listed(client)["Steve"]
    steve_hollows = listed(client, "Crystal Hollows")["Steve"]
    steve_hub = listed(client, "Hub")["Steve"]

    assert steve_all["sightings"] == 4 and steve_all["worlds"] == {"Hub": 2, "Crystal Hollows": 1, "Dungeon Hub": 1}
    assert steve_hollows["sightings"] == 1 and steve_hollows["worlds"] == {"Crystal Hollows": 1}
    assert steve_hub["sightings"] == 2 and steve_hub["last_world"] == "Hub"


def test_the_world_ignores_case(client, three_worlds):
    assert list(listed(client, "CRYSTAL hollows")) == ["HollowsOnly", "Steve"]
    assert client.get("/tracker/overview", params={"owner": UUID_A, "world": "CRYSTAL hollows"}).json()["world"] == "crystal hollows"


def test_without_a_world_every_player_is_listed(client, three_worlds):
    assert list(listed(client)) == ["DungeonHubOnly", "HollowsOnly", "HubAlso", "HubOnly", "Steve"]


def test_a_world_with_no_visits_lists_nobody(client, three_worlds):
    assert listed(client, "The End") == {}
    assert client.get("/tracker/overview", params={"owner": UUID_A, "world": "The End"}).json()["players"] == 0


def test_paging_works_within_a_world(client):
    key = register(client)
    for hour in range(5):
        snap(client, key, ["HubPlayer%d" % hour], "2026-09-20T%02d:00:00Z" % (10 + hour), world="Hub")
        snap(client, key, ["DHubPlayer%d" % hour], "2026-09-20T%02d:30:00Z" % (10 + hour), world="Dungeon Hub")

    pages = [client.get("/tracker/players", params={"owner": UUID_A, "world": "Hub", "limit": 2, "offset": n}).json()
             for n in (0, 2, 4)]
    assert [len(p["players"]) for p in pages] == [2, 2, 1]
    assert all(p["total"] == 5 for p in pages)
    everyone = [x["username"] for p in pages for x in p["players"]]
    assert len(set(everyone)) == 5 and all(name.startswith("HubPlayer") for name in everyone)


def test_one_world_keeps_each_account_separate(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    snap(client, key_a, ["MainGuest"], HUB_AT, world="Hub")
    snap(client, key_b, ["AltGuest"], HUB_AT, world="Hub", me="AltAccount")

    assert list(listed(client, "Hub")) == ["MainGuest"]
    assert list(listed(client, "Hub", owner=UUID_B)) == ["AltGuest"]


# ------------------------------------------------- filtering the search by world

def test_search_shows_the_world_of_every_encounter_and_a_per_world_count(client, three_worlds):
    steve = search(client, "steve", exact="true")[0]

    assert steve["by_world"] == {"Hub": 2, "Crystal Hollows": 1, "Dungeon Hub": 1}
    assert [(s["world"]) for s in steve["sightings"]] == ["Hub", "Dungeon Hub", "Crystal Hollows", "Hub"]
    assert steve["matching"] == 4 and steve["times_seen"] == 4


def test_search_filtered_by_a_world_lists_only_those_encounters(client, three_worlds):
    steve = search(client, "steve", "Crystal Hollows", exact="true")[0]

    assert [s["world"] for s in steve["sightings"]] == ["Crystal Hollows"]
    assert steve["matching"] == 1
    assert steve["by_world"] == {"Hub": 2, "Crystal Hollows": 1, "Dungeon Hub": 1}, "the counts still cover every world"


def test_search_filtered_by_several_worlds(client, three_worlds):
    steve = search(client, "steve", "Hub", "Dungeon Hub", exact="true")[0]
    assert sorted(s["world"] for s in steve["sightings"]) == ["Dungeon Hub", "Hub", "Hub"]
    assert steve["matching"] == 3


def test_search_only_returns_players_met_in_the_chosen_worlds(client, three_worlds):
    assert sorted(p["username"] for p in search(client, "only", "Hub")) == ["HubOnly"]
    assert sorted(p["username"] for p in search(client, "only", "Crystal Hollows")) == ["HollowsOnly"]
    assert search(client, "hollowsonly", "Hub") == []
    assert len(search(client, "only")) == 3   # no filter: all three


def test_the_world_filter_works_with_partial_matches_and_the_light_list(client, three_worlds):
    light = search(client, "hub", "Hub", sightings="false")

    assert sorted(p["username"] for p in light) == ["HubAlso", "HubOnly"]
    assert all("sightings" not in p and "by_world" in p for p in light)


def test_search_world_filter_ignores_case(client, three_worlds):
    assert [s["world"] for s in search(client, "steve", "CRYSTAL HOLLOWS", exact="true")[0]["sightings"]] == ["Crystal Hollows"]


def test_the_encounters_of_two_accounts_carry_their_worlds(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    snap(client, key_a, ["Alice"], HUB_AT, world="Hub")
    snap(client, key_b, ["Alice"], HOLLOWS_AT, world="Crystal Hollows", me="AltAccount")

    sightings = search(client, "alice", exact="true")[0]["sightings"]
    assert [(s["account"], s["world"]) for s in sightings] == [("AltAccount", "Crystal Hollows"), (ME, "Hub")]


def test_search_rejects_too_many_worlds(client):
    params = [("q", "a")] + [("world", "World%d" % i) for i in range(21)]
    assert client.get("/tracker/search", params=params).status_code == 422


# ---------------------------------------------------------------- the worlds list

def test_the_worlds_list_counts_players_in_each_world(client, three_worlds):
    worlds = client.get("/tracker/worlds").json()["worlds"]

    assert {w["world"]: (w["players"], w["visits"]) for w in worlds} == {
        "Hub": (3, 2),                 # Steve, HubOnly, HubAlso over two visits
        "Crystal Hollows": (2, 1),
        "Dungeon Hub": (2, 1),
    }
    assert worlds[0]["world"] == "Hub", "the world with the most players comes first"


def test_the_worlds_list_can_be_limited_to_one_account(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    snap(client, key_a, ["Alice"], HUB_AT, world="Hub")
    snap(client, key_b, ["Bob"], HOLLOWS_AT, world="Crystal Hollows", me="AltAccount")

    assert [w["world"] for w in client.get("/tracker/worlds", params={"owner": UUID_A}).json()["worlds"]] == ["Hub"]
    assert [w["world"] for w in client.get("/tracker/worlds", params={"owner": UUID_B}).json()["worlds"]] == ["Crystal Hollows"]
    assert len(client.get("/tracker/worlds").json()["worlds"]) == 2


def test_the_worlds_list_starts_empty_and_rejects_a_bad_account(client):
    assert client.get("/tracker/worlds").json() == {"worlds": [], "total_players": 0, "party_players": 0}
    assert client.get("/tracker/worlds", params={"owner": "nope"}).status_code == 422


def test_unknown_visits_are_listed_as_a_world_too(client):
    key = register(client)
    snap(client, key, ["Alice"], HUB_AT, world=None)

    assert [w["world"] for w in client.get("/tracker/worlds").json()["worlds"]] == ["Unknown"]


# ---------------------------------------------------------------------- live events

def test_live_events_carry_the_world_and_the_world_of_the_last_meeting(client, three_worlds):
    events = app_module._tracker_events(0, UUID_A)
    steve = [e for e in events if e["username"] == "Steve"]

    assert [e["world"] for e in steve] == ["Hub", "Crystal Hollows", "Dungeon Hub", "Hub"]
    assert [e["previous_world"] for e in steve] == [None, "Hub", "Crystal Hollows", "Dungeon Hub"]
    assert [e["returning"] for e in steve] == [False, True, True, True]


def test_live_events_list_every_world_the_player_was_met_in_before(client, three_worlds):
    steve = [e for e in app_module._tracker_events(0, UUID_A) if e["username"] == "Steve"]

    assert [e["previous_worlds"] for e in steve] == [
        [], ["Hub"], ["Crystal Hollows", "Hub"], ["Crystal Hollows", "Dungeon Hub", "Hub"]]


def test_a_new_player_has_no_previous_world(client):
    key = register(client)
    snap(client, key, ["Alice"], HUB_AT, world="Hub")

    event = app_module._tracker_events(0, UUID_A)[0]
    assert event["world"] == "Hub" and event["previous_world"] is None and event["returning"] is False


# ------------------------------------------------------------------ scanner status

def beat(client, key, **extra):
    body = {"player_uuid": UUID_A, "player_name": ME, "in_skyblock": True}
    body.update(extra)
    return client.post("/heartbeat", json=body, headers={"X-API-Key": key})


def test_the_scanner_status_from_the_mod_is_shown_with_the_account(client):
    key = register(client)
    assert client.get("/players").json()[0]["scanner"] == ""

    beat(client, key, scanner="ON (world: Crystal Hollows)")
    assert client.get("/players").json()[0]["scanner"] == "ON (world: Crystal Hollows)"

    beat(client, key, scanner="OFF")
    assert client.get("/players").json()[0]["scanner"] == "OFF"


def test_a_heartbeat_without_a_scanner_status_leaves_it_alone(client):
    key = register(client)
    beat(client, key, scanner="ON (all worlds)")
    beat(client, key)

    assert client.get("/players").json()[0]["scanner"] == "ON (all worlds)"


def test_the_scanner_status_is_kept_short_and_printable(client):
    key = register(client)
    beat(client, key, scanner="ON\n" + "x" * 500 + "\x00")

    status = client.get("/players").json()[0]["scanner"]
    assert len(status) <= 60 and "\n" not in status and "\x00" not in status


# ---------------------------------------------------------------------- upgrading

def test_an_existing_tracker_database_gets_worlds_and_keeps_its_data(tmp_path, monkeypatch):
    legacy = tmp_path / "before_worlds.db"
    conn = sqlite3.connect(legacy)
    conn.executescript("""
        CREATE TABLE coin_rows (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL,
          player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, timestamp TEXT NOT NULL, coins INTEGER NOT NULL,
          change INTEGER NOT NULL, cause_label TEXT NOT NULL, cause_message TEXT NOT NULL DEFAULT '',
          received_at TEXT NOT NULL, UNIQUE(session_id, timestamp));
        CREATE TABLE players (player_uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'offline', in_skyblock INTEGER NOT NULL DEFAULT 0, session_id TEXT,
          last_seen_ms INTEGER NOT NULL, registered_ms INTEGER NOT NULL);
        CREATE TABLE tracker_players (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL,
          username_lower TEXT NOT NULL UNIQUE, first_seen TEXT NOT NULL, last_seen TEXT NOT NULL,
          times_seen INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE tracker_visits (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid TEXT NOT NULL,
          started_at TEXT NOT NULL, UNIQUE(owner_uuid, started_at));
        CREATE TABLE tracker_sightings (id INTEGER PRIMARY KEY AUTOINCREMENT, player_id INTEGER NOT NULL,
          visit_id INTEGER NOT NULL, seen_at TEXT NOT NULL, UNIQUE(player_id, visit_id));
        INSERT INTO players VALUES ('1adf8788-e917-4675-b758-defaaee07477', 'Old', 'offline', 0, NULL, 1, 1);
        INSERT INTO tracker_players VALUES (1, 'Alice', 'alice', '2026-09-21T06:17:45.001Z', '2026-09-21T06:17:45.001Z', 1);
        INSERT INTO tracker_visits VALUES (1, '1adf8788-e917-4675-b758-defaaee07477', '2026-09-21T06:17:40.000Z');
        INSERT INTO tracker_sightings VALUES (1, 1, 1, '2026-09-21T06:17:45.001Z');
    """)
    conn.commit()
    conn.close()

    monkeypatch.setattr(app_module, "DB_PATH", legacy)
    app_module.init_db()
    app_module.init_db()   # twice must be harmless

    check = sqlite3.connect(legacy)
    assert check.execute("SELECT world FROM tracker_visits").fetchone()[0] == "Unknown", "old visits become Unknown"
    assert check.execute("SELECT username, times_seen FROM tracker_players").fetchone() == ("Alice", 1)
    assert check.execute("SELECT COUNT(*) FROM tracker_sightings").fetchone()[0] == 1
    assert check.execute("SELECT scanner FROM players").fetchone()[0] == ""
    indexes = {r[0] for r in check.execute("SELECT name FROM sqlite_master WHERE type = 'index'")}
    assert "idx_tracker_visits_world" in indexes
    check.close()


def test_the_world_column_is_indexed(client):
    with app_module.db() as conn:
        assert "idx_tracker_visits_world" in {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type = 'index'")}


def test_the_worlds_answer_has_the_total_of_different_players_met(client, three_worlds):
    # Steve is met in three worlds and HubOnly twice, but each counts once.
    assert client.get("/tracker/worlds").json()["total_players"] == 5
    assert client.get("/tracker/worlds", params={"owner": UUID_A}).json()["total_players"] == 5


def test_the_total_is_per_account_and_ignores_case(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    snap(client, key_a, ["Alice", "Bob"], HUB_AT, world="Hub")
    snap(client, key_b, ["alice", "Carol", "Dave"], HOLLOWS_AT, world="Crystal Hollows", me="AltAccount")

    assert client.get("/tracker/worlds", params={"owner": UUID_A}).json()["total_players"] == 2
    assert client.get("/tracker/worlds", params={"owner": UUID_B}).json()["total_players"] == 3
    assert client.get("/tracker/worlds").json()["total_players"] == 4, "Alice is one player on both accounts"
