package main

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

var errPickerCancelled = errors.New("model picker cancelled")

func findFzf(explicit string) (string, error) {
	if explicit != "" {
		if info, err := os.Stat(explicit); err == nil && !info.IsDir() {
			return explicit, nil
		}
		return "", fmt.Errorf("fzf not found at %q", explicit)
	}
	if path, err := exec.LookPath("fzf"); err == nil {
		return path, nil
	}
	for _, path := range fzfCandidatePaths() {
		if info, err := os.Stat(path); err == nil && !info.IsDir() {
			return path, nil
		}
	}
	return "", errors.New("fzf not found; run the matching scripts/install-prereqs-* script")
}

func fzfCandidatePaths() []string {
	paths := []string{
		`C:\Program Files\fzf\bin\fzf.exe`,
		`C:\Program Files (x86)\fzf\bin\fzf.exe`,
		`/usr/local/bin/fzf`,
		`/usr/bin/fzf`,
	}
	if home, err := os.UserHomeDir(); err == nil {
		paths = append(paths,
			filepath.Join(home, "scoop", "shims", "fzf.exe"),
			filepath.Join(home, ".local", "bin", "fzf"),
			filepath.Join(home, "AppData", "Local", "Microsoft", "WinGet", "Links", "fzf.exe"),
		)
	}
	if runtime.GOOS == "windows" {
		if local := os.Getenv("LOCALAPPDATA"); local != "" {
			paths = append(paths, filepath.Join(local, "Microsoft", "WinGet", "Links", "fzf.exe"))
		}
	}
	return paths
}

// pickWithFzf pipes the model list into fzf and captures the chosen line. fzf
// draws its UI on the controlling terminal, so stdout can safely be a pipe.
func pickWithFzf(fzfPath string, models []string) (string, error) {
	args := []string{
		"--height", "40%",
		"--layout", "reverse",
		"--border", "rounded",
		"--prompt", "OpenCode model> ",
		"--no-multi",
		"--select-1",
	}
	cmd := execCommand(fzfPath, args...)
	cmd.Stdin = strings.NewReader(strings.Join(models, "\n") + "\n")
	cmd.Stderr = os.Stderr
	var out bytes.Buffer
	cmd.Stdout = &out

	if err := cmd.Run(); err != nil {
		if out.Len() == 0 {
			if exitErr, ok := err.(*exec.ExitError); ok && (exitErr.ExitCode() == 130 || exitErr.ExitCode() == 1) {
				return "", errPickerCancelled
			}
		}
		return "", fmt.Errorf("fzf failed: %w", err)
	}
	selected := strings.TrimSpace(out.String())
	if selected == "" {
		return "", errPickerCancelled
	}
	return strings.Split(selected, "\n")[0], nil
}
