"""Tests for the live view: the game posts a picture of its screen, and only a page on the PC the website runs on may look at it."""

import app as app_module
from conftest import UUID_A, UUID_B, register

import pytest

JPEG = b"\xff\xd8\xff\xe0" + b"picture" * 20


@pytest.fixture()
def key(client):
    with app_module._live_lock:
        app_module._live_frames.clear()
        app_module._live_watched.clear()
    return register(client, UUID_A, "WeTrollALittle")


@pytest.fixture()
def here(monkeypatch):
    """The test client comes from 'testclient', which is not this PC. This makes it this PC."""
    monkeypatch.setattr(app_module, "_is_this_pc", lambda host: True)


def post(client, key, body=JPEG, uuid=UUID_A):
    return client.post("/live/frame?player_uuid=" + uuid, content=body, headers={"X-API-Key": key, "Content-Type": "image/jpeg"})


def test_a_picture_is_stored_and_shown_to_this_pc(client, key, here):
    assert post(client, key).status_code == 200
    reply = client.get("/liveview/" + UUID_A + "/frame.jpg")
    assert reply.status_code == 200
    assert reply.headers["content-type"] == "image/jpeg"
    assert reply.content == JPEG
    assert UUID_A in client.get("/liveview/status").json()["frames"]


def test_another_pc_may_not_look(client, key):
    assert post(client, key).status_code == 200
    assert client.get("/liveview/" + UUID_A + "/frame.jpg").status_code == 403
    assert client.get("/liveview/status").status_code == 403


def test_the_game_is_told_when_the_page_is_watching(client, key, here):
    assert post(client, key).json() == {"viewers": 0}
    client.get("/liveview/" + UUID_A + "/frame.jpg")
    assert post(client, key).json() == {"viewers": 1}


def test_a_picture_needs_a_known_key(client, key):
    assert client.post("/live/frame?player_uuid=" + UUID_A, content=JPEG).status_code == 401
    assert post(client, "wrong").status_code == 401


def test_a_key_may_only_send_its_own_account(client, key):
    assert post(client, key, uuid=UUID_B).status_code == 403


@pytest.mark.parametrize("kind", ["text", "empty", "huge"])
def test_only_a_small_jpeg_is_taken(client, key, kind):
    body = {"text": b"not a picture", "empty": b"", "huge": b"\xff\xd8\xff" + b"x" * 500_000}[kind]
    assert post(client, key, body=body).status_code in (413, 422)


def test_an_old_picture_is_not_shown(client, key, here):
    post(client, key)
    with app_module._live_lock:
        app_module._live_frames[UUID_A]["at"] -= app_module.LIVE_STALE_S + 1
    assert client.get("/liveview/" + UUID_A + "/frame.jpg").status_code == 404
    assert UUID_A not in client.get("/liveview/status").json()["frames"]


@pytest.mark.parametrize("host,expected", [
    ("127.0.0.1", True), ("::1", True), ("::ffff:127.0.0.1", True),
    ("8.8.8.8", False), ("100.99.99.99", False), ("testclient", False), ("", False), (None, False),
])
def test_what_counts_as_this_pc(host, expected):
    assert app_module._is_this_pc(host) is expected


def test_an_address_of_this_pc_counts():
    own = app_module._lan_address()
    if own:
        assert app_module._is_this_pc(own) is True


LIVE_MARKUP = ("tabLive", "tabBtnLive", "lvAccounts", "lvInputSwitch", "Game controls", "remoteMsg", "remoteQuit", "THISPC")


def test_another_pc_gets_a_page_with_no_live_tab_and_no_game_controls(client):
    html = client.get("/").text
    for word in LIVE_MARKUP:
        assert word not in html, word
    assert 'id="tabBtnFrienemys"' in html                     # the rest of the page is still there


def test_this_pc_gets_the_live_tab_with_the_game_controls_inside_it(client, here):
    html = client.get("/").text
    assert 'id="tabBtnLive"' in html and 'id="tabLive"' in html
    assert "THISPC" not in html                              # the markers themselves are not sent
    live = html[html.index('id="tabLive"'):]
    live = live[:live.index("</section>")]
    assert 'id="remoteMsg"' in live and 'id="remoteQuit"' in live      # the chat sender sits under the picture
    assert html.index('id="remote"') > html.index('id="tabLive"')


# ------------------------------------------------------------------------------------------------------------- input

ADMIN = "test-admin-token"


@pytest.fixture()
def admin(client):
    import json
    config = json.loads(app_module.CONFIG_PATH.read_text(encoding="utf-8"))
    config["admin_token"] = ADMIN
    app_module.CONFIG_PATH.write_text(json.dumps(config), encoding="utf-8")


def frame(client, key, input_on=True):
    headers = {"X-API-Key": key, "Content-Type": "image/jpeg", "X-Live-Input": "1" if input_on else "0"}
    assert client.post("/live/frame?player_uuid=" + UUID_A, content=JPEG, headers=headers).status_code == 200


def send(client, event, token=ADMIN):
    headers = {"X-Admin-Token": token} if token is not None else {}
    return client.post("/liveview/" + UUID_A + "/input", json=event, headers=headers)


def take(client, key):
    reply = client.get("/live/input?player_uuid=" + UUID_A, headers={"X-API-Key": key})
    assert reply.status_code == 200, reply.text
    return reply.json()["events"]


def test_a_click_reaches_the_game_once(client, key, here, admin):
    frame(client, key)
    assert send(client, {"type": "click", "x": 0.5, "y": 0.25, "button": 1, "shift": True}).status_code == 200
    assert take(client, key) == [{"type": "click", "x": 0.5, "y": 0.25, "button": 1, "shift": True}]
    assert take(client, key) == []


def test_every_kind_of_input_is_passed_on_in_order(client, key, here, admin):
    frame(client, key)
    sent = [{"type": "slot", "n": 2}, {"type": "click", "x": 0.5, "y": 0.5, "button": 1, "shift": False},
            {"type": "scroll", "x": 0.3, "y": 0.4, "dy": -1}, {"type": "key", "key": "escape"}, {"type": "text", "text": "diamond"}]
    for event in sent:
        assert send(client, event).status_code == 200
    got = take(client, key)
    assert [e["type"] for e in got] == ["slot", "click", "scroll", "key", "text"]
    assert got[0]["n"] == 2 and got[3]["key"] == "escape" and got[4]["text"] == "diamond"


def test_input_needs_this_pc_and_the_admin_token(client, key, admin, monkeypatch):
    frame(client, key)
    assert send(client, {"type": "slot", "n": 2}).status_code == 403                     # not this PC
    monkeypatch.setattr(app_module, "_is_this_pc", lambda host: True)
    assert send(client, {"type": "slot", "n": 2}, token=None).status_code == 401
    assert send(client, {"type": "slot", "n": 2}, token="wrong").status_code == 401
    assert send(client, {"type": "slot", "n": 2}).status_code == 200
    assert take(client, key) == [{"type": "slot", "n": 2}]


def test_a_game_that_has_input_off_takes_nothing(client, key, here, admin):
    frame(client, key, input_on=False)
    reply = send(client, {"type": "slot", "n": 2})
    assert reply.status_code == 409 and "/liveinput on" in reply.json()["detail"]
    assert take(client, key) == []


def test_a_game_that_never_sent_a_picture_takes_nothing(client, key, here, admin):
    assert send(client, {"type": "slot", "n": 2}).status_code == 409


def test_the_page_can_tell_which_games_take_input(client, key, here, admin):
    frame(client, key, input_on=True)
    assert client.get("/liveview/status").json()["input"] == {UUID_A: True}
    frame(client, key, input_on=False)
    assert client.get("/liveview/status").json()["input"] == {UUID_A: False}


@pytest.mark.parametrize("event", [
    {"type": "click"}, {"type": "click", "x": 2, "y": 0.5}, {"type": "click", "x": 0.5, "y": 0.5, "button": 7},
    {"type": "slot"}, {"type": "slot", "n": 10}, {"type": "slot", "n": 0},
    {"type": "key", "key": "f4"}, {"type": "key"},
    {"type": "text"}, {"type": "text", "text": ""}, {"type": "text", "text": "line\nbreak"}, {"type": "text", "text": "x" * 65},
    {"type": "teleport"},
])
def test_a_bad_input_is_refused(client, key, here, admin, event):
    frame(client, key)
    assert send(client, event).status_code == 422
    assert take(client, key) == []


def test_an_input_the_game_did_not_fetch_in_time_is_dropped(client, key, here, admin):
    frame(client, key)
    send(client, {"type": "slot", "n": 3})
    with app_module._live_lock:
        for event in app_module._live_input[UUID_A]:
            event["at"] -= app_module.LIVE_INPUT_TTL_S + 1
    assert take(client, key) == []


def test_only_the_newest_inputs_are_kept(client, key, here, admin):
    frame(client, key)
    for n in range(app_module.LIVE_INPUT_MAX + 10):
        send(client, {"type": "scroll", "x": 0.5, "y": 0.5, "dy": 1})
    assert len(take(client, key)) == app_module.LIVE_INPUT_MAX


def test_the_game_needs_its_key_to_fetch_input(client, key):
    assert client.get("/live/input?player_uuid=" + UUID_A).status_code == 401
    assert client.get("/live/input?player_uuid=" + UUID_B, headers={"X-API-Key": key}).status_code == 403


# --------------------------------------------------------------------------------------- state, skipping, the website switch

import json as _json
import urllib.parse as _urlparse


def frame_with_state(client, key, state):
    headers = {"X-API-Key": key, "Content-Type": "image/jpeg", "X-Live-State": _urlparse.quote(_json.dumps(state))}
    assert client.post("/live/frame?player_uuid=" + UUID_A, content=JPEG, headers=headers).status_code == 200


def test_what_the_game_says_about_itself_is_shown(client, key, here):
    frame_with_state(client, key, {"input": True, "slot": 1, "held": "Aspect of the End", "menu": False, "pos": [10.4, 71, -3.6]})
    account = client.get("/liveview/status").json()["accounts"][UUID_A]
    assert account["slot"] == 1 and account["held"] == "Aspect of the End" and account["menu"] is False
    assert account["pos"] == [10, 71, -4] and account["input"] is True and account["bytes"] == len(JPEG)
    assert client.get("/liveview/status").json()["input"] == {UUID_A: True}


def test_odd_state_is_cleaned(client, key, here):
    frame_with_state(client, key, {"input": "yes", "slot": 12, "held": "Bad\x00Name" + "x" * 100, "menu": 1, "pos": ["a", 1, 2]})
    account = client.get("/liveview/status").json()["accounts"][UUID_A]
    assert account["input"] is False and account["slot"] is None and account["menu"] is False and account["pos"] is None
    assert "\x00" not in account["held"] and len(account["held"]) <= 60


def test_state_that_is_not_json_is_ignored(client, key, here):
    headers = {"X-API-Key": key, "Content-Type": "image/jpeg", "X-Live-State": "%7Bnot json"}
    assert client.post("/live/frame?player_uuid=" + UUID_A, content=JPEG, headers=headers).status_code == 200
    assert client.get("/liveview/status").json()["accounts"][UUID_A]["slot"] is None


def test_the_page_is_only_sent_a_picture_it_does_not_have(client, key, here):
    post(client, key)
    first = client.get("/liveview/" + UUID_A + "/frame.jpg")
    seq = first.headers["x-frame-seq"]
    assert first.status_code == 200 and first.content == JPEG
    same = client.get("/liveview/" + UUID_A + "/frame.jpg?since=" + seq)
    assert same.status_code == 204 and same.content == b""
    post(client, key)
    newer = client.get("/liveview/" + UUID_A + "/frame.jpg?since=" + seq)
    assert newer.status_code == 200 and newer.headers["x-frame-seq"] != seq


def test_input_can_be_switched_off_and_on_from_the_page(client, key, here, admin):
    frame(client, key)
    assert client.get("/liveview/status").json()["disabled"] == []
    assert client.post("/liveview/" + UUID_A + "/input-enabled", json={"enabled": False}, headers={"X-Admin-Token": ADMIN}).status_code == 200
    assert client.get("/liveview/status").json()["disabled"] == [UUID_A]
    reply = send(client, {"type": "slot", "n": 2})
    assert reply.status_code == 409 and "switched off" in reply.json()["detail"]
    assert take(client, key) == []
    assert client.post("/liveview/" + UUID_A + "/input-enabled", json={"enabled": True}, headers={"X-Admin-Token": ADMIN}).status_code == 200
    assert send(client, {"type": "slot", "n": 2}).status_code == 200
    assert take(client, key) == [{"type": "slot", "n": 2}]


def test_switching_off_drops_what_was_already_waiting(client, key, here, admin):
    frame(client, key)
    send(client, {"type": "slot", "n": 5})
    client.post("/liveview/" + UUID_A + "/input-enabled", json={"enabled": False}, headers={"X-Admin-Token": ADMIN})
    assert take(client, key) == []


def test_the_switch_needs_this_pc_and_the_admin_token(client, key, admin, monkeypatch):
    url = "/liveview/" + UUID_A + "/input-enabled"
    assert client.post(url, json={"enabled": False}, headers={"X-Admin-Token": ADMIN}).status_code == 403
    monkeypatch.setattr(app_module, "_is_this_pc", lambda host: True)
    assert client.post(url, json={"enabled": False}).status_code == 401
    assert client.post(url, json={"enabled": False}, headers={"X-Admin-Token": "wrong"}).status_code == 401
    assert client.get("/liveview/status").json()["disabled"] == []


def test_the_game_learns_whether_the_page_is_watching_when_it_asks_for_input(client, key, here):
    assert client.get("/live/input?player_uuid=" + UUID_A, headers={"X-API-Key": key}).json()["viewers"] == 0
    client.get("/liveview/" + UUID_A + "/frame.jpg")
    assert client.get("/live/input?player_uuid=" + UUID_A, headers={"X-API-Key": key}).json()["viewers"] == 1
