"""Tests for linking every account into one shared set of tables: the migration, the account on every row, and reading one
account or all of them together (owner=all)."""

import sqlite3

import app as app_module
from conftest import UUID_A, UUID_B, register
from fastapi.testclient import TestClient

import pytest

A = "WeTrollALittle"
B = "BayHarborBeater"


@pytest.fixture()
def two(client):
    """Two linked accounts (A and B) and their API keys."""
    return register(client, UUID_A, A), register(client, UUID_B, B)


def snap(client, key, players, world="Hub", visit="2026-09-21T14:00:00Z", me=A):
    body = {"me": me, "players": players, "timestamp": visit.replace("00:00Z", "00:10Z"), "visit_started": visit, "world": world}
    assert client.post("/tracker/snapshot", json=body, headers={"X-API-Key": key}).status_code == 200


def party(client, key, players, when="2026-09-21T14:30:00Z", me=A):
    assert client.post("/tracker/party", json={"me": me, "players": players, "timestamp": when}, headers={"X-API-Key": key}).status_code == 200


def chat(client, key, messages, me=A, world="Hub"):
    body = {"me": me, "world": world, "messages": messages}
    assert client.post("/tracker/chat", json=body, headers={"X-API-Key": key}).status_code == 200


def say(text, player="Alice", kind="PUBLIC", when="2026-09-21T15:00:00Z"):
    return {"player": player, "type": kind, "text": text, "timestamp": when}


def get(client, path, **params):
    response = client.get(path, params=params)
    assert response.status_code == 200, response.text
    return response.json()


def rows(sql, *params):
    with app_module.db() as conn:
        return [dict(r) for r in conn.execute(sql, params).fetchall()]


# ------------------------------------------------------------------ one profile

def test_there_is_one_profile_and_every_account_belongs_to_it(client, two):
    assert rows("SELECT id, name FROM profiles") == [{"id": 1, "name": "Me"}]
    accounts = get(client, "/players")
    assert len(accounts) == 2 and {a["profile_id"] for a in accounts} == {1}


def test_scope_owner_means_every_account_for_missing_empty_or_all():
    assert app_module._scope_owner(None) is None
    assert app_module._scope_owner("") is None
    assert app_module._scope_owner("  ") is None
    assert app_module._scope_owner("all") is None
    assert app_module._scope_owner("ALL") is None
    assert app_module._scope_owner(UUID_A) == UUID_A


def test_a_bad_owner_is_still_refused(client):
    for path in ("/tracker/tree", "/tracker/worlds", "/tracker/party", "/tracker/mychat", "/tracker/chats"):
        assert client.get(path, params={"owner": "nope"}).status_code == 422, path


# ------------------------------------------------------------------ the account is kept on every row

def test_every_new_tracker_row_keeps_the_account_name_and_uuid(client, two):
    key_a, key_b = two
    snap(client, key_a, ["Alice"])
    party(client, key_b, ["Alice"], me=B)
    chat(client, key_a, [say("hello"), say("mine", player=A, when="2026-09-21T15:00:01Z")])
    chat(client, key_b, [say("from b", player="Cat")], me=B)

    assert rows("SELECT owner_uuid, owner_name FROM tracker_visits") == [{"owner_uuid": UUID_A, "owner_name": A}]
    assert rows("SELECT owner_uuid, owner_name FROM tracker_parties") == [{"owner_uuid": UUID_B, "owner_name": B}]
    assert {(r["owner_uuid"], r["owner_name"]) for r in rows("SELECT owner_uuid, owner_name FROM tracker_chats")} == {(UUID_A, A), (UUID_B, B)}
    assert rows("SELECT owner_uuid, owner_name FROM tracker_my_chats") == [{"owner_uuid": UUID_A, "owner_name": A}]


def test_the_coin_rows_already_keep_the_account(client, two):
    key_a, _ = two
    row = {"session_id": "s1", "player_uuid": UUID_A, "player_name": A, "timestamp": "2026-09-21T10:00:00-04:00", "coins": 5,
           "change": 0, "cause_label": "session_start", "cause_message": ""}
    assert client.post("/log", json=[row], headers={"X-API-Key": key_a}).status_code == 200
    assert rows("SELECT player_uuid, player_name FROM coin_rows") == [{"player_uuid": UUID_A, "player_name": A}]


# ------------------------------------------------------------------ migrating a database made before this

@pytest.fixture()
def old_database(tmp_path, monkeypatch):
    """A database as it was before accounts kept their names: tracker tables with owner_uuid only, and accounts without a profile."""
    path = tmp_path / "old.db"
    conn = sqlite3.connect(path)
    conn.executescript("""
        CREATE TABLE players (player_uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'offline',
            in_skyblock INTEGER NOT NULL DEFAULT 0, session_id TEXT, last_seen_ms INTEGER NOT NULL, registered_ms INTEGER NOT NULL,
            scanner TEXT NOT NULL DEFAULT '');
        CREATE TABLE tracker_visits (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid TEXT NOT NULL, started_at TEXT NOT NULL,
            world TEXT NOT NULL DEFAULT 'Unknown', UNIQUE(owner_uuid, started_at));
        CREATE TABLE tracker_parties (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid TEXT NOT NULL, player_id INTEGER NOT NULL,
            joined_at TEXT NOT NULL, UNIQUE(owner_uuid, player_id, joined_at));
        CREATE TABLE tracker_chats (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid TEXT NOT NULL, player_id INTEGER NOT NULL,
            said_at TEXT NOT NULL, channel TEXT NOT NULL DEFAULT 'Public', world TEXT NOT NULL DEFAULT 'Unknown', message TEXT NOT NULL,
            UNIQUE(owner_uuid, player_id, said_at, message));
        CREATE TABLE tracker_my_chats (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid TEXT NOT NULL, said_at TEXT NOT NULL,
            channel TEXT NOT NULL DEFAULT 'Public', world TEXT NOT NULL DEFAULT 'Unknown', message TEXT NOT NULL,
            UNIQUE(owner_uuid, said_at, message));
        INSERT INTO players VALUES ('%s', '%s', 'offline', 0, NULL, 1, 1, '');
        INSERT INTO players VALUES ('%s', '%s', 'offline', 0, NULL, 1, 1, '');
        INSERT INTO tracker_visits (owner_uuid, started_at, world) VALUES ('%s', '2026-09-21T14:00:00.000Z', 'Hub');
        INSERT INTO tracker_visits (owner_uuid, started_at, world) VALUES ('%s', '2026-09-21T14:00:00.000Z', 'Hub');
        INSERT INTO tracker_visits (owner_uuid, started_at, world) VALUES ('unknown-owner', '2026-09-21T14:00:00.000Z', 'Hub');
        INSERT INTO tracker_parties (owner_uuid, player_id, joined_at) VALUES ('%s', 1, '2026-09-21T14:30:00.000Z');
        INSERT INTO tracker_chats (owner_uuid, player_id, said_at, channel, message) VALUES ('%s', 1, '2026-09-21T15:00:00.000Z', 'Private', 'psst');
        INSERT INTO tracker_my_chats (owner_uuid, said_at, message) VALUES ('%s', '2026-09-21T15:00:01.000Z', 'hi');
    """ % (UUID_A, A, UUID_B, B, UUID_A, UUID_B, UUID_A, UUID_A, UUID_B))
    conn.commit()
    conn.close()
    monkeypatch.setattr(app_module, "DB_PATH", path)
    return path


def test_an_old_database_gets_the_new_columns_and_keeps_every_row(old_database):
    app_module.init_db()

    assert rows("SELECT owner_uuid, owner_name FROM tracker_visits ORDER BY id") == [
        {"owner_uuid": UUID_A, "owner_name": A}, {"owner_uuid": UUID_B, "owner_name": B}, {"owner_uuid": "unknown-owner", "owner_name": ""}]
    assert rows("SELECT owner_name, joined_at FROM tracker_parties") == [{"owner_name": A, "joined_at": "2026-09-21T14:30:00.000Z"}]
    assert rows("SELECT owner_name, channel, message FROM tracker_chats") == [{"owner_name": A, "channel": "Private", "message": "psst"}]
    assert rows("SELECT owner_name, message FROM tracker_my_chats") == [{"owner_name": B, "message": "hi"}]
    assert {r["profile_id"] for r in rows("SELECT profile_id FROM players")} == {1}
    assert rows("SELECT id FROM profiles") == [{"id": 1}]


def test_running_the_migration_twice_changes_nothing(old_database):
    app_module.init_db()
    first = rows("SELECT * FROM tracker_visits ORDER BY id"), rows("SELECT * FROM tracker_chats"), rows("SELECT * FROM players ORDER BY player_uuid")
    app_module.init_db()
    assert (rows("SELECT * FROM tracker_visits ORDER BY id"), rows("SELECT * FROM tracker_chats"), rows("SELECT * FROM players ORDER BY player_uuid")) == first


def test_a_name_that_is_already_saved_is_never_overwritten_by_the_migration(old_database):
    app_module.init_db()
    with app_module.db() as conn:
        conn.execute("UPDATE tracker_visits SET owner_name = 'Old Name' WHERE owner_uuid = ?", (UUID_A,))
        conn.execute("UPDATE players SET player_name = 'Renamed' WHERE player_uuid = ?", (UUID_A,))
    app_module.init_db()
    assert rows("SELECT owner_name FROM tracker_visits WHERE owner_uuid = ?", UUID_A) == [{"owner_name": "Old Name"}]


# ------------------------------------------------------------------ reading all accounts together

def test_the_tree_of_all_accounts_combines_worlds_and_players(client, two):
    key_a, key_b = two
    snap(client, key_a, ["Alice", "Bob"], world="Hub")
    snap(client, key_b, ["Bob", "Cat"], world="The End", visit="2026-09-21T16:00:00Z", me=B)

    everyone = get(client, "/tracker/tree", owner="all")
    only_a = get(client, "/tracker/tree", owner=UUID_A)
    default = get(client, "/tracker/tree")

    assert everyone["nodes"][0]["label"] == "All accounts" and everyone["nodes"][0]["uuid"] is None
    assert only_a["nodes"][0]["label"] == A
    assert {n["label"] for n in everyone["nodes"] if n["type"] == "player"} == {"Alice", "Bob", "Cat"}
    assert {n["label"] for n in only_a["nodes"] if n["type"] == "player"} == {"Alice", "Bob"}
    assert {n["world"] for n in everyone["nodes"] if n["type"] == "world"} == {"Hub", "The End"}
    assert default["nodes"] == everyone["nodes"]

    bob = next(n for n in everyone["nodes"] if n.get("label") == "Bob")
    assert set(bob["worlds"]) == {"Hub", "The End"}


def test_the_world_counts_of_all_accounts_add_up(client, two):
    key_a, key_b = two
    snap(client, key_a, ["Alice", "Bob"], world="Hub")
    snap(client, key_b, ["Bob", "Cat"], world="Hub", visit="2026-09-21T16:00:00Z", me=B)

    everyone = get(client, "/tracker/worlds", owner="all")
    assert everyone["total_players"] == 3
    assert everyone["worlds"] == [{"world": "Hub", "players": 3, "visits": 2}]
    assert get(client, "/tracker/worlds", owner=UUID_A)["total_players"] == 2
    assert get(client, "/tracker/worlds") == everyone


def test_the_party_list_of_all_accounts_never_lists_your_own_accounts(client, two):
    key_a, key_b = two
    party(client, key_a, ["Alice", B])                    # your other account joined your party
    party(client, key_b, ["Alice", "Cat"], me=B)

    everyone = get(client, "/tracker/party", owner="all")["players"]
    assert {p["username"] for p in everyone} == {"Alice", "Cat"}
    assert next(p for p in everyone if p["username"] == "Alice")["times"] == 2

    assert {p["username"] for p in get(client, "/tracker/party", owner=UUID_A)["players"]} == {"Alice", B}
    assert get(client, "/tracker/worlds", owner="all")["party_players"] == 2


def test_the_tree_of_all_accounts_has_one_parties_branch_without_your_own_accounts(client, two):
    key_a, key_b = two
    party(client, key_a, ["Alice", B])
    party(client, key_b, ["Cat"], me=B)

    tree = get(client, "/tracker/tree", owner="all")
    branch = next(n for n in tree["nodes"] if n["type"] == "party")
    assert branch["players"] == 2
    assert {n["label"] for n in tree["nodes"] if n["type"] == "player"} == {"Alice", "Cat"}


def test_your_own_chat_of_all_accounts_says_who_said_it(client, two):
    key_a, key_b = two
    chat(client, key_a, [say("from a", player=A)])
    chat(client, key_b, [say("from b", player=B, when="2026-09-21T15:00:05Z")], me=B)

    everyone = get(client, "/tracker/mychat", owner="all")
    assert everyone["count"] == 2
    assert [(c["message"], c["account"]) for c in everyone["chats"]] == [("from b", B), ("from a", A)]
    assert get(client, "/tracker/mychat", owner=UUID_A)["count"] == 1
    assert get(client, "/tracker/mychat") == everyone


def test_the_coin_rows_of_all_accounts_come_together(client, two):
    key_a, key_b = two
    for key, uuid, name, coins in ((key_a, UUID_A, A, 5), (key_b, UUID_B, B, 9)):
        row = {"session_id": "s-" + name, "player_uuid": uuid, "player_name": name, "timestamp": "2026-09-21T10:00:00-04:00",
               "coins": coins, "change": 0, "cause_label": "session_start", "cause_message": ""}
        client.post("/log", json=[row], headers={"X-API-Key": key})

    everyone = get(client, "/rows")["rows"]
    assert {(r["player_name"], r["coins"]) for r in everyone} == {(A, 5), (B, 9)}
    assert [r["player_name"] for r in get(client, "/rows", player_uuid=UUID_B)["rows"]] == [B]
    assert len(get(client, "/rows", player_uuid="all")["rows"]) == 2


def test_the_live_events_of_all_accounts_include_every_account(client, two):
    key_a, key_b = two
    snap(client, key_a, ["Alice"])
    snap(client, key_b, ["Bob"], visit="2026-09-21T16:00:00Z", me=B)
    party(client, key_a, ["Cat"])
    party(client, key_b, ["Dan"], me=B)
    chat(client, key_a, [say("one")])
    chat(client, key_b, [say("two", when="2026-09-21T15:00:09Z")], me=B)
    chat(client, key_a, [say("mine", player=A, when="2026-09-21T15:00:20Z")])
    chat(client, key_b, [say("mine too", player=B, when="2026-09-21T15:00:30Z")], me=B)

    assert {e["username"] for e in app_module._tracker_events(0, None)} == {"Alice", "Bob"}
    assert {e["username"] for e in app_module._tracker_events(0, UUID_A)} == {"Alice"}
    assert {e["username"] for e in app_module._party_events(0, None)} == {"Cat", "Dan"}
    assert {e["username"] for e in app_module._party_events(0, UUID_B)} == {"Dan"}
    assert {(e["message"], e["account"]) for e in app_module._chat_events(0, None)} == {("one", A), ("two", B)}
    assert {(e["message"], e["account"]) for e in app_module._my_chat_events(0, None)} == {("mine", A), ("mine too", B)}
    assert {e["message"] for e in app_module._my_chat_events(0, UUID_B)} == {"mine too"}


def test_the_live_stream_accepts_all_and_refuses_a_bad_owner(client, two):
    assert client.get("/tracker/live", params={"owner": "nope"}).status_code == 422


def test_clearing_never_works_on_all_accounts_at_once(client, two):
    key_a, _ = two
    snap(client, key_a, ["Alice"])
    with TestClient(app_module.app, client=("127.0.0.1", 50000)) as local:
        assert local.post("/tracker/clear", json={"owner": "all"}).status_code == 422
        assert local.post("/tracker/clear", json={"owner": ""}).status_code == 422
    assert rows("SELECT COUNT(*) AS n FROM tracker_sightings") == [{"n": 1}]


# ------------------------------------------------------------------ chat logs of all accounts

def test_the_chat_logs_of_all_accounts_say_which_account_each_came_from(client, two):
    key_a, key_b = two
    chat(client, key_a, [say("for a", when="2026-09-21T15:00:00Z")])
    chat(client, key_b, [say("for b", player="Cat", when="2026-09-21T15:01:00Z")], me=B)

    everyone = get(client, "/tracker/chats")["chats"]
    assert [(c["message"], c["account"], c["account_uuid"]) for c in everyone] == [("for b", B, UUID_B), ("for a", A, UUID_A)]
    assert [c["message"] for c in get(client, "/tracker/chats", owner=UUID_A)["chats"]] == ["for a"]
    assert get(client, "/tracker/chats", owner="all") == get(client, "/tracker/chats")


def test_a_chat_that_two_accounts_both_heard_is_listed_once(client, two):
    key_a, key_b = two
    chat(client, key_a, [say("gg everyone", when="2026-09-21T15:00:00Z")])
    chat(client, key_b, [say("gg everyone", when="2026-09-21T15:00:01Z")], me=B)             # the same message, a second later, heard by B

    both = get(client, "/tracker/chats")["chats"]
    assert [c["message"] for c in both] == ["gg everyone"] and both[0]["account"] == A            # the first copy is kept
    assert len(get(client, "/tracker/chats", owner=UUID_B)["chats"]) == 1                          # each account still has its own copy


def test_two_accounts_hearing_the_same_words_at_different_times_are_two_chats(client, two):
    key_a, key_b = two
    chat(client, key_a, [say("gg", when="2026-09-21T15:00:00Z")])
    chat(client, key_b, [say("gg", when="2026-09-21T15:00:05Z")], me=B)
    assert len(get(client, "/tracker/chats")["chats"]) == 2


def test_the_same_words_from_another_player_or_type_are_not_duplicates(client, two):
    key_a, key_b = two
    chat(client, key_a, [say("gg", player="Alice", when="2026-09-21T15:00:00Z")])
    chat(client, key_b, [say("gg", player="Bob", when="2026-09-21T15:00:00Z"), say("gg", player="Alice", kind="PARTY", when="2026-09-21T15:00:00Z")], me=B)
    assert len(get(client, "/tracker/chats")["chats"]) == 3


def test_your_own_message_heard_by_your_other_account_is_listed_once(client, two):
    key_a, key_b = two
    chat(client, key_a, [say("hello all", player=A, when="2026-09-21T15:00:00Z")])                # A's own copy
    chat(client, key_b, [say("hello all", player=A, when="2026-09-21T15:00:01Z")], me=B)          # B heard A say it

    assert len(get(client, "/tracker/chats")["chats"]) == 1


def test_paging_and_filters_work_over_all_accounts_with_duplicates_removed(client, two):
    key_a, key_b = two
    for i in range(6):
        when = "2026-09-21T15:00:%02dZ" % (i * 10)
        chat(client, key_a, [say("m%d" % i, when=when)])
        chat(client, key_b, [say("m%d" % i, when="2026-09-21T15:00:%02dZ" % (i * 10 + 1))], me=B)

    seen, before = [], None
    for _ in range(10):
        page = get(client, "/tracker/chats", limit=2, **({"before": before} if before else {}))
        seen += [c["message"] for c in page["chats"]]
        before = page["next_before"]
        if not page["has_more"]:
            break
    assert seen == ["m5", "m4", "m3", "m2", "m1", "m0"]
    assert [c["message"] for c in get(client, "/tracker/chats", q="m3")["chats"]] == ["m3"]


# ------------------------------------------------------------------ the player details still work for all accounts

def test_the_player_search_of_all_accounts_counts_every_accounts_party(client, two):
    key_a, key_b = two
    party(client, key_a, ["Alice"])
    party(client, key_b, ["Alice"], me=B, when="2026-09-21T15:30:00Z")

    found = get(client, "/tracker/search", q="Alice", exact="true", owner="all")["players"][0]
    assert found["party_times"] == 2
    assert get(client, "/tracker/search", q="Alice", exact="true", owner=UUID_A)["players"][0]["party_times"] == 1
