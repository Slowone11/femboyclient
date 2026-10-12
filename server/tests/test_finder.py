"""Tests for the Player Finder: the website side of the hop loop."""

import re
import time
from pathlib import Path

import app as app_module
from conftest import UUID_A, UUID_B, register
from fastapi.testclient import TestClient

import pytest


@pytest.fixture()
def local(client):
    """A client that looks like it is on the PC running the website. The `client` fixture sets up the database."""
    app_module._finder.clear()
    with TestClient(app_module.app, client=("127.0.0.1", 50000)) as c:
        yield c


def start(local, target="Steve", world="Hub", owner=UUID_A):
    return local.post("/finder/start", json={"owner": owner, "target": target, "world": world})


def stop(local, owner=UUID_A):
    return local.post("/finder/stop", json={"owner": owner})


def state(client, owner=UUID_A):
    return client.get("/finder/state", params={"owner": owner}).json()


def command(client, key):
    return client.get("/finder/command", headers={"X-API-Key": key}).json()


def report(client, key, run, status, hops=0, target="Steve", **extra):
    return client.post("/finder/report", headers={"X-API-Key": key},
                       json={"target": target, "run": run, "status": status, "hops": hops, **extra}).json()


# ------------------------------------------------------------------ starting

def test_starting_sets_a_target_the_mod_can_ask_for(client, local):
    key = register(client)
    started = start(local).json()

    assert started["active"] is True and started["target"] == "Steve" and started["world"] == "Hub"
    assert started["hops"] == 0 and started["max_hops"] == 30 and started["warp"] == "/warp hub"
    assert started["result"] is None

    ask = command(client, key)
    assert ask == {"target": "Steve", "world": "Hub", "run": started["run"]}


def test_with_no_search_the_mod_is_told_there_is_no_target(client, local):
    key = register(client)
    assert command(client, key) == {"target": None}


def test_the_page_waits_for_the_game_until_the_mod_first_asks(client, local):
    key = register(client)
    start(local)
    assert state(client)["waiting_for_game"] is True
    command(client, key)
    assert state(client)["waiting_for_game"] is False


def test_the_website_sends_a_name_and_a_world_never_a_command(client, local):
    key = register(client)
    start(local, "Steve", "Dungeon Hub")
    assert set(command(client, key)) == {"target", "world", "run"}


@pytest.mark.parametrize("target,world", [
    ("", "Hub"), ("ab", "Hub"), ("has space", "Hub"), ("x" * 17, "Hub"), ("/is", "Hub"),
    ("Steve", "Private Island"), ("Steve", "Dungeons"), ("Steve", "The Rift"), ("Steve", "hub; /op me"), ("Steve", ""),
])
def test_a_bad_name_or_a_world_with_no_warp_is_refused(client, local, target, world):
    register(client)
    assert start(local, target, world).status_code == 422
    assert state(client)["active"] is False


def test_starting_is_only_allowed_from_the_pc_running_the_website(client):
    register(client)
    app_module._finder.clear()
    other = client.post("/finder/start", json={"owner": UUID_A, "target": "Steve", "world": "Hub"})
    assert other.status_code == 403
    assert client.post("/finder/stop", json={"owner": UUID_A}).status_code == 403
    assert state(client)["active"] is False


def test_a_new_search_replaces_the_old_one_and_clears_the_last_result(client, local):
    key = register(client)
    first = start(local, "Steve").json()
    report(client, key, first["run"], "found", 3, world="Hub", elapsedSeconds=20)
    assert state(client)["result"]["outcome"] == "found"

    second = start(local, "Alice", "The End").json()
    assert second["result"] is None and second["run"] != first["run"] and second["target"] == "Alice"
    assert command(client, key)["target"] == "Alice"


def test_each_account_has_its_own_search(client, local):
    key_a, key_b = register(client, UUID_A, "Main"), register(client, UUID_B, "Alt")
    start(local, "Steve", owner=UUID_A)

    assert command(client, key_a)["target"] == "Steve"
    assert command(client, key_b) == {"target": None}
    assert state(client, UUID_B)["active"] is False


# ------------------------------------------------------------------ the mod reports

def test_the_hop_counter_follows_the_mods_reports(client, local):
    key = register(client)
    run = start(local).json()["run"]

    for hops in (1, 2, 3, 7):
        assert report(client, key, run, "searching", hops)["ok"] is True
        assert state(client)["hops"] == hops
    assert state(client)["active"] is True


def test_a_report_never_lowers_the_count(client, local):
    key = register(client)
    run = start(local).json()["run"]
    report(client, key, run, "searching", 5)
    report(client, key, run, "searching", 3)
    assert state(client)["hops"] == 5


def test_found_ends_the_search_clears_the_target_and_keeps_the_result(client, local):
    key = register(client)
    run = start(local).json()["run"]
    report(client, key, run, "searching", 6)
    report(client, key, run, "found", 7, world="Hub", elapsedSeconds=72)

    assert command(client, key) == {"target": None}
    view = state(client)
    assert view["active"] is False
    assert view["result"]["outcome"] == "found" and view["result"]["target"] == "Steve"
    assert view["result"]["world"] == "Hub" and view["result"]["hops"] == 7 and view["result"]["elapsed_seconds"] == 72
    assert view["result"]["finished_at"]


def test_the_result_stays_until_a_new_search_starts(client, local):
    key = register(client)
    run = start(local).json()["run"]
    report(client, key, run, "found", 2, world="Hub", elapsedSeconds=9)

    for _ in range(3):
        assert state(client)["result"]["outcome"] == "found"
    command(client, key)
    assert state(client)["result"]["outcome"] == "found"


@pytest.mark.parametrize("status,reason,expected", [
    ("not_found", "limit", "attempt limit (30 hops)"),
    ("stopped", "disconnected", "disconnected"),
    ("stopped", None, "toggle off"),
    ("stopped", "not_in_skyblock", "not in SkyBlock"),
    ("stopped", "website_unreachable", "the game lost contact with the website"),
    ("stopped", "warp_failed", "the warp did not take you anywhere (is it unlocked?)"),
])
def test_a_search_that_ends_without_finding_them_says_why(client, local, status, reason, expected):
    key = register(client)
    run = start(local).json()["run"]
    extra = {"reason": reason} if reason else {}
    report(client, key, run, status, 30 if reason == "limit" else 4, **extra)

    view = state(client)
    assert view["active"] is False and command(client, key) == {"target": None}
    assert view["result"]["outcome"] == "not_found" and view["result"]["reason_text"] == expected
    assert view["result"]["hops"] == (30 if reason == "limit" else 4)


def test_an_unknown_reason_is_shown_as_it_was_sent(client, local):
    key = register(client)
    run = start(local).json()["run"]
    report(client, key, run, "stopped", 1, reason="something_new")
    assert state(client)["result"]["reason_text"] == "something_new"


def test_a_late_report_from_an_older_search_is_ignored(client, local):
    key = register(client)
    old = start(local, "Steve").json()["run"]
    time.sleep(0.005)
    new = start(local, "Alice").json()["run"]

    answer = report(client, key, old, "found", 4, target="Steve", world="Hub", elapsedSeconds=3)
    assert answer["ok"] is False
    view = state(client)
    assert view["active"] is True and view["target"] == "Alice" and view["result"] is None
    assert new != old


def test_bad_reports_are_refused(client, local):
    key = register(client)
    run = start(local).json()["run"]
    for body in ({"run": run, "status": "exploded", "hops": 1}, {"run": run, "status": "searching", "hops": -1},
                 {"run": run, "status": "searching", "hops": 5000}, {"run": run, "status": "searching", "hops": 1, "reason": "x" * 50},
                 {"run": run, "hops": 1}):
        assert client.post("/finder/report", headers={"X-API-Key": key}, json=body).status_code == 422
    assert state(client)["hops"] == 0


def test_the_mod_endpoints_need_the_accounts_key(client, local):
    register(client)
    start(local)
    assert client.get("/finder/command").status_code in (401, 403)
    assert client.get("/finder/command", headers={"X-API-Key": "wrong"}).status_code in (401, 403)
    assert client.post("/finder/report", json={"run": 1, "status": "found", "hops": 1}).status_code in (401, 403)


# ------------------------------------------------------------------ stopping

def test_toggling_off_stops_the_search_and_keeps_the_count_so_far(client, local):
    key = register(client)
    run = start(local).json()["run"]
    report(client, key, run, "searching", 4)

    stopped = stop(local).json()
    assert stopped["active"] is False
    assert stopped["result"]["outcome"] == "not_found" and stopped["result"]["reason"] == "toggle_off"
    assert stopped["result"]["hops"] == 4 and stopped["result"]["reason_text"] == "toggle off"
    assert command(client, key) == {"target": None}, "the mod sees the target go and stops"


def test_the_mods_final_stopped_message_can_raise_the_count_after_a_toggle_off(client, local):
    key = register(client)
    run = start(local).json()["run"]
    report(client, key, run, "searching", 4)
    stop(local)

    report(client, key, run, "stopped", 5)      # the mod made one more hop before it saw the toggle
    assert state(client)["result"]["hops"] == 5 and state(client)["result"]["reason"] == "toggle_off"


def test_a_player_found_in_the_same_moment_as_a_toggle_off_still_counts_as_found(client, local):
    key = register(client)
    run = start(local).json()["run"]
    report(client, key, run, "searching", 4)
    stop(local)

    report(client, key, run, "found", 5, world="Hub", elapsedSeconds=30)
    assert state(client)["result"]["outcome"] == "found" and state(client)["result"]["hops"] == 5


def test_stopping_with_no_search_does_nothing(client, local):
    register(client)
    view = stop(local).json()
    assert view["active"] is False and view["result"] is None


def test_a_search_whose_game_stops_answering_is_ended(client, local, monkeypatch):
    key = register(client)
    run = start(local).json()["run"]
    command(client, key)
    report(client, key, run, "searching", 3)

    later = time.time() + app_module.FINDER_LOST_SECONDS + 5
    monkeypatch.setattr(app_module.time, "time", lambda: later)

    view = state(client)
    assert view["active"] is False
    assert view["result"]["reason"] == "game_not_responding" and view["result"]["hops"] == 3


def test_a_search_with_a_live_game_is_not_ended_by_the_timeout(client, local, monkeypatch):
    key = register(client)
    start(local)
    command(client, key)
    real = time.time()
    monkeypatch.setattr(app_module.time, "time", lambda: real + app_module.FINDER_LOST_SECONDS - 5)
    assert state(client)["active"] is True


# --------------------------------------------------------------- the worlds and the mod

def test_the_finder_lists_the_worlds_it_can_hop_to(client):
    listed = client.get("/finder/worlds").json()
    assert listed["worlds"]["Hub"] == "/warp hub" and listed["worlds"]["Galatea"] == "/warp galatea" and listed["max_hops"] == 30
    assert "Private Island" not in listed["worlds"] and "Dungeons" not in listed["worlds"]


def test_every_finder_world_is_a_real_scanner_world_and_matches_the_mods_warp_table():
    warps = Path(__file__).resolve().parents[2] / "mod" / "src" / "main" / "java" / "com" / "femboyclient" / "FinderWarps.java"
    if not warps.exists():
        pytest.skip("the mod source is not next to the server")
    mod = dict(re.findall(r'put\("([^"]+)",\s*"([^"]+)"\)', warps.read_text(encoding="utf-8")))
    assert mod == app_module.FINDER_WARPS, "the website and the mod must list the same worlds and warps"


def test_every_finder_world_is_one_the_status_lookup_can_return():
    assert set(app_module.FINDER_WARPS) <= set(app_module.SKYBLOCK_MODES.values())
