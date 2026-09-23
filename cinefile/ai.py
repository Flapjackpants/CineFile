"""Direct DeepSeek + Jev API clients with offline fallback."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Sequence, Tuple

import requests

# Off-peak list prices per 1M tokens (USD)
DEEPSEEK_IN = 0.15
DEEPSEEK_OUT = 0.60
JEV_IN = 0.042


@dataclass
class AiUsage:
    deepseek_in: int = 0
    deepseek_out: int = 0
    jev_in: int = 0

    def estimate_usd(self) -> float:
        return (
            self.deepseek_in / 1_000_000 * DEEPSEEK_IN
            + self.deepseek_out / 1_000_000 * DEEPSEEK_OUT
            + self.jev_in / 1_000_000 * JEV_IN
        )


@dataclass
class AiConfig:
    offline: bool = False
    max_ai_usd: float = 0.05
    think: bool = False
    deepseek_key: Optional[str] = field(default_factory=lambda: os.getenv("DEEPSEEK_API_KEY"))
    typesafe_key: Optional[str] = field(
        default_factory=lambda: os.getenv("TYPESAFE_API_KEY") or os.getenv("JEV_API_KEY")
    )
    deepseek_base: str = field(
        default_factory=lambda: os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
    )
    typesafe_base: str = field(
        default_factory=lambda: os.getenv("TYPESAFE_BASE_URL", "https://api.typesafe.ai")
    )


def score_candidates_jev(
    candidates: Sequence[Tuple[int, int, float]],
    *,
    project: str,
    cfg: AiConfig,
    usage: AiUsage,
    batch_size: int = 40,
) -> List[Tuple[int, int, float]]:
    """Re-score candidates with Jev; falls back to heuristic scores."""
    if cfg.offline or not cfg.typesafe_key:
        return list(candidates)

    scored: List[Tuple[int, int, float]] = []
    for i in range(0, len(candidates), batch_size):
        batch = candidates[i : i + batch_size]
        # Rough token estimate before calling
        est_tokens = 200 + len(batch) * 40
        if usage.estimate_usd() + est_tokens / 1_000_000 * JEV_IN > cfg.max_ai_usd:
            scored.extend(batch)
            continue

        state = {
            "project": project or "minecraft building session",
            "windows": [
                {
                    "id": f"w{j}",
                    "start_tick": t0,
                    "end_tick": t1,
                    "heuristic_score": round(s, 3),
                    "duration_s": round((t1 - t0) / 20.0, 2),
                }
                for j, (t0, t1, s) in enumerate(batch)
            ],
        }
        questions = {
            f"w{j}": {
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
            for j in range(len(batch))
        }
        try:
            resp = requests.post(
                f"{cfg.typesafe_base.rstrip('/')}/v1/systemone",
                headers={"Authorization": f"Bearer {cfg.typesafe_key}"},
                json={"model": "jev-latest", "state": state, "questions": questions},
                timeout=60,
            )
            resp.raise_for_status()
            body = resp.json()
            usage.jev_in += int(body.get("usage", {}).get("input_tokens", est_tokens))
            answers = body.get("answers", {})
            for j, (t0, t1, heur) in enumerate(batch):
                ans = answers.get(f"w{j}", {})
                jev_score = float(ans.get("score", 1.0))
                scored.append((t0, t1, heur * 0.3 + jev_score * 3.0))
        except Exception:
            scored.extend(batch)
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
