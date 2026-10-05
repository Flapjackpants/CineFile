import struct
from pathlib import Path
from types import SimpleNamespace

import pytest

from cinefile import replay
from cinefile.world import World, load_world


def _varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _section(non_air: int, fluid: int, value) -> bytes:
    out = struct.pack(">hh", non_air, fluid)
    if isinstance(value, int):
        out += bytes([0]) + _varint(value)
    else:
        pal = sorted(set(value))
        assert len(pal) <= 16
        out += bytes([4]) + _varint(len(pal)) + b"".join(_varint(p) for p in pal)
        idx = [pal.index(v) for v in value]
        for k in range(256):
            word = 0
            for j, v in enumerate(idx[k * 16 : (k + 1) * 16]):
                word |= v << (4 * j)
            if word >= 1 << 63:
                word -= 1 << 64
            out += struct.pack(">q", word)
    return out + bytes([0]) + _varint(0)


def _record(cx: int, cz: int, sections) -> bytes:
    data = b"".join(_section(*s) for s in sections)
    return (
        _varint(0x2D)
        + struct.pack(">ii", cx, cz)
        + _varint(0)
        + _varint(len(data))
        + data
    )


def _blocks_record(cx: int, cz: int, blocks) -> bytes:
    """blocks: {(x, y, z): state_id}, world coords inside chunk (cx, cz)."""
    secs = []
    for si in range(24):
        vals = [0] * 4096
        n = 0
        for (x, y, z), sid in blocks.items():
            if (y + 64) >> 4 == si:
                vals[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] = sid
                n += 1
        secs.append((n, 0, vals if n else 0))
    return _record(cx, cz, secs)


def test_parse_chunk_records_chunk_refs():
    def ident(s):
        b = s.encode()
        return _varint(len(b)) + b

    def action(aid, payload=b""):
        return _varint(aid) + struct.pack(">i", len(payload)) + payload

    data = struct.pack(">I", replay.MAGIC)
    data += _varint(2) + ident(replay.ACTION_NEXT_TICK) + ident(replay.ACTION_CHUNK_CACHED)
    data += struct.pack(">i", 0)
    data += action(1, _varint(5)) + action(0) + action(1, _varint(300))
    traj = replay.Trajectory(meta=None)
    replay._parse_chunk(data, 0, None, None, traj)
    assert traj.chunk_refs == [(0, 5), (1, 300)]


def test_single_value_air():
    w = World.from_records([_record(0, 0, [(0, 0, 0)] * 24)], [(0, 0)])
    assert w.block_at(3, 70, 4, 0) == 0
    assert not w.is_solid(3, 70, 4, 0)


def test_paletted_lookup_and_index_order():
    vals = [0] * 4096
    vals[((0 & 15) << 8) | (2 << 4) | 1] = 9
    secs = [(1, 0, vals)] + [(0, 0, 0)] * 23
    w = World.from_records([_record(0, 0, secs)], [(0, 0)])
    assert w.is_solid(1, -64, 2, 0)
    assert not w.is_solid(0, -64, 0, 0)
    assert w.block_at(1, -64, 2, 0) == 9


def test_infers_extra_air_id():
    vals = [0] * 4000 + [77] * 96
    secs = [(0, 0, vals)] + [(0, 0, 0)] * 23
    w = World.from_records([_record(0, 0, secs)], [(0, 0)])
    assert not w.is_solid(15, -49, 15, 0)
    assert 77 in w.air_ids


def test_infers_fluid_id():
    vals = [5] * (4096 - 100) + [86] * 100
    secs = [(4096, 100, vals)] + [(0, 0, 0)] * 23
    w = World.from_records([_record(0, 0, secs)], [(0, 0)])
    assert not w.is_solid(15, -49, 15, 0)
    assert w.is_solid(0, -64, 0, 0)
    assert 86 in w.fluid_ids


def test_negative_coords():
    w = World.from_records([_blocks_record(-1, -1, {(-1, 0, -1): 4})], [(0, 0)])
    assert w.is_solid(-1, 0, -1, 0)
    assert not w.is_solid(-2, 0, -1, 0)


def test_unknown_chunk():
    w = World.from_records([_record(0, 0, [(0, 0, 0)] * 24)], [(0, 0)])
    assert w.block_at(100, 70, 100, 0) is None
    assert not w.is_solid(100, 70, 100, 0)


def test_versions_by_tick():
    recs = [_record(0, 0, [(0, 0, 0)] * 24), _blocks_record(0, 0, {(1, 64, 1): 9})]
    w = World.from_records(recs, [(0, 0), (100, 1)])
    assert not w.is_solid(1, 64, 1, 50)
    assert w.is_solid(1, 64, 1, 100)
    assert w.is_solid(1, 64, 1, 500)


def test_no_refs_fallback():
    recs = [_record(0, 0, [(0, 0, 0)] * 24), _blocks_record(0, 0, {(1, 64, 1): 9})]
    w = World.from_records(recs, [])
    assert w.is_solid(1, 64, 1, 0)


def test_malformed_record_soft_fails():
    bad = _varint(0x2D) + struct.pack(">ii", 0, 0) + _varint(0) + _varint(5) + b"\x00\x01"
    w = World.from_records([bad, b"\x2d\x00"], [(0, 0), (0, 1)])
    assert w.block_at(0, 0, 0, 0) is None
    assert not w.is_solid(0, 0, 0, 0)


def test_raycast_hit():
    w = World.from_records([_blocks_record(0, 0, {(0, 64, 5): 9})], [(0, 0)])
    hit = w.raycast((0.5, 64.5, 0.5), (0, 0, 1), 32, 0)
    assert hit.dist == pytest.approx(4.5, abs=1e-6)
    assert hit.state_id == 9
    assert hit.axis == 2


def test_raycast_miss():
    w = World.from_records([_record(0, 0, [(0, 0, 0)] * 24)], [(0, 0)])
    assert w.raycast((0.5, 64.5, 0.5), (1, 0.2, 1), 32, 0) is None


def test_raycast_origin():
    w = World.from_records([_blocks_record(0, 0, {(0, 64, 0): 9})], [(0, 0)])
    hit = w.raycast((0.5, 64.5, 0.5), (0, 0, 1), 32, 0)
    assert (hit.dist, hit.state_id, hit.axis) == (0.0, 9, -1)
    assert w.raycast((0.5, 64.5, 0.5), (0, 0, 1), 32, 0, skip_origin=True) is None


def test_ray_blocked():
    a, b = (0.5, 64.5, 0.5), (0.5, 64.5, 8.5)
    walled = World.from_records([_blocks_record(0, 0, {(0, 64, 3): 9})], [(0, 0)])
    clear = World.from_records([_record(0, 0, [(0, 0, 0)] * 24)], [(0, 0)])
    assert walled.ray_blocked(a, b, 0)
    assert not clear.ray_blocked(a, b, 0)


def test_load_world_missing(tmp_path: Path):
    assert load_world(tmp_path / "nope.zip", []) is None
