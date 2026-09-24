"""Textual interface for browsing replays and generating CineFile edits."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any

from textual import work
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Vertical
from textual.screen import Screen
from textual.widgets import (
    DataTable,
    Footer,
    Header,
    Label,
    Static,
)

from .menu import list_replay_zips
from .pipeline import RunResult, run
from .editor import normalize_editor_dir
from .settings import Settings, load_settings, normalize_input_dir, normalize_output_dir, save_settings, settings_from_dict
from .vim_buffer import VimBuffer


class CineFileApp(App[int]):
    """Replay browser and editor-state generator."""

    TITLE = "CineFile"
    BINDINGS = [Binding("q", "quit", "Quit")]
    CSS = """
    Screen { layout: vertical; }
    #title-art {
        height: 1;
        padding: 0 2;
        background: $boost;
        content-align: center middle;
    }
    #content { height: 1fr; padding: 1 2; }
    #status-bar {
        height: 1;
        padding: 0 2;
        color: $text-muted;
    }
    #editor-wrap { height: 1fr; padding: 0 1; }
    #editor-wrap VimBuffer { height: 1fr; }
    #mode-bar { height: 1; padding: 0 2; color: $text-muted; }
    #showcmd { height: 1; padding: 0 2; content-align: right middle; color: $text-muted; }
    #result-box {
        width: 80%;
        height: auto;
        border: round $accent;
        padding: 1 2;
        margin: 1 0;
    }
    """

    def __init__(
        self,
        *,
        run_options: dict[str, Any] | None = None,
        editor_dir: Path | None = None,
    ) -> None:
        super().__init__()
        self.settings = load_settings()
        # Explicit CLI options override saved defaults for this TUI session.
        self.run_options = run_options or {}
        self.editor_dir = editor_dir

    def on_mount(self) -> None:
        self.push_screen(ReplayScreen())


class ShellScreen(Screen[None]):
    """Screen base for CineFile's shared keyboard driven shell."""


class ReplayScreen(ShellScreen):
    BINDINGS = [
        Binding("enter", "run_selected", "Run selected"),
        Binding("r", "run_selected", "Run selected"),
        Binding("s", "settings", "Settings"),
        Binding("q", "quit", "Quit"),
    ]

    def compose(self) -> ComposeResult:
        with Vertical():
            yield Header(show_clock=True)
            yield Static("", id="title-art")
            with Vertical(id="content"):
                yield Label("Select a Flashback replay")
                yield DataTable(id="replay-table", cursor_type="row", zebra_stripes=True)
                yield Label("↑/↓ select  Enter or r run  s settings  q quit")
            yield Static("", id="status-bar")
            yield Footer()

    def on_mount(self) -> None:
        table = self.query_one("#replay-table", DataTable)
        table.add_columns("Replay", "Location", "Size")
        table.focus()
        self.refresh_replays()

    def refresh_replays(self) -> None:
        table = self.query_one("#replay-table", DataTable)
        table.clear()
        settings = self.app.settings
        if not settings.input_path:
            self.query_one("#status-bar", Static).update(
                "Set an input folder in Settings to browse replays."
            )
            return
        input_dir = normalize_input_dir(Path(settings.input_path).expanduser())
        for replay in list_replay_zips(input_dir):
            try:
                location = str(replay.relative_to(input_dir))
            except ValueError:
                location = replay.name
            try:
                size = f"{replay.stat().st_size / (1024 * 1024):.1f} MB"
            except OSError:
                size = "—"
            table.add_row(replay.name, location, size, key=str(replay))
        status = f"{table.row_count} replay(s) in {input_dir}"
        if not input_dir.is_dir():
            status = f"Input folder not found: {input_dir}"
        elif not table.row_count:
            status = f"No replay .zip files found in {input_dir} or its immediate subfolders."
        self.query_one("#status-bar", Static).update(status)

    def selected_replay(self) -> Path | None:
        table = self.query_one("#replay-table", DataTable)
        if not table.row_count:
            return None
        try:
            row = table.coordinate_to_cell_key(table.cursor_coordinate)
            return Path(str(row.row_key.value))
        except Exception:
            return None

    def action_run_selected(self) -> None:
        replay = self.selected_replay()
        if replay is None:
            self.notify("Select a replay first.", severity="warning")
            return
        settings = self.app.settings
        options = {**settings.run_options(), **self.app.run_options}
        result_screen = ResultScreen(replay, running=True)
        self.app.push_screen(result_screen)
        self._start_run(replay, options, result_screen)

    def action_settings(self) -> None:
        self.app.push_screen(SettingsScreen())

    @work(exclusive=True)
    async def _start_run(self, replay: Path, options: dict[str, Any], result_screen: "ResultScreen") -> None:
        output_path = self.app.settings.output_path
        editor_dir = self.app.editor_dir
        if editor_dir is None and output_path:
            editor_dir = normalize_editor_dir(Path(output_path).expanduser())
        try:
            result = await asyncio.to_thread(run, replay, editor_dir=editor_dir, **options)
            await asyncio.sleep(0.01)
            result_screen.show_result(result=result, style=options["style_id"])
        except Exception as exc:
            await asyncio.sleep(0.01)
            result_screen.show_result(error=str(exc))

    def on_screen_resume(self) -> None:
        self.refresh_replays()


class SettingsScreen(ShellScreen):
    BINDINGS = []

    def _initial_text(self) -> str:
        return json.dumps(vars(self.app.settings), indent=2) + "\n"

    def compose(self) -> ComposeResult:
        with Vertical():
            yield Header(show_clock=True)
            yield Static("", id="title-art")
            yield Label("Settings — edit JSON; i insert, Escape normal, :w save, :wq save/back, :q discard")
            with Vertical(id="editor-wrap"):
                yield VimBuffer(self._initial_text(), id="editor")
            yield Static("-- NORMAL --", id="mode-bar")
            yield Static("", id="showcmd")
            yield Footer()

    def on_mount(self) -> None:
        self.query_one("#editor", VimBuffer).focus()

    def on_vim_buffer_mode_changed(self, event: VimBuffer.ModeChanged) -> None:
        self.query_one("#mode-bar", Static).update(event.status)

    def on_vim_buffer_command_submitted(self, event: VimBuffer.CommandSubmitted) -> None:
        command = event.command.strip()
        self.query_one("#showcmd", Static).update(f":{command}")
        if command == "q":
            self.app.pop_screen()
            return
        if command not in ("w", "wq"):
            self.notify(f"Unknown command: :{command}", severity="warning")
            return
        try:
            payload = json.loads(self.query_one("#editor", VimBuffer).get_text())
            if not isinstance(payload, dict):
                raise ValueError("JSON root must be an object")
            settings = settings_from_dict(payload)
            save_settings(settings)
        except json.JSONDecodeError as exc:
            self.notify(f"Invalid JSON: {exc.msg} (line {exc.lineno})", severity="error")
            return
        except (ValueError, OSError) as exc:
            self.notify(f"Could not save settings: {exc}", severity="error")
            return
        self.app.settings = settings
        self.notify("Settings saved.")
        if command == "wq":
            self.app.pop_screen()


class ResultScreen(ShellScreen):
    BINDINGS = [Binding("escape", "back", "Back"), Binding("enter", "back", "Back"), Binding("q", "quit", "Quit")]

    def __init__(
        self,
        replay: Path,
        result: RunResult | None = None,
        style: str = "locked-dolly",
        error: str | None = None,
        running: bool = False,
    ) -> None:
        super().__init__()
        self.replay = replay
        self.result = result
        self.style = style
        self.error = error
        self.running = running

    def compose(self) -> ComposeResult:
        with Vertical():
            yield Header(show_clock=True)
            yield Static("", id="title-art")
            with Vertical(id="content"):
                if self.running:
                    yield Label("Generating Flashback camera edits…", id="result-title")
                    yield Static(f"Replay: {self.replay}\nThis can take a little while.", id="result-box")
                elif self.error:
                    yield Label("Generation failed", id="result-title", classes="error")
                    yield Static(self.error, id="result-box")
                elif self.result:
                    cine_s = sum((c.end_tick - c.start_tick) / 20.0 for c in self.result.clips)
                    lines = [
                        f"Replay UUID: {self.result.replay_uuid}",
                        f"Clips: {len(self.result.clips)} ({cine_s:.1f}s cinematic)",
                        f"Teleport cuts skipped: {self.result.cuts}",
                        f"Style: {self.style}",
                        f"Wrote: {self.result.editor_state_path}",
                        f"AI estimate: ${self.result.usage.estimate_usd():.4f}",
                    ]
                    if self.result.offline:
                        lines.append("Mode: offline / heuristic")
                    lines.append("Reopen the replay in Flashback to load the new editor state.")
                    yield Label("Generation complete")
                    yield Static("\n".join(lines), id="result-box")
                yield Label("Enter/Escape back to replays  q quit")
            yield Static("", id="status-bar")
            yield Footer()

    def action_back(self) -> None:
        if not self.running:
            self.app.pop_screen()

    def action_quit(self) -> None:
        self.app.exit(0)

    def show_result(
        self,
        *,
        result: RunResult | None = None,
        style: str = "locked-dolly",
        error: str | None = None,
    ) -> None:
        self.result = result
        self.style = style
        self.error = error
        self.running = False
        if error:
            title = "Generation failed"
            details = error
        elif result:
            cine_s = sum((c.end_tick - c.start_tick) / 20.0 for c in result.clips)
            lines = [
                f"Replay UUID: {result.replay_uuid}",
                f"Clips: {len(result.clips)} ({cine_s:.1f}s cinematic)",
                f"Teleport cuts skipped: {result.cuts}",
                f"Style: {style}",
                f"Wrote: {result.editor_state_path}",
                f"AI estimate: ${result.usage.estimate_usd():.4f}",
            ]
            if result.offline:
                lines.append("Mode: offline / heuristic")
            lines.append("Reopen the replay in Flashback to load the new editor state.")
            title = "Generation complete"
            details = "\n".join(lines)
        else:
            return
        self.query_one("#result-title", Label).update(title)
        self.query_one("#result-box", Static).update(details)
