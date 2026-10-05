import json
from pathlib import Path

from cinefile.editor import normalize_editor_dir, resolve_editor_dir
from cinefile.menu import list_replay_zips
from cinefile.settings import (
    Settings,
    load_settings,
    normalize_input_dir,
    normalize_render_instance_dir,
    save_settings,
    settings_from_dict,
)


def test_settings_roundtrip(tmp_path: Path):
    cfg = tmp_path / "settings.json"
    s = Settings(input_path="/tmp/in", render_instance_path="/tmp/out")
    save_settings(s, cfg)
    loaded = load_settings(cfg)
    assert loaded.input_path == "/tmp/in"
    assert loaded.render_instance_path == "/tmp/out"
    assert loaded.clip_length_s == 10.0
    assert loaded.style_id == "locked-dolly"


def test_settings_missing_file(tmp_path: Path):
    loaded = load_settings(tmp_path / "missing.json")
    assert loaded.input_path is None
    assert loaded.render_instance_path is None


def test_legacy_path_only_settings_load_with_generation_defaults(tmp_path: Path):
    cfg = tmp_path / "settings.json"
    cfg.write_text('{"input_path": "/tmp/replays", "output_path": "/tmp/flashback"}')
    loaded = load_settings(cfg)
    assert loaded.input_path == "/tmp/replays"
    assert loaded.render_instance_path == "/tmp/flashback"
    assert loaded.run_options() == {
        "clip_length_s": 10.0,
        "style_id": "locked-dolly",
        "duration_s": 180.0,
        "project": "",
        "timelapse": False,
        "offline": False,
        "max_ai_usd": 0.05,
        "think": False,
        "dry_run": False,
    }


def test_settings_reject_invalid_values():
    for field, value in (("clip_length_s", 0), ("duration_s", float("inf")), ("max_ai_usd", -1), ("timelapse", "yes"), ("style_id", "unknown")):
        try:
            settings_from_dict({field: value})
        except ValueError:
            pass
        else:
            raise AssertionError(f"accepted invalid {field}={value!r}")


def test_normalize_render_instance_dir(tmp_path: Path):
    flashback = tmp_path / "flashback"
    states = flashback / "editor_states"
    states.mkdir(parents=True)
    assert normalize_render_instance_dir(states) == flashback.resolve()
    assert normalize_editor_dir(states) == flashback.resolve()
    assert normalize_render_instance_dir(flashback) == flashback.resolve()
    assert normalize_render_instance_dir(tmp_path) == flashback.resolve()
    assert normalize_editor_dir(tmp_path) == flashback.resolve()


def test_normalize_input_flashback_to_replays(tmp_path: Path):
    flashback = tmp_path / "flashback"
    replays = flashback / "replays"
    replays.mkdir(parents=True)
    assert normalize_input_dir(flashback) == replays.resolve()
    assert normalize_input_dir(replays) == replays.resolve()


def test_resolve_editor_dir_with_editor_states(tmp_path: Path):
    flashback = tmp_path / "flashback"
    states = flashback / "editor_states"
    states.mkdir(parents=True)
    replay = tmp_path / "elsewhere" / "replay.zip"
    replay.parent.mkdir(parents=True)
    replay.write_bytes(b"x")
    assert resolve_editor_dir(replay, states) == flashback.resolve()


def test_list_replay_zips(tmp_path: Path):
    (tmp_path / "a.zip").write_bytes(b"x")
    sub = tmp_path / "nested"
    sub.mkdir()
    (sub / "b.zip").write_bytes(b"x")
    (tmp_path / "c.txt").write_text("nope")
    found = list_replay_zips(tmp_path)
    names = {p.name for p in found}
    assert names == {"a.zip", "b.zip"}


def test_legacy_output_path_migrates_and_saves_new_key(tmp_path: Path):
    cfg = tmp_path / "settings.json"
    cfg.write_text('{"output_path": "/tmp/flashback"}')
    loaded = load_settings(cfg)
    assert loaded.render_instance_path == "/tmp/flashback"
    save_settings(loaded, cfg)
    data = json.loads(cfg.read_text())
    assert data["render_instance_path"] == "/tmp/flashback"
    assert "output_path" not in data


def test_settings_from_dict_prefers_render_instance_path(tmp_path: Path):
    instance = tmp_path / "Render"
    settings = settings_from_dict({"render_instance_path": str(instance), "output_path": "/ignored"})
    assert settings.render_instance_path == str((instance / "flashback").resolve())
    assert settings.render_dir() == (instance / "flashback").resolve()
