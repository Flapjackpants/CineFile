"""Parse Flashback replay zips into a per-tick player trajectory."""

from __future__ import annotations

import json
import math
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from .bufio import ByteReader

MAGIC = 0xD780E884
ACTION_NEXT_TICK = "flashback:action/next_tick"
ACTION_MOVE = "flashback:action/move_entities"
ACTION_CREATE_LOCAL = "flashback:action/create_local_player"


@dataclass
class Pose:
    x: float
    y: float
    z: float
    yaw: float
    pitch: float

    def dist(self, other: "Pose") -> float:
        return math.sqrt(
            (self.x - other.x) ** 2 + (self.y - other.y) ** 2 + (self.z - other.z) ** 2
        )


@dataclass
class ReplayMeta:
    uuid: str
    name: str
    total_ticks: int
    world_name: str
    path: Path
    chunks: List[str]


@dataclass
class Trajectory:
    meta: ReplayMeta
    poses: Dict[int, Pose] = field(default_factory=dict)
    cuts: List[int] = field(default_factory=list)  # tick indices where a cut starts

    def sample(self, tick: int) -> Optional[Pose]:
        if tick in self.poses:
            return self.poses[tick]
        # nearest earlier
        earlier = [t for t in self.poses if t <= tick]
        if not earlier:
            return None
        return self.poses[max(earlier)]


def load_meta(replay_path: Path) -> ReplayMeta:
    with zipfile.ZipFile(replay_path) as zf:
        meta = json.loads(zf.read("metadata.json"))
    chunks = sorted(meta.get("chunks", {}).keys(), key=_chunk_sort_key)
    if not chunks:
        # fallback: discover cN.flashback entries
        with zipfile.ZipFile(replay_path) as zf:
            chunks = sorted(
                (n for n in zf.namelist() if n.endswith(".flashback") and n.startswith("c")),
                key=_chunk_sort_key,
            )
    return ReplayMeta(
        uuid=meta["uuid"],
        name=meta.get("name", replay_path.stem),
        total_ticks=int(meta.get("total_ticks", 0)),
        world_name=meta.get("world_name", ""),
        path=replay_path.resolve(),
        chunks=chunks,
    )


def _chunk_sort_key(name: str) -> Tuple[int, str]:
    base = Path(name).name
    if base.startswith("c") and base.endswith(".flashback"):
        mid = base[1 : -len(".flashback")]
        if mid.isdigit():
            return (int(mid), base)
    return (10**9, base)


def parse_replay(replay_path: Path, teleport_blocks: float = 12.0) -> Trajectory:
    meta = load_meta(replay_path)
    traj = Trajectory(meta=meta)
    player_id: Optional[int] = None
    last_player: Optional[Pose] = None
    tick = 0

    with zipfile.ZipFile(replay_path) as zf:
        for chunk_name in meta.chunks:
            data = zf.read(chunk_name)
            tick, player_id, last_player = _parse_chunk(
                data, tick, player_id, last_player, traj
            )

    if meta.total_ticks <= 0 and traj.poses:
        meta.total_ticks = max(traj.poses) + 1

    # Detect teleports on the recorded player path
    ticks = sorted(traj.poses)
    for a, b in zip(ticks, ticks[1:]):
        if traj.poses[a].dist(traj.poses[b]) > teleport_blocks and (b - a) <= 5:
            traj.cuts.append(b)
    return traj


def _parse_chunk(
    data: bytes,
    tick: int,
    player_id: Optional[int],
    last_player: Optional[Pose],
    traj: Trajectory,
) -> Tuple[int, Optional[int], Optional[Pose]]:
    r = ByteReader(data)
    magic = r.read_int() & 0xFFFFFFFF
    if magic != MAGIC:
        raise ValueError(f"Invalid Flashback magic: 0x{magic:08X}")

    n_actions = r.read_varint()
    action_names: Dict[int, str] = {}
    for i in range(n_actions):
        action_names[i] = r.read_identifier()

    snapshot_size = r.read_int()
    if snapshot_size < 0:
        raise ValueError(f"Invalid snapshot size {snapshot_size}")
    # Snapshot also contains sized actions — parse for create_local_player / initial poses
    snap_end = r.i + snapshot_size
    while r.i < snap_end:
        aid = r.read_varint()
        size = r.read_int()
        payload = r.read_bytes(size)
        name = action_names.get(aid, "")
        if name == ACTION_CREATE_LOCAL:
            last_player = _parse_create_local(payload) or last_player
            if last_player:
                traj.poses[tick] = last_player
        elif name == ACTION_MOVE:
            player_id, last_player = _ingest_moves(
                payload, tick, player_id, last_player, traj
            )
    r.i = snap_end

    while r.remaining() > 0:
        aid = r.read_varint()
        size = r.read_int()
        payload = r.read_bytes(size)
        name = action_names.get(aid, "")
        if name.endswith("optional") and name not in action_names.values():
            continue
        if name == ACTION_NEXT_TICK:
            tick += 1
            if last_player is not None and tick not in traj.poses:
                traj.poses[tick] = last_player
        elif name == ACTION_CREATE_LOCAL:
            last_player = _parse_create_local(payload) or last_player
            if last_player:
                traj.poses[tick] = last_player
        elif name == ACTION_MOVE:
            player_id, last_player = _ingest_moves(
                payload, tick, player_id, last_player, traj
            )
    return tick, player_id, last_player


def _parse_create_local(payload: bytes) -> Optional[Pose]:
    try:
        r = ByteReader(payload)
        _uuid = r.read_uuid()
        x = r.read_double()
        y = r.read_double()
        z = r.read_double()
        pitch = r.read_float()  # xRot
        yaw = r.read_float()  # yRot
        return Pose(x, y, z, yaw, pitch)
    except Exception:
        return None


def _ingest_moves(
    payload: bytes,
    tick: int,
    player_id: Optional[int],
    last_player: Optional[Pose],
    traj: Trajectory,
) -> Tuple[Optional[int], Optional[Pose]]:
    r = ByteReader(payload)
    try:
        level_count = r.read_varint()
    except EOFError:
        return player_id, last_player

    best: Optional[Tuple[float, int, Pose]] = None
    for _ in range(level_count):
        try:
            _dim = r.read_resource_key()
            count = r.read_varint()
        except EOFError:
            break
        for _j in range(count):
            try:
                eid = r.read_varint()
                x = r.read_double()
                y = r.read_double()
                z = r.read_double()
                yaw = r.read_float()
                pitch = r.read_float()
                _head = r.read_float()
                _on_ground = r.read_bool()
            except EOFError:
                return player_id, last_player
            pose = Pose(x, y, z, yaw, pitch)
            if player_id is not None and eid == player_id:
                traj.poses[tick] = pose
                return player_id, pose
            if last_player is not None:
                d = last_player.dist(pose)
                if best is None or d < best[0]:
                    best = (d, eid, pose)
            else:
                if best is None:
                    best = (0.0, eid, pose)

    if player_id is None and best is not None:
        # Lock onto nearest entity to last known / first seen
        player_id = best[1]
        last_player = best[2]
        traj.poses[tick] = last_player
    elif player_id is not None and tick not in traj.poses and last_player is not None:
        traj.poses[tick] = last_player
    return player_id, last_player


def continuous_segments(traj: Trajectory) -> List[Tuple[int, int]]:
    """Return inclusive tick ranges that do not cross cuts."""
    if not traj.poses:
        return []
    cuts = set(traj.cuts)
    start = min(traj.poses)
    end = max(traj.poses)
    segments: List[Tuple[int, int]] = []
    seg_start = start
    for t in range(start + 1, end + 1):
        if t in cuts:
            if seg_start < t:
                segments.append((seg_start, t - 1))
            seg_start = t
    segments.append((seg_start, end))
    return [(a, b) for a, b in segments if b - a >= 1]
