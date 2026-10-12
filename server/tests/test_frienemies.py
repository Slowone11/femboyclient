"""Tests for the friendly/enemy tag endpoints (GET/POST/DELETE /tracker/frienemies)."""

from conftest import UUID_A


def test_tagging_an_unknown_player_creates_and_tags_them(client):
    answer = client.post("/tracker/frienemies", json={"name": "Alice", "tag": "enemy", "notes": "team wiped us"})
    assert answer.status_code == 200
    body = answer.json()
    assert body["name"] == "Alice" and body["tag"] == "enemy" and body["notes"] == "team wiped us"
    assert body["uuid"] is None and isinstance(body["id"], int)

    listed = client.get("/tracker/frienemies").json()
    assert len(listed) == 1 and listed[0]["name"] == "Alice" and listed[0]["tag"] == "enemy"


def test_tagging_again_replaces_the_tag_and_note_instead_of_duplicating(client):
    client.post("/tracker/frienemies", json={"name": "Bob", "tag": "enemy", "notes": "stream sniper"})
    second = client.post("/tracker/frienemies", json={"name": "Bob", "tag": "friendly", "notes": "actually fine"})
    assert second.status_code == 200
    listed = client.get("/tracker/frienemies").json()
    assert len(listed) == 1 and listed[0]["tag"] == "friendly" and listed[0]["notes"] == "actually fine"


def test_a_uuid_is_stored_and_used_to_find_the_player_again(client):
    first = client.post("/tracker/frienemies", json={"name": "Carol", "uuid": UUID_A, "tag": "friendly"})
    assert first.json()["uuid"] == UUID_A

    # Renamed (name changes, same uuid): still the same tagged player, not a second row.
    second = client.post("/tracker/frienemies", json={"name": "CarolNew", "uuid": UUID_A, "tag": "friendly"})
    assert second.json()["id"] == first.json()["id"]
    listed = client.get("/tracker/frienemies").json()
    assert len(listed) == 1 and listed[0]["name"] == "CarolNew"


def test_untagging_removes_it_from_the_list(client):
    created = client.post("/tracker/frienemies", json={"name": "Dave", "tag": "enemy"}).json()
    deleted = client.delete("/tracker/frienemies/%d" % created["id"])
    assert deleted.status_code == 200 and deleted.json() == {"deleted": True}
    assert client.get("/tracker/frienemies").json() == []


def test_untagging_a_player_with_no_tag_is_a_404(client):
    assert client.delete("/tracker/frienemies/999999").status_code == 404


def test_bad_tag_name_or_uuid_are_rejected(client):
    assert client.post("/tracker/frienemies", json={"name": "Eve", "tag": "nemesis"}).status_code == 422
    assert client.post("/tracker/frienemies", json={"name": "bad name!", "tag": "enemy"}).status_code == 422
    assert client.post("/tracker/frienemies", json={"name": "Eve", "uuid": "not-a-uuid", "tag": "enemy"}).status_code == 422


def test_notes_over_the_length_limit_are_rejected(client):
    answer = client.post("/tracker/frienemies", json={"name": "Frank", "tag": "enemy", "notes": "x" * 501})
    assert answer.status_code == 422


def test_no_api_key_is_needed_since_the_data_is_shared_not_owner_scoped(client):
    assert client.post("/tracker/frienemies", json={"name": "Grace", "tag": "friendly"}).status_code == 200
    assert client.get("/tracker/frienemies").status_code == 200
