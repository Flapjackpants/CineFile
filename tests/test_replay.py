import json
import struct
import zipfile
from pathlib import Path

import pytest

from cinefile import replay

NAMES = [replay.ACTION_NEXT_TICK, replay.ACTION_MOVE, replay.ACTION_GAME_PACKET]
REAL = Path(
    "/Applications/ATLauncher.app/Contents/Java/instances/Minecraft263withFabric/"
    "flashback/replays/market2-3.zip"
)


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


def _ident(s: str) -> bytes:
    raw = s.encode()
    return _varint(len(raw)) + raw


def _action(aid: int, payload: bytes) -> bytes:
    return _varint(aid) + struct.pack(">i", len(payload)) + payload


def _move(*ents) -> bytes:
    out = _varint(1) + _ident("minecraft:overworld") + _varint(len(ents))
    for eid, x in ents:
        out += _varint(eid) + struct.pack(">ddd", x, 0.0, 0.0)
        out += struct.pack(">fff", 0.0, 0.0, 0.0) + b"\x00"
    return out


def _login(eid: int, dim: str = "minecraft:overworld") -> bytes:
    return _varint(0x32) + struct.pack(">i", eid) + b"\x00" + _varint(1) + _ident(dim)


def _tick() -> bytes:
    return _action(0, b"")


def _chunk(snapshot: bytes, stream: bytes) -> bytes:
    head = struct.pack(">I", replay.MAGIC) + _varint(len(NAMES))
    head += b"".join(_ident(n) for n in NAMES)
    return head + struct.pack(">i", len(snapshot)) + snapshot + stream


def _zip(tmp_path, chunks) -> Path:
    p = tmp_path / "r.zip"
    meta = {
        "uuid": "u",
        "name": "n",
        "total_ticks": 100,
        "chunks": {f"c{i}.flashback": {"duration": 10} for i in range(len(chunks))},
    }
    with zipfile.ZipFile(p, "w") as zf:
        zf.writestr("metadata.json", json.dumps(meta))
        for i, c in enumerate(chunks):
            zf.writestr(f"c{i}.flashback", c)
    return p


def _xs(traj):
    return [traj.poses[t].x for t in sorted(traj.poses)]


def test_player_followed_across_join(tmp_path):
    c1 = _chunk(_action(2, _login(10)), _action(1, _move((10, 1))) + _tick() + _action(1, _move((10, 2))))
    c2 = _chunk(
        _action(2, _login(20)),
        _action(1, _move((20, 100))) + _tick() + _action(1, _move((20, 101))),
    )
    traj = replay.parse_replay(_zip(tmp_path, [c1, c2]))
    xs = _xs(traj)
    assert 101 in xs and 100 in xs
    assert xs[-1] == 101


def test_login_id_beats_nearest_entity(tmp_path):
    stream = (
        _action(1, _move((99, 5)))
        + _tick()
        + _action(1, _move((10, 50)))
        + _tick()
    )
    traj = replay.parse_replay(_zip(tmp_path, [_chunk(_action(2, _login(10)), stream)]))
    xs = _xs(traj)
    assert 5 not in xs
    assert 50 in xs


def test_no_login_keeps_nearest_entity_lock(tmp_path):
    stream = _action(1, _move((7, 3))) + _tick() + _action(1, _move((7, 4)))
    traj = replay.parse_replay(_zip(tmp_path, [_chunk(b"", stream)]))
    assert _xs(traj)[-1] == 4


def test_chunk_without_login_keeps_player(tmp_path):
    c1 = _chunk(_action(2, _login(10)), _action(1, _move((10, 1))))
    c2 = _chunk(b"", _tick() + _action(1, _move((10, 2), (99, 500))))
    traj = replay.parse_replay(_zip(tmp_path, [c1, c2]))
    xs = _xs(traj)
    assert xs[-1] == 2
    assert 500 not in xs


def test_real_joined_replay_moves_after_join():
    if not REAL.exists():
        return
    traj = replay.parse_replay(REAL)
    moved = 0
    ticks = sorted(t for t in traj.poses if t > 27140)
    for a, b in zip(ticks, ticks[1:]):
        if traj.poses[a].dist(traj.poses[b]) > 0:
            moved += 1
    assert moved > 10000


def _raw_login(eid=10, hc=0, count=1, dim="minecraft:overworld") -> bytes:
    return _varint(0x32) + struct.pack(">i", eid) + bytes([hc]) + _varint(count) + _ident(dim)


@pytest.mark.parametrize(
    "payload",
    [
        _raw_login(eid=0),
        _raw_login(eid=-5),
        _raw_login(hc=2),
        _raw_login(count=0),
        _raw_login(count=65),
        _raw_login(dim="overworld"),
        _raw_login()[:6],
        b"",
    ],
)
def test_login_shape_rejects(payload):
    assert replay._parse_login_entity_id(payload) is None


def test_login_shape_accepts():
    assert replay._parse_login_entity_id(_raw_login(eid=42)) == 42


def _run_chunk(snapshot, stream):
    traj = replay.Trajectory(meta=None)
    return replay._parse_chunk(_chunk(snapshot, stream), 0, 7, None, traj)[1]


def test_first_login_in_snapshot_wins():
    snap = _action(2, _login(10)) + _action(2, _login(20))
    assert _run_chunk(snap, b"") == 10


def test_stream_login_ignored():
    assert _run_chunk(b"", _action(2, _login(10))) == 7
