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

Opens the interactive terminal UI. Use the keyboard to select a replay, open **Settings**, and run the selected replay. Settings are edited as JSON in a vim-lite buffer:

- `i` enters insert mode; Escape returns to normal mode; use `h/j/k/l` or the arrow keys to move.
- `:w` saves and stays, `:wq` saves and returns, and `:q` returns without saving.

The settings object includes `input_path`, `output_path`, `clip_length_s`, `style_id`, `duration_s`, `project`, `timelapse`, `offline`, `max_ai_usd`, `think`, and `dry_run`. Paths accept a string or `null`; generation fields use the same defaults as the CLI. Older settings files containing only paths continue to work.

Paths are saved to `~/.config/cinefile/settings.json` (or `$XDG_CONFIG_HOME/cinefile/settings.json`).

Use ↑/↓ to select a replay, Enter or `r` to generate edits with saved settings, `s` to edit settings, and `q` to quit. Enter or Escape returns from the result screen. The title banner area is reserved for custom ASCII art and remains blank until supplied.

### Flags (scripting)

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

CLI option flags override their saved settings values. If `--editor-dir` is omitted, a saved **output path** from settings is used when present; otherwise the editor dir is inferred from the replay path.

Then **close and reopen** the replay in Flashback so it reloads `editor_states/{uuid}.json`.

### Styles

```bash
cinefile --list-styles
```

- `locked-dolly` — ep34-style locked yaw OTS (default)
- `hero-low` — low angle looking up
- `high-crane` — elevated overview
- `side-track` — strong lateral tracking
- `orbit-reveal` — gentle yaw arc across the shot

### AI (optional)

Without keys (or with `--offline`), selection is heuristic-only ($0).

```bash
export DEEPSEEK_API_KEY=...
export TYPESAFE_API_KEY=...
cinefile --replay ... --duration 180 --timelapse
```

Cost target: well under **$1 per 200 clips** (typically a few cents). Cap with `--max-ai-usd 0.05`.

## Flags

| Flag | Meaning |
|------|---------|
| `--clip-length` | Seconds per shot |
| `--duration` | Target total cinematic seconds |
| `--timelapse` | Fill gaps with 1s (`0→20` tick) timelapse tracks |
| `--editor-dir` | Instance `flashback/` folder (overrides settings output path) |
| `--dry-run` | Write `*.json.dry_run` instead of replacing state |
