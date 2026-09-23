"""Persistent CineFile settings (input/output paths)."""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Optional


def default_config_path() -> Path:
    xdg = os.environ.get("XDG_CONFIG_HOME")
    if xdg:
        return Path(xdg) / "cinefile" / "settings.json"
    return Path.home() / ".config" / "cinefile" / "settings.json"


@dataclass
class Settings:
    input_path: Optional[str] = None
    output_path: Optional[str] = None

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
    return Settings(
        input_path=_as_optional_str(data.get("input_path")),
        output_path=_as_optional_str(data.get("output_path")),
    )


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


def _as_optional_str(value: Any) -> Optional[str]:
    if value is None:
        return None
    s = str(value).strip()
    return s or None
