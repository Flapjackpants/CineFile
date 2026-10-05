"""Write Flashback editor_states JSON matching hand-keyed schema."""

from __future__ import annotations

import json
import shutil
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

from .camera import CameraClip
from .settings import normalize_render_instance_dir


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


def _camera_track(clip: CameraClip) -> Dict[str, Any]:
    a, b = clip.keyframes
    return {
        "keyframeType": "CAMERA",
        "keyframesByTick": {
            str(a.tick): _camera_kf(a),
            str(b.tick): _camera_kf(b),
        },
        "enabled": True,
        "customColour": 0,
    }


def _overlaps(t0: int, t1: int, ranges: Sequence[Tuple[int, int]]) -> bool:
    return any(not (t1 < a or t0 > b) for a, b in ranges)


def _clip_tracks(
    clips: Sequence[CameraClip],
    *,
    fill_timelapse: bool,
    occupied: Sequence[Tuple[int, int]] = (),
) -> List[Dict[str, Any]]:
    tracks = [_camera_track(clip) for clip in clips]
    if fill_timelapse and clips:
        ordered = sorted(clips, key=lambda c: c.start_tick)
        for i in range(len(ordered) - 1):
            gap_start = ordered[i].end_tick
            gap_end = ordered[i + 1].start_tick
            if gap_end - gap_start >= 5 and not _overlaps(gap_start, gap_end, occupied):
                tracks.append(_timelapse_track(gap_start, gap_end))
    return tracks


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

    tracks = _clip_tracks(clips, fill_timelapse=fill_timelapse)

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
    """Accept an instance root, …/flashback or …/editor_states; return the Flashback data dir."""
    return normalize_render_instance_dir(path)


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


def load_existing_state(editor_dir: Path, replay_uuid: str) -> Optional[Dict[str, Any]]:
    """Return the saved editor state for a replay, or None when there is none."""
    path = editor_dir / "editor_states" / f"{replay_uuid}.json"
    if not path.exists():
        return None
    try:
        state = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Existing editor state is unreadable, refusing to overwrite: {path} ({exc})") from exc
    if not isinstance(state, dict):
        raise ValueError(f"Existing editor state is not a JSON object, refusing to overwrite: {path}")
    return state


def _active_scene(state: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    scenes = state.get("scenes")
    if not isinstance(scenes, list) or not scenes:
        return None
    index = state.get("sceneIndex", 0)
    if not isinstance(index, int) or not 0 <= index < len(scenes):
        index = 0
    scene = scenes[index]
    return scene if isinstance(scene, dict) else None


def occupied_ranges(state: Dict[str, Any]) -> List[Tuple[int, int]]:
    """Tick ranges already covered by keyframe tracks in the active scene."""
    scene = _active_scene(state)
    if scene is None:
        return []
    ranges: List[Tuple[int, int]] = []
    for track in scene.get("keyframeTracks") or []:
        ticks = [int(t) for t in (track.get("keyframesByTick") or {}) if str(t).lstrip("-").isdigit()]
        if ticks:
            ranges.append((min(ticks), max(ticks)))
    return sorted(ranges)


def merge_editor_state(
    base: Dict[str, Any],
    clips: Sequence[CameraClip],
    *,
    replay_path: Path,
    total_ticks: int,
    fill_timelapse: bool = False,
) -> Dict[str, Any]:
    """Append new tracks to an existing state without touching its keyframes."""
    if not clips:
        raise ValueError("No clips to merge into the existing editor state.")
    state = json.loads(json.dumps(base))  # deep copy
    occupied = occupied_ranges(state)
    for clip in clips:
        if _overlaps(clip.start_tick, clip.end_tick, occupied):
            raise ValueError(f"Clip {clip.start_tick}-{clip.end_tick} overlaps existing keyframes.")

    used = state.get("usedByPaths")
    replay_str = str(replay_path.resolve())
    if not isinstance(used, list):
        state["usedByPaths"] = [replay_str]
    elif replay_str not in used:
        used.append(replay_str)

    scene = _active_scene(state)
    if scene is None:
        fresh = build_editor_state(
            clips,
            replay_path=replay_path,
            total_ticks=total_ticks,
            fill_timelapse=fill_timelapse,
        )
        state["scenes"] = fresh["scenes"]
        state["sceneIndex"] = 0
        return state

    tracks = scene.setdefault("keyframeTracks", [])
    tracks.extend(_clip_tracks(clips, fill_timelapse=fill_timelapse, occupied=occupied))
    new_start = min(c.start_tick for c in clips)
    new_end = max(c.end_tick for c in clips)
    old_start = scene.get("exportStartTicks")
    old_end = scene.get("exportEndTicks")
    scene["exportStartTicks"] = min(old_start, new_start) if isinstance(old_start, int) else new_start
    scene["exportEndTicks"] = max(old_end, new_end) if isinstance(old_end, int) else new_end
    return state


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
