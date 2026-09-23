"""Write Flashback editor_states JSON matching hand-keyed schema."""

from __future__ import annotations

import json
import shutil
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

from .camera import CameraClip


DEFAULT_VISUALS: Dict[str, Any] = {
    "showChat": False,
    "showBossBar": False,
    "showTitleText": False,
    "showScoreboard": False,
    "showActionBar": False,
    "showHotbar": True,
    "renderBlocks": True,
    "renderEntities": True,
    "renderPlayers": True,
    "renderParticles": True,
    "renderSky": True,
    "skyColour": [0.0, 1.0, 0.0],
    "renderNametags": False,
    "renderBeaconBeams": True,
    "overrideFog": False,
    "overrideFogStart": 0.0,
    "overrideFogEnd": 256.0,
    "overrideFogColour": False,
    "fogColour": [0.0, 1.0, 0.0],
    "overrideFov": False,
    "overrideFovAmount": -1.0,
    "overrideCameraShake": False,
    "cameraShakeSplitParams": False,
    "cameraShakeYFrequency": 1.0,
    "cameraShakeYAmplitude": 1.0,
    "cameraShakeXFrequency": 1.0,
    "cameraShakeXAmplitude": 1.0,
    "overrideRoll": False,
    "overrideRollAmount": 0.0,
    "overrideWeatherMode": "NONE",
    "overrideTimeOfDay": -1,
    "overrideNightVision": False,
    "cameraPath": True,
    "renderSizeX": 0,
    "renderSizeY": 0,
}


def _camera_kf(kf) -> Dict[str, Any]:
    return {
        "position": [kf.x, kf.y, kf.z],
        "yaw": kf.yaw,
        "pitch": kf.pitch,
        "roll": kf.roll,
        "type": "camera",
        "interpolation_type": "SMOOTH",
    }


def _timelapse_track(t0: int, t1: int) -> Dict[str, Any]:
    return {
        "keyframeType": "TIMELAPSE",
        "keyframesByTick": {
            str(t0): {"ticks": 0, "type": "timelapse"},
            str(t1): {"ticks": 20, "type": "timelapse"},
        },
        "enabled": True,
        "customColour": 0,
    }


def build_editor_state(
    clips: Sequence[CameraClip],
    *,
    replay_path: Path,
    total_ticks: int,
    fill_timelapse: bool = False,
    base: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    if base is None:
        state: Dict[str, Any] = {
            "replayVisuals": dict(DEFAULT_VISUALS),
            "sceneLock": {},
            "scenes": [],
            "sceneIndex": 0,
            "zoomMin": 0.0,
            "zoomMax": 1.0,
            "usedByPaths": [str(replay_path.resolve())],
            "hideDuringExport": [],
            "hideAllSpectators": False,
            "muteVoice": [],
            "hideNametags": [],
            "skinOverride": {},
            "skinOverrideFromFile": {},
            "nameOverride": {},
            "glowingOverride": {},
            "hideTeamPrefix": [],
            "hideTeamSuffix": [],
            "hideBelowName": [],
            "hideCape": [],
            "filteredEntities": [],
            "filteredParticles": [],
            "hiddenEquipment": {},
            "hiddenModelParts": {},
        }
    else:
        state = json.loads(json.dumps(base))  # deep copy
        state["usedByPaths"] = [str(replay_path.resolve())]

    tracks: List[Dict[str, Any]] = []
    for clip in clips:
        a, b = clip.keyframes
        tracks.append(
            {
                "keyframeType": "CAMERA",
                "keyframesByTick": {
                    str(a.tick): _camera_kf(a),
                    str(b.tick): _camera_kf(b),
                },
                "enabled": True,
                "customColour": 0,
            }
        )

    if fill_timelapse and clips:
        ordered = sorted(clips, key=lambda c: c.start_tick)
        for i in range(len(ordered) - 1):
            gap_start = ordered[i].end_tick
            gap_end = ordered[i + 1].start_tick
            if gap_end - gap_start >= 5:
                tracks.append(_timelapse_track(gap_start, gap_end))

    export_start = min(c.start_tick for c in clips) if clips else 0
    export_end = max(c.end_tick for c in clips) if clips else max(0, total_ticks - 1)

    state["scenes"] = [
        {
            "name": "Scene 1",
            "keyframeTracks": tracks,
            "exportStartTicks": export_start,
            "exportEndTicks": export_end,
            "history": {"entries": [], "position": -1},
        }
    ]
    state["sceneIndex"] = 0
    return state


def normalize_editor_dir(path: Path) -> Path:
    """Accept …/flashback or …/editor_states; return the Flashback data dir."""
    p = path.expanduser().resolve()
    if p.name == "editor_states":
        return p.parent
    return p


def resolve_editor_dir(replay_path: Path, editor_dir: Optional[Path]) -> Path:
    if editor_dir is not None:
        return normalize_editor_dir(editor_dir)
    # replay lives in .../flashback/replays/file.zip → editor_states sibling
    parent = replay_path.resolve().parent
    if parent.name == "replays" and parent.parent.name == "flashback":
        return parent.parent
    # also accept .../flashback/
    if parent.name == "flashback":
        return parent
    raise ValueError(
        "Could not auto-detect Flashback data dir. Pass --editor-dir "
        "pointing at the instance's flashback/ folder."
    )


def backup_and_write(state: Dict[str, Any], editor_dir: Path, replay_uuid: str) -> Path:
    states_dir = editor_dir / "editor_states"
    backups_dir = editor_dir / "editor_backups"
    states_dir.mkdir(parents=True, exist_ok=True)
    backups_dir.mkdir(parents=True, exist_ok=True)

    out = states_dir / f"{replay_uuid}.json"
    if out.exists():
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        shutil.copy2(out, backups_dir / f"{replay_uuid}_{stamp}.json")

    out.write_text(json.dumps(state, separators=(",", ":")))
    return out
