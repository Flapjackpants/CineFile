import struct
import zlib
from types import SimpleNamespace

from cinefile.camera import CameraClip, CameraKeyframe
from cinefile.preview import (
    INSIDE_RGB,
    PLAYER_RGB,
    PREVIEW_H,
    PREVIEW_W,
    SKY_RGB,
    block_rgb,
    clip_previews,
    encode_png,
    render_rgb,
)
from cinefile.replay import Pose
from cinefile.world import RayHit

PNG_SIG = b"\x89PNG\r\n\x1a\n"
CAM = (0.0, 1.0, 0.0, 0.0, 0.0)
PLAYER = Pose(0, 0, 10, 0, 0)


def _world(hit=None):
    return SimpleNamespace(raycast=lambda *a, **k: hit)


def _pixel(rgb, i, j):
    o = (j * PREVIEW_W + i) * 3
    return tuple(rgb[o : o + 3])


def test_render_size():
    assert len(render_rgb(_world(), CAM, PLAYER, 0)) == PREVIEW_W * PREVIEW_H * 3


def test_render_player_center():
    rgb = render_rgb(_world(), CAM, PLAYER, 0)
    assert _pixel(rgb, 64, 36) == PLAYER_RGB
    assert _pixel(rgb, 0, 0) == SKY_RGB


def test_render_inside_wall():
    rgb = render_rgb(_world(RayHit(0.0, 1, -1)), CAM, None, 0)
    assert all(_pixel(rgb, i, j) == INSIDE_RGB for i in (0, 64, 127) for j in (0, 36, 71))


def test_render_shading():
    rgb = render_rgb(_world(RayHit(16.0, 9, 0)), CAM, None, 0)
    expected = tuple(int(c * 0.8 * 0.7) for c in block_rgb(9))
    assert _pixel(rgb, 10, 10) == expected


def test_encode_png_roundtrip():
    png = encode_png(bytes(PREVIEW_W * PREVIEW_H * 3), PREVIEW_W, PREVIEW_H)
    assert png.startswith(PNG_SIG)
    pos, idat, ihdr = 8, b"", None
    while pos < len(png):
        (n,) = struct.unpack(">I", png[pos : pos + 4])
        kind, body = png[pos + 4 : pos + 8], png[pos + 8 : pos + 8 + n]
        (crc,) = struct.unpack(">I", png[pos + 8 + n : pos + 12 + n])
        assert crc == zlib.crc32(kind + body)
        if kind == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
        pos += 12 + n
    assert ihdr == (128, 72, 8, 2, 0, 0, 0)
    assert len(zlib.decompress(idat)) == 72 * (1 + 128 * 3)


def test_clip_previews_three_frames():
    clip = CameraClip(0, 100, (CameraKeyframe(0, 0, 1, 0, 0, 0), CameraKeyframe(100, 0, 1, 0, 0, 0)))
    traj = SimpleNamespace(sample=lambda t: PLAYER)
    out = clip_previews(_world(), traj, clip)
    assert len(out) == 3 and all(p.startswith(PNG_SIG) for p in out)
