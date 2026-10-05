import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from cinefile import pipeline
from cinefile.camera import CameraClip, CameraKeyframe
from cinefile.framing import FramingStats
from cinefile.editor import (
    build_editor_state,
    load_existing_state,
    merge_editor_state,
    occupied_ranges,
)


def _clip(t0: int, t1: int) -> CameraClip:
    return CameraClip(
        t0,
        t1,
        (CameraKeyframe(t0, 0.0, 64.0, 0.0, 0.0, 0.0), CameraKeyframe(t1, 1.0, 64.0, 1.0, 0.0, 0.0)),
    )


def _existing_state(tmp_path: Path) -> dict:
    replay = tmp_path / "replay.zip"
    return build_editor_state([_clip(100, 300)], replay_path=replay, total_ticks=2000)


def test_occupied_ranges_reads_active_scene(tmp_path: Path):
    state = _existing_state(tmp_path)
    assert occupied_ranges(state) == [(100, 300)]
    assert occupied_ranges({"scenes": []}) == []


def test_merge_keeps_existing_tracks_and_appends_new(tmp_path: Path):
    base = _existing_state(tmp_path)
    original_tracks = json.dumps(base["scenes"][0]["keyframeTracks"])
    other_replay = tmp_path / "other.zip"

    merged = merge_editor_state(
        base,
        [_clip(400, 600), _clip(700, 900)],
        replay_path=other_replay,
        total_ticks=2000,
        fill_timelapse=True,
    )

    tracks = merged["scenes"][0]["keyframeTracks"]
    assert json.dumps(tracks[:1]) == original_tracks
    assert [t["keyframeType"] for t in tracks[1:]] == ["CAMERA", "CAMERA", "TIMELAPSE"]
    assert merged["scenes"][0]["exportStartTicks"] == 100
    assert merged["scenes"][0]["exportEndTicks"] == 900
    assert str(other_replay.resolve()) in merged["usedByPaths"]
    assert str((tmp_path / "replay.zip").resolve()) in merged["usedByPaths"]
    # base must not be mutated
    assert json.dumps(base["scenes"][0]["keyframeTracks"]) == original_tracks


def test_merge_skips_timelapse_over_existing_keyframes(tmp_path: Path):
    base = _existing_state(tmp_path)
    merged = merge_editor_state(
        base,
        [_clip(0, 50), _clip(400, 600)],
        replay_path=tmp_path / "replay.zip",
        total_ticks=2000,
        fill_timelapse=True,
    )
    types = [t["keyframeType"] for t in merged["scenes"][0]["keyframeTracks"]]
    assert types == ["CAMERA", "CAMERA", "CAMERA"]


def test_merge_rejects_overlapping_clip(tmp_path: Path):
    base = _existing_state(tmp_path)
    with pytest.raises(ValueError):
        merge_editor_state(base, [_clip(250, 450)], replay_path=tmp_path / "replay.zip", total_ticks=2000)


def test_load_existing_state_refuses_corrupt_file(tmp_path: Path):
    states = tmp_path / "editor_states"
    states.mkdir()
    assert load_existing_state(tmp_path, "abc") is None
    (states / "abc.json").write_text("{not json")
    with pytest.raises(ValueError):
        load_existing_state(tmp_path, "abc")


def _fake_pipeline(monkeypatch, cands, seen):
    traj = SimpleNamespace(meta=SimpleNamespace(uuid="abc", total_ticks=2000), cuts=[], chunk_refs=[])
    monkeypatch.setattr(pipeline, "parse_replay", lambda replay: traj)
    monkeypatch.setattr(pipeline, "candidate_windows", lambda *a, **k: list(cands))
    monkeypatch.setattr(pipeline, "score_candidates_laya", lambda c, **k: c)
    monkeypatch.setattr(pipeline, "plan_clips_deepseek", lambda *a, **k: [])
    monkeypatch.setattr(pipeline, "load_world", lambda *a, **k: None)

    monkeypatch.setattr(
        pipeline,
        "best_clip",
        lambda traj, t0, t1, styles, world=None: (_clip(t0, t1), FramingStats(1.0, 1.0, 0.0, 1.0)),
    )

    def greedy(candidates, traj, style, target_ticks, min_gap_ticks=0, synth=None):
        seen.extend(candidates)
        return [_clip(t0, t1) for t0, t1, _ in candidates]

    monkeypatch.setattr(pipeline, "select_clips_greedy", greedy)


def test_pipeline_fills_only_empty_space(monkeypatch, tmp_path: Path):
    flashback = tmp_path / "Render" / "flashback"
    states = flashback / "editor_states"
    states.mkdir(parents=True)
    existing = _existing_state(tmp_path)
    (states / "abc.json").write_text(json.dumps(existing))
    seen = []
    _fake_pipeline(monkeypatch, [(0, 99, 1.0), (200, 400, 2.0), (500, 700, 3.0)], seen)

    result = pipeline.run(tmp_path / "replay.zip", editor_dir=tmp_path / "Render", offline=True)

    assert result.merged
    assert sorted((t0, t1) for t0, t1, _ in seen) == [(0, 99), (500, 700)]
    written = json.loads((states / "abc.json").read_text())
    tracks = written["scenes"][0]["keyframeTracks"]
    assert tracks[0] == existing["scenes"][0]["keyframeTracks"][0]
    assert len(tracks) == 3
    assert list((flashback / "editor_backups").iterdir())


def test_pipeline_errors_when_no_empty_space(monkeypatch, tmp_path: Path):
    flashback = tmp_path / "flashback"
    states = flashback / "editor_states"
    states.mkdir(parents=True)
    existing = _existing_state(tmp_path)
    (states / "abc.json").write_text(json.dumps(existing))
    _fake_pipeline(monkeypatch, [(150, 250, 1.0)], [])

    with pytest.raises(RuntimeError, match="No empty space"):
        pipeline.run(tmp_path / "replay.zip", editor_dir=flashback, offline=True)
    assert json.loads((states / "abc.json").read_text()) == existing
