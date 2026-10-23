"""Live ASCII-art view of the camera being scored."""

from __future__ import annotations

import time
from typing import Callable, List, Optional, TextIO, Tuple

from .framing import ScoreEvent, camera_at
from .preview import INSIDE_RGB, PLAYER_RGB, SKY_RGB, render_rgb
from .replay import Pose

VIEW_COLS = 64
RAMP = ".,:;-=+*%#"
SKY_CHAR = " "
PLAYER_CHAR = "@"
INSIDE_CHAR = "X"


class _EmptyWorld:
    def raycast(self, *a, **k):
        return None


_EMPTY = _EmptyWorld()


def view_rows(cols: int) -> int:
    return max(1, round(cols * 9 / 32))


def render_ascii(
    world,
    cam: Tuple[float, float, float, float, float],
    subject: Optional[Pose],
    tick: int,
    cols: int = VIEW_COLS,
    rows: Optional[int] = None,
) -> List[str]:
    rows = rows or view_rows(cols)
    rgb = render_rgb(world if world is not None else _EMPTY, cam, subject, tick, w=cols, h=rows)
    out: List[str] = []
    for j in range(rows):
        line = []
        for i in range(cols):
            o = (j * cols + i) * 3
            px = tuple(rgb[o : o + 3])
            if px == SKY_RGB:
                line.append(SKY_CHAR)
            elif px == PLAYER_RGB:
                line.append(PLAYER_CHAR)
            elif px == INSIDE_RGB:
                line.append(INSIDE_CHAR)
            else:
                luma = (299 * px[0] + 587 * px[1] + 114 * px[2]) // 1000
                line.append(RAMP[min(len(RAMP) - 1, luma * len(RAMP) // 192)])
        out.append("".join(line))
    return out


def format_frame(event: ScoreEvent, cols: int = VIEW_COLS) -> str:
    clip, stats = event.clip, event.stats
    tick = (clip.start_tick + clip.end_tick) // 2
    cam = camera_at(clip, tick)
    subject = event.traj.sample(tick)
    header = (
        f"window {event.window_index + 1}/{event.window_total}  "
        f"ticks {clip.start_tick}-{clip.end_tick}  {clip.style_id or '?'}  "
        f"framing {stats.score:.2f}  on-screen {stats.onscreen_frac:.0%}"
    )
    if stats.world_checked:
        header += f"  blocked {stats.occluded_frac:.0%}"
    header = header[:cols]
    return header + "\n" + "\n".join(render_ascii(event.world, cam, subject, tick, cols))


class ScoreViewer:
    def __init__(
        self,
        emit: Callable[[str], None],
        *,
        cols: int = VIEW_COLS,
        interval_s: float = 0.5,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._emit = emit
        self._cols = cols
        self._interval = interval_s
        self._clock = clock
        self._last: Optional[float] = None

    def __call__(self, event: ScoreEvent) -> None:
        now = self._clock()
        if self._last is not None and now - self._last < self._interval:
            return
        self._last = now
        try:
            self._emit(format_frame(event, self._cols))
        except Exception:
            pass


class TerminalFrames:
    def __init__(self, stream: TextIO) -> None:
        self._stream = stream
        self._lines = 0

    def __call__(self, text: str) -> None:
        lines = text.split("\n")
        out = f"\x1b[{self._lines}F" if self._lines else ""
        out += "".join(f"\x1b[2K{l}\n" for l in lines)
        self._stream.write(out)
        self._stream.flush()
        self._lines = len(lines)

    def clear(self) -> None:
        if self._lines:
            self._stream.write(f"\x1b[{self._lines}F\x1b[J")
            self._stream.flush()
            self._lines = 0
