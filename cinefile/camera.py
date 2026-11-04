"""Camera clip synthesis from trajectory + style."""

from __future__ import annotations

import math
import statistics
from dataclasses import dataclass
from typing import Callable, List, Optional, Sequence, Tuple

from .replay import Pose, Trajectory, continuous_segments
from .styles_loader import Style

SPREAD_FLOOR_RATIO = 0.25  # slices whose best window is below this × median score count as idle

# Style IDs with two directions per window.
STRAIGHT_DOLLY = "straight-dolly"
TOP_DOWN = "top-down"
# Shortest and longest straight-dolly travel, in blocks.
STRAIGHT_MIN_TRAVEL = 2.0
STRAIGHT_MAX_TRAVEL = 5.0
# Share of the player's sideways movement a straight dolly follows.
STRAIGHT_SIDEWAYS_SHARE = 0.1
# How far a top-down camera drifts vertically between its two keys.
TOP_DOWN_DRIFT = 1.5
TOP_DOWN_PITCH = 90.0


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
    style_id: str = ""


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
    # Thinning by score drops whole stretches of the timeline, so widen the stride instead.
    segs = [(a, b) for a, b in continuous_segments(traj) if b - a >= clip_ticks]
    if max_candidates > len(segs):
        usable = sum(b - a - clip_ticks for a, b in segs)
        stride_ticks = max(stride_ticks, math.ceil(usable / (max_candidates - len(segs))))
    out: List[Tuple[int, int, float]] = []
    for seg_a, seg_b in segs:
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
    return out[:max_candidates]


def synthesize_clips(
    traj: Trajectory,
    t0: int,
    t1: int,
    style: Style,
    score: float = 0.0,
) -> List[CameraClip]:
    """Every clip a style makes for this window: forward then backward for the two directional styles."""
    p0 = traj.sample(t0)
    p1 = traj.sample(t1)
    if p0 is None or p1 is None:
        return []

    if style.thirds_side == "left":
        side = -1.0
    elif style.thirds_side == "right":
        side = 1.0
    else:
        # auto: put player on right third if moving +X-ish else left
        side = 1.0 if (p1.x - p0.x) >= 0 else -1.0

    # Locked shot yaw from average player facing (or start)
    shot_yaw = p0.yaw if style.yaw_lock else None

    if style.id == STRAIGHT_DOLLY:
        x0, y0, z0, yaw0, pitch0 = _offset_camera(p0, style, side, shot_yaw)
        yaw_rad = math.radians(yaw0)
        forward_x, forward_z = -math.sin(yaw_rad), math.cos(yaw_rad)
        right_x, right_z = math.cos(yaw_rad), math.sin(yaw_rad)
        dx, dz = p1.x - p0.x, p1.z - p0.z
        along = dx * forward_x + dz * forward_z
        sideways = dx * right_x + dz * right_z
        travel = min(
            STRAIGHT_MAX_TRAVEL, max(STRAIGHT_MIN_TRAVEL, abs(along) * style.dolly_scale)
        )
        clips = []
        for direction in (1.0, -1.0):
            end_x = x0 + forward_x * direction * travel + right_x * sideways * STRAIGHT_SIDEWAYS_SHARE
            end_z = z0 + forward_z * direction * travel + right_z * sideways * STRAIGHT_SIDEWAYS_SHARE
            clips.append(
                CameraClip(
                    t0,
                    t1,
                    (
                        CameraKeyframe(t0, x0, y0, z0, yaw0, pitch0),
                        CameraKeyframe(t1, end_x, y0, end_z, yaw0, pitch0),
                    ),
                    score=score,
                    style_id=style.id,
                )
            )
        return clips

    if style.id == TOP_DOWN:
        x = (p0.x + p1.x) / 2
        z = (p0.z + p1.z) / 2
        start_y = max(p0.y, p1.y) + style.height
        return [
            CameraClip(
                t0,
                t1,
                (
                    CameraKeyframe(t0, x, start_y, z, p0.yaw, TOP_DOWN_PITCH),
                    CameraKeyframe(t1, x, start_y - direction * TOP_DOWN_DRIFT, z, p0.yaw, TOP_DOWN_PITCH),
                ),
                score=score,
                style_id=style.id,
            )
            for direction in (1.0, -1.0)
        ]

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
    return [CameraClip(t0, t1, (kf0, kf1), score=score, style_id=style.id)]


def synthesize_clip(
    traj: Trajectory,
    t0: int,
    t1: int,
    style: Style,
    score: float = 0.0,
) -> Optional[CameraClip]:
    clips = synthesize_clips(traj, t0, t1, style, score=score)
    return clips[0] if clips else None


def select_clips_greedy(
    candidates: Sequence[Tuple[int, int, float]],
    traj: Trajectory,
    style: Style,
    target_ticks: int,
    min_gap_ticks: int = 0,
    synth: Optional[Callable[[int, int, float], Optional[CameraClip]]] = None,
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
        clip = (
            synth(t0, t1, score)
            if synth
            else synthesize_clip(traj, t0, t1, style, score=score)
        )
        if clip is None:
            continue
        picked.append(clip)
        used.append((t0, t1))
        total += t1 - t0
    picked.sort(key=lambda c: c.start_tick)
    return picked


def slice_candidates(candidates: Sequence[Tuple[int, int, float]], n: int) -> List[List[int]]:
    """n equal time slices over candidates' start ticks; each holds candidate indices, best score first."""
    slices: List[List[int]] = [[] for _ in range(n)]
    if not candidates:
        return slices
    lo = min(c[0] for c in candidates)
    hi = max(c[0] for c in candidates)
    for i, (t0, _, _) in enumerate(candidates):
        slices[(t0 - lo) * n // (hi - lo + 1)].append(i)
    for sl in slices:
        sl.sort(key=lambda i: candidates[i][2], reverse=True)
    return slices


def select_clips_spread(
    candidates: Sequence[Tuple[int, int, float]],
    traj: Trajectory,
    style: Style,
    target_ticks: int,
    synth: Optional[Callable[[int, int, float], Optional[CameraClip]]] = None,
) -> List[CameraClip]:
    """Best clip per time slice, then greedy fill for idle or empty slices."""
    if not candidates:
        return []
    clip_ticks = max(t1 - t0 for t0, t1, _ in candidates)
    n = max(1, target_ticks // clip_ticks)
    floor = SPREAD_FLOOR_RATIO * statistics.median(c[2] for c in candidates)
    picked: List[CameraClip] = []
    used: List[Tuple[int, int]] = []
    for sl in slice_candidates(candidates, n):
        for i in sl:
            t0, t1, score = candidates[i]
            if score < floor:
                break
            if any(not (t1 < a or t0 > b) for a, b in used):
                continue
            clip = synth(t0, t1, score) if synth else synthesize_clip(traj, t0, t1, style, score=score)
            if clip is None:
                continue
            picked.append(clip)
            used.append((t0, t1))
            break
    rest = [c for c in candidates if not any(not (c[1] < a or c[0] > b) for a, b in used)]
    remaining = target_ticks - sum(t1 - t0 for t0, t1 in used)
    fill = select_clips_greedy(rest, traj, style, target_ticks=remaining, synth=synth)
    return sorted(picked + fill, key=lambda c: c.start_tick)
