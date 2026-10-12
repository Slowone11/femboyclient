"""Tests for GET /api/player/{uuid}, the in-game screen's one call. The network is faked."""

import app as app_module
from conftest import UUID_A, register
from test_discord import UUID_ALICE as ALICE_UUID, net  # noqa: F401  (net is a fixture)

ME = "WeTrollALittle"


def seed(client, key):
    client.post("/tracker/snapshot", headers={"X-API-Key": key}, json={
        "me": ME, "players": ["Alice"], "timestamp": "2026-09-21T15:00:10Z",
        "visit_started": "2026-09-21T15:00:00Z", "world": "Hub"})
    client.post("/tracker/chat", headers={"X-API-Key": key}, json={"me": ME, "world": "Hub", "messages": [
        {"player": "Alice", "channel": "Public", "text": "hello", "timestamp": "2026-09-21T15:01:00Z"}]})


def get(client, key, uuid=ALICE_UUID, **params):
    return client.get("/api/player/" + uuid, params=params, headers={"X-API-Key": key} if key else {})


def test_a_known_player_by_name_gives_discord_times_met_and_chat(client, net):
    net.players[ALICE_UUID]["socialMedia"] = {"links": {"DISCORD": "alice_dc"}}
    key = register(client)
    seed(client, key)
    answer = get(client, key, name="Alice").json()

    assert answer["found"] and answer["discord"] == "alice_dc" and answer["timesMet"] == 1
    assert answer["chatLogs"] == [{"at": "2026-09-21T15:01:00.000Z", "channel": "Public", "world": "Hub", "message": "hello"}]


def test_a_player_is_found_by_the_uuid_once_it_is_saved(client, net):
    key = register(client)
    seed(client, key)
    with app_module.db() as conn:
        conn.execute("UPDATE tracker_players SET uuid = ?", (ALICE_UUID,))
    assert get(client, key).json()["found"] is True


def test_a_player_not_in_the_database_is_not_found_but_still_has_a_discord(client, net):
    net.players[ALICE_UUID]["socialMedia"] = {"links": {"DISCORD": "alice_dc"}}
    key = register(client)
    answer = get(client, key).json()
    assert answer["found"] is False and answer["timesMet"] == 0 and answer["chatLogs"] == [] and answer["discord"] == "alice_dc"


def test_only_your_own_accounts_data_is_counted(client, net):
    key_a, key_b = register(client), register(client, "22222222-2222-4222-8222-222222222222", "AltAccount")
    seed(client, key_a)
    answer = get(client, key_b, name="Alice").json()
    assert answer["timesMet"] == 0 and answer["chatLogs"] == []


def test_a_failed_discord_lookup_is_an_error_text_not_a_failure(client, net, monkeypatch):
    net.reply = (500, None, {})
    key = register(client)
    seed(client, key)
    answer = get(client, key, name="Alice")
    assert answer.status_code == 200 and answer.json()["discordError"] and answer.json()["timesMet"] == 1


def test_it_needs_a_key_and_a_valid_uuid_and_name(client, net):
    key = register(client)
    assert get(client, None).status_code == 401
    assert get(client, "wrong").status_code == 401
    assert get(client, key, uuid="not-a-uuid").status_code == 422
    assert get(client, key, name="bad name!").status_code == 422
