"""Shared test setup.

The app reads its database and config paths from environment variables when it
is imported, so they are pointed at a throwaway folder first. Your real
femboyclient.db and config.json are never touched.
"""

import json
import os
import sys
import tempfile
from pathlib import Path

import pytest

_TEMP = Path(tempfile.mkdtemp(prefix="femboyclient-tests-"))
os.environ["FEMBOYCLIENT_DB"] = str(_TEMP / "test.db")
os.environ["FEMBOYCLIENT_CONFIG"] = str(_TEMP / "config.json")

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import app as app_module  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

SECRET = "test-enroll-secret"
UUID_A = "1adf8788-e917-4675-b758-defaaee07477"
UUID_B = "22222222-2222-4222-8222-222222222222"


@pytest.fixture()
def client():
    """A fresh empty database and config for every test."""
    with app_module.db() as conn:
        conn.execute("DELETE FROM players")
        conn.execute("DELETE FROM tracker_chats")
        conn.execute("DELETE FROM tracker_my_chats")
        conn.execute("DELETE FROM tracker_parties")
        conn.execute("DELETE FROM tracker_frienemies")
        conn.execute("DELETE FROM tracker_sightings")
        conn.execute("DELETE FROM tracker_visits")
        conn.execute("DELETE FROM tracker_players")

    app_module.CONFIG_PATH.write_text(
        json.dumps({"enroll_secret": SECRET, "api_keys": {}}), encoding="utf-8")

    with TestClient(app_module.app) as test_client:
        yield test_client


def register(client, player_uuid=UUID_A, name="WeTrollALittle"):
    """Link an account and return its API key."""
    response = client.post(
        "/register",
        json={"player_uuid": player_uuid, "player_name": name},
        headers={"X-Enroll-Secret": SECRET})
    assert response.status_code == 200, response.text
    return response.json()["api_key"]
