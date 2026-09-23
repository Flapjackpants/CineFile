"""Camera clip synthesis from trajectory + style."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import List, Optional, Sequence, Tuple

from .replay import Pose, Trajectory, continuous_segments
from .styles_loader import Style


@dataclass
class CameraKeyframe:
    tick: int
    x: float
    y: float
    z: float
    yaw: float
    pitch: float
    roll: float = 0.0


@dataclass
class CameraClip:
    start_tick: int
    end_tick: int
    keyframes: Tuple[CameraKeyframe, CameraKeyframe]
    score: float = 0.0
    phase_hint: str = ""


def _wrap_degrees(deg: float) -> float:
    return (deg + 180.0) % 360.0 - 180.0


def _look_yaw_pitch(from_pos: Tuple[float, float, float], to_pos: Tuple[float, float, float]) -> Tuple[float, float]:
    dx = to_pos[0] - from_pos[0]
    dy = to_pos[1] - from_pos[1]
    dz = to_pos[2] - from_pos[2]
    horiz = math.sqrt(dx * dx + dz * dz)
    yaw = -math.degrees(math.atan2(dx, dz))
    pitch = -math.degrees(math.atan2(dy, horiz)) if horiz > 1e-6 else 0.0
    return yaw, pitch


def _offset_camera(
    player: Pose,
    style: Style,
    side_sign: float,
    yaw_override: Optional[float] = None,
) -> Tuple[float, float, float, float, float]:
    """Place camera behind + lateral to player; return x,y,z,yaw,pitch."""
    base_yaw = yaw_override if yaw_override is not None else player.yaw
    yaw_rad = math.radians(base_yaw)
    # Minecraft: yaw 0 looks +Z; increasing yaw turns left (negative X from +Z)
    forward_x = -math.sin(yaw_rad)
    forward_z = math.cos(yaw_rad)
    right_x = math.cos(yaw_rad)
    right_z = math.sin(yaw_rad)

    cx = player.x - forward_x * style.distance + right_x * style.lateral * side_sign
    cy = player.y + style.height + 1.62  # roughly eye height reference
    cz = player.z - forward_z * style.distance + right_z * style.lateral * side_sign

    # Look slightly ahead of player so they land on a thirds line
    look_x = player.x + forward_x * 2.0
    look_y = player.y + 1.4
    look_z = player.z + forward_z * 2.0
    yaw, pitch = _look_yaw_pitch((cx, cy, cz), (look_x, look_y, look_z))
    pitch = style.pitch if style.yaw_lock else pitch
    if style.yaw_lock:
        # Keep framing yaw stable: face toward look target but we'll lock later
        pass
    return cx, cy, cz, yaw, pitch


def activity_score(traj: Trajectory, t0: int, t1: int) -> float:
    poses = [traj.poses[t] for t in range(t0, t1 + 1) if t in traj.poses]
    if len(poses) < 2:
        return 0.0
    dist = sum(poses[i].dist(poses[i + 1]) for i in range(len(poses) - 1))
    yaw_delta = sum(
        abs(_wrap_degrees(poses[i + 1].yaw - poses[i].yaw)) for i in range(len(poses) - 1)
    )
    # Prefer moderate movement (building) over standing still or sprinting endlessly
    move = min(dist, 40.0)
    turn = min(yaw_delta, 180.0)
    dwell = 1.0 / (1.0 + abs(dist - 8.0))
    return move * 0.5 + turn * 0.05 + dwell * 5.0


def candidate_windows(
    traj: Trajectory,
    clip_ticks: int,
    stride_ticks: Optional[int] = None,
    max_candidates: int = 500,
) -> List[Tuple[int, int, float]]:
    if stride_ticks is None:
        stride_ticks = max(10, clip_ticks // 4)
    out: List[Tuple[int, int, float]] = []
    for seg_a, seg_b in continuous_segments(traj):
        if seg_b - seg_a < clip_ticks:
            continue
        t = seg_a
        while t + clip_ticks <= seg_b:
            t1 = t + clip_ticks
            # reject if any cut inside
            if any(t < c <= t1 for c in traj.cuts):
                t += stride_ticks
                continue
            score = activity_score(traj, t, t1)
            out.append((t, t1, score))
            t += stride_ticks
    out.sort(key=lambda x: x[2], reverse=True)
    if len(out) > max_candidates:
        # keep top half by score, then subsample rest evenly for coverage
        top = out[: max_candidates // 2]
        rest = out[max_candidates // 2 :]
        step = max(1, len(rest) // max(1, max_candidates - len(top)))
        out = top + rest[::step]
        out = out[:max_candidates]
    return out


def synthesize_clip(
    traj: Trajectory,
    t0: int,
    t1: int,
    style: Style,
    score: float = 0.0,
) -> Optional[CameraClip]:
    p0 = traj.sample(t0)
    p1 = traj.sample(t1)
    if p0 is None or p1 is None:
        return None

    if style.thirds_side == "left":
        side = -1.0
    elif style.thirds_side == "right":
        side = 1.0
    else:
        # auto: put player on right third if moving +X-ish else left
        side = 1.0 if (p1.x - p0.x) >= 0 else -1.0

    # Locked shot yaw from average player facing (or start)
    shot_yaw = p0.yaw if style.yaw_lock else None

    x0, y0, z0, yaw0, pitch0 = _offset_camera(p0, style, side, shot_yaw)
    x1, y1, z1, yaw1, pitch1 = _offset_camera(p1, style, side, shot_yaw)

    if style.yaw_lock:
        yaw1 = yaw0
        pitch0 = style.pitch
        pitch1 = style.pitch
    else:
        # orbit: rotate yaw across clip
        yaw1 = _wrap_degrees(yaw0 + style.orbit_degrees * side)

    # Apply mild dolly by blending positions along look axis
    scale = style.dolly_scale
    x1 = x0 + (x1 - x0) * scale
    y1 = y0 + (y1 - y0) * scale
    z1 = z0 + (z1 - z0) * scale

    kf0 = CameraKeyframe(t0, x0, y0, z0, yaw0, pitch0)
    kf1 = CameraKeyframe(t1, x1, y1, z1, yaw1, pitch1)
    return CameraClip(t0, t1, (kf0, kf1), score=score)


def select_clips_greedy(
    candidates: Sequence[Tuple[int, int, float]],
    traj: Trajectory,
    style: Style,
    target_ticks: int,
    min_gap_ticks: int = 0,
) -> List[CameraClip]:
    """Pick non-overlapping high-score windows until duration budget filled."""
    picked: List[CameraClip] = []
    used: List[Tuple[int, int]] = []
    total = 0
    for t0, t1, score in sorted(candidates, key=lambda c: c[2], reverse=True):
        if total >= target_ticks:
            break
        if any(not (t1 + min_gap_ticks < a or t0 - min_gap_ticks > b) for a, b in used):
            continue
        clip = synthesize_clip(traj, t0, t1, style, score=score)
        if clip is None:
            continue
        picked.append(clip)
        used.append((t0, t1))
        total += t1 - t0
    picked.sort(key=lambda c: c.start_tick)
    return picked
