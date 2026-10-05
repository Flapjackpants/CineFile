# CineFile

Python CLI that automates [Flashback](https://modrinth.com/mod/flashback/) cinematic camera keyframes from a replay zip.

## Install

```bash
cd CineFile
python3 -m pip install -e .
```

## Usage

### Interactive menu

```bash
cinefile
```

Opens the terminal UI.

**Menu keys**

- `↑`/`↓` select a replay.
- `Enter` or `r` generate editor states for the selected replay, using your saved settings.
- `s` open Settings.
- `q` quit.
- On the result screen, `Enter` or `Escape` goes back to the menu.

**Editing settings**

Settings are edited as JSON in a vim-like buffer:

- `i` enters insert mode. `Escape` returns to normal mode.
- `h`/`j`/`k`/`l` or the arrow keys move the cursor.
- `:w` saves and stays. `:wq` saves and returns. `:q` returns without saving.

Settings are saved to `~/.config/cinefile/settings.json` (or `$XDG_CONFIG_HOME/cinefile/settings.json`).

### Settings

| Setting | Meaning |
|---------|---------|
| `input_flashback_folder` | The Flashback folder that holds the replay files (`.zip`) you want to edit. CineFile lists the replays it finds here. You can give the `flashback/` folder or its `replays/` subfolder. |
| `output_flashback_folder` | The Flashback folder of the Minecraft instance where the editor states are written (`flashback/editor_states/`). You can give the instance root, its `flashback/` folder, or `flashback/editor_states/`. |
| `clip_length_s` | Seconds per shot. |
| `style_id` | Camera style. See [Styles](#styles). |
| `duration_s` | Target total length of the cinematic, in seconds. |
| `project` | Optional description that helps CineFile pick representative clips. |
| `timelapse` | Fill gaps between clips with timelapse tracks. |
| `offline` | Skip DeepSeek and use heuristic-only selection. |
| `max_ai_usd` | Maximum DeepSeek spend per run, in USD. |
| `think` | Allow higher DeepSeek reasoning effort (costs more). |
| `dry_run` | Write `*.json.dry_run` files instead of changing the editor state. |

Both paths accept a string or `null`. Other settings use the same defaults as the CLI. Older settings files still work: legacy `input_path`, `render_instance_path` and `output_path` keys are migrated to the new names.

### Input and output folders

CineFile reads replays from one Flashback folder and writes editor states into another:

- **Input Flashback folder (`input_flashback_folder`)**: the Flashback folder where your replay files are. CineFile only reads from it.
- **Output Flashback folder (`output_flashback_folder`)**: the Flashback folder of the instance you render in. CineFile writes editor states to its `flashback/editor_states/`.

Use one render instance for all your renders, so mods and texture packs stay the same for every recording. Copy the replay zip into that instance's `flashback/replays/` so Flashback can open it there.

After a run, **close and reopen** the replay in Flashback. Flashback then reloads `editor_states/{uuid}.json`.

### Existing editor states

If an editor state already exists for the replay, CineFile does not overwrite or remove any of its keyframes:

- It keeps every existing track.
- It adds new camera clips only in tick ranges that have no keyframes.
- It adds timelapse gaps only where they do not cross existing keyframes.
- It backs up the previous file to `editor_backups/`.
- If no empty space is left, the run fails and nothing is written.

### Command-line flags (scripting)

```bash
cinefile \
  --replay "/path/to/flashback/replays/2026-09-15T21_38_50.zip" \
  --clip-length 10 \
  --style locked-dolly \
  --duration 180 \
  --project "building town hall" \
  --timelapse \
  --offline
```

Command-line flags override the saved settings.

If you omit `--editor-dir`, CineFile uses the saved `output_flashback_folder`. If that is not set, it infers the editor folder from the replay path.

To start the menu with paths already filled in, use `-i/--input-flashback-folder` (alias `--input`) for the input Flashback folder and `-o/--output-flashback-folder` (aliases `--output`, `--render-instance`) for the output Flashback folder. This opens Settings.

### Styles

```bash
cinefile --list-styles
```

- `locked-dolly` — ep34-style locked yaw OTS (default)
- `hero-low` — low angle looking up
- `high-crane` — elevated overview
- `side-track` — strong lateral tracking
- `orbit-reveal` — gentle yaw arc across the shot
- `auto` — builds every style per clip and keeps the best-framed one

### AI (optional)

Candidate windows are re-scored locally with [laya-mlx](https://huggingface.co/aac6fef/laya-mlx)
(Apple silicon only, installed automatically there; the ~843 MB model downloads from Hugging Face on
first run). Override the checkpoint with `LAYA_MODEL`. On other platforms this step falls back to
heuristic scores.

DeepSeek optionally picks the final clips. Without a key (or with `--offline`), selection is heuristic-only ($0).

```bash
export DEEPSEEK_API_KEY=...
cinefile --replay ... --duration 180 --timelapse
```

Laya is free; `--max-ai-usd 0.05` caps DeepSeek spend (typically a few cents per run).

## Flags

| Flag | Meaning |
|------|---------|
| `--clip-length` | Seconds per shot |
| `--duration` | Target total cinematic seconds |
| `--timelapse` | Fill gaps with 1s (`0→20` tick) timelapse tracks |
| `--editor-dir` | Instance root or `flashback/` folder (overrides the settings output Flashback folder) |
| `--dry-run` | Write `*.json.dry_run` instead of replacing state |
