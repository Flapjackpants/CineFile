import asyncio
from pathlib import Path

import pytest
from cinefile.ai import AiUsage
from cinefile.pipeline import RunResult
from cinefile.settings import Settings
from cinefile.tui import CineFileApp, ResultScreen, RunOptionsScreen
from textual.widgets import Checkbox, Input, Select


def test_replay_browser_lists_zips_and_keeps_title_area_blank(tmp_path: Path):
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

    asyncio.run(check())


def test_settings_screen_saves_paths(monkeypatch, tmp_path: Path):
    from cinefile import tui

    saved = []
    monkeypatch.setattr(tui, "save_settings", lambda settings: saved.append(settings))
    app = CineFileApp()

    async def check():
        async with app.run_test() as pilot:
            await pilot.click("#settings")
            app.screen.query_one("#input-path", Input).value = str(tmp_path / "replays")
            app.screen.query_one("#output-path", Input).value = str(tmp_path / "flashback")
            await pilot.click("#save")
            await pilot.pause()
            assert app.settings.input_path == str((tmp_path / "replays").resolve())
            assert app.settings.output_path == str((tmp_path / "flashback").resolve())
            assert saved == [app.settings]

    asyncio.run(check())


def test_run_options_validate_and_pass_values_to_pipeline(monkeypatch, tmp_path: Path):
    from cinefile import tui

    replay_dir = tmp_path / "replays"
    replay_dir.mkdir()
    replay = replay_dir / "scene.zip"
    replay.write_bytes(b"zip")
    app = CineFileApp()
    app.settings = Settings(input_path=str(replay_dir))
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
            table = app.screen.query_one("#replay-table")
            table.move_cursor(row=0)
            await pilot.click("#configure")
            await pilot.pause()
            app.screen.query_one("#clip-length", Input).value = "12"
            app.screen.query_one("#duration", Input).value = "90"
            app.screen.query_one("#max-ai-usd", Input).value = "0.2"
            app.screen.query_one("#project", Input).value = "town hall"
            app.screen.query_one("#style", Select).value = "hero-low"
            app.screen.query_one("#timelapse", Checkbox).value = True
            app.screen.query_one("#offline", Checkbox).value = True
            app.screen.query_one("#think", Checkbox).value = True
            app.screen.query_one("#dry-run", Checkbox).value = True
            await pilot.click("#run")
            await pilot.pause(0.2)
            assert isinstance(app.screen, ResultScreen)
            assert calls == [
                (
                    replay,
                    {
                        "editor_dir": None,
                        "clip_length_s": 12.0,
                        "style_id": "hero-low",
                        "duration_s": 90.0,
                        "project": "town hall",
                        "timelapse": True,
                        "offline": True,
                        "max_ai_usd": 0.2,
                        "think": True,
                        "dry_run": True,
                    },
                )
            ]

    asyncio.run(check())


def test_invalid_run_options_stay_on_form(monkeypatch, tmp_path: Path):
    from cinefile import tui

    replay_dir = tmp_path / "replays"
    replay_dir.mkdir()
    replay = replay_dir / "scene.zip"
    replay.write_bytes(b"zip")
    app = CineFileApp()
    app.settings = Settings(input_path=str(replay_dir))
    monkeypatch.setattr(tui, "run", lambda *args, **kwargs: pytest.fail("pipeline must not run"))

    async def check():
        async with app.run_test() as pilot:
            await pilot.pause()
            app.screen.query_one("#replay-table").move_cursor(row=0)
            await pilot.click("#configure")
            await pilot.pause()
            app.screen.query_one("#clip-length", Input).value = "0"
            await pilot.click("#run")
            await pilot.pause()
            assert isinstance(app.screen, RunOptionsScreen)

    asyncio.run(check())
