"""Tests for the mod update: a linked game can ask for the newest mod jar and get it, nobody else can."""

import base64
import hashlib

import app as app_module
from conftest import UUID_A, register

import pytest

JAR = b"PK\x03\x04 pretend this is a mod jar " * 50


@pytest.fixture()
def jar_file(tmp_path, monkeypatch):
    path = tmp_path / "femboyclient-0.1.0.jar"
    path.write_bytes(JAR)
    monkeypatch.setattr(app_module, "_mod_jar_path", lambda: path)
    return path


def test_a_linked_game_gets_the_newest_jar_and_its_checksum(client, jar_file):
    key = register(client, UUID_A, "WeTrollALittle")
    info = client.get("/mod/latest.json", headers={"X-API-Key": key}).json()
    assert info == {"name": "femboyclient-0.1.0.jar", "sha256": hashlib.sha256(JAR).hexdigest(), "size": len(JAR)}

    body = client.get("/mod/latest.b64", headers={"X-API-Key": key})
    assert base64.b64decode(body.text) == JAR
    assert body.headers["x-sha256"] == info["sha256"]


def test_a_rebuilt_jar_is_offered_at_once(client, jar_file):
    key = register(client, UUID_A, "WeTrollALittle")
    before = client.get("/mod/latest.json", headers={"X-API-Key": key}).json()["sha256"]
    jar_file.write_bytes(JAR + b"newer build")
    after = client.get("/mod/latest.json", headers={"X-API-Key": key}).json()["sha256"]
    assert after == hashlib.sha256(JAR + b"newer build").hexdigest() != before


@pytest.mark.parametrize("path", ["/mod/latest.json", "/mod/latest.b64"])
def test_nobody_without_a_key_gets_the_jar(client, jar_file, path):
    assert client.get(path).status_code == 401
    assert client.get(path, headers={"X-API-Key": "wrong"}).status_code == 401


def test_no_jar_means_not_found(client, monkeypatch):
    key = register(client, UUID_A, "WeTrollALittle")
    monkeypatch.setattr(app_module, "_mod_jar_path", lambda: None)
    assert client.get("/mod/latest.json", headers={"X-API-Key": key}).status_code == 404
    assert client.get("/mod/latest.b64", headers={"X-API-Key": key}).status_code == 404


def test_the_jar_is_found_in_the_mod_build_folder(tmp_path, monkeypatch):
    libs = tmp_path / "mod" / "build" / "libs"
    libs.mkdir(parents=True)
    (libs / "femboyclient-0.1.0.jar").write_bytes(b"real")
    (libs / "femboyclient-0.1.0-sources.jar").write_bytes(b"sources")
    (libs / "femboyclient-0.1.0-dev.jar").write_bytes(b"dev")
    monkeypatch.setattr(app_module, "BASE", tmp_path / "server")
    monkeypatch.setattr(app_module, "_read_config", lambda: {})
    assert app_module._mod_jar_path().name == "femboyclient-0.1.0.jar"
