# ocmodel

Pick an OpenCode model with `fzf` and open it in a new `psmux` tab. The picker
can be reopened from any pane with a psmux prefix key.

## Requirements

- Go (to build this program)
- [psmux](https://github.com/psmux/psmux) 3.x on Windows; tmux on Linux/macOS
  (psmux is Windows-only, and ocmodel auto-selects tmux when psmux is missing)
- OpenCode CLI (`opencode models` must work)
- fzf

## Prerequisite install scripts

Run the script for your platform; each one installs Go, the multiplexer, fzf,
and the OpenCode CLI, and is safe to rerun:

```powershell
# Windows (PowerShell)
.\scripts\install-prereqs-windows.ps1
```

```bash
# Linux
bash scripts/install-prereqs-linux.sh

# macOS
bash scripts/install-prereqs-macos.sh
```

Set `OCMODEL_PSMUX` if you need to override the auto-detected multiplexer.

## Build and install

```powershell
# Windows
go build -o ocmodel.exe .
.\ocmodel.exe install          # binds prefix+m in ~/.psmux.conf and live sessions
```

```bash
# Linux/macOS
go build -o ocmodel .
./ocmodel install              # binds prefix+m in ~/.tmux.conf and live sessions
```

## Use

```powershell
.\ocmodel.exe                  # start/attach the "ocmodels" session and pick a model
```

Then, from **any** multiplexer tab (psmux on Windows, tmux on Unix):

- press `prefix+m` to open the fzf model picker in a new tab
- select a model → a new tab runs OpenCode with that model selected
- the picker tab closes itself automatically
- press `prefix+m` again to launch another model in another tab of the **same** multiplexer session
- selecting a model that is already open switches back to that existing tab instead of creating a duplicate

For example: select `opencode/nemotron-3.5-lightning-free`, then press `prefix+m`
and select `opencode/big-pickle`. Both OpenCode tabs stay open side by side in
the same multiplexer session, each with its own model. Selecting `beam-free` again
later routes you straight back to the already-open `beam-free` tab.

You can also run `ocmodel` directly inside a multiplexer pane; in that case the pane is
left open and only the new OpenCode tab is created.

## How the model is applied

By default each tab runs `opencode mini --model <model>`. The mini interface
does not show OpenCode's shared session bar, so tabs in the same directory stay
visually isolated.

The full OpenCode TUI does not accept `--model`. With `--full` (or
`OCMODEL_MINI=0`), `ocmodel` instead creates an OpenCode session through
`opencode api post /api/session` with the selected `provider/model`, then opens
it with `opencode --session <id> <dir>`. OpenCode caches sessions per directory,
so the full TUI will show the other sessions from that folder in its tab bar.

## Options

| Flag | Env | Meaning |
| --- | --- | --- |
| `--session`, `-s` | `OCMODEL_SESSION` | multiplexer session name (default `ocmodels`) |
| `--key`, `-k` | `OCMODEL_KEY` | prefix key for the picker binding (default `m`) |
| `--dir`, `-d` | — | working directory for the new OpenCode tab |
| `--mini` | `OCMODEL_MINI=1` | use `opencode mini --model` (default) |
| `--full` | `OCMODEL_MINI=0` | use the full OpenCode TUI with a session API object |
| `--fzf` | `OCMODEL_FZF` | explicit fzf path |
| `--psmux` | `OCMODEL_PSMUX` | multiplexer path (auto: psmux on Windows, tmux on Unix) |
| `--opencode` | `OCMODEL_OPENCODE` | explicit opencode path |
| `--namespace`, `-L` | `OCMODEL_NAMESPACE` | multiplexer `-L` socket namespace |

Subcommands: `pick`, `open`, `install`, `models`, `help`.

## Development

```powershell
go test ./...
go vet ./...
```

`AGENTS.md` contains a one-line log of every feature and fix.
