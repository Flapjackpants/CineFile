from pathlib import Path

from cinefile import cli


def test_no_replay_launches_textual_tui(monkeypatch):
    received = {}

    class FakeApp:
        def __init__(self, *, run_options, editor_dir):
            received["options"] = run_options
            received["editor_dir"] = editor_dir

        def run(self):
            return 7

    monkeypatch.setattr(cli, "_resolve_editor_dir_arg", lambda value: None)
    monkeypatch.setattr("cinefile.tui.CineFileApp", FakeApp, raising=False)
    assert cli.main([]) == 7
    assert received["options"]["style_id"] == "locked-dolly"
    assert received["options"]["duration_s"] == 180.0
    assert received["editor_dir"] is None


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
