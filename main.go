package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

const (
	defaultSession = "ocmodels"
	defaultKey     = "m"
)

// config holds every setting the program needs. Flags override environment
// variables, and the resolved values are passed to child picker processes so a
// key binding or a bootstrap always behaves like the invocation that started it.
type config struct {
	Session   string
	Key       string
	Dir       string
	Fzf       string
	Psmux     string
	Opencode  string
	Exe       string
	Namespace string
	Mini      bool
}

type options struct {
	Session   string
	Key       string
	Dir       string
	Ephemeral bool
	Full      bool
}

func main() {
	if err := run(os.Args[1:]); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return
		}
		fmt.Fprintln(os.Stderr, "ocmodel:", err)
		os.Exit(1)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func envBool(key string, fallback bool) bool {
	v := strings.ToLower(strings.TrimSpace(os.Getenv(key)))
	switch v {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	default:
		return fallback
	}
}

func loadConfig() config {
	exe, err := os.Executable()
	if err != nil {
		exe = "ocmodel"
	}
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}
	return config{
		Session:   envOr("OCMODEL_SESSION", defaultSession),
		Key:       envOr("OCMODEL_KEY", defaultKey),
		Fzf:       envOr("OCMODEL_FZF", ""),
		Psmux:     envOr("OCMODEL_PSMUX", defaultPsmux()),
		Opencode:  envOr("OCMODEL_OPENCODE", "opencode"),
		Exe:       exe,
		Namespace: envOr("OCMODEL_NAMESPACE", envOr("PSMUX_NAMESPACE", "")),
		Mini:      envBool("OCMODEL_MINI", true),
	}
}

func newFlagSet(name string, c *config, o *options) *flag.FlagSet {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	fs.SetOutput(os.Stderr)
	fs.StringVar(&o.Session, "session", c.Session, "psmux session used by bootstrap (also OCMODEL_SESSION)")
	fs.StringVar(&o.Session, "s", c.Session, "alias for --session")
	fs.StringVar(&o.Key, "key", c.Key, "prefix key for the model picker binding (also OCMODEL_KEY)")
	fs.StringVar(&o.Key, "k", c.Key, "alias for --key")
	fs.StringVar(&o.Dir, "dir", "", "working directory for the new OpenCode tab")
	fs.StringVar(&o.Dir, "d", "", "alias for --dir")
	fs.BoolVar(&o.Ephemeral, "ephemeral", false, "close the picker window after launching OpenCode")
	fs.BoolVar(&c.Mini, "mini", c.Mini, "use 'opencode mini --model' (default)")
	fs.BoolVar(&o.Full, "full", false, "use the full OpenCode TUI instead of mini")
	fs.StringVar(&c.Fzf, "fzf", c.Fzf, "path to fzf (also OCMODEL_FZF)")
	fs.StringVar(&c.Psmux, "psmux", c.Psmux, "path to psmux (also OCMODEL_PSMUX)")
	fs.StringVar(&c.Opencode, "opencode", c.Opencode, "path to opencode (also OCMODEL_OPENCODE)")
	fs.StringVar(&c.Namespace, "namespace", c.Namespace, "psmux -L namespace (also OCMODEL_NAMESPACE)")
	fs.StringVar(&c.Namespace, "L", c.Namespace, "alias for --namespace")
	fs.Usage = func() { printUsage(fs) }
	return fs
}

func printUsage(fs *flag.FlagSet) {
	fmt.Fprintf(fs.Output(), `ocmodel - pick an OpenCode model with fzf and open it in a psmux tab

USAGE
  ocmodel [flags]                 bootstrap a psmux session and show the picker
  ocmodel pick [flags]            show the picker in the current psmux pane
  ocmodel open [flags]            open a picker tab (used by the key binding)
  ocmodel install [flags]         install the prefix key binding into psmux config
  ocmodel models [flags]          print the models offered by 'opencode models'
  ocmodel help                    show this help

EXAMPLES
  ocmodel install                 bind prefix+%s to the model picker
  ocmodel                         start/attach session %s and pick a model
  prefix+%s                       pick another model from anywhere in psmux

FLAGS
`, defaultKey, defaultSession, defaultKey)
	fs.PrintDefaults()
}

func run(args []string) error {
	c := loadConfig()
	o := options{Session: c.Session, Key: c.Key}

	cmd := "bootstrap"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd = args[0]
		args = args[1:]
	}
	fs := newFlagSet(cmd, &c, &o)
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() > 0 {
		return fmt.Errorf("unexpected argument %q", fs.Arg(0))
	}
	if o.Full {
		c.Mini = false
	}

	switch cmd {
	case "bootstrap":
		return bootstrap(c, o)
	case "pick":
		return pick(c, o)
	case "open":
		o.Ephemeral = true
		return openPicker(c, o)
	case "install":
		return installBinding(c, o)
	case "models":
		models, err := listModels(c)
		if err != nil {
			return err
		}
		_, err = io.Copy(os.Stdout, strings.NewReader(strings.Join(models, "\n")+"\n"))
		return err
	case "help", "-h", "--help":
		printUsage(fs)
		return nil
	default:
		return fmt.Errorf("unknown command %q (try 'ocmodel help')", cmd)
	}
}

// bootstrap starts the picker either in the current pane (when already inside
// psmux) or in a fresh/existing session that it then attaches to.
func bootstrap(c config, o options) error {
	if inPsmux() {
		return pick(c, o)
	}

	dir := o.Dir
	if dir == "" {
		wd, err := os.Getwd()
		if err != nil {
			return err
		}
		dir = wd
	}

	if c.sessionExists(o.Session) {
		args := []string{"new-window", "-t", o.Session, "-n", "ocmodel", "-c", dir, "--", c.Exe}
		args = append(args, pickerArgs(c, dir, o)...)
		if _, err := c.psmuxRun(args...); err != nil {
			return err
		}
	} else {
		args := []string{"new-session", "-d", "-s", o.Session, "-n", "ocmodel", "--", c.Exe}
		args = append(args, pickerArgs(c, dir, o)...)
		cmd := execCommand(c.Psmux, c.psmuxArgs(args...)...)
		cmd.Dir = dir
		if out, err := cmd.CombinedOutput(); err != nil {
			return fmt.Errorf("create psmux session: %w: %s", err, strings.TrimSpace(string(out)))
		}
	}

	return c.psmuxAttach(o.Session)
}

// pickerArgs rebuilds the exact child invocation, including resolved tool
// paths, so the picker never depends on the caller's PATH or flags.
func pickerArgs(c config, dir string, o options) []string {
	args := []string{"pick", "--dir", dir}
	if o.Ephemeral {
		args = append(args, "--ephemeral")
	}
	if c.Mini {
		args = append(args, "--mini")
	}
	// Empty values must not be passed as separate argv entries: psmux drops
	// them, which would leave the child with a dangling flag.
	if c.Fzf != "" {
		args = append(args, "--fzf", c.Fzf)
	}
	if c.Psmux != "" {
		args = append(args, "--psmux", c.Psmux)
	}
	if c.Opencode != "" {
		args = append(args, "--opencode", c.Opencode)
	}
	if c.Namespace != "" {
		args = append(args, "--namespace", c.Namespace)
	}
	return args
}

// openPicker is invoked by the psmux key binding through run-shell. It creates
// a picker tab in the session the key was pressed in.
func openPicker(c config, o options) error {
	dir := o.Dir
	if dir == "" {
		dir = c.currentDir()
	}
	args := []string{"new-window", "-n", "ocmodel", "-c", dir, "--", c.Exe}
	o.Ephemeral = true
	args = append(args, pickerArgs(c, dir, o)...)
	_, err := c.psmuxRun(args...)
	return err
}

// pick runs fzf in the current pane, then launches OpenCode in a fresh tab.
func pick(c config, o options) error {
	dir := o.Dir
	if dir == "" {
		dir = c.currentDir()
	}

	models, err := listModels(c)
	if err != nil {
		return err
	}
	fzfPath, err := findFzf(c.Fzf)
	if err != nil {
		return err
	}
	selected, err := pickWithFzf(fzfPath, models)
	if errors.Is(err, errPickerCancelled) {
		return nil
	}
	if err != nil {
		return err
	}
	if err := launchOpencode(c, selected, dir); err != nil {
		return err
	}
	// The picker window was created with this command, so psmux closes it when
	// we exit; renumber-windows keeps the remaining tabs consecutive.
	return nil
}

// modelWindowName is both the psmux tab name and the per-session key used to
// detect that a model is already open.
func modelWindowName(model string) string {
	// Dots and '#' are part of psmux window/pane target grammar, so keep the
	// tab name easy to address with `session:window`.
	name := "oc-" + strings.NewReplacer(".", "-", "#", "-").Replace(model)
	if len(name) > 80 {
		name = name[:80]
	}
	return name
}

func launchOpencode(c config, model, dir string) error {
	name := modelWindowName(model)

	// Re-select an existing tab instead of creating a duplicate. psmux window
	// names are session-scoped, so this works independently of the working dir.
	if session := c.currentSession(); session != "" {
		target, err := c.findWindowByName(session, name)
		if err != nil {
			fmt.Fprintf(os.Stderr, "ocmodel: could not check existing tabs (%v); opening a new one\n", err)
		} else if target != "" {
			return c.selectWindow(target)
		}
	}

	if c.Mini {
		return c.newWindow(name, dir, c.Opencode, "mini", "--model", model)
	}

	sessionID, err := createSession(c, model, dir)
	if err != nil {
		fmt.Fprintf(os.Stderr, "ocmodel: session API failed (%v); using opencode mini\n", err)
		return c.newWindow(name, dir, c.Opencode, "mini", "--model", model)
	}
	return c.newWindow(name, dir, c.Opencode, "--session", sessionID, dir)
}
