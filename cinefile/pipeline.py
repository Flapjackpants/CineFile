"""End-to-end cinematography pipeline."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

from .ai import AiConfig, AiUsage, plan_clips_deepseek, score_candidates_jev
from .camera import CameraClip, candidate_windows, select_clips_greedy, synthesize_clip
from .editor import backup_and_write, build_editor_state, resolve_editor_dir
from .replay import parse_replay
from .styles_loader import load_style


@dataclass
class RunResult:
    replay_uuid: str
    editor_state_path: Path
    clips: List[CameraClip]
    cuts: int
    usage: AiUsage
    offline: bool


def run(
    replay: Path,
    *,
    clip_length_s: float = 10.0,
    style_id: str = "locked-dolly",
    duration_s: float = 180.0,
    project: str = "",
    timelapse: bool = False,
    editor_dir: Optional[Path] = None,
    offline: bool = False,
    max_ai_usd: float = 0.05,
    think: bool = False,
    dry_run: bool = False,
    max_candidates: int = 500,
) -> RunResult:
    style = load_style(style_id)
    traj = parse_replay(replay)
    clip_ticks = max(20, int(round(clip_length_s * 20)))
    target_ticks = max(clip_ticks, int(round(duration_s * 20)))
    target_clips = max(1, target_ticks // clip_ticks)

    cands = candidate_windows(
        traj, clip_ticks=clip_ticks, max_candidates=max_candidates
    )
    if not cands:
        raise RuntimeError(
            "No valid clip windows found (replay too short or only teleport cuts)."
        )

    cfg = AiConfig(offline=offline, max_ai_usd=max_ai_usd, think=think)
    usage = AiUsage()
    cands = score_candidates_jev(
        cands, project=project, cfg=cfg, usage=usage
    )

    indices = plan_clips_deepseek(
        cands,
        project=project,
        target_clips=target_clips,
        clip_seconds=clip_length_s,
        cfg=cfg,
        usage=usage,
    )

    clips: List[CameraClip]
    if indices:
        clips = []
        used_ranges = []
        for idx in indices:
            t0, t1, score = cands[idx]
            if any(not (t1 < a or t0 > b) for a, b in used_ranges):
                continue
            clip = synthesize_clip(traj, t0, t1, style, score=score)
            if clip:
                clips.append(clip)
                used_ranges.append((t0, t1))
            if len(clips) >= target_clips:
                break
        clips.sort(key=lambda c: c.start_tick)
    else:
        clips = select_clips_greedy(
            cands, traj, style, target_ticks=target_ticks, min_gap_ticks=0
        )

    if not clips:
        raise RuntimeError("Clip selection produced no cameras.")

    ed_dir = resolve_editor_dir(replay, editor_dir)
    state = build_editor_state(
        clips,
        replay_path=replay,
        total_ticks=traj.meta.total_ticks,
        fill_timelapse=timelapse,
    )

    if dry_run:
        out = ed_dir / "editor_states" / f"{traj.meta.uuid}.json.dry_run"
        out.parent.mkdir(parents=True, exist_ok=True)
        import json

        out.write_text(json.dumps(state, indent=2))
    else:
        out = backup_and_write(state, ed_dir, traj.meta.uuid)

    return RunResult(
        replay_uuid=traj.meta.uuid,
        editor_state_path=out,
        clips=clips,
        cuts=len(traj.cuts),
        usage=usage,
        offline=cfg.offline or not (cfg.deepseek_key or cfg.typesafe_key),
    )
