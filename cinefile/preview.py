"""PNG previews of a camera clip, ray-cast through replay blocks."""

from __future__ import annotations

import math
import struct
import zlib
from typing import List, Optional, Tuple

from .framing import ASPECT, FOV_Y_DEG, camera_at
from .replay import Pose

PREVIEW_W = 128
PREVIEW_H = 72
MAX_DIST = 32.0
PLAYER_HALF_WIDTH = 0.3
PLAYER_HEIGHT = 1.8
SKY_RGB = (150, 200, 240)
PLAYER_RGB = (230, 40, 40)
INSIDE_RGB = (25, 25, 25)


def block_rgb(state_id: int) -> Tuple[int, int, int]:
    h = (state_id * 2654435761) & 0xFFFFFFFF
    return (64 + (h & 0x7F), 64 + ((h >> 8) & 0x7F), 64 + ((h >> 16) & 0x7F))


def _player_dist(
    origin: Tuple[float, float, float], d: Tuple[float, float, float], p: Pose
) -> Optional[float]:
    lo = (p.x - PLAYER_HALF_WIDTH, p.y, p.z - PLAYER_HALF_WIDTH)
    hi = (p.x + PLAYER_HALF_WIDTH, p.y + PLAYER_HEIGHT, p.z + PLAYER_HALF_WIDTH)
    t_near, t_far = 0.0, math.inf
    for a in range(3):
        if abs(d[a]) < 1e-12:
            if not lo[a] <= origin[a] <= hi[a]:
                return None
            continue
        t1, t2 = (lo[a] - origin[a]) / d[a], (hi[a] - origin[a]) / d[a]
        t_near = max(t_near, min(t1, t2))
        t_far = min(t_far, max(t1, t2))
        if t_near > t_far:
            return None
    return t_near


def render_rgb(
    world,
    cam: Tuple[float, float, float, float, float],
    subject: Optional[Pose],
    tick: int,
    w: int = PREVIEW_W,
    h: int = PREVIEW_H,
    fov_y_deg: float = FOV_Y_DEG,
) -> bytes:
    cx, cy_, cz, yaw, pitch = cam
    sy, cyw = math.sin(math.radians(yaw)), math.cos(math.radians(yaw))
    sp, cp = math.sin(math.radians(pitch)), math.cos(math.radians(pitch))
    f = (-sy * cp, -sp, cyw * cp)
    r = (cyw, 0.0, sy)
    up = (-sp * sy, cp, sp * cyw)
    th = math.tan(math.radians(fov_y_deg) / 2)
    origin = (cx, cy_, cz)
    out = bytearray()
    for j in range(h):
        sv = (1 - 2 * (j + 0.5) / h) * th
        for i in range(w):
            su = (2 * (i + 0.5) / w - 1) * th * ASPECT
            d = tuple(f[a] + su * r[a] + sv * up[a] for a in range(3))
            hit = world.raycast(origin, d, MAX_DIST, tick)
            tp = _player_dist(origin, d, subject) if subject is not None else None
            if tp is not None and (hit is None or tp < hit.dist):
                rgb = PLAYER_RGB
            elif hit is None:
                rgb = SKY_RGB
            elif hit.axis == -1:
                rgb = INSIDE_RGB
            else:
                if hit.axis == 0:
                    face = 0.8
                elif hit.axis == 2:
                    face = 0.9
                elif d[1] < 0:
                    face = 1.0
                else:
                    face = 0.6
                depth = 1 - 0.6 * min(1.0, hit.dist / MAX_DIST)
                rgb = tuple(int(c * face * depth) for c in block_rgb(hit.state_id))
            out += bytes(rgb)
    return bytes(out)


def _chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def encode_png(rgb: bytes, w: int, h: int) -> bytes:
    stride = w * 3
    raw = b"".join(b"\x00" + rgb[y * stride : (y + 1) * stride] for y in range(h))
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
        + _chunk(b"IDAT", zlib.compress(raw))
        + _chunk(b"IEND", b"")
    )


def clip_previews(world, traj, clip) -> List[bytes]:
    ticks = (clip.start_tick, (clip.start_tick + clip.end_tick) // 2, clip.end_tick)
    return [
        encode_png(
            render_rgb(world, camera_at(clip, t), traj.sample(t), t), PREVIEW_W, PREVIEW_H
        )
        for t in ticks
    ]
