"""Tests for chat types (PUBLIC, PARTY, PRIVATE_FROM, PRIVATE_TO, ...) and GET /tracker/chats, the Chat Logs tab's data."""

import app as app_module
from conftest import UUID_A, UUID_B, register

import pytest

ME = "WeTrollALittle"


def at(second, minute=0, hour=15):
    return "2026-09-21T%02d:%02d:%02dZ" % (hour, minute, second)


def say(text, player="Alice", kind=None, when=None, channel=None):
    message = {"player": player, "text": text, "timestamp": when or at(0)}
    if kind is not None:
        message["type"] = kind
    if channel is not None:
        message["channel"] = channel
    return message


def send(client, key, messages, world="Hub", me=ME):
    return client.post("/tracker/chat", json={"me": me, "world": world, "messages": messages}, headers={"X-API-Key": key})


def logs(client, owner=UUID_A, **params):
    response = client.get("/tracker/chats", params={"owner": owner, **params})
    assert response.status_code == 200, response.text
    return response.json()


def texts(answer):
    return [c["message"] for c in answer["chats"]]


def stored_channel(text):
    with app_module.db() as conn:
        row = conn.execute("SELECT channel FROM tracker_chats WHERE message = ? UNION ALL "
                           "SELECT channel FROM tracker_my_chats WHERE message = ?", (text, text)).fetchone()
    return row["channel"]


# ------------------------------------------------------------------ receiving a type

@pytest.mark.parametrize("kind,label", [("PUBLIC", "Public"), ("PARTY", "Party"), ("GUILD", "Guild"), ("COOP", "Co-op"),
                                        ("PRIVATE_FROM", "Private"), ("PRIVATE_TO", "Private To")])
def test_every_type_is_stored_in_the_channel_column(client, kind, label):
    key = register(client)
    assert send(client, key, [say("hello", kind=kind)]).json()["saved"] == 1
    assert stored_channel("hello") == label


def test_the_type_is_case_insensitive(client):
    key = register(client)
    send(client, key, [say("hello", kind=" party ")])
    assert stored_channel("hello") == "Party"


def test_a_missing_type_is_public(client):
    key = register(client)
    send(client, key, [say("hello")])
    assert stored_channel("hello") == "Public"


def test_an_empty_type_is_public(client):
    key = register(client)
    send(client, key, [say("hello", kind="  ")])
    assert stored_channel("hello") == "Public"


def test_the_channel_older_versions_of_the_mod_sent_still_works(client):
    key = register(client)
    send(client, key, [say("one", channel="Party"), say("two", channel="Private", when=at(1)), say("three", channel="nonsense", when=at(2))])
    assert (stored_channel("one"), stored_channel("two"), stored_channel("three")) == ("Party", "Private", "Public")


def test_the_type_wins_over_the_channel(client):
    key = register(client)
    send(client, key, [say("hello", kind="PARTY", channel="Public")])
    assert stored_channel("hello") == "Party"


def test_an_unknown_type_refuses_the_whole_request_and_saves_nothing(client):
    key = register(client)
    response = send(client, key, [say("good", kind="PARTY"), say("bad", kind="WHISPER", when=at(1))])

    assert response.status_code == 422
    assert logs(client)["chats"] == []


def test_a_private_message_you_sent_is_filed_under_the_player_you_wrote_to(client):
    key = register(client)
    send(client, key, [say("on my way", player="Bob", kind="PRIVATE_TO")])

    chat = logs(client)["chats"][0]
    assert (chat["player"], chat["type"], chat["mine"]) == ("Bob", "PRIVATE_TO", False)


def test_your_own_public_and_party_messages_are_in_the_list_under_your_name(client):
    key = register(client)
    send(client, key, [say("my public", player=ME, kind="PUBLIC"), say("my party", player=ME, kind="PARTY", when=at(1))])

    chats = logs(client)["chats"]
    assert [(c["player"], c["type"], c["mine"]) for c in chats] == [(ME, "PARTY", True), (ME, "PUBLIC", True)]


# ------------------------------------------------------------------ reading

def test_the_newest_chats_come_first_with_time_type_player_message_and_world(client):
    key = register(client)
    send(client, key, [say("old", when=at(0)), say("new", kind="PARTY", when=at(5))], world="The End")

    answer = logs(client)
    assert answer["chats"][0] == {"at": "2026-09-21T15:00:05.000Z", "type": "PARTY", "player": "Alice", "message": "new",
                                  "world": "The End", "mine": False, "account": ME, "account_uuid": UUID_A}
    assert texts(answer) == ["new", "old"]
    assert answer["has_more"] is False


def test_old_rows_read_as_public_and_private_reads_as_from(client):
    key = register(client)
    send(client, key, [say("a", channel="Public"), say("b", channel="Private", when=at(1))])
    assert [c["type"] for c in logs(client)["chats"]] == ["PRIVATE_FROM", "PUBLIC"]


def test_an_empty_database_gives_an_empty_list(client):
    register(client)
    assert logs(client) == {"chats": [], "has_more": False, "next_before": None}


def test_a_page_is_100_chats_and_before_loads_older_ones_without_gaps_or_repeats(client):
    key = register(client)
    everything = [say("m%03d" % i, when=at(i % 60, minute=i // 60)) for i in range(250)]
    send(client, key, everything[:200])
    send(client, key, everything[200:])

    seen, before = [], None
    for expected in (100, 100, 50):
        params = {"before": before} if before else {}
        page = logs(client, **params)
        assert len(page["chats"]) == expected
        seen += texts(page)
        before = page["next_before"]
        assert page["has_more"] is (expected == 100)

    assert seen == ["m%03d" % i for i in range(249, -1, -1)]


def test_a_page_never_splits_chats_that_share_a_second_or_come_from_both_tables(client):
    key = register(client)
    send(client, key, [say("a", when=at(1)), say("b", player="Bob", when=at(1)), say("mine", player=ME, when=at(1)),
                       say("c", player="Cat", when=at(1)), say("d", when=at(2))])

    seen, before = [], None
    for _ in range(10):
        page = logs(client, limit=2, **({"before": before} if before else {}))
        seen += texts(page)
        before = page["next_before"]
        if not page["has_more"]:
            break
    assert sorted(seen) == ["a", "b", "c", "d", "mine"] and len(seen) == 5


def test_the_limit_can_be_changed_but_not_past_200(client):
    register(client)
    assert client.get("/tracker/chats", params={"owner": UUID_A, "limit": 201}).status_code == 422
    assert client.get("/tracker/chats", params={"owner": UUID_A, "limit": 0}).status_code == 422


# ------------------------------------------------------------------ search and filters

def test_search_finds_the_message_text_ignoring_capitals(client):
    key = register(client)
    send(client, key, [say("Selling a HYPERION", when=at(0)), say("hello", when=at(1))])
    assert texts(logs(client, q="hyperion")) == ["Selling a HYPERION"]


def test_search_finds_the_player_name_too(client):
    key = register(client)
    send(client, key, [say("one", player="Zed_the_Great", when=at(0)), say("two", player="Alice", when=at(1))])
    assert texts(logs(client, q="zed")) == ["one"]


def test_search_treats_percent_and_underscore_as_themselves(client):
    key = register(client)
    send(client, key, [say("100% sure", when=at(0)), say("100 sure", when=at(1)), say("a_b", when=at(2)), say("axb", when=at(3))])
    assert texts(logs(client, q="100%")) == ["100% sure"]
    assert texts(logs(client, q="a_b")) == ["a_b"]


def test_search_ignores_spaces_around_it_and_an_empty_search_finds_everything(client):
    key = register(client)
    send(client, key, [say("one", when=at(0)), say("two", when=at(1))])
    assert texts(logs(client, q="  one ")) == ["one"]
    assert len(logs(client, q="   ")["chats"]) == 2


def test_search_nothing_found_is_an_empty_list(client):
    key = register(client)
    send(client, key, [say("hello")])
    assert logs(client, q="zzzz")["chats"] == []


def test_type_filters(client):
    key = register(client)
    send(client, key, [say("pub", kind="PUBLIC", when=at(0)), say("par", kind="PARTY", when=at(1)),
                       say("from", kind="PRIVATE_FROM", when=at(2)), say("to", kind="PRIVATE_TO", when=at(3)),
                       say("gld", kind="GUILD", when=at(4))])

    assert texts(logs(client, type="PUBLIC")) == ["pub"]
    assert texts(logs(client, type="party")) == ["par"]
    assert texts(logs(client, type="PRIVATE_FROM")) == ["from"]
    assert texts(logs(client, type="PRIVATE_TO")) == ["to"]
    assert texts(logs(client, type="PRIVATE")) == ["to", "from"]
    assert texts(logs(client, type="GUILD")) == ["gld"]
    assert len(logs(client, type="ALL")["chats"]) == 5
    assert len(logs(client, type="")["chats"]) == 5


def test_an_unknown_type_filter_is_refused(client):
    register(client)
    assert client.get("/tracker/chats", params={"owner": UUID_A, "type": "WHISPER"}).status_code == 422


def test_player_filter_is_one_exact_name_ignoring_capitals(client):
    key = register(client)
    send(client, key, [say("a", player="Alice", when=at(0)), say("b", player="Alice2", when=at(1)), say("c", player="Bob", when=at(2))])
    assert texts(logs(client, player="ALICE")) == ["a"]
    assert logs(client, player="nobody")["chats"] == []


def test_the_player_filter_finds_your_own_messages_by_your_name(client):
    key = register(client)
    send(client, key, [say("mine", player=ME, when=at(0)), say("theirs", when=at(1))])
    assert texts(logs(client, player=ME.lower())) == ["mine"]


def test_filters_combine_and_paging_keeps_them(client):
    key = register(client)
    send(client, key, [say("gg %d" % i, kind="PARTY" if i % 2 else "PUBLIC", when=at(i)) for i in range(20)])

    first = logs(client, q="gg", type="PARTY", limit=4)
    assert len(first["chats"]) == 4 and first["has_more"] is True
    second = logs(client, q="gg", type="PARTY", limit=4, before=first["next_before"])
    assert set(texts(first)).isdisjoint(texts(second))
    assert all(c["type"] == "PARTY" for c in first["chats"] + second["chats"])


# ------------------------------------------------------------------ day folders

def on_day(text, day, hour=15, minute=0):
    return say(text, when="2026-09-%02dT%02d:%02d:00Z" % (day, hour, minute))


def hours(client, owner=UUID_A, **params):
    response = client.get("/tracker/chats/days", params={"owner": owner, **params})
    assert response.status_code == 200, response.text
    return response.json()["hours"]


def test_since_and_until_open_one_day_of_chats(client):
    key = register(client)
    send(client, key, [on_day("mon", 21), on_day("tue early", 22, hour=0), on_day("tue late", 22, hour=23, minute=59), on_day("wed", 23, hour=0)])

    day = logs(client, since="2026-09-22T00:00:00.000Z", until="2026-09-23T00:00:00.000Z")
    assert texts(day) == ["tue late", "tue early"]


def test_since_and_until_keep_the_other_filters_and_paging(client):
    key = register(client)
    send(client, key, [say("gg %d" % i, kind="PARTY" if i % 2 else "PUBLIC", when=at(i)) for i in range(10)] + [on_day("gg other day", 22)])

    first = logs(client, q="gg", type="PARTY", since="2026-09-21T00:00:00Z", until="2026-09-22T00:00:00Z", limit=2)
    assert len(first["chats"]) == 2 and first["has_more"] is True
    second = logs(client, q="gg", type="PARTY", since="2026-09-21T00:00:00Z", until="2026-09-22T00:00:00Z", limit=10, before=first["next_before"])
    assert len(second["chats"]) == 3
    assert "gg other day" not in texts(first) + texts(second)


def test_a_bad_since_or_until_is_refused(client):
    register(client)
    for name in ("since", "until"):
        assert client.get("/tracker/chats", params={"owner": UUID_A, name: "yesterday"}).status_code == 422
        assert client.get("/tracker/chats", params={"owner": UUID_A, name: "x' OR 1=1 --"}).status_code == 422


def test_the_days_list_counts_chats_per_utc_hour_newest_first(client):
    key = register(client)
    send(client, key, [on_day("a", 21, hour=15), on_day("b", 21, hour=15, minute=30), on_day("c", 22, hour=3)])

    assert hours(client) == [{"hour": "2026-09-22T03", "count": 1}, {"hour": "2026-09-21T15", "count": 2}]


def test_the_days_list_follows_the_same_filters(client):
    key = register(client)
    send(client, key, [on_day("hello there", 21), say("bye", kind="PARTY", when="2026-09-22T10:00:00Z")])

    assert hours(client, q="hello") == [{"hour": "2026-09-21T15", "count": 1}]
    assert hours(client, type="PARTY") == [{"hour": "2026-09-22T10", "count": 1}]
    assert hours(client, player="nobody") == []


def test_the_days_list_counts_a_chat_heard_by_two_accounts_once(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    send(client, key_a, [say("same")])
    send(client, key_b, [say("same")], me="AltAccount")

    assert hours(client, owner="all") == [{"hour": "2026-09-21T15", "count": 1}]
    assert hours(client, owner=UUID_A) == [{"hour": "2026-09-21T15", "count": 1}]


def test_the_days_list_of_an_empty_database_is_empty_and_a_bad_owner_is_refused(client):
    register(client)
    assert hours(client) == []
    assert client.get("/tracker/chats/days", params={"owner": "nope"}).status_code == 422
    assert client.get("/tracker/chats/days", params={"owner": UUID_A, "type": "NOPE"}).status_code == 422


# ------------------------------------------------------------------ safety

def test_each_account_only_sees_its_own_chats(client):
    key_a, key_b = register(client, UUID_A, ME), register(client, UUID_B, "AltAccount")
    send(client, key_a, [say("for a")])
    send(client, key_b, [say("for b")], me="AltAccount")

    assert texts(logs(client, owner=UUID_A)) == ["for a"]
    assert texts(logs(client, owner=UUID_B)) == ["for b"]


def test_a_bad_owner_or_cursor_is_refused(client):
    register(client)
    assert client.get("/tracker/chats", params={"owner": "nope"}).status_code == 422
    assert client.get("/tracker/chats", params={"owner": UUID_A, "before": "yesterday"}).status_code == 422
    assert client.get("/tracker/chats", params={"owner": UUID_A, "before": "x' OR 1=1 --"}).status_code == 422


def test_sql_in_a_search_is_only_text(client):
    key = register(client)
    send(client, key, [say("hello")])
    assert logs(client, q="' OR 1=1 --")["chats"] == []
    assert logs(client, player="x' OR '1'='1")["chats"] == []
    assert texts(logs(client, q="hello")) == ["hello"]


def test_html_in_a_message_comes_back_as_plain_text_for_the_page_to_escape(client):
    key = register(client)
    send(client, key, [say("<script>alert(1)</script>")])
    assert texts(logs(client)) == ["<script>alert(1)</script>"]
