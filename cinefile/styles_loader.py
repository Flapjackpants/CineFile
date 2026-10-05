"""Style preset loading."""

from __future__ import annotations

import random
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Dict, List

import yaml

AUTO_STYLE_ID = "auto"


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


def style_choices() -> List[str]:
    return list_styles() + [AUTO_STYLE_ID]


def resolve_styles(style_id: str) -> List[Style]:
    if style_id == AUTO_STYLE_ID:
        return [load_style(s) for s in list_styles()]
    return [load_style(style_id)]


def style_variant(style: Style, index: int, t0: int) -> Style:
    """Deterministic jittered copy of a style; index 0 is the unchanged preset."""
    if index == 0:
        return style
    rng = random.Random(f"{style.id}:{t0}:{index}")
    return replace(
        style,
        distance=style.distance * rng.uniform(0.8, 1.25),
        height=style.height + rng.uniform(-1.0, 1.0),
        lateral=style.lateral * rng.uniform(0.6, 1.4),
        pitch=style.pitch + rng.uniform(-6.0, 6.0),
        orbit_degrees=style.orbit_degrees * rng.uniform(0.7, 1.3),
        dolly_scale=style.dolly_scale * rng.uniform(0.85, 1.15),
    )


def tries_per_style(tries: int, n_styles: int) -> int:
    return max(1, (tries + n_styles // 2) // n_styles)


def styles_as_dict() -> Dict[str, str]:
    out = {s: load_style(s).description for s in list_styles()}
    out[AUTO_STYLE_ID] = "Try every style for each clip and keep the best-framed one."
    return out
