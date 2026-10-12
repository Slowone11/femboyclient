"""Tests for the Femboy Client API."""

import app as app_module
from conftest import SECRET, UUID_A, UUID_B, register


# ----------------------------------------------------------------- register

def test_register_rejects_a_missing_or_wrong_secret(client):
    body = {"player_uuid": UUID_A, "player_name": "Steve"}
    assert client.post("/register", json=body).status_code == 401
    assert client.post("/register", json=body, headers={"X-Enroll-Secret": "nope"}).status_code == 401


def test_register_creates_a_key_then_returns_the_same_one(client):
    first = client.post("/register", json={"player_uuid": UUID_A, "player_name": "Steve"},
                        headers={"X-Enroll-Secret": SECRET}).json()
    second = client.post("/register", json={"player_uuid": UUID_A.upper(), "player_name": "Steve2"},
                         headers={"X-Enroll-Secret": SECRET}).json()

    assert first["created"] is True
    assert second["created"] is False
    assert first["api_key"] == second["api_key"]  # a second PC gets the same key


def test_register_gives_different_accounts_different_keys(client):
    assert register(client, UUID_A) != register(client, UUID_B, "Alex")


def test_register_rejects_a_bad_uuid(client):
    response = client.post("/register", json={"player_uuid": "not-a-uuid", "player_name": "x"},
                           headers={"X-Enroll-Secret": SECRET})
    assert response.status_code == 422


# ------------------------------------------------------- heartbeat and players

def beat(client, key, **overrides):
    body = {"player_uuid": UUID_A, "player_name": "WeTrollALittle", "in_skyblock": True}
    body.update(overrides)
    return client.post("/heartbeat", json=body, headers={"X-API-Key": key})


def test_heartbeat_needs_a_valid_key_for_that_account(client):
    key = register(client, UUID_A)
    assert client.post("/heartbeat", json={"player_uuid": UUID_A, "player_name": "x"}).status_code == 401
    assert beat(client, key, player_uuid=UUID_B).status_code == 403


def test_an_account_is_online_after_a_heartbeat(client):
    key = register(client)
    beat(client, key)

    player = client.get("/players").json()[0]
    assert player["online"] is True and player["in_skyblock"] is True


def test_leaving_skyblock_clears_the_skyblock_flag(client):
    key = register(client)
    beat(client, key)
    beat(client, key, in_skyblock=False)

    player = client.get("/players").json()[0]
    assert player["online"] is True and player["in_skyblock"] is False


def test_the_goodbye_heartbeat_marks_the_account_offline(client):
    key = register(client)
    beat(client, key)
    beat(client, key, online=False)

    player = client.get("/players").json()[0]
    assert player["online"] is False and player["in_skyblock"] is False


def test_an_account_goes_offline_when_heartbeats_stop(client):
    key = register(client)
    beat(client, key)
    with app_module.db() as conn:
        conn.execute("UPDATE players SET last_seen_ms = last_seen_ms - ?", (app_module.ONLINE_WINDOW_MS + 5000,))

    assert client.get("/players").json()[0]["online"] is False


def test_players_lists_every_linked_account(client):
    register(client, UUID_A)
    register(client, UUID_B, "Alex")

    players = {p["player_name"]: p for p in client.get("/players").json()}
    assert set(players) == {"WeTrollALittle", "Alex"}
    assert "coins" not in players["Alex"]


def test_an_old_mod_that_still_sends_a_session_id_is_accepted(client):
    key = register(client)
    assert beat(client, key, session_id="from-an-old-jar").status_code == 200


def test_a_renamed_account_shows_its_new_name(client):
    register(client, UUID_A, "OldName")
    register(client, UUID_A, "NewName")

    assert [p["player_name"] for p in client.get("/players").json()] == ["NewName"]


# -------------------------------------------------------------------- page

def test_health_answers_without_any_data(client):
    assert client.get("/health").json() == {"status": "ok", "players": 0}


def test_the_page_and_its_scripts_are_never_served_from_a_stale_browser_cache(client):
    # The browser may keep them, but must ask the server whether they changed.
    for path in ("/", "/static/app.js", "/static/style.css"):
        assert client.get(path).headers["cache-control"] == "no-cache", path


def test_the_page_links_its_script_and_styles_with_a_version_so_they_are_never_stale(client):
    html = client.get("/").text
    import re
    assert re.search(r'href="/static/style\.css\?v=\d+"', html)
    scripts = re.findall(r'src="/static/(\w+\.js)\?v=\d+"', html)
    assert "app.js" in scripts and "core.js" in scripts
    for name in scripts:
        assert client.get("/static/%s?v=123" % name).status_code == 200, name


def test_the_graph_engine_is_in_the_script_before_the_tracker_and_the_old_library_is_not_needed(client):
    import re
    html = client.get("/").text
    scripts = re.findall(r'src="/static/(\w+\.js)', html)
    assert "window.GraphKit =" in client.get("/static/graph.js").text
    assert scripts.index("graph.js") < scripts.index("tracker.js"), "the engine must load before the tracker"
    assert scripts.index("core.js") < scripts.index("app.js"), "the shared helpers must load first"
    assert "vis-network" not in html and "cdn.jsdelivr.net/npm/vis" not in html

