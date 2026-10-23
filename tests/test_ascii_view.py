import io
from types import SimpleNamespace

from cinefile.ascii_view import (
    RAMP,
    ScoreViewer,
    TerminalFrames,
    format_frame,
    render_ascii,
)
from cinefile.camera import CameraClip, CameraKeyframe
from cinefile.framing import FramingStats, ScoreEvent
from cinefile.replay import Pose
from cinefile.world import RayHit

CAM = (0.0, 1.0, 0.0, 0.0, 0.0)
PLAYER = Pose(0, 0, 10, 0, 0)


def _world(hit=None):
    return SimpleNamespace(raycast=lambda *a, **k: hit)


def _event(cols_index=1, total=5):
    clip = CameraClip(
        0,
        100,
        (CameraKeyframe(0, 0, 1, 0, 0, 0), CameraKeyframe(100, 0, 1, 0, 0, 0)),
        style_id="hero-low",
    )
    traj = SimpleNamespace(sample=lambda t: Pose(0, 0, 10, 0, 0))
    return ScoreEvent(traj, _world(), clip, FramingStats(1, 1, 0, 1), cols_index, total)


def test_render_size():
    rows = render_ascii(_world(), CAM, PLAYER, 0)
    assert len(rows) == 18
    assert all(len(r) == 64 for r in rows)


def test_render_player_and_sky():
    rows = render_ascii(_world(), CAM, PLAYER, 0)
    assert rows[9][32] == "@"
    assert rows[0][0] == " "


def test_render_without_world():
    assert render_ascii(None, CAM, PLAYER, 0) == render_ascii(_world(), CAM, PLAYER, 0)


def test_render_inside_wall():
    rows = render_ascii(_world(RayHit(0.0, 1, -1)), CAM, None, 0)
    assert all(set(r) == {"X"} for r in rows)


def test_render_block_uses_ramp():
    rows = render_ascii(_world(RayHit(16.0, 9, 0)), CAM, None, 0)
    assert all(ch in RAMP for r in rows for ch in r)


def test_format_frame_header():
    lines = format_frame(_event()).split("\n")
    assert "window 2/5" in lines[0]
    assert "hero-low" in lines[0]
    assert len(lines) == 19
    short = format_frame(_event(), cols=20).split("\n")
    assert len(short[0]) <= 20


def test_viewer_throttles():
    times = iter([0.0, 0.1, 0.6])
    emitted = []
    viewer = ScoreViewer(emitted.append, clock=lambda: next(times))
    for _ in range(3):
        viewer(_event())
    assert len(emitted) == 2


def test_viewer_swallows_errors():
    def boom(text):
        raise RuntimeError("x")

    ScoreViewer(boom)(_event())


def test_terminal_frames_redraw_and_clear():
    out = io.StringIO()
    frames = TerminalFrames(out)
    frames.clear()
    assert out.getvalue() == ""
    frames("a\nb\nc")
    first = out.getvalue()
    frames("a\nb\nc")
    assert out.getvalue()[len(first):].startswith("\x1b[3F")
    mark = len(out.getvalue())
    frames.clear()
    assert "\x1b[J" in out.getvalue()[mark:]
