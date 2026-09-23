from pathlib import Path

from cinefile.camera import candidate_windows, select_clips_greedy
from cinefile.replay import parse_replay
from cinefile.styles_loader import load_style

SAMPLE = Path(
    "/Applications/ATLauncher.app/Contents/Java/instances/"
    "Minecraft262withFabric/flashback/replays/2026-09-18T22_24_40.zip"
)


def test_parse_and_select():
    if not SAMPLE.exists():
        return
    traj = parse_replay(SAMPLE)
    assert len(traj.poses) > 100
    style = load_style("locked-dolly")
    cands = candidate_windows(traj, clip_ticks=200, max_candidates=50)
    assert cands
    clips = select_clips_greedy(cands, traj, style, target_ticks=600)
    assert clips
    for c in clips:
        assert c.keyframes[0].yaw == c.keyframes[1].yaw
        assert c.end_tick - c.start_tick == 200
