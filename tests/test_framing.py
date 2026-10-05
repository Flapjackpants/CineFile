import math
from types import SimpleNamespace

import pytest

from cinefile.camera import CameraClip, CameraKeyframe
from cinefile.framing import (
    FramingStats,
    best_clip,
    camera_at,
    framing_multiplier,
    framing_note,
    framing_stats,
    project_to_screen,
)
from cinefile.replay import Pose
from cinefile.styles_loader import Style, list_styles, resolve_styles


def _clip(x0=0.0, x1=0.0, y=1.0, t0=0, t1=100):
    return CameraClip(
        t0,
        t1,
        (CameraKeyframe(t0, x0, y, 0, 0, 0), CameraKeyframe(t1, x1, y, 0, 0, 0)),
    )


def test_project_center():
    u, v = project_to_screen((0, 0, 0, 0, 0), (0, 0, 10))
    assert u == pytest.approx(0.5, abs=1e-6)
    assert v == pytest.approx(0.5, abs=1e-6)


def test_project_behind_is_none():
    assert project_to_screen((0, 0, 0, 0, 0), (0, 0, -5)) is None


def test_project_above():
    u, v = project_to_screen((0, 0, 0, 0, 0), (0, 3, 10))
    assert v < 0.5
    assert u == pytest.approx(0.5, abs=1e-6)


def test_project_pitch_down():
    p = (0, -10 * math.tan(math.radians(30)), 10)
    u, v = project_to_screen((0, 0, 0, 0, 30), p)
    assert u == pytest.approx(0.5, abs=1e-6)
    assert v == pytest.approx(0.5, abs=1e-6)


def test_camera_at_smoothstep():
    clip = _clip(0.0, 10.0)
    assert camera_at(clip, 50)[0] == 5.0
    assert camera_at(clip, 25)[0] == pytest.approx(1.5625)


def test_stats_centered():
    traj = SimpleNamespace(sample=lambda t: Pose(0, 0, 10, 0, 0))
    s = framing_stats(traj, _clip())
    assert s.score > 0.99
    assert s.center_frac == 1.0


def test_stats_offscreen():
    traj = SimpleNamespace(sample=lambda t: Pose(0, 0, -10, 0, 0))
    s = framing_stats(traj, _clip())
    assert s.score == 0
    assert s.onscreen_frac == 0


def test_stats_no_samples():
    traj = SimpleNamespace(sample=lambda t: None)
    assert framing_stats(traj, _clip()) == FramingStats(0, 0, 0, 0.0)


def test_multiplier_majority_rule():
    assert framing_multiplier(FramingStats(1, 1, 0, 1.0)) == pytest.approx(1.0)
    assert framing_multiplier(FramingStats(1, 0.2, 0.2, 0.6)) == pytest.approx(0.36)


def test_framing_note():
    assert (
        framing_note(FramingStats(1.0, 0.5, 0.25, 0.7), "hero")
        == "Framing (hero): subject on screen 100%, centered 50%, on thirds 25%."
    )


def _style(sid, yaw_lock, orbit):
    return Style(sid, "", 6.0, 1.0, 0.0, 0.0, yaw_lock, orbit, 1.0, "auto")


def test_best_clip_prefers_framed_style():
    traj = SimpleNamespace(sample=lambda t: Pose(0, 64, 0, 0, 0))
    bad = _style("bad", False, 180.0)
    good = _style("good", True, 0.0)
    clip, stats = best_clip(traj, 0, 100, [bad, good])
    assert clip.style_id == "good"


def test_resolve_auto():
    assert [s.id for s in resolve_styles("auto")] == list_styles()
    assert len(resolve_styles("auto")) == 5


def _world(solid=False, blocked=False):
    return SimpleNamespace(
        is_solid=lambda x, y, z, t: solid, ray_blocked=lambda a, b, t: blocked
    )


def _centered_traj():
    return SimpleNamespace(sample=lambda t: Pose(0, 0, 10, 0, 0))


def test_inside_wall_penalized():
    s = framing_stats(_centered_traj(), _clip(), world=_world(solid=True))
    assert s.inside_frac == 1.0 and s.world_checked
    base = framing_multiplier(FramingStats(s.onscreen_frac, s.center_frac, s.thirds_frac, s.score))
    assert framing_multiplier(s) == pytest.approx(0.05 * base)


def test_occlusion_penalized():
    s = framing_stats(_centered_traj(), _clip(), world=_world(blocked=True))
    assert s.occluded_frac == 1.0 and s.inside_frac == 0.0
    base = framing_multiplier(FramingStats(s.onscreen_frac, s.center_frac, s.thirds_frac, s.score))
    assert framing_multiplier(s) == pytest.approx(0.4 * base)


def test_framing_note_world():
    s = FramingStats(1.0, 0.5, 0.25, 0.7, inside_frac=1.0, occluded_frac=0.0, world_checked=True)
    assert framing_note(s, "hero").endswith(" Camera inside blocks 100%, view blocked 0%.")


def test_best_clip_avoids_walls():
    traj = SimpleNamespace(sample=lambda t: Pose(0, 64, 0, 0, 0))
    a = Style("a", "", 6.0, 1.0, 0.0, 0.0, True, 0.0, 1.0, "auto")
    b = Style("b", "", 6.0, 1.0, 5.0, 0.0, True, 0.0, 1.0, "auto")
    world = SimpleNamespace(is_solid=lambda x, y, z, t: x > 3, ray_blocked=lambda a, b, t: False)
    clip, _ = best_clip(traj, 0, 100, [b, a], world=world)
    assert clip.style_id == "a"
