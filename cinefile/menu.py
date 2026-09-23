"""Interactive numbered menus for CineFile."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Callable, Optional

from .pipeline import RunResult, run
from .settings import (
    Settings,
    expand_user_path,
    load_settings,
    normalize_input_dir,
    normalize_output_dir,
    save_settings,
)

RunKwargs = dict[str, Any]


def _prompt(msg: str) -> str:
    try:
        return input(msg)
    except EOFError:
        return ""


def _choose(prompt: str, options: list[tuple[str, Callable[[], Optional[str]]]]) -> None:
    """Show a numbered menu until Back is chosen."""
    while True:
        print()
        print(prompt)
        for i, (label, _) in enumerate(options, start=1):
            print(f"  {i}. {label}")
        raw = _prompt("Choice: ").strip()
        if not raw:
            continue
        try:
            idx = int(raw)
        except ValueError:
            print("Enter a number.")
            continue
        if idx < 1 or idx > len(options):
            print("Invalid choice.")
            continue
        action = options[idx - 1][1]()
        if action == "back":
            return


def _fmt_path(value: Optional[str]) -> str:
    return value if value else "(not set)"


def _header(settings: Settings) -> None:
    print()
    print("=== CineFile ===")
    print(f"  Input:  {_fmt_path(settings.input_path)}")
    print(f"  Output: {_fmt_path(settings.output_path)}")


def list_replay_zips(input_dir: Path) -> list[Path]:
    """List .zip files in input_dir, plus one level of subdirs."""
    if not input_dir.is_dir():
        return []
    found: list[Path] = []
    for p in sorted(input_dir.iterdir()):
        if p.is_file() and p.suffix.lower() == ".zip":
            found.append(p)
        elif p.is_dir():
            for child in sorted(p.iterdir()):
                if child.is_file() and child.suffix.lower() == ".zip":
                    found.append(child)
    return found


def _set_path(settings: Settings, field: str, label: str, config_path: Optional[Path]) -> None:
    raw = _prompt(f"Enter {label} pathname: ").strip()
    if not raw:
        print("Cancelled (empty path).")
        return
    try:
        path = expand_user_path(raw)
    except OSError as exc:
        print(f"Invalid path: {exc}")
        return
    if field == "input_path":
        path = normalize_input_dir(path)
        settings.input_path = str(path)
    else:
        path = normalize_output_dir(path)
        settings.output_path = str(path)
    if not path.exists():
        print(f"Warning: path does not exist yet: {path}")
    elif not path.is_dir():
        print(f"Warning: path is not a directory: {path}")
    save_settings(settings, config_path)
    print(f"Saved {label}: {path}")


def _settings_menu(settings: Settings, config_path: Optional[Path]) -> None:
    def set_input() -> Optional[str]:
        _set_path(settings, "input_path", "Flashback input", config_path)
        return None

    def set_output() -> Optional[str]:
        _set_path(settings, "output_path", "Flashback output", config_path)
        return None

    def show() -> Optional[str]:
        print(f"  Input:  {_fmt_path(settings.input_path)}")
        print(f"  Output: {_fmt_path(settings.output_path)}")
        cfg = config_path
        if cfg is None:
            from .settings import default_config_path

            cfg = default_config_path()
        print(f"  File:   {cfg}")
        return None

    def clear_input() -> Optional[str]:
        settings.input_path = None
        save_settings(settings, config_path)
        print("Cleared input path.")
        return None

    def clear_output() -> Optional[str]:
        settings.output_path = None
        save_settings(settings, config_path)
        print("Cleared output path.")
        return None

    def back() -> Optional[str]:
        return "back"

    _choose(
        "--- Settings ---",
        [
            ("Set Flashback input path", set_input),
            ("Set Flashback output path", set_output),
            ("Show current paths", show),
            ("Clear input path", clear_input),
            ("Clear output path", clear_output),
            ("Back", back),
        ],
    )


def _pick_replay(settings: Settings) -> Optional[Path]:
    if not settings.input_path:
        print("Set an input path in Settings first.")
        return None
    input_dir = normalize_input_dir(Path(settings.input_path).expanduser())
    if not input_dir.is_dir():
        print(f"Input path is not a directory: {input_dir}")
        return None
    zips = list_replay_zips(input_dir)
    if not zips:
        print(f"No .zip replays found under {input_dir}")
        return None
    print()
    print("Select a replay:")
    for i, z in enumerate(zips, start=1):
        try:
            rel = z.relative_to(input_dir)
        except ValueError:
            rel = z
        print(f"  {i}. {rel}")
    print("  0. Cancel")
    raw = _prompt("Choice: ").strip()
    if raw == "0" or not raw:
        return None
    try:
        idx = int(raw)
    except ValueError:
        print("Enter a number.")
        return None
    if idx < 1 or idx > len(zips):
        print("Invalid choice.")
        return None
    return zips[idx - 1]


def _print_result(result: RunResult, style: str, offline_flag: bool) -> None:
    cine_s = sum((c.end_tick - c.start_tick) / 20.0 for c in result.clips)
    print(f"replay uuid:  {result.replay_uuid}")
    print(f"clips:        {len(result.clips)} ({cine_s:.1f}s cinematic)")
    print(f"teleport cuts skipped: {result.cuts}")
    print(f"style:        {style}")
    print(f"wrote:        {result.editor_state_path}")
    print(
        f"AI estimate:  ${result.usage.estimate_usd():.4f} "
        f"(ds in/out={result.usage.deepseek_in}/{result.usage.deepseek_out}, "
        f"jev in={result.usage.jev_in})"
    )
    if result.offline or offline_flag:
        print("mode:         offline / heuristic")
    print()
    print("Reopen the replay in Flashback to load the new editor state.")


def _create_edits(settings: Settings, run_kwargs: RunKwargs) -> None:
    replay = _pick_replay(settings)
    if replay is None:
        return
    editor_dir = None
    if settings.output_path:
        editor_dir = normalize_output_dir(Path(settings.output_path).expanduser())
    kwargs = dict(run_kwargs)
    kwargs["editor_dir"] = editor_dir
    try:
        result = run(replay, **kwargs)
    except Exception as exc:
        print(f"error: {exc}")
        return
    _print_result(result, kwargs.get("style_id", "locked-dolly"), kwargs.get("offline", False))


def run_menu(
    *,
    run_kwargs: Optional[RunKwargs] = None,
    config_path: Optional[Path] = None,
) -> int:
    """Interactive main loop. Returns process exit code."""
    settings = load_settings(config_path)
    kwargs = run_kwargs or {}
    # Defaults for interactive runs if not provided by CLI flags
    kwargs.setdefault("clip_length_s", 10.0)
    kwargs.setdefault("style_id", "locked-dolly")
    kwargs.setdefault("duration_s", 180.0)
    kwargs.setdefault("project", "")
    kwargs.setdefault("timelapse", False)
    kwargs.setdefault("offline", False)
    kwargs.setdefault("max_ai_usd", 0.05)
    kwargs.setdefault("think", False)
    kwargs.setdefault("dry_run", False)

    while True:
        _header(settings)

        def create() -> Optional[str]:
            _create_edits(settings, kwargs)
            return None

        def settings_action() -> Optional[str]:
            _settings_menu(settings, config_path)
            # reload in case file was edited; keep in-memory object already updated
            return None

        def quit_menu() -> Optional[str]:
            return "quit"

        print()
        print("Main menu")
        print("  1. Create flashback edits")
        print("  2. Settings")
        print("  3. Quit")
        raw = _prompt("Choice: ").strip()
        if raw == "1":
            create()
        elif raw == "2":
            settings_action()
        elif raw == "3":
            return 0
        elif not raw:
            continue
        else:
            print("Invalid choice.")
