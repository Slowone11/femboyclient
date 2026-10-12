"""Tests for storing what players say."""

import json

import app as app_module
from conftest import UUID_A, UUID_B, register
from fastapi.testclient import TestClient

import pytest

ME = "WeTrollALittle"
T1 = "2026-09-21T15:00:00Z"
T2 = "2026-09-21T15:00:05Z"
T3 = "2026-09-22T09:30:00Z"


def say(text, player="TEST", channel="Public", when=T1):
    return {"player": player, "channel": channel, "text": text, "timestamp": when}


def chat(client, key, messages, world="The End", me=ME):
    body = {"me": me, "world": world, "messages": messages}
    return client.post("/tracker/chat", json=body, headers={"X-API-Key": key})


def snap(client, key, players, visit, world="The End", me=ME):
    body = {"me": me, "players": players, "timestamp": visit.replace("00:00Z", "00:10Z"),
            "visit_started": visit, "world": world}
    assert client.post("/tracker/snapshot", json=body, headers={"X-API-Key": key}).status_code == 200


def count(table):
    with app_module.db() as conn:
        return conn.execute("SELECT COUNT(*) FROM " + table).fetchone()[0]


def find(client, name, **params):
    found = client.get("/tracker/search", params={"q": name, "exact": "true", **params}).json()["players"]
    return found[0] if found else None


# --------------------------------------------------------------------- recording

def test_a_message_is_saved_under_the_player_with_its_time_channel_and_world(client):
    key = register(client)
    result = chat(client, key, [say("hello there")]).json()

    assert result == {"received": 1, "saved": 1, "repeated": 0, "ignored": 0}
    test = find(client, "test")
    assert test["chat_count"] == 1
    assert test["chats"] == [{"said_at": "2026-09-21T15:00:00.000Z", "channel": "Public", "world": "The End",
                              "message": "hello there", "account": ME}]


def test_a_player_who_only_ever_chatted_is_added_to_the_database(client):
    key = register(client)
    chat(client, key, [say("hi", player="Alice")])

    alice = find(client, "Alice")
    assert alice["times_seen"] == 0 and alice["sightings"] == [] and alice["chat_count"] == 1
    assert count("tracker_players") == 1


def test_a_player_met_in_a_lobby_and_heard_in_chat_is_one_player(client):
    key = register(client)
    snap(client, key, ["TEST"], "2026-09-21T14:00:00Z")
    chat(client, key, [say("hi")])

    assert count("tracker_players") == 1
    test = find(client, "TEST")
    assert test["times_seen"] == 1 and test["chat_count"] == 1


def test_messages_come_back_newest_first(client):
    key = register(client)
    chat(client, key, [say("one", when=T1), say("three", when=T3), say("two", when=T2)])

    assert [c["message"] for c in find(client, "TEST")["chats"]] == ["three", "two", "one"]


def test_the_same_message_sent_twice_is_stored_once(client):
    key = register(client)
    chat(client, key, [say("hello")])
    again = chat(client, key, [say("hello")]).json()

    assert again["repeated"] == 1 and again["saved"] == 0
    assert count("tracker_chats") == 1


def test_the_same_words_at_another_time_are_two_messages(client):
    key = register(client)
    chat(client, key, [say("gg", when=T1), say("gg", when=T2)])
    assert count("tracker_chats") == 2


def test_capitalisation_of_the_name_does_not_make_a_second_player(client):
    key = register(client)
    chat(client, key, [say("a", player="Alice", when=T1), say("b", player="ALICE", when=T2)])
    assert count("tracker_players") == 1 and find(client, "alice")["chat_count"] == 2


def test_the_channel_is_kept_and_an_unknown_one_becomes_public(client):
    key = register(client)
    chat(client, key, [say("a", channel="Party", when=T1), say("b", channel="guild", when=T2),
                       say("c", channel="Private", when=T3), say("d", channel="Co-op", when="2026-09-22T10:00:00Z"),
                       say("e", channel="Mystery", when="2026-09-22T11:00:00Z")])

    channels = {c["message"]: c["channel"] for c in find(client, "TEST")["chats"]}
    assert channels == {"a": "Party", "b": "Guild", "c": "Private", "d": "Co-op", "e": "Public"}


def test_the_text_is_tidied(client):
    key = register(client)
    chat(client, key, [say("  spaced   out\ttext\n  here  ")])
    assert find(client, "TEST")["chats"][0]["message"] == "spaced out text here"


def test_a_very_long_message_is_cut(client):
    key = register(client)
    chat(client, key, [say("x" * 1000)])
    message = find(client, "TEST")["chats"][0]["message"]
    assert len(message) == app_module.CHAT_MAX_TEXT and message.endswith("…")


def test_html_in_a_message_is_stored_as_plain_text_never_run(client):
    key = register(client)
    chat(client, key, [say("<script>alert(1)</script> <b>hi</b>")])
    assert find(client, "TEST")["chats"][0]["message"] == "<script>alert(1)</script> <b>hi</b>"


def test_rubbish_names_and_empty_text_are_ignored(client):
    key = register(client)
    result = chat(client, key, [say("x", player="ab"), say("x", player="has space"), say("x", player="!A-a"),
                                say("   ", when=T3), say("kept", player="Alice")]).json()

    assert result["saved"] == 1 and result["ignored"] == 4
    assert count("tracker_players") == 1


def test_your_own_messages_are_kept_apart_and_never_make_you_a_player(client):
    key = register(client)
    result = chat(client, key, [say("mine", player=ME), say("mine again", player=ME.lower(), when=T2),
                                say("theirs", player="Alice", when=T3)]).json()

    assert result["saved"] == 3 and result["ignored"] == 0
    assert (count("tracker_my_chats"), count("tracker_chats"), count("tracker_players")) == (2, 1, 1)
    assert find(client, ME) is None


def test_your_own_messages_can_be_read_newest_first_with_a_total(client):
    key = register(client)
    chat(client, key, [say("first", player=ME, when=T1), say("party talk", player=ME, channel="Party", when=T3)])

    answer = client.get("/tracker/mychat", params={"owner": UUID_A}).json()
    assert answer["count"] == 2
    assert [(c["message"], c["channel"], c["world"]) for c in answer["chats"]] == \
        [("party talk", "Party", "The End"), ("first", "Public", "The End")]
    assert client.get("/tracker/mychat", params={"owner": UUID_B}).json() == {"count": 0, "chats": []}
    assert client.get("/tracker/mychat", params={"owner": "nope"}).status_code == 422


def test_the_same_own_message_is_stored_once(client):
    key = register(client)
    chat(client, key, [say("hi", player=ME)])
    result = chat(client, key, [say("hi", player=ME)]).json()

    assert result["repeated"] == 1 and count("tracker_my_chats") == 1


def test_your_own_messages_are_tidied_like_everyone_elses(client):
    key = register(client)
    chat(client, key, [say("a  b\tc" + "z" * 400, player=ME)])
    text = client.get("/tracker/mychat", params={"owner": UUID_A}).json()["chats"][0]["message"]

    assert text.startswith("a b cz") and len(text) == app_module.CHAT_MAX_TEXT


def test_your_own_messages_become_live_events_for_your_account_only(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    chat(client, key_a, [say("mine", player=ME, when=T1)])
    chat(client, key_b, [say("alt", player="AltAccount", when=T2)], me="AltAccount")

    events = app_module._my_chat_events(0, UUID_A)
    assert [(e["message"], e["kind"]) for e in events] == [("mine", "mychat")]
    assert app_module._my_chat_events(events[-1]["id"], UUID_A) == []
    assert app_module._newest_my_chat_id() > events[-1]["id"]


def test_an_empty_request_records_nothing(client):
    key = register(client)
    assert chat(client, key, []).json()["saved"] == 0
    assert count("tracker_chats") == 0


def test_the_chat_message_needs_a_key_and_a_valid_body(client):
    assert client.post("/tracker/chat", json={"messages": []}).status_code in (401, 403)
    key = register(client)
    assert client.post("/tracker/chat", json={"me": ME}, headers={"X-API-Key": key}).status_code == 422
    assert chat(client, key, [say("hi", when="not a time")]).status_code == 422
    assert chat(client, key, [{"player": "Alice", "text": "no time"}]).status_code == 422
    too_many = [say("m%d" % i, when="2026-09-21T15:%02d:%02dZ" % (i // 60, i % 60)) for i in range(app_module.TRACKER_MAX_CHATS + 1)]
    assert chat(client, key, too_many).status_code == 413
    assert count("tracker_chats") == 0


def test_a_bad_timestamp_refuses_the_whole_request(client):
    key = register(client)
    assert chat(client, key, [say("good"), say("bad", when="nonsense")]).status_code == 422
    assert count("tracker_chats") == 0


def test_a_world_name_that_could_be_a_server_code_is_saved_as_unknown(client):
    key = register(client)
    chat(client, key, [say("hi")], world="mini264C")
    assert find(client, "TEST")["chats"][0]["world"] == "Unknown"


def test_only_the_message_and_its_details_are_stored(client):
    key = register(client)
    body = {"me": ME, "world": "Hub", "messages": [say("hello")], "server": "mc.hypixel.net", "lobby": "m28CW"}
    client.post("/tracker/chat", json=body, headers={"X-API-Key": key})

    with app_module.db() as conn:
        dump = json.dumps([list(r) for t in ("tracker_players", "tracker_chats") for r in conn.execute("SELECT * FROM " + t).fetchall()])
    for text in ("hypixel", "m28CW"):
        assert text not in dump


# --------------------------------------------------------------- where it shows

def test_the_light_search_has_the_message_count_but_not_the_messages(client):
    key = register(client)
    chat(client, key, [say("one", when=T1), say("two", when=T2)])

    light = client.get("/tracker/search", params={"q": "te", "sightings": "false"}).json()["players"][0]
    assert light["chat_count"] == 2 and "chats" not in light


def test_a_player_who_never_spoke_has_no_chat(client):
    key = register(client)
    snap(client, key, ["Quiet"], "2026-09-21T14:00:00Z")
    quiet = find(client, "Quiet")
    assert quiet["chat_count"] == 0 and quiet["chats"] == []


def test_each_account_can_see_only_what_it_heard(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    chat(client, key_a, [say("heard by main", when=T1)])
    chat(client, key_b, [say("heard by alt", when=T2)], me="AltAccount")

    assert [c["message"] for c in find(client, "TEST", owner=UUID_A)["chats"]] == ["heard by main"]
    assert [c["message"] for c in find(client, "TEST", owner=UUID_B)["chats"]] == ["heard by alt"]
    both = find(client, "TEST")
    assert both["chat_count"] == 2 and {c["account"] for c in both["chats"]} == {ME, "AltAccount"}


def test_at_most_the_newest_few_hundred_messages_are_listed_but_all_are_counted(client):
    key = register(client)
    for start in range(0, 350, 100):
        chat(client, key, [say("m%d" % i, when="2026-09-21T%02d:%02d:%02dZ" % (i // 3600, (i // 60) % 60, i % 60))
                           for i in range(start, min(start + 100, 350))])

    test = find(client, "TEST")
    assert test["chat_count"] == 350 and len(test["chats"]) == app_module.SEARCH_CHAT_LIMIT
    assert test["chats"][0]["message"] == "m349"


# ------------------------------------------------------------------- live events

def test_a_message_becomes_a_live_event_for_the_account_that_heard_it(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    chat(client, key_a, [say("one", when=T1)])
    chat(client, key_b, [say("two", when=T2)], me="AltAccount")

    events = app_module._chat_events(0, UUID_A)
    assert [(e["username"], e["message"], e["channel"], e["kind"]) for e in events] == [("TEST", "one", "Public", "chat")]
    assert app_module._chat_events(events[-1]["id"], UUID_A) == []
    assert app_module._newest_chat_id() > events[-1]["id"]


# ----------------------------------------------------------------------- clearing

@pytest.fixture()
def local(client):
    with TestClient(app_module.app, client=("127.0.0.1", 50000)) as c:
        yield c


def test_clearing_everything_also_forgets_the_chat_and_chat_only_players(client, local):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-21T14:00:00Z")
    chat(client, key, [say("hi", player="Alice"), say("yo", player="Zed", when=T2)])

    chat(client, key, [say("mine", player=ME, when=T3)])

    result = local.post("/tracker/clear", json={"owner": UUID_A}).json()

    assert result["chats_removed"] == 3
    assert (count("tracker_players"), count("tracker_chats"), count("tracker_my_chats")) == (0, 0, 0)


def test_clearing_one_world_clears_your_own_messages_from_that_world_only(client, local):
    key = register(client)
    chat(client, key, [say("in the hub", player=ME, when=T1)], world="Hub")
    chat(client, key, [say("in the end", player=ME, when=T2)], world="The End")

    local.post("/tracker/clear", json={"owner": UUID_A, "world": "Hub"})

    assert [c["message"] for c in client.get("/tracker/mychat", params={"owner": UUID_A}).json()["chats"]] == ["in the end"]


def test_clearing_one_world_clears_only_the_chat_from_that_world(client, local):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-21T14:00:00Z", world="Hub")
    chat(client, key, [say("in the hub", player="Alice", when=T1)], world="Hub")
    chat(client, key, [say("in the end", player="Alice", when=T2)], world="The End")

    local.post("/tracker/clear", json={"owner": UUID_A, "world": "Hub"})

    assert [c["message"] for c in find(client, "Alice")["chats"]] == ["in the end"]


def test_a_player_who_chatted_stays_when_their_world_is_cleared(client, local):
    key = register(client)
    snap(client, key, ["Alice"], "2026-09-21T14:00:00Z", world="Hub")
    chat(client, key, [say("still here", player="Alice")], world="The End")

    local.post("/tracker/clear", json={"owner": UUID_A, "world": "Hub"})

    alice = find(client, "Alice")
    assert alice is not None and alice["times_seen"] == 0 and alice["chat_count"] == 1


def test_clearing_one_account_leaves_the_others_chat(client, local):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    chat(client, key_a, [say("main", when=T1)])
    chat(client, key_b, [say("alt", when=T2)], me="AltAccount")

    local.post("/tracker/clear", json={"owner": UUID_A})

    assert [c["message"] for c in find(client, "TEST")["chats"]] == ["alt"]
