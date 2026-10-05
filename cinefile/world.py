"""Time-aware block lookup decoded from Flashback level chunk caches."""

from __future__ import annotations

import array
import bisect
import math
import re
import struct
import zipfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Set, Tuple, Union

from .bufio import ByteReader

AIR_STATE_ID = 0
_SECTION_ENTRIES = 4096
_BIOME_ENTRIES = 64


@dataclass
class DecodedChunk:
    min_y: int
    sections: List[Union[int, "array.array"]]


@dataclass
class RayHit:
    dist: float
    state_id: int
    axis: int


def _read_longs(r: ByteReader, entries: int, entry_bits: int) -> bytes:
    per_long = 64 // entry_bits
    return r.read_bytes(-(-entries // per_long) * 8)


def _unpack(raw: bytes, entries: int, entry_bits: int) -> List[int]:
    per_long = 64 // entry_bits
    mask = (1 << entry_bits) - 1
    out: List[int] = []
    for (word,) in struct.iter_unpack(">Q", raw):
        for j in range(per_long):
            out.append((word >> (j * entry_bits)) & mask)
            if len(out) == entries:
                return out
    return out


def _skip_biomes(r: ByteReader) -> None:
    bits = r.read_u8()
    if bits == 0:
        r.read_varint()
        return
    if bits <= 3:
        for _ in range(r.read_varint()):
            r.read_varint()
    _read_longs(r, _BIOME_ENTRIES, bits)


def decode_chunk(record: bytes, world: "World") -> Tuple[int, int, DecodedChunk]:
    r = ByteReader(record)
    cx, cz = _read_header(r)
    for _ in range(r.read_varint()):
        r.read_varint()
        r.skip(r.read_varint() * 8)
    data = r.read_bytes(r.read_varint())
    d = ByteReader(data)
    sections: List[Union[int, array.array]] = []
    while d.remaining() > 0:
        non_air, fluid = struct.unpack(">hh", d.read_bytes(4))
        bits = d.read_u8()
        if bits == 0:
            v = d.read_varint()
            if non_air == 0:
                world.air_ids.add(v)
            elif fluid == _SECTION_ENTRIES:
                world.fluid_ids.add(v)
            sections.append(v)
        else:
            palette: Optional[List[int]] = None
            if bits <= 8:
                palette = [d.read_varint() for _ in range(d.read_varint())]
                entry_bits = max(4, bits)
            else:
                entry_bits = bits
            raw = _read_longs(d, _SECTION_ENTRIES, entry_bits)
            idx = _unpack(raw, _SECTION_ENTRIES, entry_bits)
            if palette is not None:
                vals = array.array("H", (palette[i] for i in idx))
            else:
                vals = array.array("H", idx)
            _infer_ids(vals, non_air, fluid, world)
            sections.append(vals)
        _skip_biomes(d)
    min_y = 0 if len(sections) == 16 else -64
    return cx, cz, DecodedChunk(min_y, sections)


def _infer_ids(vals: "array.array", non_air: int, fluid: int, world: "World") -> None:
    counts = Counter(vals)
    deficit = (_SECTION_ENTRIES - non_air) - sum(
        n for i, n in counts.items() if i in world.air_ids
    )
    if deficit > 0:
        match = [i for i, n in counts.items() if i not in world.air_ids and n == deficit]
        if len(match) == 1:
            world.air_ids.add(match[0])
    if fluid > 0:
        deficit_f = fluid - sum(n for i, n in counts.items() if i in world.fluid_ids)
        if deficit_f > 0:
            match = [
                i
                for i, n in counts.items()
                if i not in world.air_ids and i not in world.fluid_ids and n == deficit_f
            ]
            if len(match) == 1:
                world.fluid_ids.add(match[0])


def _read_header(r: ByteReader) -> Tuple[int, int]:
    r.read_varint()  # packet id
    return r.read_int(), r.read_int()


class World:
    def __init__(self, records: Sequence[bytes]):
        self.air_ids: Set[int] = {AIR_STATE_ID}
        self.fluid_ids: Set[int] = set()
        self._records = records
        self._versions: Dict[Tuple[int, int], List[Tuple[int, int]]] = {}
        self._decoded: Dict[int, Optional[DecodedChunk]] = {}

    @classmethod
    def from_records(
        cls, records: Sequence[bytes], chunk_refs: Sequence[Tuple[int, int]]
    ) -> "World":
        world = cls(records)
        refs = list(chunk_refs) if chunk_refs else [(0, i) for i in range(len(records))]
        for tick, idx in refs:
            if not 0 <= idx < len(records):
                continue
            try:
                key = _read_header(ByteReader(records[idx]))
            except Exception:
                continue
            world._versions.setdefault(key, []).append((tick, idx))
        for v in world._versions.values():
            v.sort()
        return world

    def _chunk_for(self, cx: int, cz: int, tick: int) -> Optional[DecodedChunk]:
        versions = self._versions.get((cx, cz))
        if not versions:
            return None
        pos = bisect.bisect_right(versions, (tick, float("inf"))) - 1
        idx = versions[max(pos, 0)][1]
        if idx not in self._decoded:
            try:
                self._decoded[idx] = decode_chunk(self._records[idx], self)[2]
            except Exception:
                self._decoded[idx] = None
        return self._decoded[idx]

    def block_at(self, x: int, y: int, z: int, tick: int) -> Optional[int]:
        chunk = self._chunk_for(x >> 4, z >> 4, tick)
        if chunk is None:
            return None
        si = (y - chunk.min_y) >> 4
        if not 0 <= si < len(chunk.sections):
            return None
        sec = chunk.sections[si]
        if isinstance(sec, int):
            return sec
        return sec[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)]

    def is_solid(self, x: int, y: int, z: int, tick: int) -> bool:
        sid = self.block_at(x, y, z, tick)
        return sid is not None and sid not in self.air_ids and sid not in self.fluid_ids

    def raycast(
        self,
        origin: Tuple[float, float, float],
        direction: Tuple[float, float, float],
        max_dist: float,
        tick: int,
        skip_origin: bool = False,
    ) -> Optional[RayHit]:
        length = math.sqrt(sum(c * c for c in direction))
        if length == 0:
            return None
        d = [c / length for c in direction]
        pos = [math.floor(c) for c in origin]
        if not skip_origin and self.is_solid(pos[0], pos[1], pos[2], tick):
            return RayHit(0.0, self.block_at(pos[0], pos[1], pos[2], tick), -1)
        step = [0, 0, 0]
        t_max = [math.inf] * 3
        t_delta = [math.inf] * 3
        for a in range(3):
            if d[a] > 0:
                step[a] = 1
                t_max[a] = (pos[a] + 1 - origin[a]) / d[a]
                t_delta[a] = 1 / d[a]
            elif d[a] < 0:
                step[a] = -1
                t_max[a] = (pos[a] - origin[a]) / d[a]
                t_delta[a] = -1 / d[a]
        while True:
            axis = 0 if t_max[0] <= t_max[1] and t_max[0] <= t_max[2] else (1 if t_max[1] <= t_max[2] else 2)
            dist = t_max[axis]
            if dist > max_dist:
                return None
            pos[axis] += step[axis]
            t_max[axis] += t_delta[axis]
            if self.is_solid(pos[0], pos[1], pos[2], tick):
                return RayHit(dist, self.block_at(pos[0], pos[1], pos[2], tick), axis)

    def ray_blocked(
        self,
        a: Tuple[float, float, float],
        b: Tuple[float, float, float],
        tick: int,
    ) -> bool:
        diff = (b[0] - a[0], b[1] - a[1], b[2] - a[2])
        d = math.sqrt(sum(c * c for c in diff))
        if d <= 0.75:
            return False
        return self.raycast(a, diff, d - 0.75, tick, skip_origin=True) is not None


def load_world(
    replay_path: Path, chunk_refs: Sequence[Tuple[int, int]]
) -> Optional[World]:
    try:
        with zipfile.ZipFile(replay_path) as zf:
            names = sorted(
                (
                    (int(m.group(1)), n)
                    for n in zf.namelist()
                    if (m := re.fullmatch(r"level_chunk_caches/(\d+)", n))
                ),
            )
            if not names:
                return None
            records: List[bytes] = []
            for _, n in names:
                data = memoryview(zf.read(n))
                i = 0
                while i + 4 <= len(data):
                    (size,) = struct.unpack(">i", data[i : i + 4])
                    i += 4
                    if size < 0 or i + size > len(data):
                        break
                    records.append(data[i : i + size])
                    i += size
        return World.from_records(records, chunk_refs)
    except Exception:
        return None
