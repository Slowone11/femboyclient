"""Tests for looking up a player's linked Discord. The network is replaced by a fake, so nothing real is called."""

import json
import threading
import time

import app as app_module
from conftest import register

import pytest

KEY = "fake-key-1234-5678-9abc-def012345678"     # a made-up key: the real one is never in a test
UUID_STEVE = "11111111-2222-4333-8444-555555555555"
UUID_ALICE = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
MOJANG = {"steve": ("Steve", UUID_STEVE), "alice": ("Alice", UUID_ALICE)}


class FakeNet:
    """Stands in for Mojang and Hypixel's player endpoint."""

    def __init__(self):
        self.player_calls = []
        self.profile_calls = []              # the SkyBlock profile and museum lookups the networth needs
        self.museum_calls = []
        self.profiles_reply = (200, {"success": True, "profiles": []}, {})
        self.museum_reply = (200, {"success": True, "members": {}}, {})
        self.players = {UUID_STEVE: {"displayname": "Steve"}, UUID_ALICE: {"displayname": "Alice"}}
        self.reply = None                    # a (status, body, headers) to force, or an exception to raise
        self.headers = {"ratelimit-limit": "300", "ratelimit-remaining": "250", "ratelimit-reset": "200"}

    def __call__(self, url, headers=None, timeout=6):
        if url.startswith(app_module.MOJANG_PROFILE_URL):
            name = url[len(app_module.MOJANG_PROFILE_URL):].lower()
            if name in MOJANG:
                real, uuid = MOJANG[name]
                return 200, {"id": uuid.replace("-", ""), "name": real}, {}
            return 404, {}, {}

        if url.startswith(app_module.HYPIXEL_PROFILES_URL):
            self.profile_calls.append((url.split("uuid=")[1], (headers or {}).get("API-Key")))
            return self.profiles_reply
        if url.startswith(app_module.HYPIXEL_MUSEUM_URL):
            self.museum_calls.append((url.split("profile=")[1], (headers or {}).get("API-Key")))
            return self.museum_reply

        assert url.startswith(app_module.HYPIXEL_PLAYER_URL + "?uuid="), url
        uuid = url.split("uuid=")[1]
        self.player_calls.append((uuid, (headers or {}).get("API-Key")))
        if self.reply is not None:
            if isinstance(self.reply, Exception):
                raise self.reply
            return self.reply
        if (headers or {}).get("API-Key") != KEY:
            return 403, {"success": False}, {}
        return 200, {"success": True, "player": self.players[uuid]}, dict(self.headers)


@pytest.fixture()
def net(client, monkeypatch):
    fake = FakeNet()
    monkeypatch.setattr(app_module, "_http_json", fake)
    monkeypatch.setenv("HYPIXEL_API_KEY", KEY)
    app_module._discord_cache.clear()
    app_module._networth_results.clear()
    app_module._networth_locks.clear()
    app_module._discord_locks.clear()
    app_module._hypixel_calls.clear()
    app_module._name_misses.clear()
    app_module._uuid_memory.clear()
    monkeypatch.setattr(app_module, "_hypixel_blocked_until", 0.0)
    monkeypatch.setattr(app_module, "_hypixel_block_reason", {})
    monkeypatch.setattr(app_module, "_mojang_blocked_until", 0.0)
    return fake


def discord(client, name="Steve"):
    response = client.get("/tracker/discord", params={"name": name})
    assert response.status_code == 200, response.text
    return response.json()


def test_a_linked_discord_is_shown(client, net):
    net.players[UUID_STEVE]["socialMedia"] = {"links": {"DISCORD": "steve_dev", "YOUTUBE": "https://youtube.com/x"}}
    answer = discord(client)
    assert answer["state"] == "linked" and answer["discord"] == "steve_dev" and answer["error"] is None


def test_the_older_place_for_the_discord_name_also_works(client, net):
    net.players[UUID_STEVE]["socialMedia"] = {"DISCORD": "old_style#0001"}
    assert discord(client)["discord"] == "old_style#0001"


@pytest.mark.parametrize("player", [
    {}, {"socialMedia": None}, {"socialMedia": {}}, {"socialMedia": {"links": {"TWITTER": "x"}}},
    {"socialMedia": {"links": {"DISCORD": ""}}}, {"socialMedia": {"links": {"DISCORD": 12}}},
    {"socialMedia": {"links": {"DISCORD": "   "}}}, {"socialMedia": "text"}, {"socialMedia": {"links": "text"}}])
def test_no_discord_is_none_never_an_error(client, net, player):
    net.players[UUID_STEVE] = player
    answer = discord(client)
    assert answer["state"] == "none" and answer["discord"] is None and answer["error"] is None


def test_a_player_who_never_joined_hypixel_has_none(client, net):
    net.reply = (200, {"success": True, "player": None}, {})
    assert discord(client)["state"] == "none"


def test_the_name_is_tidied_and_cut(client, net):
    net.players[UUID_STEVE]["socialMedia"] = {"links": {"DISCORD": "  na\u0007me\n" + "x" * 100}}
    answer = discord(client)["discord"]
    assert answer.startswith("name") and "\u0007" not in answer and len(answer) == app_module.DISCORD_MAX_LENGTH


def test_a_second_click_within_the_hour_costs_no_call(client, net):
    net.players[UUID_STEVE]["socialMedia"] = {"links": {"DISCORD": "steve_dev"}}
    discord(client)
    again = discord(client)
    assert len(net.player_calls) == 1 and again["cached"] is True and again["discord"] == "steve_dev"


def test_an_answer_older_than_an_hour_is_asked_again(client, net, monkeypatch):
    discord(client)
    real = time.monotonic
    monkeypatch.setattr(app_module.time, "monotonic", lambda: real() + app_module.DISCORD_TTL_SECONDS + 5)
    discord(client)
    assert len(net.player_calls) == 2


def test_the_cache_is_per_player(client, net):
    discord(client, "Steve")
    discord(client, "Alice")
    assert [u for u, _ in net.player_calls] == [UUID_STEVE, UUID_ALICE]


def test_the_key_is_sent_only_in_a_header_and_never_in_the_answer(client, net):
    answer = discord(client)
    assert net.player_calls == [(UUID_STEVE, KEY)]
    assert KEY not in str(answer) and UUID_STEVE not in str(answer)


def test_it_is_never_saved_in_the_database(client, net):
    net.players[UUID_STEVE]["socialMedia"] = {"links": {"DISCORD": "steve_dev"}}
    key = register(client)
    client.post("/tracker/snapshot", headers={"X-API-Key": key}, json={
        "me": "WeTrollALittle", "players": ["Steve"], "timestamp": "2026-09-21T15:00:10Z",
        "visit_started": "2026-09-21T15:00:00Z", "world": "Hub"})
    discord(client)
    with app_module.db() as conn:
        dump = "\n".join(conn.iterdump())
    assert "steve_dev" not in dump


@pytest.mark.parametrize("name", ["", "a", "has space", "x" * 17, "bad!name", "../etc"])
def test_an_invalid_username_is_refused_without_calling_anyone(client, net, name):
    assert client.get("/tracker/discord", params={"name": name}).status_code == 422
    assert net.player_calls == []


def test_a_name_that_does_not_exist(client, net):
    answer = discord(client, "Nobody")
    assert answer["state"] == "error" and answer["error"]["code"] == "not_found" and net.player_calls == []


def test_an_invalid_key_is_a_short_error(client, net, monkeypatch):
    monkeypatch.setenv("HYPIXEL_API_KEY", "some-other-fake-key")
    answer = discord(client)
    assert answer["state"] == "error" and answer["error"]["code"] == "invalid_key"


def test_no_key_at_all(client, net, monkeypatch):
    monkeypatch.delenv("HYPIXEL_API_KEY", raising=False)
    monkeypatch.setattr(app_module, "_hypixel_key", lambda: "")
    assert discord(client)["error"]["code"] == "no_key"


def test_a_timeout_and_a_rate_limit(client, net):
    net.reply = app_module._Unreachable()
    assert discord(client)["error"]["code"] == "timeout"
    app_module._hypixel_blocked_until = 0.0
    net.reply = (429, {"success": False}, {"retry-after": "20"})
    answer = discord(client)
    assert answer["error"]["code"] == "rate_limited" and answer["error"]["retry_after"] == 20


def test_a_failure_is_not_cached_so_the_next_click_can_work(client, net):
    net.reply = (500, None, {})
    assert discord(client)["state"] == "error"
    net.reply = None
    net.players[UUID_STEVE]["socialMedia"] = {"links": {"DISCORD": "back"}}
    assert discord(client)["discord"] == "back"


def test_discord_and_status_lookups_share_the_call_budget(client, net, monkeypatch):
    monkeypatch.setattr(app_module, "STATUS_BUDGET", 1)
    discord(client, "Steve")
    answer = discord(client, "Alice")
    assert answer["error"]["code"] == "rate_limited" and len(net.player_calls) == 1


def test_two_clicks_at_once_make_one_call(client, net, monkeypatch):
    gate = threading.Event()

    def slow(url, headers=None, timeout=6):
        if url.startswith(app_module.HYPIXEL_PLAYER_URL):
            gate.wait(1.0)
        return net(url, headers, timeout)

    monkeypatch.setattr(app_module, "_http_json", slow)
    results = []
    threads = [threading.Thread(target=lambda: results.append(client.get("/tracker/discord", params={"name": "Steve"}).json()))
               for _ in range(4)]
    [t.start() for t in threads]
    time.sleep(0.2)
    gate.set()
    [t.join() for t in threads]
    assert len(net.player_calls) == 1 and len(results) == 4


# ------------------------------------------------------------------ rank

@pytest.mark.parametrize("player,rank", [
    ({"rank": "ADMIN"}, "ADMIN"),
    ({"rank": "MODERATOR", "newPackageRank": "MVP_PLUS"}, "MODERATOR"),
    ({"rank": "HELPER"}, "HELPER"),
    ({"rank": "YOUTUBER", "monthlyPackageRank": "SUPERSTAR"}, "YOUTUBER"),
    ({"monthlyPackageRank": "SUPERSTAR", "newPackageRank": "MVP_PLUS"}, "MVP++"),
    ({"monthlyPackageRank": "SUPERSTAR"}, "MVP++"),
    ({"newPackageRank": "MVP_PLUS"}, "MVP+"),
    ({"newPackageRank": "MVP"}, "MVP"),
    ({"newPackageRank": "VIP_PLUS"}, "VIP+"),
    ({"newPackageRank": "VIP"}, "VIP"),
    ({"rank": "NORMAL", "newPackageRank": "VIP"}, "VIP"),
    ({"monthlyPackageRank": "NONE", "newPackageRank": "MVP_PLUS"}, "MVP+"),
    ({"newPackageRank": "SOMETHING_NEW"}, "none"),
    ({"newPackageRank": None, "rank": None}, "none"),
    ({}, "none")])
def test_the_rank_follows_hypixels_own_fields_in_order(client, net, player, rank):
    net.players[UUID_STEVE] = player
    assert discord(client)["rank"] == rank


def test_a_player_who_never_joined_hypixel_has_no_rank(client, net):
    net.reply = (200, {"success": True, "player": None}, {})
    assert discord(client)["rank"] == "none"


def test_the_rank_is_unknown_not_none_when_the_lookup_failed(client, net):
    net.reply = (500, None, {})
    assert discord(client)["rank"] is None


def test_the_rank_is_cached_with_the_discord_name(client, net):
    net.players[UUID_STEVE] = {"newPackageRank": "MVP_PLUS"}
    discord(client)
    again = discord(client)
    assert again["rank"] == "MVP+" and len(net.player_calls) == 1



# ------------------------------------------------------------------ big answers

def test_the_download_limits_fit_each_kind_of_answer():
    assert app_module._download_limits(app_module.HYPIXEL_PROFILES_URL + "?uuid=x", 6)[0] >= 20_000_000
    assert app_module._download_limits(app_module.HYPIXEL_MUSEUM_URL + "?profile=x", 6)[0] >= 5_000_000
    assert app_module._download_limits(app_module.HYPIXEL_PLAYER_URL + "?uuid=x", 6)[0] >= 1_000_000
    assert app_module._download_limits("https://api.hypixel.net/v2/status?uuid=x", 6) == (200_000, 6)
    assert app_module._download_limits("https://api.mojang.com/users/profiles/minecraft/x", 6) == (200_000, 6)


def test_a_big_player_answer_is_read_in_full_not_cut_off_and_lost(monkeypatch):
    """A player's Hypixel data can be far bigger than a status answer. A cut-off answer is not valid JSON."""
    import http.server
    import threading

    payload = json.dumps({"success": True, "player": {"blob": "x" * 900_000, "newPackageRank": "MVP_PLUS"}}).encode()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        monkeypatch.setattr(app_module, "HYPIXEL_PLAYER_URL", "http://127.0.0.1:%d/v2/player" % server.server_port)
        status, body, _ = app_module._http_json(app_module.HYPIXEL_PLAYER_URL + "?uuid=abc")
    finally:
        server.shutdown()

    assert status == 200 and body["player"]["newPackageRank"] == "MVP_PLUS" and len(body["player"]["blob"]) == 900_000
