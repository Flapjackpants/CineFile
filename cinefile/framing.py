"""Screen-space framing evaluation for synthesized camera clips."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Optional, Sequence, Tuple

from .camera import CameraClip, synthesize_clip
from .styles_loader import Style

FOV_Y_DEG = 70.0
ASPECT = 16.0 / 9.0
SUBJECT_HEIGHT = 1.0
CENTER_SIGMA = 0.15
THIRDS_SIGMA = 0.10
THIRDS_WEIGHT = 0.85
THIRDS_POINTS = ((1 / 3, 1 / 3), (2 / 3, 1 / 3), (1 / 3, 2 / 3), (2 / 3, 2 / 3))


@dataclass
class FramingStats:
    onscreen_frac: float
    center_frac: float
    thirds_frac: float
    score: float

    @property
    def near_frac(self) -> float:
        return self.center_frac + self.thirds_frac


def project_to_screen(
    cam: Tuple[float, float, float, float, float],
    point: Tuple[float, float, float],
    fov_y_deg: float = FOV_Y_DEG,
    aspect: float = ASPECT,
) -> Optional[Tuple[float, float]]:
    """Project a world point to (u, v); None if behind the camera."""
    cx, cy_, cz, yaw, pitch = cam
    sy, cyw = math.sin(math.radians(yaw)), math.cos(math.radians(yaw))
    sp, cp = math.sin(math.radians(pitch)), math.cos(math.radians(pitch))
    f = (-sy * cp, -sp, cyw * cp)
    r = (cyw, 0.0, sy)
    up = (-sp * sy, cp, sp * cyw)
    rel = (point[0] - cx, point[1] - cy_, point[2] - cz)
    depth = sum(a * b for a, b in zip(rel, f))
    if depth <= 0.05:
        return None
    xc = sum(a * b for a, b in zip(rel, r))
    yc = sum(a * b for a, b in zip(rel, up))
    th = math.tan(math.radians(fov_y_deg) / 2)
    return 0.5 + 0.5 * xc / (depth * th * aspect), 0.5 - 0.5 * yc / (depth * th)


def camera_at(
    clip: CameraClip, tick: int
) -> Tuple[float, float, float, float, float]:
    k0, k1 = clip.keyframes
    span = clip.end_tick - clip.start_tick
    a = (tick - clip.start_tick) / span if span else 0.0
    a = min(1.0, max(0.0, a))
    s = a * a * (3 - 2 * a)

    def lerp(p: float, q: float) -> float:
        return p + (q - p) * s

    return (
        lerp(k0.x, k1.x),
        lerp(k0.y, k1.y),
        lerp(k0.z, k1.z),
        lerp(k0.yaw, k1.yaw),
        lerp(k0.pitch, k1.pitch),
    )


def framing_stats(traj, clip: CameraClip, samples: int = 20) -> FramingStats:
    span = clip.end_tick - clip.start_tick
    n = on = cen = thi = 0
    total = 0.0
    for i in range(samples):
        t = round(clip.start_tick + i * span / (samples - 1))
        p = traj.sample(t)
        if p is None:
            continue
        n += 1
        uv = project_to_screen(camera_at(clip, t), (p.x, p.y + SUBJECT_HEIGHT, p.z))
        if uv is None or not (0 <= uv[0] <= 1 and 0 <= uv[1] <= 1):
            continue
        on += 1
        dc = math.dist(uv, (0.5, 0.5))
        dt = min(math.dist(uv, q) for q in THIRDS_POINTS)
        if dc <= CENTER_SIGMA:
            cen += 1
        elif dt <= THIRDS_SIGMA:
            thi += 1
        total += max(
            math.exp(-((dc / CENTER_SIGMA) ** 2)),
            THIRDS_WEIGHT * math.exp(-((dt / THIRDS_SIGMA) ** 2)),
        )
    if n == 0:
        return FramingStats(0, 0, 0, 0.0)
    return FramingStats(on / n, cen / n, thi / n, total / n)


def framing_multiplier(stats: FramingStats) -> float:
    m = 0.3 + 0.7 * stats.score
    if stats.near_frac < 0.5:
        m *= 0.5
    return m


def framing_note(stats: FramingStats, style_id: str) -> str:
    return (
        f"Framing ({style_id}): subject on screen {stats.onscreen_frac:.0%}, "
        f"centered {stats.center_frac:.0%}, on thirds {stats.thirds_frac:.0%}."
    )


def best_clip(
    traj, t0: int, t1: int, styles: Sequence[Style]
) -> Optional[Tuple[CameraClip, FramingStats]]:
    best: Optional[Tuple[CameraClip, FramingStats]] = None
    for style in styles:
        clip = synthesize_clip(traj, t0, t1, style)
        if clip is None:
            continue
        stats = framing_stats(traj, clip)
        if best is None or stats.score > best[1].score:
            best = (clip, stats)
    return best
