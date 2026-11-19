package main

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

const (
	blockBegin = "# >>> ocmodel >>>"
	blockEnd   = "# <<< ocmodel <<<"
)

func configPath() (string, error) {
	if p := os.Getenv("PSMUX_CONFIG"); p != "" {
		return p, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	candidates := []string{
		filepath.Join(home, ".psmux.conf"),
		filepath.Join(home, ".psmuxrc"),
		filepath.Join(home, ".tmux.conf"),
		filepath.Join(home, ".config", "psmux", "psmux.conf"),
	}
	defaultPath := filepath.Join(home, ".psmux.conf")
	if runtime.GOOS != "windows" {
		// tmux is the multiplexer on Unix; write where it actually loads config.
		candidates = []string{
			filepath.Join(home, ".tmux.conf"),
			filepath.Join(home, ".config", "tmux", "tmux.conf"),
			filepath.Join(home, ".psmux.conf"),
		}
		defaultPath = filepath.Join(home, ".tmux.conf")
	}
	for _, p := range candidates {
		if info, err := os.Stat(p); err == nil && !info.IsDir() {
			return p, nil
		}
	}
	return defaultPath, nil
}

// configQuote makes a value a single psmux-config argument. Forward slashes
// avoid Windows escape surprises, and embedded quotes are escaped for the
// config parser.
func configQuote(value string) string {
	value = strings.ReplaceAll(value, `\`, "/")
	value = strings.ReplaceAll(value, `"`, `\"`)
	return `"` + value + `"`
}

// pickerFlags forwards resolved tool paths without passing empty values, which
// psmux would otherwise turn into dangling arguments.
func pickerFlags(c config) []string {
	var args []string
	add := func(flag, value string) {
		if value != "" {
			args = append(args, flag, configQuote(value))
		}
	}
	add("--fzf", c.Fzf)
	add("--psmux", c.Psmux)
	add("--opencode", c.Opencode)
	add("--namespace", c.Namespace)
	if c.Mini {
		args = append(args, "--mini")
	} else {
		args = append(args, "--full")
	}
	return args
}

// bindingLine uses new-window instead of run-shell. run-shell has to start a
// shell process, which fails on some Windows psmux servers with
// "The handle is invalid" (os error 6); new-window starts our exe directly.
func bindingLine(c config, key string) string {
	args := []string{
		"new-window",
		"-n", "ocmodel",
		"-c", "'#{pane_current_path}'",
		"--", configQuote(c.Exe),
		"pick", "--ephemeral",
	}
	args = append(args, pickerFlags(c)...)
	return fmt.Sprintf("bind-key -T prefix %s %s", key, strings.Join(args, " "))
}

func buildBlock(c config, key string) string {
	return strings.Join([]string{
		blockBegin,
		"set-option -g renumber-windows on",
		fmt.Sprintf("# prefix+%s: pick an OpenCode model and open it in a new tab", key),
		bindingLine(c, key),
		blockEnd,
	}, "\n") + "\n"
}

// upsertBlock replaces a previous ocmodel block, or appends one when absent.
func upsertBlock(path, block string) error {
	content := ""
	if data, err := os.ReadFile(path); err == nil {
		content = string(data)
	} else if !os.IsNotExist(err) {
		return err
	}

	start := strings.Index(content, blockBegin)
	end := strings.Index(content, blockEnd)
	if start >= 0 && end > start {
		end += len(blockEnd)
		if end < len(content) && content[end] == '\r' {
			end++
		}
		if end < len(content) && content[end] == '\n' {
			end++
		}
		content = content[:start] + block + content[end:]
	} else {
		if content != "" && !strings.HasSuffix(content, "\n") {
			content += "\n"
		}
		if content != "" {
			content += "\n"
		}
		content += block
	}

	if dir := filepath.Dir(path); dir != "" {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
	}
	return os.WriteFile(path, []byte(content), 0o644)
}

func installBinding(c config, o options) error {
	path, err := configPath()
	if err != nil {
		return err
	}
	block := buildBlock(c, o.Key)
	if err := upsertBlock(path, block); err != nil {
		return err
	}
	fmt.Printf("installed prefix+%s in %s\n", o.Key, path)

	// Plain `source-file` works on both psmux and tmux; the -t variant is
	// psmux-only and would fail on Unix where tmux backs the psmux shim.
	out, err := c.psmuxRun("source-file", path)
	if err != nil {
		fmt.Fprintf(os.Stderr, "ocmodel: could not apply binding live: %v: %s\n", err, out)
	}
	return nil
}
