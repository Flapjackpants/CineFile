"""DeepSeek API client + local Laya (MLX) scoring with offline fallback."""

from __future__ import annotations

import json
import os
import warnings
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Sequence, Tuple

import requests

# Off-peak list prices per 1M tokens (USD)
DEEPSEEK_IN = 0.15
DEEPSEEK_OUT = 0.60


@dataclass
class AiUsage:
    deepseek_in: int = 0
    deepseek_out: int = 0
    laya_calls: int = 0

    def estimate_usd(self) -> float:
        # Laya runs locally, so only DeepSeek costs money
        return (
            self.deepseek_in / 1_000_000 * DEEPSEEK_IN
            + self.deepseek_out / 1_000_000 * DEEPSEEK_OUT
        )


@dataclass
class AiConfig:
    offline: bool = False
    max_ai_usd: float = 0.05
    think: bool = False
    deepseek_key: Optional[str] = field(default_factory=lambda: os.getenv("DEEPSEEK_API_KEY"))
    deepseek_base: str = field(
        default_factory=lambda: os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
    )
    laya_model: str = field(
        default_factory=lambda: os.getenv("LAYA_MODEL", "aac6fef/laya-mlx")
    )


_LAYA_AGENTS: Dict[str, Any] = {}

_LAYA_QUESTION = {
    "type": "score",
    "instructions": (
        "How good is this window as a cinematic Minecraft build clip? "
        "Prefer active building / interesting movement; avoid idle AFK."
    ),
    "criteria": [
        "Idle or teleport-adjacent",
        "Mildly interesting",
        "Good representative action",
        "Excellent cinematic moment",
    ],
}


def _load_laya(model_id: str) -> Any:
    """Load (and cache) a local laya-mlx agent; None if unavailable."""
    if model_id not in _LAYA_AGENTS:
        try:
            import laya_mlx

            # Checkpoint temperature-clamp warning is noise for our score use
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", RuntimeWarning)
                _LAYA_AGENTS[model_id] = laya_mlx.load(model_id)
        except Exception:
            _LAYA_AGENTS[model_id] = None
    return _LAYA_AGENTS[model_id]


def laya_available(cfg: AiConfig) -> bool:
    return not cfg.offline and _load_laya(cfg.laya_model) is not None


def score_candidates_laya(
    candidates: Sequence[Tuple[int, int, float]],
    *,
    project: str,
    cfg: AiConfig,
    usage: AiUsage,
    notes: Optional[Sequence[str]] = None,
) -> List[Tuple[int, int, float]]:
    """Re-score candidates with local Laya; falls back to heuristic scores."""
    if notes is not None and len(notes) != len(candidates):
        raise ValueError("notes must match candidates")
    if cfg.offline:
        return list(candidates)
    agent = _load_laya(cfg.laya_model)
    if agent is None:
        return list(candidates)

    # Laya has a 512-token context, so score one window per call (~20ms each)
    scored: List[Tuple[int, int, float]] = []
    for i, (t0, t1, heur) in enumerate(candidates):
        text = (
            f"Project: {project or 'minecraft building session'}. "
            f"Window {t0 / 20.0:.1f}s-{t1 / 20.0:.1f}s "
            f"({(t1 - t0) / 20.0:.1f}s). "
            f"Heuristic activity score {heur:.3f}."
        )
        if notes is not None:
            text += " " + notes[i]
        try:
            result = agent.predict(text, {"clip": _LAYA_QUESTION})
            usage.laya_calls += 1
            laya_score = float(result["answers"]["clip"].get("score", 1.0))
            scored.append((t0, t1, heur * 0.3 + laya_score * 3.0))
        except Exception:
            scored.append((t0, t1, heur))
    scored.sort(key=lambda x: x[2], reverse=True)
    return scored


def plan_clips_deepseek(
    candidates: Sequence[Tuple[int, int, float]],
    *,
    project: str,
    target_clips: int,
    clip_seconds: float,
    cfg: AiConfig,
    usage: AiUsage,
) -> Optional[List[int]]:
    """
    Ask DeepSeek to pick candidate indices for representative coverage.
    Returns list of indices into `candidates`, or None to use greedy fallback.
    """
    if cfg.offline or not cfg.deepseek_key or target_clips <= 0:
        return None

    # Only send top pool to keep cost down
    pool = list(candidates)[: min(120, len(candidates))]
    payload_windows = [
        {
            "index": i,
            "start_s": round(t0 / 20.0, 1),
            "end_s": round(t1 / 20.0, 1),
            "score": round(s, 2),
        }
        for i, (t0, t1, s) in enumerate(pool)
    ]
    messages = [
        {
            "role": "system",
            "content": (
                "You pick cinematic Minecraft Flashback clip windows. "
                "Reply with ONLY JSON: {\"indices\": [int, ...]}. "
                "Cover representative phases of the project; avoid clustering; "
                "never pick overlapping windows."
            ),
        },
        {
            "role": "user",
            "content": json.dumps(
                {
                    "project": project or "minecraft session",
                    "clip_seconds": clip_seconds,
                    "need_clips": target_clips,
                    "windows": payload_windows,
                }
            ),
        },
    ]

    est_in = 800 + len(payload_windows) * 25
    est_out = 200 + target_clips * 3
    if usage.estimate_usd() + est_in / 1e6 * DEEPSEEK_IN + est_out / 1e6 * DEEPSEEK_OUT > cfg.max_ai_usd:
        return None

    body: Dict[str, Any] = {
        "model": "deepseek-flash",
        "messages": messages,
        "temperature": 0.3,
    }
    # Prefer low reasoning cost unless --think
    if not cfg.think:
        body["reasoning_effort"] = "low"

    try:
        resp = requests.post(
            f"{cfg.deepseek_base.rstrip('/')}/chat/completions",
            headers={"Authorization": f"Bearer {cfg.deepseek_key}"},
            json=body,
            timeout=120,
        )
        resp.raise_for_status()
        data = resp.json()
        usage.deepseek_in += int(data.get("usage", {}).get("prompt_tokens", est_in))
        usage.deepseek_out += int(data.get("usage", {}).get("completion_tokens", est_out))
        content = data["choices"][0]["message"]["content"]
        # Extract JSON object
        start = content.find("{")
        end = content.rfind("}")
        if start < 0 or end < 0:
            return None
        parsed = json.loads(content[start : end + 1])
        indices = [int(i) for i in parsed.get("indices", []) if 0 <= int(i) < len(pool)]
        return indices or None
    except Exception:
        return None
