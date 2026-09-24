"""Persistent CineFile paths and generation defaults."""

from __future__ import annotations

import json
import math
import os
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Optional

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


def default_config_path() -> Path:
    xdg = os.environ.get("XDG_CONFIG_HOME")
    if xdg:
        return Path(xdg) / "cinefile" / "settings.json"
    return Path.home() / ".config" / "cinefile" / "settings.json"


@dataclass
class Settings:
    input_path: Optional[str] = None
    output_path: Optional[str] = None
    clip_length_s: float = 10.0
    style_id: str = "locked-dolly"
    duration_s: float = 180.0
    project: str = ""
    timelapse: bool = False
    offline: bool = False
    max_ai_usd: float = 0.05
    think: bool = False
    dry_run: bool = False

    def run_options(self) -> dict[str, Any]:
        return {key: getattr(self, key) for key in DEFAULT_RUN_OPTIONS}

    def input_dir(self) -> Optional[Path]:
        if not self.input_path:
            return None
        return Path(self.input_path).expanduser()

    def output_dir(self) -> Optional[Path]:
        if not self.output_path:
            return None
        return Path(self.output_path).expanduser()


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
    input_path = _as_nullable_path(data.get("input_path"), "input_path", normalize=normalize_paths)
    output_path = _as_nullable_path(data.get("output_path"), "output_path", normalize=normalize_paths)
    clip_length = _positive_number(data.get("clip_length_s", 10.0), "clip_length_s")
    duration = _positive_number(data.get("duration_s", 180.0), "duration_s")
    max_ai_usd = _nonnegative_number(data.get("max_ai_usd", 0.05), "max_ai_usd")
    style = data.get("style_id", "locked-dolly")
    if not isinstance(style, str) or style not in list_styles():
        raise ValueError(f"style_id must be one of: {', '.join(list_styles())}")
    project = data.get("project", "")
    if not isinstance(project, str):
        raise ValueError("project must be a string")
    booleans = {}
    for key in ("timelapse", "offline", "think", "dry_run"):
        value = data.get(key, False)
        if not isinstance(value, bool):
            raise ValueError(f"{key} must be true or false")
        booleans[key] = value
    return Settings(
        input_path=input_path,
        output_path=output_path,
        clip_length_s=clip_length,
        style_id=style,
        duration_s=duration,
        project=project,
        max_ai_usd=max_ai_usd,
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
    if key == "input_path":
        path = normalize_input_dir(path)
    else:
        path = normalize_output_dir(path)
    return str(path)


def _positive_number(value: Any, key: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{key} must be a positive number")
    result = float(value)
    if not math.isfinite(result) or result <= 0:
        raise ValueError(f"{key} must be a positive number")
    return result


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


def normalize_output_dir(path: Path) -> Path:
    """Map a user output path to the Flashback data dir (parent of editor_states)."""
    p = path.expanduser().resolve()
    if p.name == "editor_states":
        return p.parent
    return p


def normalize_input_dir(path: Path) -> Path:
    """Prefer a directory that contains replay zips when given flashback/."""
    p = path.expanduser().resolve()
    if p.name == "flashback":
        replays = p / "replays"
        if replays.is_dir():
            return replays
    return p
