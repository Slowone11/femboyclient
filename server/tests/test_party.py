"""Tests for party tracking: players confirmed to be in your party."""

import json

import app as app_module
from conftest import UUID_A, UUID_B, register
from fastapi.testclient import TestClient

import pytest

ME = "WeTrollALittle"
T1 = "2026-09-21T15:00:00Z"
T2 = "2026-09-21T16:00:00Z"
T3 = "2026-09-22T09:00:00Z"


def party(client, key, players, when=T1, me=ME, **extra):
    body = {"me": me, "players": players, "timestamp": when, **extra}
    return client.post("/tracker/party", json=body, headers={"X-API-Key": key})


def snap(client, key, players, visit, world="Hub", me=ME):
    body = {"me": me, "players": players, "timestamp": visit.replace("00:00Z", "00:10Z"),
            "visit_started": visit, "world": world}
    assert client.post("/tracker/snapshot", json=body, headers={"X-API-Key": key}).status_code == 200


def count(table):
    with app_module.db() as conn:
        return conn.execute("SELECT COUNT(*) FROM " + table).fetchone()[0]


def find(client, name):
    found = client.get("/tracker/search", params={"q": name, "exact": "true"}).json()["players"]
    return found[0] if found else None


# --------------------------------------------------------------------- recording

def test_a_confirmed_party_member_is_added_to_the_database(client):
    key = register(client)

    result = party(client, key, ["Alice", "Bob"]).json()

    assert result["first_time"] == ["Alice", "Bob"] and result["again"] == []
    assert count("tracker_players") == 2 and count("tracker_parties") == 2
    assert find(client, "alice")["party_times"] == 1


def test_a_party_member_never_seen_in_a_lobby_is_a_player_with_no_lobby_meetings(client):
    key = register(client)
    party(client, key, ["Alice"])

    alice = find(client, "Alice")
    assert alice["times_seen"] == 0 and alice["sightings"] == [] and alice["party_times"] == 1


def test_partying_again_counts_again_and_says_so(client):
    key = register(client)
    party(client, key, ["Alice"], T1)
    second = party(client, key, ["alice"], T2).json()      # capitalisation does not matter
    party(client, key, ["Alice"], T3)

    assert second["again"] == ["alice"] and second["first_time"] == []
    alice = find(client, "Alice")
    assert alice["party_times"] == 3
    assert [p["joined_at"] for p in alice["parties"]] == ["2026-09-22T09:00:00.000Z", "2026-09-21T16:00:00.000Z", "2026-09-21T15:00:00.000Z"]
    assert count("tracker_players") == 1


def test_the_same_message_sent_twice_counts_once(client):
    key = register(client)
    party(client, key, ["Alice"], T1)
    again = party(client, key, ["Alice"], T1).json()

    assert again["repeated"] == 1 and again["again"] == [] and again["first_time"] == []
    assert find(client, "Alice")["party_times"] == 1


def test_a_player_met_in_a_lobby_and_a_party_is_one_player(client):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-21T14:00:00Z")
    party(client, key, ["Alice"])

    alice = find(client, "Alice")
    assert count("tracker_players") == 1
    assert alice["times_seen"] == 1 and alice["party_times"] == 1


def test_your_own_name_and_rubbish_are_not_recorded(client):
    key = register(client)
    result = party(client, key, [ME, ME.lower(), "ab", "has space", "!A-a", "Alice", "Alice"]).json()

    assert result["first_time"] == ["Alice"] and result["ignored"] == 3
    assert count("tracker_players") == 1


def test_an_empty_party_message_records_nothing(client):
    key = register(client)
    assert party(client, key, []).json()["first_time"] == []
    assert count("tracker_parties") == 0


def test_the_party_message_needs_a_key_and_a_valid_body(client):
    assert client.post("/tracker/party", json={"players": ["Alice"], "timestamp": T1}).status_code in (401, 403)
    key = register(client)
    assert client.post("/tracker/party", json={"players": ["Alice"]}, headers={"X-API-Key": key}).status_code == 422
    assert party(client, key, ["Alice"], when="not a time").status_code == 422
    too_many = ["Player%03d" % i for i in range(app_module.TRACKER_MAX_PLAYERS + 1)]
    assert party(client, key, too_many).status_code == 413
    assert count("tracker_parties") == 0


def test_no_chat_or_server_details_are_stored_for_a_party(client):
    key = register(client)
    party(client, key, ["Alice"], message="Alice joined the party.", server="mc.hypixel.net", lobby="m28CW")

    with app_module.db() as conn:
        dump = json.dumps([list(r) for t in ("tracker_players", "tracker_parties") for r in conn.execute("SELECT * FROM " + t).fetchall()])
    for text in ("joined the party", "hypixel", "m28CW"):
        assert text not in dump


# ------------------------------------------------------------------ where it shows

def test_the_search_says_whether_you_have_partied_with_a_player(client):
    key = register(client)
    snap(client, key, ["Alice", "Bob"], "2026-09-21T14:00:00Z")
    party(client, key, ["Alice"])

    assert find(client, "Alice")["party_times"] == 1
    bob = find(client, "Bob")
    assert bob["party_times"] == 0 and bob["parties"] == []
    light = client.get("/tracker/search", params={"q": "a", "sightings": "false"}).json()["players"]
    assert {p["username"]: p["party_times"] for p in light} == {"Alice": 1}


def test_the_search_lists_which_account_partied(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    party(client, key_a, ["Alice"], T1)
    party(client, key_b, ["Alice"], T2, me="AltAccount")

    assert [p["account"] for p in find(client, "Alice")["parties"]] == ["AltAccount", ME]


def test_the_graph_marks_every_player_you_have_partied_with(client):
    key = register(client)
    snap(client, key, ["Alice", "Bob", "Carol"], "2026-09-21T14:00:00Z", world="Hub")
    snap(client, key, ["Alice"], "2026-09-21T15:00:00Z", world="Dwarven Mines")
    party(client, key, ["Alice", "Carol"])
    party(client, key, ["Alice"], T2)

    tree = client.get("/tracker/tree", params={"owner": UUID_A}).json()
    marks = {n["label"]: n["party_times"] for n in tree["nodes"] if n["type"] == "player"}
    assert marks == {"Alice": 2, "Bob": 0, "Carol": 1}


def tree(client, owner=UUID_A, **params):
    return client.get("/tracker/tree", params={"owner": owner, **params}).json()


def party_links(result):
    return {l["target"]: l["times"] for l in result["links"] if l["source"] == "party"}


def test_players_partied_with_get_their_own_parties_branch_even_if_never_met_in_a_lobby(client):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-21T14:00:00Z")
    party(client, key, ["Alice", "Zed"])
    party(client, key, ["Zed"], T2)

    result = tree(client)
    by_label = {n["label"]: n for n in result["nodes"] if n["type"] == "player"}
    node = next(n for n in result["nodes"] if n["type"] == "party")

    assert set(by_label) == {"Alice", "Zed"}, "Zed was never in a lobby but is on the tree"
    assert node["id"] == "party" and node["label"] == "Parties" and node["players"] == 2
    assert {"source": "me", "target": "party"} in result["links"]
    assert party_links(result) == {by_label["Alice"]["id"]: 1, by_label["Zed"]["id"]: 2}
    assert by_label["Zed"]["party_times"] == 2 and by_label["Zed"]["worlds"] == {}
    assert by_label["Zed"]["times_seen"] == 0


def test_a_player_in_a_world_and_a_party_is_linked_to_both_branches(client):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-21T14:00:00Z", world="Hub")
    party(client, key, ["Alice"])

    result = tree(client)
    alice = next(n["id"] for n in result["nodes"] if n.get("label") == "Alice")
    assert {l["source"] for l in result["links"] if l["target"] == alice} == {"w:Hub", "party"}
    assert not [n for n in result["nodes"] if n["type"] == "world" and n["world"] == "Parties"]


def test_the_parties_branch_is_only_there_when_there_are_parties(client):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-21T14:00:00Z")

    assert not [n for n in tree(client)["nodes"] if n["type"] == "party"]


def test_a_world_filter_hides_the_parties_branch(client):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-21T14:00:00Z", world="Hub")
    party(client, key, ["Alice", "Zed"])

    result = tree(client, world="Hub")
    assert not [n for n in result["nodes"] if n["type"] == "party"]
    assert [n["label"] for n in result["nodes"] if n["type"] == "player"] == ["Alice"]
    assert result["nodes"][[n["type"] for n in result["nodes"]].index("player")]["party_times"] == 1, "the glow still applies"


def test_the_parties_branch_comes_once_on_the_first_page_and_does_not_use_up_the_limit(client):
    key = register(client)
    for hour in range(3):
        snap(client, key, ["Lobby%d" % hour], "2026-09-20T%02d:00:00Z" % (10 + hour))
    party(client, key, ["Zed", "Amy"])

    first = tree(client, players=2)
    second = tree(client, players=2, before=first["next_before"])

    assert len([n for n in first["nodes"] if n["type"] == "party"]) == 1
    assert sorted(n["label"] for n in first["nodes"] if n["type"] == "player") == ["Amy", "Lobby1", "Lobby2", "Zed"]
    assert not [n for n in second["nodes"] if n["type"] == "party"]
    assert first["has_more"] is True and [n["label"] for n in second["nodes"] if n["type"] == "player"] == ["Lobby0"]


# ---------------------------------------------- each account has its own parties

def test_each_account_only_sees_the_players_it_partied_with(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "BayHarborBeater")
    party(client, key_a, ["BayHarborBeater"], T1)                    # the main partied with the alt
    party(client, key_b, [ME], T1, me="BayHarborBeater")             # and the alt partied with the main

    main, alt = tree(client, UUID_A), tree(client, UUID_B)
    assert sorted(n["label"] for n in main["nodes"] if n["type"] == "player") == ["BayHarborBeater"]
    assert sorted(n["label"] for n in alt["nodes"] if n["type"] == "player") == [ME]
    assert client.get("/tracker/worlds", params={"owner": UUID_A}).json()["party_players"] == 1
    assert client.get("/tracker/worlds", params={"owner": UUID_B}).json()["party_players"] == 1


def test_the_search_and_list_can_be_limited_to_one_account(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    party(client, key_a, ["Alice"], T1)
    party(client, key_b, ["Alice", "Bob"], T2, me="AltAccount")

    def mine(owner):
        return client.get("/tracker/search", params={"q": "alice", "exact": "true", "owner": owner}).json()["players"][0]

    assert mine(UUID_A)["party_times"] == 1 and [p["account"] for p in mine(UUID_A)["parties"]] == [ME]
    assert mine(UUID_B)["party_times"] == 1 and [p["account"] for p in mine(UUID_B)["parties"]] == ["AltAccount"]
    assert find(client, "Alice")["party_times"] == 2, "without an account every account counts"

    assert [p["username"] for p in client.get("/tracker/party", params={"owner": UUID_A}).json()["players"]] == ["Alice"]
    assert sorted(p["username"] for p in client.get("/tracker/party", params={"owner": UUID_B}).json()["players"]) == ["Alice", "Bob"]
    assert client.get("/tracker/party", params={"owner": "nope"}).status_code == 422


def test_the_party_list_gives_counts_and_dates(client):
    key = register(client)
    party(client, key, ["Alice", "Bob"], T1)
    party(client, key, ["Alice"], T3)

    listed = {p["username"]: (p["times"], p["first"], p["last"]) for p in client.get("/tracker/party").json()["players"]}
    assert listed == {"Alice": (2, "2026-09-21T15:00:00.000Z", "2026-09-22T09:00:00.000Z"),
                      "Bob": (1, "2026-09-21T15:00:00.000Z", "2026-09-21T15:00:00.000Z")}


# ------------------------------------------------------------------- live events

def test_a_party_confirmation_becomes_a_live_event_with_the_running_count(client):
    key = register(client)
    party(client, key, ["Alice"], T1)
    party(client, key, ["Alice", "Bob"], T2)

    events = app_module._party_events(0, UUID_A)
    assert [(e["username"], e["party_times"], e["kind"]) for e in events] == [
        ("Alice", 1, "party"), ("Alice", 2, "party"), ("Bob", 1, "party")]
    assert app_module._party_events(events[-1]["id"], UUID_A) == []
    assert app_module._newest_party_id() == events[-1]["id"]


def test_live_party_events_are_only_for_the_account_that_partied(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    party(client, key_a, ["Alice"], T1)
    party(client, key_b, ["Bob"], T2, me="AltAccount")

    assert [e["username"] for e in app_module._party_events(0, UUID_A)] == ["Alice"]
    assert [e["username"] for e in app_module._party_events(0, UUID_B)] == ["Bob"]


def test_lobby_events_are_unchanged_by_parties(client):
    key = register(client)
    party(client, key, ["Alice"])
    snap(client, key, ["Alice"], "2026-09-21T14:00:00Z")

    events = app_module._tracker_events(0, UUID_A)
    assert [e["username"] for e in events] == ["Alice"] and "kind" not in events[0]
    assert events[0]["returning"] is False


# ----------------------------------------------------------------------- clearing

@pytest.fixture()
def local(client):
    with TestClient(app_module.app, client=("127.0.0.1", 50000)) as c:
        yield c


def test_clearing_everything_also_forgets_the_accounts_parties_and_party_only_players(client, local):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-21T14:00:00Z")
    party(client, key, ["Alice", "Zed"])

    result = local.post("/tracker/clear", json={"owner": UUID_A}).json()

    assert result["parties_removed"] == 2
    assert (count("tracker_players"), count("tracker_parties")) == (0, 0)


def test_clearing_one_world_keeps_parties_and_keeps_a_partied_player_with_no_meetings_left(client, local):
    key = register(client)
    snap(client, key, ["Alice", "Bob"], "2026-09-21T14:00:00Z", world="Hub")
    party(client, key, ["Alice"])

    local.post("/tracker/clear", json={"owner": UUID_A, "world": "Hub"})

    alice = find(client, "Alice")
    assert alice is not None and alice["times_seen"] == 0 and alice["party_times"] == 1
    assert find(client, "Bob") is None


def test_clearing_one_account_leaves_the_others_parties(client, local):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    party(client, key_a, ["Alice"], T1)
    party(client, key_b, ["Alice", "Bob"], T2, me="AltAccount")

    local.post("/tracker/clear", json={"owner": UUID_A})

    assert find(client, "Alice")["party_times"] == 1 and find(client, "Bob") is not None


def test_live_sightings_say_how_often_you_have_partied_with_the_player(client):
    key = register(client)
    party(client, key, ["Alice"], T1)
    party(client, key, ["Alice"], T2)
    snap(client, key, ["Alice", "Bob"], "2026-09-21T14:00:00Z")

    events = {e["username"]: e["party_times"] for e in app_module._tracker_events(0, UUID_A)}
    assert events == {"Alice": 2, "Bob": 0}


def test_live_sightings_count_only_your_own_parties(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    party(client, key_b, ["Alice"], T1, me="AltAccount")
    snap(client, key_a, ["Alice"], "2026-09-21T14:00:00Z")

    assert [e["party_times"] for e in app_module._tracker_events(0, UUID_A)] == [0]


def test_you_are_never_listed_in_your_own_parties_even_if_a_row_exists(client):
    key = register(client)
    party(client, key, ["Alice"])
    # A row for your own name that should never exist, made by hand to prove it is hidden anyway.
    with app_module.db() as conn:
        me = conn.execute("INSERT INTO tracker_players (username, username_lower, first_seen, last_seen, times_seen)"
                          " VALUES (?, ?, ?, ?, 0)", (ME, ME.lower(), T1, T1)).lastrowid
        conn.execute("INSERT INTO tracker_parties (owner_uuid, player_id, joined_at) VALUES (?, ?, ?)", (UUID_A, me, T1))

    result = tree(client)
    assert sorted(n["label"] for n in result["nodes"] if n["type"] == "player") == ["Alice"]
    assert [p["username"] for p in client.get("/tracker/party", params={"owner": UUID_A}).json()["players"]] == ["Alice"]
    assert client.get("/tracker/worlds", params={"owner": UUID_A}).json()["party_players"] == 1
    mine = client.get("/tracker/search", params={"q": ME, "exact": "true", "owner": UUID_A}).json()["players"][0]
    assert mine["party_times"] == 0


def test_your_own_name_is_not_recorded_as_a_party_member_in_any_capitalisation(client):
    key = register(client)
    assert party(client, key, ["wetrollalittle", "WETROLLALITTLE"]).json()["first_time"] == []
    assert count("tracker_parties") == 0
