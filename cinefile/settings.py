"""Persistent CineFile paths and generation defaults."""

from __future__ import annotations

import json
import math
import os
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Optional

from .styles_loader import style_choices


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
    "visual_review": False,
    "tries": 1,
}


def default_config_path() -> Path:
    xdg = os.environ.get("XDG_CONFIG_HOME")
    if xdg:
        return Path(xdg) / "cinefile" / "settings.json"
    return Path.home() / ".config" / "cinefile" / "settings.json"


@dataclass
class Settings:
    input_flashback_folder: Optional[str] = None
    output_flashback_folder: Optional[str] = None
    clip_length_s: float = 10.0
    style_id: str = "locked-dolly"
    duration_s: float = 180.0
    project: str = ""
    timelapse: bool = False
    offline: bool = False
    max_ai_usd: float = 0.05
    think: bool = False
    dry_run: bool = False
    visual_review: bool = False
    tries: int = 1

    def run_options(self) -> dict[str, Any]:
        return {key: getattr(self, key) for key in DEFAULT_RUN_OPTIONS}

    def input_dir(self) -> Optional[Path]:
        if not self.input_flashback_folder:
            return None
        return Path(self.input_flashback_folder).expanduser()

    def render_dir(self) -> Optional[Path]:
        if not self.output_flashback_folder:
            return None
        return normalize_output_flashback_folder(Path(self.output_flashback_folder))


def load_settings(path: Optional[Path] = None) -> Settings:
    cfg = path or default_config_path()
    if not cfg.exists():
        return Settings()
    try:
        data = json.loads(cfg.read_text())
    except (OSError, json.JSONDecodeError):
        return Settings()
    if not isinstance(data, dict):
        return Settings()
    try:
        return settings_from_dict(data, normalize_paths=False)
    except ValueError:
        return Settings()


def settings_from_dict(data: dict[str, Any], *, normalize_paths: bool = True) -> Settings:
    """Validate an edited settings object and return its normalized values."""
    # Legacy settings files used input_path, render_instance_path and output_path.
    raw_input = data.get("input_flashback_folder", data.get("input_path"))
    input_flashback_folder = _as_nullable_path(raw_input, "input_flashback_folder", normalize=normalize_paths)
    raw_output = data.get(
        "output_flashback_folder", data.get("render_instance_path", data.get("output_path"))
    )
    output_flashback_folder = _as_nullable_path(raw_output, "output_flashback_folder", normalize=normalize_paths)
    clip_length = _positive_number(data.get("clip_length_s", 10.0), "clip_length_s")
    duration = _positive_number(data.get("duration_s", 180.0), "duration_s")
    max_ai_usd = _nonnegative_number(data.get("max_ai_usd", 0.05), "max_ai_usd")
    tries = _positive_int(data.get("tries", 1), "tries")
    style = data.get("style_id", "locked-dolly")
    if not isinstance(style, str) or style not in style_choices():
        raise ValueError(f"style_id must be one of: {', '.join(style_choices())}")
    project = data.get("project", "")
    if not isinstance(project, str):
        raise ValueError("project must be a string")
    booleans = {}
    for key in ("timelapse", "offline", "think", "dry_run", "visual_review"):
        value = data.get(key, False)
        if not isinstance(value, bool):
            raise ValueError(f"{key} must be true or false")
        booleans[key] = value
    return Settings(
        input_flashback_folder=input_flashback_folder,
        output_flashback_folder=output_flashback_folder,
        clip_length_s=clip_length,
        style_id=style,
        duration_s=duration,
        project=project,
        max_ai_usd=max_ai_usd,
        tries=tries,
        **booleans,
    )


def _as_nullable_path(value: Any, key: str, *, normalize: bool = True) -> Optional[str]:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(f"{key} must be a path string or null")
    if not value.strip():
        return None
    if not normalize:
        return value.strip()
    path = Path(value.strip()).expanduser().resolve()
    if key == "input_flashback_folder":
        path = normalize_input_flashback_folder(path)
    else:
        path = normalize_output_flashback_folder(path)
    return str(path)


def _positive_number(value: Any, key: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{key} must be a positive number")
    result = float(value)
    if not math.isfinite(result) or result <= 0:
        raise ValueError(f"{key} must be a positive number")
    return result


def _positive_int(value: Any, key: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 100:
        raise ValueError(f"{key} must be an integer from 1 to 100")
    return value


def _nonnegative_number(value: Any, key: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{key} must be a nonnegative number")
    result = float(value)
    if not math.isfinite(result) or result < 0:
        raise ValueError(f"{key} must be a nonnegative number")
    return result


def save_settings(settings: Settings, path: Optional[Path] = None) -> Path:
    cfg = path or default_config_path()
    cfg.parent.mkdir(parents=True, exist_ok=True)
    payload: dict[str, Any] = asdict(settings)
    cfg.write_text(json.dumps(payload, indent=2) + "\n")
    return cfg


def expand_user_path(raw: str) -> Path:
    return Path(raw.strip()).expanduser().resolve()


def normalize_output_flashback_folder(path: Path) -> Path:
    """Map an instance root, flashback/ or editor_states/ path to the Flashback data dir."""
    p = path.expanduser().resolve()
    if p.name == "editor_states":
        return p.parent
    if p.name == "flashback":
        return p
    return p / "flashback"


def normalize_input_flashback_folder(path: Path) -> Path:
    """Prefer a directory that contains replay zips when given flashback/."""
    p = path.expanduser().resolve()
    if p.name == "flashback":
        replays = p / "replays"
        if replays.is_dir():
            return replays
    return p
