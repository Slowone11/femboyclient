"""Tests for looking up a player's Hypixel status. The network is replaced by a fake, so nothing real is called."""

import json
import re
import time
from collections import deque
from pathlib import Path

import app as app_module
from conftest import register

import pytest

KEY = "fake-key-1234-5678-9abc-def012345678"     # a made-up key: the real one is never in a test
UUID_STEVE = "11111111-2222-4333-8444-555555555555"
UUID_ALICE = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
MOJANG = {"steve": ("Steve", UUID_STEVE), "alice": ("Alice", UUID_ALICE)}


class FakeNet:
    """Stands in for Mojang and Hypixel. Counts every call and can be told how to misbehave."""

    def __init__(self):
        self.mojang_calls = []
        self.hypixel_calls = []
        self.sessions = {UUID_STEVE: {"online": False}, UUID_ALICE: {"online": False}}
        self.hypixel_reply = None            # a (status, body, headers) to force, or an exception to raise
        self.mojang_reply = None
        self.headers = {"ratelimit-limit": "300", "ratelimit-remaining": "250", "ratelimit-reset": "200"}

    def __call__(self, url, headers=None, timeout=6):
        if url.startswith(app_module.MOJANG_PROFILE_URL):
            name = url[len(app_module.MOJANG_PROFILE_URL):].lower()
            self.mojang_calls.append(name)
            if self.mojang_reply is not None:
                return self._give(self.mojang_reply)
            if name in MOJANG:
                real, uuid = MOJANG[name]
                return 200, {"id": uuid.replace("-", ""), "name": real}, {}
            return 404, {"path": "/users/profiles/minecraft/" + name, "errorMessage": "Couldn't find any profile with name"}, {}

        assert url.startswith(app_module.HYPIXEL_STATUS_URL), url
        uuid = url.split("uuid=")[1]
        self.hypixel_calls.append((uuid, (headers or {}).get("API-Key")))
        if self.hypixel_reply is not None:
            return self._give(self.hypixel_reply)
        if (headers or {}).get("API-Key") != KEY:
            return 403, {"success": False, "cause": "Invalid API key"}, {}
        return 200, {"success": True, "uuid": uuid.replace("-", ""), "session": self.sessions[uuid]}, dict(self.headers)

    @staticmethod
    def _give(reply):
        if isinstance(reply, Exception):
            raise reply
        return reply


@pytest.fixture()
def net(client, monkeypatch):
    """A fresh fake network, a fake key, and empty caches for every test."""
    fake = FakeNet()
    monkeypatch.setattr(app_module, "_http_json", fake)
    monkeypatch.setenv("HYPIXEL_API_KEY", KEY)
    app_module._status_cache.clear()
    app_module._status_locks.clear()
    app_module._hypixel_calls.clear()
    app_module._name_misses.clear()
    app_module._uuid_memory.clear()
    monkeypatch.setattr(app_module, "_hypixel_blocked_until", 0.0)
    monkeypatch.setattr(app_module, "_hypixel_block_reason", {})
    monkeypatch.setattr(app_module, "_mojang_blocked_until", 0.0)
    return fake


def status(client, name="Steve", **params):
    response = client.get("/tracker/status", params={"name": name, **params})
    assert response.status_code == 200, response.text
    return response.json()


def add_player(client, name):
    key = register(client)
    client.post("/tracker/snapshot", headers={"X-API-Key": key}, json={
        "me": "WeTrollALittle", "players": [name], "timestamp": "2026-09-21T15:00:10Z",
        "visit_started": "2026-09-21T15:00:00Z", "world": "Hub"})


def saved_uuid(name):
    with app_module.db() as conn:
        return conn.execute("SELECT uuid FROM tracker_players WHERE username_lower = ?", (name.lower(),)).fetchone()["uuid"]


# ------------------------------------------------------------------- the answers

def test_an_online_skyblock_player_gets_the_friendly_world_name(client, net):
    net.sessions[UUID_STEVE] = {"online": True, "gameType": "SKYBLOCK", "mode": "crystal_hollows"}
    reply = status(client)

    assert reply["state"] == "online" and reply["online"] is True
    assert reply["game"] == "SkyBlock" and reply["skyblock"] is True
    assert reply["world"] == "Crystal Hollows" and reply["mode"] == "crystal_hollows"
    assert reply["error"] is None and reply["cached"] is False and reply["checked_at"]


@pytest.mark.parametrize("mode,world", [
    ("hub", "Hub"), ("dungeon_hub", "Dungeon Hub"), ("dungeon", "Dungeons"), ("crystal_hollows", "Crystal Hollows"),
    ("mining_3", "Dwarven Mines"), ("combat_3", "The End"), ("crimson_isle", "Crimson Isle"),
    ("combat_1", "Spider's Den"), ("foraging_1", "The Park"), ("farming_1", "The Farming Islands"),
    ("garden", "Garden"), ("dynamic", "Private Island"), ("rift", "The Rift"), ("mining_2", "Deep Caverns"),
    ("foraging_2", "Galatea"),
])
def test_every_skyblock_mode_maps_to_the_name_the_scanner_uses(client, net, mode, world):
    net.sessions[UUID_STEVE] = {"online": True, "gameType": "SKYBLOCK", "mode": mode}
    assert status(client)["world"] == world


def test_the_mapped_names_are_all_real_scanner_worlds():
    scanner = Path(__file__).resolve().parents[2] / "mod" / "src" / "main" / "java" / "com" / "femboyclient" / "SkyBlockWorlds.java"
    if not scanner.exists():
        pytest.skip("the mod source is not next to the server")
    known = set(re.findall(r'new World\("([^"]+)"', scanner.read_text(encoding="utf-8")))
    extra = {"Deep Caverns", "Gold Mine", "Blazing Fortress", "Jerry's Workshop", "Galatea"}    # areas the scanner names by their own area text
    assert set(app_module.SKYBLOCK_MODES.values()) <= known | extra


def test_an_unknown_skyblock_mode_has_no_friendly_world_but_keeps_the_raw_mode(client, net):
    net.sessions[UUID_STEVE] = {"online": True, "gameType": "SKYBLOCK", "mode": "brand_new_island"}
    reply = status(client)
    assert reply["skyblock"] is True and reply["world"] is None and reply["mode"] == "brand_new_island"


def test_an_online_player_in_another_game(client, net):
    net.sessions[UUID_STEVE] = {"online": True, "gameType": "BEDWARS", "mode": "BEDWARS_EIGHT_ONE"}
    reply = status(client)

    assert reply["state"] == "online" and reply["skyblock"] is False
    assert reply["game"] == "Bed Wars" and reply["world"] is None


def test_an_unknown_game_name_is_made_readable(client, net):
    net.sessions[UUID_STEVE] = {"online": True, "gameType": "SOME_NEW_GAME"}
    assert status(client)["game"] == "Some New Game"


def test_an_offline_player_is_offline_or_hidden_never_a_definite_offline(client, net):
    reply = status(client)

    assert reply["state"] == "offline_or_hidden" and reply["online"] is False
    assert reply["game"] is None and reply["world"] is None and reply["error"] is None


def test_a_player_with_hidden_status_looks_exactly_like_an_offline_one(client, net):
    # Hypixel reports a hidden player as offline. There is no way to tell, so the answer must not claim to.
    net.sessions[UUID_STEVE] = {"online": False}
    net.sessions[UUID_ALICE] = {"online": False}
    assert status(client, "Steve")["state"] == status(client, "Alice")["state"] == "offline_or_hidden"


def test_the_answer_never_holds_the_key_or_the_uuid(client, net):
    net.sessions[UUID_STEVE] = {"online": True, "gameType": "SKYBLOCK", "mode": "hub"}
    text = client.get("/tracker/status", params={"name": "Steve"}).text
    assert KEY not in text and UUID_STEVE not in text and UUID_STEVE.replace("-", "") not in text


# ---------------------------------------------------------------------- uuid saving

def test_the_uuid_is_looked_up_once_saved_and_reused(client, net):
    add_player(client, "Steve")
    status(client)
    assert saved_uuid("Steve") == UUID_STEVE and net.mojang_calls == ["steve"]

    app_module._status_cache.clear()          # forget the status, but the uuid stays
    status(client)
    assert net.mojang_calls == ["steve"], "Mojang was asked again"
    assert len(net.hypixel_calls) == 2


def test_an_old_saved_uuid_is_looked_up_again(client, net):
    add_player(client, "Steve")
    status(client)
    with app_module.db() as conn:
        conn.execute("UPDATE tracker_players SET uuid_checked_at = '2020-01-01T00:00:00.000Z'")
    app_module._status_cache.clear()

    status(client)
    assert net.mojang_calls == ["steve", "steve"]


def test_a_player_not_in_the_database_can_still_be_looked_up(client, net):
    reply = status(client, "Alice")
    assert reply["state"] == "offline_or_hidden" and reply["name"] == "Alice"


def test_the_saved_spelling_is_returned(client, net):
    add_player(client, "STEVE")
    assert status(client, "steve")["name"] == "STEVE"


def test_the_uuid_column_exists_and_is_empty_until_a_lookup(client, net):
    add_player(client, "Steve")
    with app_module.db() as conn:
        assert {r["name"] for r in conn.execute("PRAGMA table_info(tracker_players)")} >= {"uuid", "uuid_checked_at"}
        assert conn.execute("SELECT uuid FROM tracker_players").fetchone()["uuid"] is None


# ------------------------------------------------------------------------- the cache

def test_a_second_search_within_45_seconds_costs_no_api_call(client, net):
    first = status(client)
    second = status(client)

    assert len(net.hypixel_calls) == 1 and len(net.mojang_calls) == 1
    assert first["cached"] is False and second["cached"] is True
    assert second["checked_at"] == first["checked_at"]


def test_a_result_older_than_45_seconds_is_looked_up_again(client, net, monkeypatch):
    status(client)
    for entry in app_module._status_cache.values():
        entry["at"] -= app_module.STATUS_TTL_SECONDS + 1
    reply = status(client)

    assert len(net.hypixel_calls) == 2 and reply["cached"] is False


def test_the_cache_is_per_player(client, net):
    status(client, "Steve")
    status(client, "Alice")
    status(client, "Steve")
    assert [uuid for uuid, _ in net.hypixel_calls] == [UUID_STEVE, UUID_ALICE]


def test_a_refresh_right_after_a_lookup_is_answered_from_the_cache(client, net):
    status(client)
    reply = status(client, refresh="true")

    assert len(net.hypixel_calls) == 1 and reply["cached"] is True
    assert 0 < reply["refresh_in"] <= app_module.STATUS_MIN_REFRESH_SECONDS


def test_a_refresh_after_the_short_cooldown_asks_again(client, net):
    net.sessions[UUID_STEVE] = {"online": False}
    status(client)
    for entry in app_module._status_cache.values():
        entry["at"] -= app_module.STATUS_MIN_REFRESH_SECONDS + 1
    net.sessions[UUID_STEVE] = {"online": True, "gameType": "SKYBLOCK", "mode": "hub"}

    reply = status(client, refresh="true")
    assert len(net.hypixel_calls) == 2 and reply["state"] == "online" and reply["world"] == "Hub"


def test_without_refresh_a_result_inside_45_seconds_is_never_looked_up_again(client, net):
    status(client)
    for entry in app_module._status_cache.values():
        entry["at"] -= 30
    status(client)
    assert len(net.hypixel_calls) == 1


def test_the_age_grows_as_the_result_is_kept(client, net):
    status(client)
    for entry in app_module._status_cache.values():
        entry["at"] -= 20
    assert status(client)["age_seconds"] >= 20


# ------------------------------------------------------------------------- the errors

def test_a_username_that_does_not_exist(client, net):
    reply = status(client, "Zzqxjw_notreal9")

    assert reply["state"] == "error" and reply["error"]["code"] == "not_found"
    assert "username" in reply["error"]["message"] and net.hypixel_calls == []


def test_a_username_that_does_not_exist_is_not_looked_up_again_for_a_minute(client, net):
    status(client, "Zzqxjw_notreal9")
    status(client, "zzqxjw_notreal9")
    assert net.mojang_calls == ["zzqxjw_notreal9"]


@pytest.mark.parametrize("name", ["", "   ", "a", "has space", "x" * 17, "bad!name", "../etc"])
def test_an_invalid_username_is_refused_without_calling_anyone(client, net, name):
    response = client.get("/tracker/status", params={"name": name})
    assert response.status_code == 422
    assert net.mojang_calls == [] and net.hypixel_calls == []


def test_an_invalid_key(client, net, monkeypatch):
    monkeypatch.setenv("HYPIXEL_API_KEY", "wrong-key")
    reply = status(client)

    assert reply["state"] == "error" and reply["error"]["code"] == "invalid_key"
    assert "wrong-key" not in json.dumps(reply)


def test_after_an_invalid_key_hypixel_is_not_asked_again_for_a_minute(client, net, monkeypatch):
    monkeypatch.setenv("HYPIXEL_API_KEY", "wrong-key")
    status(client, "Steve")
    status(client, "Alice")
    assert len(net.hypixel_calls) == 1


def test_no_key_at_all(client, net, monkeypatch):
    monkeypatch.delenv("HYPIXEL_API_KEY")
    reply = status(client)
    assert reply["error"]["code"] == "no_key" and net.hypixel_calls == []


def test_the_key_can_come_from_config_json(client, net, monkeypatch):
    monkeypatch.delenv("HYPIXEL_API_KEY")
    config = json.loads(app_module.CONFIG_PATH.read_text(encoding="utf-8"))
    config["hypixel_api_key"] = KEY
    app_module.CONFIG_PATH.write_text(json.dumps(config), encoding="utf-8")

    assert status(client)["state"] == "offline_or_hidden"


def test_a_timeout_from_hypixel(client, net):
    net.hypixel_reply = app_module._Unreachable()
    reply = status(client)
    assert reply["state"] == "error" and reply["error"]["code"] == "timeout"


def test_a_timeout_from_mojang(client, net):
    net.mojang_reply = app_module._Unreachable()
    reply = status(client, "Alice")
    assert reply["error"]["code"] == "timeout" and net.hypixel_calls == []


def test_a_saved_uuid_is_used_if_mojang_cannot_be_reached_to_refresh_it(client, net):
    add_player(client, "Steve")
    status(client)
    with app_module.db() as conn:
        conn.execute("UPDATE tracker_players SET uuid_checked_at = '2020-01-01T00:00:00.000Z'")
    app_module._status_cache.clear()
    net.mojang_reply = app_module._Unreachable()

    assert status(client)["state"] == "offline_or_hidden"


def test_a_rate_limit_from_hypixel_says_how_long_to_wait(client, net):
    net.hypixel_reply = (429, {"success": False, "cause": "Key throttle"}, {"retry-after": "17"})
    reply = status(client)

    assert reply["state"] == "error" and reply["error"]["code"] == "rate_limited" and reply["error"]["retry_after"] == 17
    assert "17" in reply["error"]["message"]


def test_after_a_rate_limit_hypixel_is_left_alone_until_it_ends(client, net):
    net.hypixel_reply = (429, {"success": False}, {"retry-after": "20"})
    status(client, "Steve")
    net.hypixel_reply = None
    reply = status(client, "Alice")

    assert len(net.hypixel_calls) == 1 and reply["error"]["code"] == "rate_limited"
    assert 1 <= reply["error"]["retry_after"] <= 20


def test_a_rate_limited_lookup_returns_the_last_answer_if_there_is_one(client, net):
    net.sessions[UUID_STEVE] = {"online": True, "gameType": "SKYBLOCK", "mode": "hub"}
    status(client)
    for entry in app_module._status_cache.values():
        entry["at"] -= app_module.STATUS_TTL_SECONDS + 1
    net.hypixel_reply = (429, {"success": False}, {"retry-after": "30"})

    reply = status(client)
    assert reply["state"] == "online" and reply["world"] == "Hub"
    assert reply["stale"] is True and reply["cached"] is True and reply["error"]["code"] == "rate_limited"


def test_a_rate_limit_from_mojang(client, net):
    net.mojang_reply = (429, None, {})
    reply = status(client, "Alice")
    assert reply["error"]["code"] == "rate_limited" and net.hypixel_calls == []


def test_running_low_on_hypixels_own_count_pauses_lookups(client, net):
    net.headers = {"ratelimit-limit": "300", "ratelimit-remaining": "2", "ratelimit-reset": "40"}
    assert status(client, "Steve")["state"] == "offline_or_hidden"      # this one still works

    reply = status(client, "Alice")
    assert reply["error"]["code"] == "rate_limited" and len(net.hypixel_calls) == 1


def test_the_servers_own_budget_keeps_it_under_the_limit(client, net):
    now = time.monotonic()
    app_module._hypixel_calls.extend([now] * app_module.STATUS_BUDGET)

    reply = status(client)
    assert reply["error"]["code"] == "rate_limited" and net.hypixel_calls == []
    assert app_module.STATUS_BUDGET < 300, "must stay under Hypixel's limit of 300 per 5 minutes"


def test_old_calls_leave_the_budget(client, net):
    old = time.monotonic() - app_module.STATUS_WINDOW_SECONDS - 5
    app_module._hypixel_calls.extend([old] * app_module.STATUS_BUDGET)
    assert status(client)["state"] == "offline_or_hidden"


@pytest.mark.parametrize("reply", [(500, None, {}), (502, {"success": False}, {}), (200, {"success": False}, {}),
                                   (200, "not a dict", {}), (422, {"success": False, "cause": "Malformed UUID"}, {})])
def test_any_other_failure_is_a_short_error_and_never_a_crash(client, net, reply):
    net.hypixel_reply = reply
    answer = status(client)
    assert answer["state"] == "error" and answer["error"]["code"] == "unavailable"


def test_a_success_with_no_session_is_offline_or_hidden(client, net):
    net.hypixel_reply = (200, {"success": True, "uuid": "x"}, {})
    assert status(client)["state"] == "offline_or_hidden"


def test_a_failure_is_not_cached_so_the_next_click_can_work(client, net):
    net.hypixel_reply = (500, None, {})
    assert status(client)["state"] == "error"
    net.hypixel_reply = None
    assert status(client)["state"] == "offline_or_hidden"


# ---------------------------------------------------------------- the key stays secret

def test_the_key_is_only_ever_sent_to_hypixel_in_a_header(client, net):
    status(client)
    assert net.hypixel_calls == [(UUID_STEVE, KEY)]
    assert all(KEY not in call for call in net.mojang_calls)


def test_the_key_is_in_no_website_file():
    static = Path(app_module.STATIC_DIR)
    for path in static.rglob("*"):
        if path.is_file():
            assert KEY not in path.read_text(encoding="utf-8", errors="ignore"), path
    source = Path(app_module.__file__).read_text(encoding="utf-8")
    assert not re.search(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"", source.split("HYPIXEL_STATUS_URL")[0][-400:]), "no key literal in the code"


def test_no_other_answer_holds_the_key(client, net, monkeypatch):
    add_player(client, "Steve")
    status(client)
    for path in ("/", "/health", "/tracker/worlds", "/tracker/party", "/tracker/search?q=steve", "/players"):
        assert KEY not in client.get(path).text, path
    assert client.get("/static/app.js").status_code == 200
    assert client.get("/config.json").status_code == 404


def test_an_unreachable_service_leaves_no_trace_of_the_request_in_the_answer(client, net):
    net.hypixel_reply = app_module._Unreachable()
    text = client.get("/tracker/status", params={"name": "Steve"}).text
    assert KEY not in text and "api.hypixel.net" not in text


def test_single_flight_two_clicks_at_once_make_one_call(client, net):
    import threading
    gate = threading.Event()
    real = net.__call__

    def slow(url, headers=None, timeout=6):
        if url.startswith(app_module.HYPIXEL_STATUS_URL):
            gate.wait(1.0)
        return real(url, headers, timeout)

    app_module._http_json = slow
    results = []
    threads = [threading.Thread(target=lambda: results.append(client.get("/tracker/status", params={"name": "Steve"}).json()))
               for _ in range(4)]
    [t.start() for t in threads]
    time.sleep(0.2)
    gate.set()
    [t.join() for t in threads]

    assert len(net.hypixel_calls) == 1 and len(results) == 4
    assert sum(1 for r in results if r["cached"]) == 3
