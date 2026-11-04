import math

import pytest

from cinefile.camera import (
    CameraClip,
    CameraKeyframe,
    candidate_windows,
    select_clips_spread,
    slice_candidates,
    synthesize_clip,
    synthesize_clips,
)
from cinefile.replay import Pose, Trajectory
from cinefile.styles_loader import load_style, style_variant


def _traj(total: int, fast_until: int) -> Trajectory:
    poses = {}
    x = 0.0
    for t in range(total + 1):
        x += 1.0 if t < fast_until else 0.1
        poses[t] = Pose(x, 64.0, 0.0, 0.0, 0.0)
    return Trajectory(meta=None, poses=poses)


def _clip(t0, t1, score=0.0):
    kf = CameraKeyframe(t0, 0.0, 64.0, 0.0, 0.0, 0.0)
    return CameraClip(t0, t1, (kf, CameraKeyframe(t1, 0.0, 64.0, 0.0, 0.0, 0.0)), score=score)


def _synth(t0, t1, score):
    return _clip(t0, t1, score)


def test_cap_keeps_candidates_across_timeline():
    traj = _traj(6000, 3000)
    out = candidate_windows(traj, 100, max_candidates=50)
    assert 0 < len(out) <= 50
    counts = [sum(1 for t0, _, _ in out if t0 * 5 // 6001 == b) for b in range(5)]
    assert min(counts) >= 8, counts


def test_under_cap_keeps_default_stride():
    traj = _traj(300, 150)
    out = candidate_windows(traj, 100)
    assert {(a, b) for a, b, _ in out} == {(t, t + 100) for t in range(0, 201, 25)}


def test_candidates_sorted_best_first():
    out = candidate_windows(_traj(6000, 3000), 100, max_candidates=50)
    scores = [s for _, _, s in out]
    assert scores == sorted(scores, reverse=True)


def test_slice_candidates_groups_by_time():
    cands = [(0, 10, 1.0), (5, 15, 3.0), (10, 20, 2.0), (20, 30, 1.0), (29, 39, 5.0)]
    # lo=0, hi=29 -> slice = t0 * 3 // 30
    assert slice_candidates(cands, 3) == [[1, 0], [2], [4, 3]]


def test_slice_candidates_empty():
    assert slice_candidates([], 3) == [[], [], []]


def test_slice_candidates_same_start():
    out = slice_candidates([(7, 17, 1.0), (7, 20, 2.0)], 3)
    assert out == [[1, 0], [], []]


def _wide(low_slice=None):
    cands = []
    for t0 in range(0, 5900, 50):
        score = 10.0 if t0 < 3000 else 5.0
        if low_slice is not None and low_slice[0] <= t0 < low_slice[1]:
            score = 0.1
        cands.append((t0, t0 + 100, score))
    return cands


def test_spread_covers_low_scoring_half():
    clips = select_clips_spread(_wide(), None, None, 1200, synth=_synth)
    assert len(clips) == 12
    hi = 5850
    assert {c.start_tick * 12 // (hi + 1) for c in clips} == set(range(12))
    assert any(c.start_tick >= 3000 for c in clips)


def test_spread_skips_idle_slice_and_fills():
    # slice 3 spans roughly t0 490*3..490*4
    clips = select_clips_spread(_wide((1500, 1900)), None, None, 1200, synth=_synth)
    assert len(clips) == 12
    assert not [c for c in clips if 1500 <= c.start_tick < 1900]


def test_spread_no_overlap_within_budget():
    cands = [(t0, t0 + 100, 1.0 + (t0 % 70) / 10) for t0 in range(0, 3000, 30)]
    clips = select_clips_spread(cands, None, None, 1000, synth=_synth)
    assert clips
    assert sum(c.end_tick - c.start_tick for c in clips) <= 1000
    starts = [c.start_tick for c in clips]
    assert starts == sorted(starts)
    for a, b in zip(clips, clips[1:]):
        assert a.end_tick < b.start_tick or b.start_tick > a.end_tick


def test_spread_empty():
    assert select_clips_spread([], None, None, 1000, synth=_synth) == []


def test_spread_next_best_when_synth_none():
    cands = [(0, 100, 9.0), (10, 110, 8.0), (20, 120, 7.0)]
    calls = []

    def synth(t0, t1, score):
        calls.append(t0)
        return None if t0 == 0 else _clip(t0, t1, score)

    clips = select_clips_spread(cands, None, None, 100, synth=synth)
    assert [c.start_tick for c in clips] == [10]


def test_cap_smaller_than_segments():
    poses = {t: Pose(float(t), 64.0, 0.0, 0.0, 0.0) for t in range(0, 1000)}
    traj = Trajectory(meta=None, poses=poses, cuts=[200, 400, 600, 800])
    out = candidate_windows(traj, 100, max_candidates=3)
    assert 0 < len(out) <= 3


def _walk() -> Trajectory:
    poses = {t: Pose(0.5 * t, 64.0, 0.0, 30.0 + t, 5.0) for t in range(31)}
    return Trajectory(meta=None, poses=poses)


def _poses(fn) -> Trajectory:
    return Trajectory(meta=None, poses={t: fn(t) for t in range(31)})


def _start(clip):
    return clip.keyframes[0]


def _end(clip):
    return clip.keyframes[1]


def _view_move(clip):
    """Camera move as (along the start view, along the start right)."""
    yaw = math.radians(_start(clip).yaw)
    dx = _end(clip).x - _start(clip).x
    dz = _end(clip).z - _start(clip).z
    return (
        -dx * math.sin(yaw) + dz * math.cos(yaw),
        dx * math.cos(yaw) + dz * math.sin(yaw),
    )


def test_straight_dolly_tries_both_directions():
    clips = synthesize_clips(_walk(), 0, 30, load_style("straight-dolly"))
    assert len(clips) == 2
    for clip in clips:
        assert (clip.start_tick, clip.end_tick, clip.style_id) == (0, 30, "straight-dolly")
        assert (_start(clip).x, _start(clip).y, _start(clip).z) == pytest.approx(
            (4.558845726811989, 66.62, -4.296152422706632), abs=1e-6
        )
        assert _start(clip).yaw == pytest.approx(42.68038349181982, abs=1e-6)
        assert _start(clip).pitch == pytest.approx(0.0, abs=1e-6)
        assert _end(clip).yaw == _start(clip).yaw
        assert _end(clip).pitch == _start(clip).pitch

    assert (_end(clips[0]).x, _end(clips[0]).y, _end(clips[0]).z) == pytest.approx(
        (1.979966733340314, 66.62, 0.12712405099581892), abs=1e-6
    )
    assert (_end(clips[1]).x, _end(clips[1]).y, _end(clips[1]).z) == pytest.approx(
        (8.759046887989083, 66.62, -7.2243433029987045), abs=1e-6
    )


def test_top_down_looks_straight_down_and_drifts_both_ways():
    clips = synthesize_clips(_walk(), 0, 30, load_style("top-down"))
    assert len(clips) == 2
    for clip in clips:
        for key in (_start(clip), _end(clip)):
            assert (key.x, key.z, key.yaw, key.pitch) == pytest.approx((7.5, 0.0, 30.0, 90.0))
        assert _start(clip).y == pytest.approx(74.0)
    assert _end(clips[0]).y == pytest.approx(72.5)
    assert _end(clips[1]).y == pytest.approx(75.5)


def test_straight_dolly_stationary_travels_two_blocks_each_way():
    clips = synthesize_clips(_poses(lambda t: Pose(0, 64, 0, 0, 0)), 0, 30, load_style("straight-dolly"))
    assert len(clips) == 2
    assert _view_move(clips[0]) == pytest.approx((2.0, 0.0), abs=1e-9)
    assert _view_move(clips[1]) == pytest.approx((-2.0, 0.0), abs=1e-9)


def test_straight_dolly_keeps_one_level_even_when_the_player_climbs():
    clips = synthesize_clips(
        _poses(lambda t: Pose(0, 64 + t / 10.0, 0, 0, 0)), 0, 30, load_style("straight-dolly")
    )
    assert len(clips) == 2
    for clip in clips:
        assert _start(clip).y == _end(clip).y


def test_top_down_pitch_stays_90_on_a_jittered_variant():
    style = style_variant(load_style("top-down"), 3, 40)
    for clip in synthesize_clips(_poses(lambda t: Pose(0, 64, 0, 0, 0)), 0, 30, style):
        assert _start(clip).pitch == 90.0
        assert _end(clip).pitch == 90.0


def test_locked_dolly_makes_one_clip_matching_synthesize_clip():
    traj = _walk()
    style = load_style("locked-dolly")
    clips = synthesize_clips(traj, 0, 30, style, score=1.5)
    assert clips == [synthesize_clip(traj, 0, 30, style, score=1.5)]


def test_synthesize_clip_returns_first_direction():
    traj = _walk()
    style = load_style("straight-dolly")
    assert synthesize_clip(traj, 0, 30, style) == synthesize_clips(traj, 0, 30, style)[0]


def test_missing_pose_gives_no_clips():
    traj = Trajectory(meta=None, poses={t: Pose(0, 64, 0, 0, 0) for t in range(1, 31)})
    assert synthesize_clips(traj, 0, 30, load_style("straight-dolly")) == []
    assert synthesize_clip(traj, 0, 30, load_style("straight-dolly")) is None
