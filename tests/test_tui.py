import asyncio
import json
from pathlib import Path

from textual import events

from cinefile.ai import AiUsage
from cinefile.pipeline import RunResult
from cinefile.settings import Settings
from cinefile.tui import CineFileApp, ReplayScreen, ResultScreen, SettingsScreen
from cinefile.vim_buffer import PUT_REQUEST, EditorMode, VimBuffer, VimBufferModel


def test_replay_browser_lists_zips_and_has_no_buttons(tmp_path: Path):
    replay_dir = tmp_path / "replays"
    nested = replay_dir / "session"
    nested.mkdir(parents=True)
    (replay_dir / "direct.zip").write_bytes(b"zip")
    (nested / "nested.zip").write_bytes(b"zip")
    (nested / "ignore.txt").write_text("not a replay")
    app = CineFileApp()
    app.settings = Settings(input_flashback_folder=str(replay_dir))

    async def check():
        async with app.run_test() as pilot:
            await pilot.pause()
            table = app.screen.query_one("#replay-table")
            assert table.row_count == 2
            assert app.screen.query_one("#title-art").render().plain == ""
            assert not app.screen.query("Button")
            assert isinstance(app.screen, ReplayScreen)

    asyncio.run(check())


def test_cli_path_overrides_open_settings_and_save_with_saved_fallback(monkeypatch, tmp_path: Path):
    from cinefile import tui

    saved_settings = Settings(
        input_flashback_folder=str(tmp_path / "saved-replays"),
        output_flashback_folder=str(tmp_path / "saved-flashback"),
    )
    saved = []
    monkeypatch.setattr(tui, "load_settings", lambda: saved_settings)
    monkeypatch.setattr(tui, "save_settings", lambda settings: saved.append(settings))
    input_flashback_folder = tmp_path / "cli-replays"
    app = CineFileApp(settings_overrides={"input_flashback_folder": str(input_flashback_folder)})

    async def check():
        async with app.run_test() as pilot:
            await pilot.pause()
            assert isinstance(app.screen, SettingsScreen)
            assert saved == []
            editor = app.screen.query_one("#editor", VimBuffer)
            data = json.loads(editor.get_text())
            assert data["input_flashback_folder"] == str(input_flashback_folder)
            assert data["output_flashback_folder"] == saved_settings.output_flashback_folder

            app.screen.on_vim_buffer_command_submitted(VimBuffer.CommandSubmitted("wq"))
            await pilot.pause()
            assert isinstance(app.screen, ReplayScreen)
            assert len(saved) == 1
            assert saved[0].input_flashback_folder == str(input_flashback_folder.resolve())
            assert saved[0].output_flashback_folder == str((tmp_path / "saved-flashback" / "flashback").resolve())

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
                input_flashback_folder=str(tmp_path / "replays"),
                output_flashback_folder=str(tmp_path / "flashback"),
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
            assert app.settings.input_flashback_folder == str((tmp_path / "replays").resolve())
            assert app.settings.output_flashback_folder == str((tmp_path / "flashback").resolve())
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
        input_flashback_folder=str(replay_dir),
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
                        "visual_review": False,
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


def _model(text: str, col: int, mode: EditorMode, row: int = 0) -> VimBufferModel:
    model = VimBufferModel(text)
    model.mode = mode
    model.row = row
    model.col = col
    return model


def test_paste_text_insert_single_line():
    model = _model("ab", 1, EditorMode.INSERT)
    model.paste_text("XY")
    assert model.get_text() == "aXYb"
    assert model.col == 3


def test_paste_text_insert_multi_line():
    model = _model("ab", 1, EditorMode.INSERT)
    model.paste_text("X\nY")
    assert model.lines == ["aX", "Yb"]
    assert (model.row, model.col) == (1, 1)


def test_paste_normalizes_text():
    model = _model("", 0, EditorMode.INSERT)
    model.paste_text("a\r\nb\tc\x07")
    assert model.get_text() == "a\nb    c"


def test_paste_text_ignored_outside_insert():
    model = _model("ab", 0, EditorMode.NORMAL)
    model.paste_text("XY")
    assert model.get_text() == "ab"


def test_normal_p_returns_put_request():
    model = VimBufferModel("ab")
    assert model.handle_key("p", "p") == PUT_REQUEST
    assert model.get_text() == "ab"


def test_put_after_charwise():
    model = _model("ab", 0, EditorMode.NORMAL)
    model.put_after("XY")
    assert model.get_text() == "aXYb"
    assert model.col == 2


def test_put_after_empty_line():
    model = _model("", 0, EditorMode.NORMAL)
    model.put_after("XY")
    assert model.get_text() == "XY"
    assert model.col == 1


def test_put_after_linewise():
    model = _model("a\nb", 0, EditorMode.NORMAL)
    model.put_after("  x\ny\n")
    assert model.lines == ["a", "  x", "y", "b"]
    assert (model.row, model.col) == (1, 2)


def test_put_after_empty_noop():
    model = _model("ab", 0, EditorMode.NORMAL)
    model.put_after("")
    assert model.get_text() == "ab"
    assert model.col == 0


def test_settings_editor_handles_paste_event(monkeypatch, tmp_path: Path):
    from cinefile import tui

    monkeypatch.setattr(tui, "load_settings", lambda: Settings())
    monkeypatch.setattr(tui, "save_settings", lambda settings: None)
    app = CineFileApp(settings_overrides={"input_flashback_folder": str(tmp_path)})

    async def check():
        async with app.run_test() as pilot:
            await pilot.pause()
            editor = app.screen.query_one("#editor", VimBuffer)
            await pilot.press("i")
            editor.on_paste(events.Paste("Z"))
            assert editor.get_text().startswith("Z")

    asyncio.run(check())


def test_settings_editor_p_puts_clipboard(monkeypatch, tmp_path: Path):
    from cinefile import tui, vim_buffer

    monkeypatch.setattr(tui, "load_settings", lambda: Settings())
    monkeypatch.setattr(vim_buffer, "read_system_clipboard", lambda: "Q")
    app = CineFileApp(settings_overrides={"input_flashback_folder": str(tmp_path)})

    async def check():
        async with app.run_test() as pilot:
            await pilot.pause()
            editor = app.screen.query_one("#editor", VimBuffer)
            await pilot.press("p")
            assert editor.get_text().split("\n")[0] == "{Q"

    asyncio.run(check())


def test_settings_editor_p_empty_clipboard(monkeypatch, tmp_path: Path):
    from cinefile import tui, vim_buffer

    monkeypatch.setattr(tui, "load_settings", lambda: Settings())
    monkeypatch.setattr(vim_buffer, "read_system_clipboard", lambda: None)
    app = CineFileApp(settings_overrides={"input_flashback_folder": str(tmp_path)})

    async def check():
        async with app.run_test() as pilot:
            await pilot.pause()
            editor = app.screen.query_one("#editor", VimBuffer)
            before = editor.get_text()
            await pilot.press("p")
            assert editor.get_text() == before

    asyncio.run(check())
