"""Tests for the remote commands: the page sends a quit or a chat message to one account's game with the admin token, the game
asks for it with its API key (GET /commands/next) and says how it went (POST /commands/{id}/result)."""

import json

import app as app_module
from conftest import SECRET, UUID_A, UUID_B, register

import pytest

A = "WeTrollALittle"
B = "BayHarborBeater"
ADMIN = "test-admin-token"


@pytest.fixture()
def keys(client):
    """Two linked accounts, both online, an admin token in the config and no commands yet. Returns the two API keys."""
    with app_module.db() as conn:
        conn.execute("DELETE FROM commands")
    key_a, key_b = register(client, UUID_A, A), register(client, UUID_B, B)
    config = json.loads(app_module.CONFIG_PATH.read_text(encoding="utf-8"))
    config["admin_token"] = ADMIN
    app_module.CONFIG_PATH.write_text(json.dumps(config), encoding="utf-8")
    online(client, key_a, UUID_A, A)
    online(client, key_b, UUID_B, B)
    return key_a, key_b


def online(client, key, uuid, name, is_online=True):
    body = {"player_uuid": uuid, "player_name": name, "online": is_online}
    assert client.post("/heartbeat", json=body, headers={"X-API-Key": key}).status_code == 200


def send(client, account, body, token=ADMIN):
    headers = {"X-Admin-Token": token} if token is not None else {}
    return client.post("/api/accounts/" + account + "/command", json=body, headers=headers)


def take(client, key):
    response = client.get("/commands/next", headers={"X-API-Key": key})
    assert response.status_code == 200, response.text
    return response.json()["command"]


def answer(client, key, command_id, status, reason=None):
    return client.post("/commands/%d/result" % command_id, json={"status": status, "reason": reason}, headers={"X-API-Key": key})


def history(client, account, token=ADMIN):
    return client.get("/api/accounts/" + account + "/commands", headers={"X-Admin-Token": token})


def age(command_id, created_seconds_ago=None, taken_seconds_ago=None):
    """Moves a command's times into the past, as if it had been waiting that long."""
    with app_module.db() as conn:
        if created_seconds_ago is not None:
            conn.execute("UPDATE commands SET created_at = ? WHERE id = ?", (app_module._utc_stamp(created_seconds_ago), command_id))
        if taken_seconds_ago is not None:
            conn.execute("UPDATE commands SET taken_at = ? WHERE id = ?", (app_module._utc_stamp(taken_seconds_ago), command_id))


# ------------------------------------------------------------------ sending from the page

def test_a_chat_command_is_queued_for_the_named_account(client, keys):
    response = send(client, A, {"type": "chat", "payload": "  hello   there  "})
    assert response.status_code == 200, response.text
    made = response.json()
    assert made["status"] == "PENDING" and made["type"] == "CHAT" and made["target"] == A

    listed = history(client, A).json()
    assert listed["account"] == A
    assert [(c["id"], c["type"], c["payload"], c["status"]) for c in listed["commands"]] == [(made["id"], "CHAT", "hello there", "PENDING")]


def test_the_account_can_be_named_by_uuid_or_by_name_in_any_case(client, keys):
    assert send(client, UUID_A, {"type": "QUIT"}).json()["target"] == A
    assert send(client, A.upper(), {"type": "QUIT"}).json()["target"] == A


@pytest.mark.parametrize("token", [None, "", "wrong", ADMIN + "x"])
def test_sending_needs_the_right_admin_token(client, keys, token):
    assert send(client, A, {"type": "QUIT"}, token=token).status_code == 401
    assert history(client, A, token=token or "nope").status_code == 401
    with app_module.db() as conn:
        assert conn.execute("SELECT COUNT(*) FROM commands").fetchone()[0] == 0


def test_without_an_admin_token_in_the_config_nothing_can_be_sent(client, keys):
    config = json.loads(app_module.CONFIG_PATH.read_text(encoding="utf-8"))
    config["admin_token"] = ""
    app_module.CONFIG_PATH.write_text(json.dumps(config), encoding="utf-8")
    assert send(client, A, {"type": "QUIT"}, token="").status_code == 401
    assert send(client, A, {"type": "QUIT"}, token="anything").status_code == 401


def test_an_api_key_is_not_an_admin_token(client, keys):
    assert send(client, A, {"type": "QUIT"}, token=keys[0]).status_code == 401


@pytest.mark.parametrize("payload", ["/warp hub", "  /pc hi", "/", "\n/p leave"])
def test_a_chat_message_can_never_be_a_command(client, keys, payload):
    response = send(client, A, {"type": "CHAT", "payload": payload})
    assert response.status_code == 422
    with app_module.db() as conn:
        assert conn.execute("SELECT COUNT(*) FROM commands").fetchone()[0] == 0


@pytest.mark.parametrize("body", [{"type": "CHAT"}, {"type": "CHAT", "payload": "   "}, {"type": "CHAT", "payload": "x" * 257},
                                  {"type": "DANCE"}, {"type": ""}])
def test_bad_commands_are_refused(client, keys, body):
    assert send(client, A, body).status_code == 422


def test_control_characters_are_turned_into_spaces(client, keys):
    send(client, A, {"type": "CHAT", "payload": "hi\u0000\u0007there\tyou"})
    assert take(client, keys[0])["payload"] == "hi there you"


def test_an_unknown_account_is_404(client, keys):
    assert send(client, "NobodyAtAll", {"type": "QUIT"}).status_code == 404
    assert history(client, "NobodyAtAll").status_code == 404


def test_an_offline_account_cannot_be_sent_anything(client, keys):
    online(client, keys[1], UUID_B, B, is_online=False)
    assert send(client, B, {"type": "QUIT"}).status_code == 409


def test_at_most_three_commands_wait_per_account(client, keys):
    for _ in range(app_module.COMMAND_MAX_PENDING):
        assert send(client, A, {"type": "CHAT", "payload": "hi"}).status_code == 200
    assert send(client, A, {"type": "CHAT", "payload": "hi"}).status_code == 429
    assert send(client, B, {"type": "CHAT", "payload": "hi"}).status_code == 200          # the limit is per account


def test_history_is_newest_first_and_limited(client, keys):
    ids = [send(client, A, {"type": "CHAT", "payload": str(n)}).json()["id"] for n in range(3)]
    listed = client.get("/api/accounts/" + A + "/commands", params={"limit": 2}, headers={"X-Admin-Token": ADMIN}).json()
    assert [c["id"] for c in listed["commands"]] == [ids[2], ids[1]]


# ------------------------------------------------------------------ the game taking and answering

def test_the_game_gets_only_its_own_commands_oldest_first_and_each_once(client, keys):
    key_a, key_b = keys
    first = send(client, A, {"type": "CHAT", "payload": "one"}).json()["id"]
    second = send(client, A, {"type": "QUIT"}).json()["id"]

    assert take(client, key_b) is None                                     # B's game never sees A's commands
    assert take(client, key_a) == {"id": first, "type": "CHAT", "payload": "one"}
    assert take(client, key_a) == {"id": second, "type": "QUIT", "payload": None}
    assert take(client, key_a) is None                                     # handed out once


def test_asking_for_commands_needs_a_known_api_key(client, keys):
    assert client.get("/commands/next").status_code == 401
    assert client.get("/commands/next", headers={"X-API-Key": "nope"}).status_code == 401
    assert client.get("/commands/next", headers={"X-API-Key": ADMIN}).status_code == 401


def test_the_game_reports_done_and_the_page_sees_it(client, keys):
    made = send(client, A, {"type": "CHAT", "payload": "hi"}).json()["id"]
    take(client, keys[0])
    assert answer(client, keys[0], made, "done", reason="ignored when done").status_code == 200

    cmd = history(client, A).json()["commands"][0]
    assert cmd["status"] == "DONE" and cmd["reason"] is None and cmd["finished_at"]


def test_the_game_reports_a_failure_with_a_short_reason(client, keys):
    made = send(client, A, {"type": "CHAT", "payload": "hi"}).json()["id"]
    take(client, keys[0])
    assert answer(client, keys[0], made, "FAILED", reason="not   in a world" + "x" * 300).status_code == 200

    cmd = history(client, A).json()["commands"][0]
    assert cmd["status"] == "FAILED" and cmd["reason"].startswith("not in a world") and len(cmd["reason"]) == 200


def test_a_command_is_answered_only_once_only_by_its_account_and_only_after_it_was_taken(client, keys):
    key_a, key_b = keys
    made = send(client, A, {"type": "QUIT"}).json()["id"]
    assert answer(client, key_a, made, "DONE").status_code == 404        # not taken yet
    take(client, key_a)
    assert answer(client, key_b, made, "DONE").status_code == 404        # someone else's
    assert answer(client, key_a, made, "DONE").status_code == 200
    assert answer(client, key_a, made, "FAILED").status_code == 404      # already answered
    assert answer(client, key_a, 999999, "DONE").status_code == 404


def test_an_answer_must_be_done_or_failed_and_needs_a_key(client, keys):
    made = send(client, A, {"type": "QUIT"}).json()["id"]
    take(client, keys[0])
    assert answer(client, keys[0], made, "MAYBE").status_code == 422
    assert client.post("/commands/%d/result" % made, json={"status": "DONE"}).status_code == 401


# ------------------------------------------------------------------ expiry

def test_a_command_nobody_picks_up_in_time_is_failed_and_never_handed_out(client, keys):
    made = send(client, A, {"type": "QUIT"}).json()["id"]
    age(made, created_seconds_ago=app_module.COMMAND_WAIT_SECONDS + 5)

    assert take(client, keys[0]) is None
    cmd = history(client, A).json()["commands"][0]
    assert cmd["status"] == "FAILED" and "expired" in cmd["reason"]


def test_a_command_taken_but_never_answered_is_failed(client, keys):
    made = send(client, A, {"type": "CHAT", "payload": "hi"}).json()["id"]
    take(client, keys[0])
    age(made, taken_seconds_ago=app_module.COMMAND_ANSWER_SECONDS + 5)

    cmd = history(client, A).json()["commands"][0]
    assert cmd["status"] == "FAILED" and cmd["reason"] == "no answer from the game"
    assert answer(client, keys[0], made, "DONE").status_code == 404


def test_expired_commands_free_up_room_in_the_limit(client, keys):
    ids = [send(client, A, {"type": "QUIT"}).json()["id"] for _ in range(app_module.COMMAND_MAX_PENDING)]
    for command_id in ids:
        age(command_id, created_seconds_ago=app_module.COMMAND_WAIT_SECONDS + 5)
    assert send(client, A, {"type": "QUIT"}).status_code == 200


def test_the_enrol_secret_is_not_an_admin_token(client, keys):
    assert send(client, A, {"type": "QUIT"}, token=SECRET).status_code == 401
