"""Textual interface for browsing replays and generating CineFile edits."""

from __future__ import annotations

import asyncio
import math
from pathlib import Path
from typing import Any

from textual import work
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Grid, Horizontal, Vertical, VerticalScroll
from textual.screen import Screen
from textual.widgets import (
    Button,
    Checkbox,
    DataTable,
    Footer,
    Header,
    Input,
    Label,
    Select,
    Static,
)

from .menu import list_replay_zips
from .pipeline import RunResult, run
from .editor import normalize_editor_dir
from .settings import (
    Settings,
    expand_user_path,
    load_settings,
    normalize_input_dir,
    normalize_output_dir,
    save_settings,
)
from .styles_loader import list_styles


DEFAULT_RUN_OPTIONS: dict[str, Any] = {
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
    #button-row { height: auto; align-horizontal: right; }
    #button-row Button { margin-left: 1; }
    .form-row { height: auto; margin-bottom: 1; }
    .form-row Label { width: 24; padding-top: 1; }
    .form-row Input, .form-row Select { width: 1fr; }
    .check-row { height: 1; width: 1fr; }
    #option-grid {
        grid-size: 2 3;
        grid-columns: 1fr 1fr;
        grid-gutter: 0 2;
        height: auto;
    }
    .option-field { height: 4; }
    .option-field Label { height: 1; }
    .option-field Input, .option-field Select { height: 3; }
    #message { height: auto; margin: 1 0; }
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
        self.run_options = {**DEFAULT_RUN_OPTIONS, **(run_options or {})}
        self.editor_dir = editor_dir

    def on_mount(self) -> None:
        self.push_screen(ReplayScreen())


class ShellScreen(Screen[None]):
    """Screen base for CineFile's shared keyboard driven shell."""


class ReplayScreen(ShellScreen):
    BINDINGS = [
        Binding("enter", "configure", "Configure run"),
        Binding("r", "configure", "Run options"),
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
                with Horizontal(id="button-row"):
                    yield Button("Run options", id="configure", variant="primary")
                    yield Button("Settings", id="settings")
                    yield Button("Quit", id="quit")
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

    def action_configure(self) -> None:
        replay = self.selected_replay()
        if replay is None:
            self.notify("Select a replay first.", severity="warning")
            return
        self.app.push_screen(RunOptionsScreen(replay))

    def action_settings(self) -> None:
        self.app.push_screen(SettingsScreen())

    def on_data_table_row_selected(self, event: DataTable.RowSelected) -> None:
        self.action_configure()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "configure":
            self.action_configure()
        elif event.button.id == "settings":
            self.action_settings()
        elif event.button.id == "quit":
            self.app.exit(0)

    def on_screen_resume(self) -> None:
        self.refresh_replays()


class SettingsScreen(ShellScreen):
    BINDINGS = [Binding("escape", "cancel", "Back")]

    def compose(self) -> ComposeResult:
        settings = self.app.settings
        with Vertical():
            yield Header(show_clock=True)
            yield Static("", id="title-art")
            with VerticalScroll(id="content"):
                yield Label("Settings")
                with Horizontal(classes="form-row"):
                    yield Label("Replay input folder")
                    yield Input(settings.input_path or "", id="input-path", placeholder="~/.../flashback/replays")
                with Horizontal(classes="form-row"):
                    yield Label("Flashback output folder")
                    yield Input(settings.output_path or "", id="output-path", placeholder="~/.../flashback")
                yield Static("Paths are saved in ~/.config/cinefile/settings.json.", id="message")
                with Horizontal(id="button-row"):
                    yield Button("Save", id="save", variant="primary")
                    yield Button("Cancel", id="cancel")
            yield Static("", id="status-bar")
            yield Footer()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "cancel":
            self.app.pop_screen()
            return
        if event.button.id != "save":
            return
        input_raw = self.query_one("#input-path", Input).value.strip()
        output_raw = self.query_one("#output-path", Input).value.strip()
        try:
            input_path = str(normalize_input_dir(expand_user_path(input_raw))) if input_raw else None
            output_path = str(normalize_output_dir(expand_user_path(output_raw))) if output_raw else None
        except OSError as exc:
            self.notify(f"Invalid path: {exc}", severity="error")
            return
        settings = Settings(input_path=input_path, output_path=output_path)
        try:
            save_settings(settings)
        except OSError as exc:
            self.notify(f"Could not save settings: {exc}", severity="error")
            return
        self.app.settings = settings
        self.notify("Settings saved.")
        self.app.pop_screen()

    def action_cancel(self) -> None:
        self.app.pop_screen()


class RunOptionsScreen(ShellScreen):
    BINDINGS = [Binding("escape", "cancel", "Back")]

    def __init__(self, replay: Path) -> None:
        super().__init__()
        self.replay = replay

    def compose(self) -> ComposeResult:
        opts = self.app.run_options
        with Vertical():
            yield Header(show_clock=True)
            yield Static("", id="title-art")
            with VerticalScroll(id="content"):
                yield Label(f"Run options — {self.replay.name}")
                with Grid(id="option-grid"):
                    with Vertical(classes="option-field"):
                        yield Label("Clip length (seconds)")
                        yield Input(str(opts["clip_length_s"]), id="clip-length", type="number")
                    with Vertical(classes="option-field"):
                        yield Label("Camera style")
                        yield Select(
                            [(style, style) for style in list_styles()],
                            value=opts["style_id"],
                            id="style",
                            allow_blank=False,
                        )
                    with Vertical(classes="option-field"):
                        yield Label("Target duration (seconds)")
                        yield Input(str(opts["duration_s"]), id="duration", type="number")
                    with Vertical(classes="option-field"):
                        yield Label("Project description")
                        yield Input(str(opts["project"]), id="project")
                    with Vertical(classes="option-field"):
                        yield Label("Maximum AI spend (USD)")
                        yield Input(str(opts["max_ai_usd"]), id="max-ai-usd", type="number")
                with Horizontal():
                    yield Checkbox("Fill gaps with timelapse", value=opts["timelapse"], id="timelapse", classes="check-row")
                    yield Checkbox("Offline / heuristic selection", value=opts["offline"], id="offline", classes="check-row")
                with Horizontal():
                    yield Checkbox("Allow higher reasoning effort", value=opts["think"], id="think", classes="check-row")
                    yield Checkbox("Dry run (.dry_run file)", value=opts["dry_run"], id="dry-run", classes="check-row")
                with Horizontal(id="button-row"):
                    yield Button("Generate edits", id="run", variant="primary")
                    yield Button("Cancel", id="cancel")
            yield Static("", id="status-bar")
            yield Footer()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "cancel":
            self.app.pop_screen()
            return
        if event.button.id != "run":
            return
        try:
            clip_length = float(self.query_one("#clip-length", Input).value)
            duration = float(self.query_one("#duration", Input).value)
            max_ai_usd = float(self.query_one("#max-ai-usd", Input).value)
            if (
                not math.isfinite(clip_length)
                or not math.isfinite(duration)
                or not math.isfinite(max_ai_usd)
                or clip_length <= 0
                or duration <= 0
                or max_ai_usd < 0
            ):
                raise ValueError
        except ValueError:
            self.notify("Clip length and duration must be positive; AI spend must be nonnegative.", severity="error")
            return
        options = {
            "clip_length_s": clip_length,
            "style_id": self.query_one("#style", Select).value,
            "duration_s": duration,
            "project": self.query_one("#project", Input).value,
            "timelapse": self.query_one("#timelapse", Checkbox).value,
            "offline": self.query_one("#offline", Checkbox).value,
            "max_ai_usd": max_ai_usd,
            "think": self.query_one("#think", Checkbox).value,
            "dry_run": self.query_one("#dry-run", Checkbox).value,
        }
        self.app.run_options = options
        result_screen = ResultScreen(self.replay, running=True)
        self.app.push_screen(result_screen)
        self._start_run(options, result_screen)

    @work(exclusive=True)
    async def _start_run(self, options: dict[str, Any], result_screen: "ResultScreen") -> None:
        try:
            result = await asyncio.to_thread(
                run,
                self.replay,
                editor_dir=self.app.editor_dir or self._settings_editor_dir(),
                **options,
            )
            await asyncio.sleep(0.01)
            result_screen.show_result(result=result, style=options["style_id"])
        except Exception as exc:
            await asyncio.sleep(0.01)
            result_screen.show_result(error=str(exc))

    def _settings_editor_dir(self) -> Path | None:
        output_path = self.app.settings.output_path
        if not output_path:
            return None
        return normalize_editor_dir(Path(output_path).expanduser())

    def action_cancel(self) -> None:
        self.app.pop_screen()


class ResultScreen(ShellScreen):
    BINDINGS = [Binding("escape", "back", "Back"), Binding("enter", "back", "Back")]

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
                    yield Label("Generation failed", classes="error")
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
                with Horizontal(id="button-row"):
                    yield Button("Back to replays", id="back", variant="primary", disabled=self.running)
                    yield Button("Quit", id="quit")
            yield Static("", id="status-bar")
            yield Footer()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "back":
            self.app.pop_screen()
        elif event.button.id == "quit":
            self.app.exit(0)

    def action_back(self) -> None:
        if not self.running:
            self.app.pop_screen()

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
        self.query_one("#back", Button).disabled = False
