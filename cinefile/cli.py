"""CineFile command-line interface."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import __version__
from .editor import normalize_editor_dir
from .menu import run_menu
from .pipeline import run
from .settings import load_settings
from .styles_loader import list_styles, styles_as_dict


def build_parser() -> argparse.ArgumentParser:
    styles = list_styles()
    p = argparse.ArgumentParser(
        prog="cinefile",
        description=(
            "Automate Flashback cinematic camera + optional 1s timelapse gaps. "
            "Writes editor_states/{uuid}.json — reopen the replay in Flashback to load. "
            "Run with no arguments for an interactive menu."
        ),
    )
    p.add_argument("--version", action="version", version=f"cinefile {__version__}")
    p.add_argument(
        "--replay",
        type=Path,
        default=None,
        help="Path to Flashback replay .zip (omit to open interactive menu)",
    )
    p.add_argument(
        "--clip-length",
        type=float,
        default=10.0,
        help="Length of each cinematic shot in seconds (default: 10)",
    )
    p.add_argument(
        "--style",
        choices=styles,
        default="locked-dolly",
        help="Pre-made camera style preset",
    )
    p.add_argument(
        "--duration",
        type=float,
        default=180.0,
        help="Target total cinematic duration in seconds (sum of clips)",
    )
    p.add_argument(
        "--project",
        default="",
        help="Optional description to guide representative clip picking",
    )
    p.add_argument(
        "--timelapse",
        action="store_true",
        help="Fill gaps between clips with 1-second (0→20 tick) timelapse tracks",
    )
    p.add_argument(
        "--editor-dir",
        type=Path,
        default=None,
        help="Flashback data dir (…/flashback). Default: settings output or sibling of replays/",
    )
    p.add_argument(
        "--offline",
        action="store_true",
        help="Skip DeepSeek/Jev; use heuristic selection only",
    )
    p.add_argument(
        "--max-ai-usd",
        type=float,
        default=0.05,
        help="Abort further AI calls once estimated spend exceeds this (default 0.05)",
    )
    p.add_argument(
        "--think",
        action="store_true",
        help="Allow higher DeepSeek reasoning effort (costs more)",
    )
    p.add_argument(
        "--dry-run",
        action="store_true",
        help="Write *.json.dry_run instead of replacing editor state",
    )
    p.add_argument(
        "--list-styles",
        action="store_true",
        help="Print available styles and exit",
    )
    return p


def _print_result(result, style: str, offline_flag: bool) -> None:
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


def _resolve_editor_dir_arg(editor_dir: Path | None) -> Path | None:
    if editor_dir is not None:
        return normalize_editor_dir(editor_dir)
    settings = load_settings()
    if settings.output_path:
        return normalize_editor_dir(Path(settings.output_path).expanduser())
    return None


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    # Allow --list-styles without --replay
    if "--list-styles" in argv:
        for sid, desc in styles_as_dict().items():
            print(f"{sid}: {desc}")
        return 0

    # No args → interactive menu
    if not argv:
        return run_menu()

    parser = build_parser()
    args = parser.parse_args(argv)

    run_kwargs = {
        "clip_length_s": args.clip_length,
        "style_id": args.style,
        "duration_s": args.duration,
        "project": args.project,
        "timelapse": args.timelapse,
        "offline": args.offline,
        "max_ai_usd": args.max_ai_usd,
        "think": args.think,
        "dry_run": args.dry_run,
    }

    # Flags without --replay → menu, carrying flag defaults
    if args.replay is None:
        return run_menu(run_kwargs=run_kwargs)

    if not args.replay.exists():
        print(f"error: replay not found: {args.replay}", file=sys.stderr)
        return 1

    editor_dir = _resolve_editor_dir_arg(args.editor_dir)

    try:
        result = run(
            args.replay,
            editor_dir=editor_dir,
            **run_kwargs,
        )
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    _print_result(result, args.style, args.offline)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
