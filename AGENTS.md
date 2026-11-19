# Agents

Implementation log — one line per feature or fix, newest last.

- 2026-11-19: Added the Go module and CLI skeleton with flag/env configuration resolution (`main.go`).
- 2026-11-19: Added fzf discovery (PATH, `OCMODEL_FZF`, winget/scoop paths) and the fzf selection wrapper (`fzf.go`).
- 2026-11-19: Added model discovery from `opencode models` with provider/model filtering and sorting (`models.go`).
- 2026-11-19: Added full-TUI model selection by creating an OpenCode session through `opencode api` with the chosen model, plus an `opencode mini --model` fallback (`models.go`, `main.go`).
- 2026-11-19: Added psmux integration: new tab launch, session bootstrap/attach, and namespace (`-L`) support (`psmux.go`, `main.go`).
- 2026-11-19: Added ephemeral picker windows that close themselves after opening the new OpenCode tab (`main.go`, `psmux.go`).
- 2026-11-19: Added key-binding installation that upserts a marked block in the psmux config and applies it live to existing sessions (`config.go`).
- 2026-11-19: Added working-directory capture from the current psmux pane so new tabs inherit the caller's project (`psmux.go`, `config.go`).
- 2026-11-19: Added unit tests for model parsing, config block upserts, payload generation, and fzf discovery (`main_test.go`).
- 2026-11-19: Added a README with install, key-binding, usage, and flag documentation (`README.md`).
- 2026-11-19: Fixed the model-sort test expectation to match lexicographic ordering of provider IDs.
- 2026-11-19: Fixed session-API error reporting to include stdout, where `opencode api` writes its JSON errors.
- 2026-11-19: Fixed psmux window errors to include command output instead of a bare exit-status string.
- 2026-11-19: Fixed ephemeral cleanup from a plain shell so it never kills the newly opened OpenCode tab.
- 2026-11-19: Widened the OpenCode tab name limit so full provider/model selectors remain targetable in psmux.
- 2026-11-19: Fixed ephemeral cleanup to resolve and kill the picker window before the new tab becomes active.
- 2026-11-19: Changed OpenCode tab names to use a hyphen prefix so psmux target syntax is not ambiguous.
- 2026-11-19: Sanitized dots and variants out of psmux tab names so window targets are not parsed as pane indices.
- 2026-11-19: Fixed key-binding quoting so the executable path is quoted once for PowerShell and once for the psmux config parser.
- 2026-11-19: Added a PowerShell call-operator prefix to the key binding when psmux uses PowerShell as its shell.
- 2026-11-19: Fixed bootstrap startup by waiting for a psmux client before the picker tries to use fzf.
- 2026-11-19: Removed the bootstrap client wait because it blocked attaching and detached panes already provide fzf a terminal.
- 2026-11-19: Fixed picker child arguments so empty fzf/namespace values are omitted instead of passed as dangling flags.
- 2026-11-19: Documented and verified launcher behavior for opening multiple model-specific OpenCode tabs in one psmux session.
- 2026-11-19: Fixed the key binding to launch the picker with psmux `new-window` instead of `run-shell`, avoiding Windows invalid-handle failures.
- 2026-11-19: Enabled psmux window renumbering so launcher tabs stay consecutively numbered after picker windows close.
- 2026-11-19: Fixed window gaps by letting psmux close the picker naturally instead of self-killing before renumbering could run.
- 2026-11-19: Switched the launcher default to `opencode mini --model` so same-directory sessions do not share OpenCode's session bar.
- 2026-11-19: Added `--full` and `OCMODEL_MINI=0` to opt back into the full OpenCode TUI.
- 2026-11-19: Tracked selected models by psmux tab name so reselecting a model switches to its existing tab instead of opening a duplicate.
- 2026-11-19: Added automatic tmux selection on Linux/macOS where psmux is unavailable.
- 2026-11-19: Added reusable prerequisite install scripts for Windows, Linux, and macOS.
- 2026-11-19: Documented the platform prerequisite scripts and tmux behavior in the README.
