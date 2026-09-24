from pathlib import Path

from cinefile import cli


def test_no_replay_launches_textual_tui(monkeypatch):
    received = {}

    class FakeApp:
        def __init__(self, *, run_options, editor_dir, settings_overrides):
            received["options"] = run_options
            received["editor_dir"] = editor_dir
            received["settings_overrides"] = settings_overrides

        def run(self):
            return 7

    monkeypatch.setattr(cli, "_resolve_editor_dir_arg", lambda value: None)
    monkeypatch.setattr("cinefile.tui.CineFileApp", FakeApp, raising=False)
    assert cli.main([]) == 7
    assert received["options"] == {}
    assert received["editor_dir"] is None
    assert received["settings_overrides"] == {}


def test_no_replay_cli_flags_override_saved_settings(monkeypatch):
    received = {}

    class FakeApp:
        def __init__(self, *, run_options, editor_dir, settings_overrides):
            received["options"] = run_options
            received["editor_dir"] = editor_dir
            received["settings_overrides"] = settings_overrides

        def run(self):
            return 0

    monkeypatch.setattr(cli, "_resolve_editor_dir_arg", lambda value: None)
    monkeypatch.setattr("cinefile.tui.CineFileApp", FakeApp, raising=False)
    assert cli.main(["--clip-length", "15", "--offline"]) == 0
    assert received["options"] == {"clip_length_s": 15.0, "offline": True}
    assert received["settings_overrides"] == {}


def test_input_and_output_path_flags_are_passed_to_tui(monkeypatch, tmp_path: Path):
    received = {}

    class FakeApp:
        def __init__(self, *, run_options, editor_dir, settings_overrides):
            received["settings_overrides"] = settings_overrides

        def run(self):
            return 0

    monkeypatch.setattr("cinefile.tui.CineFileApp", FakeApp, raising=False)
    input_path = tmp_path / "replays"
    output_path = tmp_path / "flashback"
    assert cli.main(["-i", str(input_path), "--output", str(output_path)]) == 0
    assert received["settings_overrides"] == {
        "input_path": str(input_path),
        "output_path": str(output_path),
    }


def test_long_input_and_short_output_path_flags_are_passed_to_tui(monkeypatch, tmp_path: Path):
    received = {}

    class FakeApp:
        def __init__(self, *, run_options, editor_dir, settings_overrides):
            received["settings_overrides"] = settings_overrides

        def run(self):
            return 0

    monkeypatch.setattr("cinefile.tui.CineFileApp", FakeApp, raising=False)
    input_path = tmp_path / "replays"
    output_path = tmp_path / "flashback"
    assert cli.main(["--input", str(input_path), "-o", str(output_path)]) == 0
    assert received["settings_overrides"] == {
        "input_path": str(input_path),
        "output_path": str(output_path),
    }


def test_direct_replay_cli_keeps_pipeline_path(monkeypatch, tmp_path: Path):
    replay = tmp_path / "replay.zip"
    replay.write_bytes(b"zip")
    called = {}

    class Usage:
        deepseek_in = 0
        deepseek_out = 0
        jev_in = 0

        def estimate_usd(self):
            return 0

    class Result:
        replay_uuid = "uuid"
        clips = []
        cuts = 0
        editor_state_path = tmp_path / "state.json"
        usage = Usage()
        offline = True

    def fake_run(path, **kwargs):
        called["path"] = path
        called["kwargs"] = kwargs
        return Result()

    monkeypatch.setattr(cli, "run", fake_run)
    monkeypatch.setattr(cli, "_resolve_editor_dir_arg", lambda value: None)
    assert cli.main(["--replay", str(replay), "--offline", "--duration", "60"]) == 0
    assert called["path"] == replay
    assert called["kwargs"]["offline"] is True
    assert called["kwargs"]["duration_s"] == 60.0
