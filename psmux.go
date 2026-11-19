package main

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"strings"
)

func execCommand(name string, args ...string) *exec.Cmd {
	return exec.Command(name, args...)
}

// defaultPsmux keeps the same launcher working on Unix, where psmux is not
// available and tmux provides the compatible command/config surface.
func defaultPsmux() string {
	return resolvePsmux(runtime.GOOS, exec.LookPath)
}

func resolvePsmux(goos string, lookPath func(string) (string, error)) string {
	if goos == "windows" {
		return "psmux"
	}
	if _, err := lookPath("psmux"); err == nil {
		return "psmux"
	}
	return "tmux"
}

func inPsmux() bool {
	return os.Getenv("TMUX") != "" || os.Getenv("TMUX_PANE") != ""
}

func (c config) psmuxArgs(args ...string) []string {
	if c.Namespace == "" {
		return args
	}
	return append([]string{"-L", c.Namespace}, args...)
}

// psmuxOutput captures stdout only, which keeps formatted queries clean.
func (c config) psmuxOutput(args ...string) (string, error) {
	cmd := execCommand(c.Psmux, c.psmuxArgs(args...)...)
	out, err := cmd.Output()
	return strings.TrimRight(string(out), "\r\n"), err
}

// psmuxRun captures both streams so command errors are useful to the caller.
func (c config) psmuxRun(args ...string) (string, error) {
	cmd := execCommand(c.Psmux, c.psmuxArgs(args...)...)
	out, err := cmd.CombinedOutput()
	return strings.TrimRight(string(out), "\r\n"), err
}

func (c config) psmuxAttach(session string) error {
	cmd := execCommand(c.Psmux, c.psmuxArgs("attach", "-t", session)...)
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

func (c config) sessionExists(session string) bool {
	_, err := c.psmuxOutput("has-session", "-t", session)
	return err == nil
}

func (c config) listSessions() []string {
	out, err := c.psmuxOutput("list-sessions", "-F", "#{session_name}")
	if err != nil {
		return nil
	}
	var result []string
	for _, line := range strings.Split(out, "\n") {
		if line = strings.TrimSpace(line); line != "" {
			result = append(result, line)
		}
	}
	return result
}

func (c config) currentSession() string {
	out, err := c.psmuxOutput("display-message", "-p", "#{session_name}")
	if err != nil || out == "" || strings.Contains(out, "#{") {
		return ""
	}
	return out
}

// findWindowByName returns the psmux target of the first window in session
// whose name matches name. Window names are the persistent, per-session record
// of which model tabs are already open, so no extra state file is needed.
func (c config) findWindowByName(session, name string) (string, error) {
	out, err := c.psmuxOutput("list-windows", "-t", session, "-F", "#{window_index}|#{window_name}")
	if err != nil {
		return "", err
	}
	for _, line := range strings.Split(out, "\n") {
		parts := strings.SplitN(line, "|", 2)
		if len(parts) != 2 {
			continue
		}
		index := strings.TrimSpace(parts[0])
		windowName := strings.TrimSpace(parts[1])
		if index != "" && windowName == name {
			return session + ":" + index, nil
		}
	}
	return "", nil
}

func (c config) selectWindow(target string) error {
	out, err := c.psmuxRun("select-window", "-t", target)
	if err != nil {
		return fmt.Errorf("psmux select-window: %w: %s", err, out)
	}
	return nil
}

func (c config) currentDir() string {
	out, err := c.psmuxOutput("display-message", "-p", "#{pane_current_path}")
	if err == nil && out != "" && !strings.Contains(out, "#{") {
		return out
	}
	wd, err := os.Getwd()
	if err != nil {
		return "."
	}
	return wd
}

func (c config) currentTarget() (string, error) {
	out, err := c.psmuxOutput("display-message", "-p", "#{session_name}:#{window_index}")
	if err != nil {
		return "", err
	}
	if out == "" || strings.Contains(out, "#{") {
		return "", errors.New("could not resolve the current psmux window")
	}
	return out, nil
}

func (c config) newWindow(name, dir string, argv ...string) error {
	args := []string{"new-window", "-n", name, "-c", dir, "--"}
	args = append(args, argv...)
	out, err := c.psmuxRun(args...)
	if err != nil {
		return fmt.Errorf("psmux new-window: %w: %s", err, out)
	}
	if out != "" && strings.HasPrefix(out, "ERROR") {
		return errors.New(out)
	}
	return nil
}
