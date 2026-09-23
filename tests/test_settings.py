from pathlib import Path

from cinefile.editor import normalize_editor_dir, resolve_editor_dir
from cinefile.menu import list_replay_zips
from cinefile.settings import (
    Settings,
    load_settings,
    normalize_input_dir,
    normalize_output_dir,
    save_settings,
)


def test_settings_roundtrip(tmp_path: Path):
    cfg = tmp_path / "settings.json"
    s = Settings(input_path="/tmp/in", output_path="/tmp/out")
    save_settings(s, cfg)
    loaded = load_settings(cfg)
    assert loaded.input_path == "/tmp/in"
    assert loaded.output_path == "/tmp/out"


def test_settings_missing_file(tmp_path: Path):
    loaded = load_settings(tmp_path / "missing.json")
    assert loaded.input_path is None
    assert loaded.output_path is None


def test_normalize_output_editor_states(tmp_path: Path):
    flashback = tmp_path / "flashback"
    states = flashback / "editor_states"
    states.mkdir(parents=True)
    assert normalize_output_dir(states) == flashback.resolve()
    assert normalize_editor_dir(states) == flashback.resolve()
    assert normalize_output_dir(flashback) == flashback.resolve()


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
