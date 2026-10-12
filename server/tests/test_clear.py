"""Tests for clearing player tracker data."""

import app as app_module
from conftest import UUID_A, UUID_B, register
from fastapi.testclient import TestClient

import pytest

ME = "WeTrollALittle"
HUB1 = "2026-09-20T19:00:00Z"
HUB2 = "2026-09-21T15:00:00Z"
MINES = "2026-09-21T16:00:00Z"


@pytest.fixture()
def local(client):
    """A client that looks like it is on the PC running the website. The `client` fixture sets up the database."""
    with TestClient(app_module.app, client=("127.0.0.1", 50000)) as c:
        yield c


def snap(client, key, players, visit, world, me=ME):
    body = {"me": me, "players": players, "timestamp": visit.replace("00:00Z", "00:10Z"),
            "visit_started": visit, "world": world}
    assert client.post("/tracker/snapshot", json=body, headers={"X-API-Key": key}).status_code == 200


def count(table):
    with app_module.db() as conn:
        return conn.execute("SELECT COUNT(*) FROM " + table).fetchone()[0]


def player(client, name):
    found = client.get("/tracker/search", params={"q": name, "exact": "true"}).json()["players"]
    return found[0] if found else None


def clear(client, owner=UUID_A, **extra):
    return client.post("/tracker/clear", json={"owner": owner, **extra})


@pytest.fixture()
def data(client):
    """Steve is met in the Hub twice and once in the Mines; the others once each."""
    key = register(client)
    snap(client, key, ["Steve", "HubOnly"], HUB1, "Hub")
    snap(client, key, ["Steve", "HubAlso"], HUB2, "Hub")
    snap(client, key, ["Steve", "MinesOnly"], MINES, "Dwarven Mines")
    return key


def test_clearing_everything_removes_all_of_the_accounts_tracker_data(client, local, data):
    result = clear(local).json()

    assert result["players_removed"] == 4 and result["visits_removed"] == 3
    assert (count("tracker_players"), count("tracker_visits"), count("tracker_sightings")) == (0, 0, 0)


def test_clearing_one_world_keeps_the_others(client, local, data):
    result = clear(local, world="Hub").json()

    assert result["visits_removed"] == 2 and result["players_removed"] == 2   # HubOnly and HubAlso
    assert player(client, "HubOnly") is None and player(client, "MinesOnly") is not None
    tree = client.get("/tracker/tree", params={"owner": UUID_A}).json()
    assert [n["world"] for n in tree["nodes"] if n["type"] == "world"] == ["Dwarven Mines"]


def test_a_player_met_elsewhere_stays_and_their_counts_are_worked_out_again(client, local, data):
    before = player(client, "Steve")
    assert before["times_seen"] == 3

    clear(local, world="hub")   # not case sensitive

    steve = player(client, "Steve")
    assert steve["times_seen"] == 1
    assert steve["first_seen"] == steve["last_seen"] == "2026-09-21T16:00:10.000Z"
    assert [s["world"] for s in steve["sightings"]] == ["Dwarven Mines"]


def test_clearing_only_touches_the_chosen_account(client, local, data):
    key_b = register(client, UUID_B, "AltAccount")
    snap(client, key_b, ["Steve", "AltGuest"], HUB1, "Hub", me="AltAccount")

    clear(local, world="Hub")

    assert player(client, "AltGuest") is not None, "the other account's visit is untouched"
    steve = player(client, "Steve")
    assert steve["times_seen"] == 2 and {s["account"] for s in steve["sightings"]} == {"AltAccount", ME}
    assert player(client, "HubOnly") is None


def test_clearing_a_world_with_nothing_in_it_changes_nothing(client, local, data):
    result = clear(local, world="The End").json()

    assert result["visits_removed"] == 0 and result["players_removed"] == 0
    assert count("tracker_players") == 4


def test_clearing_leaves_coin_data_and_the_account_alone(client, local, data):
    with app_module.db() as conn:
        accounts = conn.execute("SELECT COUNT(*) FROM players").fetchone()[0]

    clear(local)

    assert count("players") == accounts == 1


def test_new_players_can_be_recorded_after_clearing(client, local, data):
    clear(local)
    snap(client, data, ["Steve"], "2026-09-22T10:00:00Z", "Hub")

    steve = player(client, "Steve")
    assert steve["times_seen"] == 1 and steve["first_seen"] == steve["last_seen"]


def test_clearing_is_refused_from_another_pc(client, data):
    # The default test client is not on the local PC, like someone else on the network.
    assert clear(client).status_code == 403
    assert clear(client, world="Hub").status_code == 403
    assert count("tracker_players") == 4


@pytest.mark.parametrize("body", [{}, {"owner": "nope"}, {"owner": UUID_A, "world": ["Hub"]}])
def test_clearing_rejects_bad_input(client, local, data, body):
    assert local.post("/tracker/clear", json=body).status_code == 422
    assert count("tracker_players") == 4


def test_clearing_needs_a_json_body(client, local, data):
    # A web page on another site can only send a plain-text form post, which must not work.
    response = local.post("/tracker/clear", content='{"owner": "%s"}' % UUID_A, headers={"Content-Type": "text/plain"})
    assert response.status_code == 422
    assert count("tracker_players") == 4


def test_a_big_clear_works(client, local):
    key = register(client)
    for visit in range(700):
        start = "2026-08-%02dT%02d:%02d:00Z" % (1 + visit // 300, (visit // 60) % 24, visit % 60)
        snap(client, key, ["Player%d" % (visit % 50), "Extra%d" % visit], start, "Hub")

    result = clear(local).json()

    assert result["visits_removed"] == 700 and count("tracker_players") == 0
