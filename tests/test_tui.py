import asyncio
import json
from pathlib import Path

from cinefile.ai import AiUsage
from cinefile.pipeline import RunResult
from cinefile.settings import Settings
from cinefile.tui import CineFileApp, ReplayScreen, ResultScreen, SettingsScreen
from cinefile.vim_buffer import VimBuffer, VimBufferModel


def test_replay_browser_lists_zips_and_has_no_buttons(tmp_path: Path):
    replay_dir = tmp_path / "replays"
    nested = replay_dir / "session"
    nested.mkdir(parents=True)
    (replay_dir / "direct.zip").write_bytes(b"zip")
    (nested / "nested.zip").write_bytes(b"zip")
    (nested / "ignore.txt").write_text("not a replay")
    app = CineFileApp()
    app.settings = Settings(input_path=str(replay_dir))

    async def check():
        async with app.run_test() as pilot:
            await pilot.pause()
            table = app.screen.query_one("#replay-table")
            assert table.row_count == 2
            assert app.screen.query_one("#title-art").render().plain == ""
            assert not app.screen.query("Button")
            assert isinstance(app.screen, ReplayScreen)

    asyncio.run(check())


def test_settings_json_editor_saves_and_closes(monkeypatch, tmp_path: Path):
    from cinefile import tui

    saved = []
    monkeypatch.setattr(tui, "save_settings", lambda settings: saved.append(settings))
    app = CineFileApp()

    async def check():
        async with app.run_test() as pilot:
            await pilot.press("s")
            await pilot.pause()
            assert isinstance(app.screen, SettingsScreen)
            editor = app.screen.query_one("#editor", VimBuffer)
            data = vars(Settings(
                input_path=str(tmp_path / "replays"),
                output_path=str(tmp_path / "flashback"),
                clip_length_s=12,
                style_id="hero-low",
                duration_s=90,
                project="town hall",
                timelapse=True,
                offline=True,
                max_ai_usd=0.2,
                think=True,
                dry_run=True,
            ))
            editor.model.lines = json.dumps(data, indent=2).splitlines()
            app.screen.on_vim_buffer_command_submitted(VimBuffer.CommandSubmitted("wq"))
            await pilot.pause()
            assert isinstance(app.screen, ReplayScreen)
            assert app.settings.clip_length_s == 12
            assert app.settings.style_id == "hero-low"
            assert app.settings.input_path == str((tmp_path / "replays").resolve())
            assert app.settings.output_path == str((tmp_path / "flashback").resolve())
            assert saved == [app.settings]

    asyncio.run(check())


def test_settings_editor_rejects_invalid_json_and_values_without_closing():
    app = CineFileApp()

    async def check():
        async with app.run_test() as pilot:
            await pilot.press("s")
            await pilot.pause()
            editor = app.screen.query_one("#editor", VimBuffer)
            invalid_settings = vars(Settings(clip_length_s=0))
            editor.model.lines = json.dumps(invalid_settings, indent=2).splitlines()
            app.screen.on_vim_buffer_command_submitted(VimBuffer.CommandSubmitted("w"))
            await pilot.pause()
            assert isinstance(app.screen, SettingsScreen)
            editor.model.lines = ["{bad json"]
            app.screen.on_vim_buffer_command_submitted(VimBuffer.CommandSubmitted("wq"))
            await pilot.pause()
            assert isinstance(app.screen, SettingsScreen)

    asyncio.run(check())


def test_settings_w_saves_in_place_and_q_discards_unsaved_edits(monkeypatch):
    from cinefile import tui

    saved = []
    monkeypatch.setattr(tui, "save_settings", lambda settings: saved.append(settings))
    app = CineFileApp()

    async def check():
        async with app.run_test() as pilot:
            await pilot.press("s")
            await pilot.pause()
            editor = app.screen.query_one("#editor", VimBuffer)
            data = vars(Settings(clip_length_s=14))
            editor.model.lines = json.dumps(data, indent=2).splitlines()
            app.screen.on_vim_buffer_command_submitted(VimBuffer.CommandSubmitted("w"))
            await pilot.pause()
            assert isinstance(app.screen, SettingsScreen)
            assert app.settings.clip_length_s == 14
            assert len(saved) == 1

            data["clip_length_s"] = 25
            editor.model.lines = json.dumps(data, indent=2).splitlines()
            app.screen.on_vim_buffer_command_submitted(VimBuffer.CommandSubmitted("q"))
            await pilot.pause()
            assert isinstance(app.screen, ReplayScreen)
            assert app.settings.clip_length_s == 14
            assert len(saved) == 1

    asyncio.run(check())


def test_replay_run_uses_persisted_generation_settings(monkeypatch, tmp_path: Path):
    from cinefile import tui

    replay_dir = tmp_path / "replays"
    replay_dir.mkdir()
    replay = replay_dir / "scene.zip"
    replay.write_bytes(b"zip")
    app = CineFileApp()
    app.settings = Settings(
        input_path=str(replay_dir),
        clip_length_s=12,
        style_id="hero-low",
        duration_s=90,
        project="town hall",
        timelapse=True,
        offline=True,
        max_ai_usd=0.2,
        think=True,
        dry_run=True,
    )
    calls = []

    def fake_run(path, **kwargs):
        calls.append((path, kwargs))
        return RunResult(
            replay_uuid="uuid",
            editor_state_path=tmp_path / "state.json",
            clips=[],
            cuts=0,
            usage=AiUsage(),
            offline=True,
        )

    monkeypatch.setattr(tui, "run", fake_run)

    async def check():
        async with app.run_test() as pilot:
            await pilot.pause()
            app.screen.query_one("#replay-table").move_cursor(row=0)
            await pilot.press("r")
            await pilot.pause(0.2)
            assert isinstance(app.screen, ResultScreen)
            assert calls == [
                (
                    replay,
                    {
                        "editor_dir": None,
                        "clip_length_s": 12,
                        "style_id": "hero-low",
                        "duration_s": 90,
                        "project": "town hall",
                        "timelapse": True,
                        "offline": True,
                        "max_ai_usd": 0.2,
                        "think": True,
                        "dry_run": True,
                    },
                )
            ]
            assert not app.screen.query("Button")

    asyncio.run(check())


def test_vim_buffer_supports_insert_navigation_and_commands():
    model = VimBufferModel("{}")
    model.handle_key("i")
    model.handle_key("left")
    model.handle_key("{", "{")
    assert model.get_text() == "{{}"
    model.handle_key("escape")
    model.handle_key("colon")
    model.handle_key("w", "w")
    assert model.handle_key("enter") == "w"
