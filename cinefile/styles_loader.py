"""Style preset loading."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List

import yaml


@dataclass
class Style:
    id: str
    description: str
    distance: float
    height: float
    lateral: float
    pitch: float
    yaw_lock: bool
    orbit_degrees: float
    dolly_scale: float
    thirds_side: str  # auto | left | right


def _styles_dir() -> Path:
    return Path(__file__).resolve().parent / "styles"


def list_styles() -> List[str]:
    return sorted(p.stem for p in _styles_dir().glob("*.yaml"))


def load_style(style_id: str) -> Style:
    path = _styles_dir() / f"{style_id}.yaml"
    if not path.exists():
        known = ", ".join(list_styles())
        raise FileNotFoundError(f"Unknown style '{style_id}'. Available: {known}")
    raw = yaml.safe_load(path.read_text())
    return Style(
        id=raw["id"],
        description=raw.get("description", ""),
        distance=float(raw["distance"]),
        height=float(raw["height"]),
        lateral=float(raw["lateral"]),
        pitch=float(raw["pitch"]),
        yaw_lock=bool(raw.get("yaw_lock", True)),
        orbit_degrees=float(raw.get("orbit_degrees", 0.0)),
        dolly_scale=float(raw.get("dolly_scale", 1.0)),
        thirds_side=str(raw.get("thirds_side", "auto")),
    )


def styles_as_dict() -> Dict[str, str]:
    return {s: load_style(s).description for s in list_styles()}
