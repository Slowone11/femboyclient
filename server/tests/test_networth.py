"""Tests for the SkyBlock networth in GET /api/player/{uuid}. Hypixel and the Node calculator are both faked."""

import json
import subprocess
import time

import app as app_module
from conftest import register
from test_discord import KEY, UUID_STEVE, net  # noqa: F401  (net is a fixture)

import pytest

PLAIN = UUID_STEVE.replace("-", "")


def profile(name="Mango", selected=True, inventory=True, last_save=1, bank=None, profile_id="prof-1"):
    member = {"last_save": last_save, "currencies": {"coin_purse": 5}}
    if inventory:
        member["inventory"] = {"inv_contents": {"data": "abc"}}
    body = {"profile_id": profile_id, "cute_name": name, "selected": selected, "members": {PLAIN: member}}
    if bank is not None:
        body["banking"] = {"balance": bank}
    return body


class FakeNode:
    """Stands in for `node networth.js`: records what it was given and answers like the script does."""

    def __init__(self):
        self.runs = []
        self.stdout = json.dumps({"networth": 1_725_000_000})
        self.raises = None

    def __call__(self, command, **kwargs):
        self.runs.append({"command": command, "input": kwargs.get("input"), "timeout": kwargs.get("timeout")})
        if self.raises:
            raise self.raises
        return subprocess.CompletedProcess(command, 0, stdout=self.stdout, stderr="")


@pytest.fixture()
def node(monkeypatch):
    fake = FakeNode()
    monkeypatch.setattr(app_module.subprocess, "run", fake)
    return fake


def ask(client, key, **params):
    response = client.get("/api/player/" + UUID_STEVE, params=params, headers={"X-API-Key": key})
    assert response.status_code == 200, response.text
    return response.json()


@pytest.fixture()
def key(client):
    return register(client)


def test_the_networth_comes_from_the_selected_profile(client, net, node, key):
    net.profiles_reply = (200, {"success": True, "profiles": [profile("Old", selected=False, last_save=99), profile("Mango")]}, {})
    answer = ask(client, key)

    assert answer["networth"] == 1_725_000_000 and answer["networthNote"] is None
    sent = json.loads(node.runs[0]["input"])
    assert sent["member"]["last_save"] == 1 and sent["bank"] == 0 and sent["museum"] is None


def test_without_a_selected_profile_the_newest_one_is_used(client, net, node, key):
    net.profiles_reply = (200, {"success": True, "profiles": [profile("A", selected=False, last_save=5), profile("B", selected=False, last_save=50)]}, {})
    ask(client, key)
    assert json.loads(node.runs[0]["input"])["member"]["last_save"] == 50


def test_the_bank_and_the_museum_are_passed_on(client, net, node, key):
    net.profiles_reply = (200, {"success": True, "profiles": [profile(bank=4_000_000)]}, {})
    net.museum_reply = (200, {"success": True, "members": {PLAIN: {"items": {"x": 1}}}}, {})
    ask(client, key)

    sent = json.loads(node.runs[0]["input"])
    assert sent["bank"] == 4_000_000 and sent["museum"] == {"items": {"x": 1}}
    assert net.museum_calls == [("prof-1", KEY)]


def test_a_museum_that_cannot_be_read_only_lowers_the_number(client, net, node, key):
    net.profiles_reply = (200, {"success": True, "profiles": [profile()]}, {})
    net.museum_reply = (500, None, {})
    assert ask(client, key)["networth"] == 1_725_000_000


def test_a_player_with_the_api_off_gives_null_and_api_off(client, net, node, key):
    net.profiles_reply = (200, {"success": True, "profiles": [profile(inventory=False)]}, {})
    answer = ask(client, key)

    assert answer["networth"] is None and answer["networthNote"] == "api_off"
    assert node.runs == []


def test_a_player_with_no_skyblock_profile(client, net, node, key):
    answer = ask(client, key)
    assert answer["networth"] is None and answer["networthNote"] == "no_profile" and node.runs == []


def test_a_profile_without_the_player_in_it(client, net, node, key):
    body = profile()
    body["members"] = {"somebodyelse": body["members"][PLAIN]}
    net.profiles_reply = (200, {"success": True, "profiles": [body]}, {})
    assert ask(client, key)["networthNote"] == "no_profile"


def test_the_result_is_kept_for_five_minutes(client, net, node, key, monkeypatch):
    net.profiles_reply = (200, {"success": True, "profiles": [profile()]}, {})
    ask(client, key)
    ask(client, key)
    assert len(node.runs) == 1 and len(net.profile_calls) == 1

    real = time.monotonic
    monkeypatch.setattr(app_module.time, "monotonic", lambda: real() + app_module.NETWORTH_TTL_SECONDS + 5)
    ask(client, key)
    assert len(node.runs) == 2


def test_api_off_is_kept_too_so_the_website_is_not_asked_again_and_again(client, net, node, key):
    net.profiles_reply = (200, {"success": True, "profiles": [profile(inventory=False)]}, {})
    ask(client, key)
    ask(client, key)
    assert len(net.profile_calls) == 1


@pytest.mark.parametrize("stdout", ["not json", "[]", "{}", json.dumps({"error": "prices are down"}), json.dumps({"networth": "lots"})])
def test_a_calculator_that_fails_gives_a_note_and_is_tried_again_next_time(client, net, node, key, stdout):
    net.profiles_reply = (200, {"success": True, "profiles": [profile()]}, {})
    node.stdout = stdout
    answer = ask(client, key)
    assert answer["networth"] is None and answer["networthNote"]

    node.stdout = json.dumps({"networth": 42})
    assert ask(client, key)["networth"] == 42


def test_a_calculation_that_takes_too_long_is_stopped(client, net, node, key):
    net.profiles_reply = (200, {"success": True, "profiles": [profile()]}, {})
    node.raises = subprocess.TimeoutExpired("node", 25)
    answer = ask(client, key)

    assert answer["networth"] is None and "too long" in answer["networthNote"]
    assert node.runs[0]["timeout"] == app_module.NETWORTH_TIMEOUT_SECONDS


def test_node_missing_from_the_website_pc_is_a_note_not_a_crash(client, net, node, key):
    net.profiles_reply = (200, {"success": True, "profiles": [profile()]}, {})
    node.raises = FileNotFoundError("node")
    answer = ask(client, key)
    assert answer["networth"] is None and "node" in answer["networthNote"]


def test_a_hypixel_error_on_the_profile_lookup_is_a_note(client, net, node, key):
    net.profiles_reply = (429, {"success": False}, {"retry-after": "20"})
    answer = ask(client, key)
    assert answer["networth"] is None and "Rate limited" in answer["networthNote"] and node.runs == []


def test_the_api_key_is_only_ever_sent_to_hypixel_never_to_the_calculator(client, net, node, key):
    net.profiles_reply = (200, {"success": True, "profiles": [profile()]}, {})
    answer = ask(client, key)

    assert KEY not in node.runs[0]["input"] and KEY not in node.runs[0]["command"] and KEY not in json.dumps(answer)
    assert net.profile_calls == [(UUID_STEVE, KEY)]


def test_the_answer_carries_the_rank_and_the_networth(client, net, node, key):
    net.players[UUID_STEVE] = {"newPackageRank": "MVP_PLUS", "monthlyPackageRank": "SUPERSTAR"}
    net.profiles_reply = (200, {"success": True, "profiles": [profile()]}, {})
    answer = ask(client, key, name="Steve")

    assert answer["rank"] == "MVP++" and answer["networth"] == 1_725_000_000


def test_the_calculator_script_and_its_library_are_in_the_website_folder():
    assert app_module.NETWORTH_SCRIPT.exists()
    assert (app_module.NETWORTH_SCRIPT.parent / "node_modules" / "skyhelper-networth" / "package.json").exists()
