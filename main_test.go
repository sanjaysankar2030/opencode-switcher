package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseModels(t *testing.T) {
	input := "\nlist of models\nopencode/big-pickle\r\nanthropic/claude-sonnet-5-5\nnoise line\nopencode/big-pickle\nopenrouter/anthropic/claude-sonnet-5.5\n"
	got := parseModels(input)
	want := []string{
		"anthropic/claude-sonnet-5-5",
		"opencode/big-pickle",
		"openrouter/anthropic/claude-sonnet-5.5",
	}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("parseModels() = %q, want %q", got, want)
	}
}

func TestParseModelRef(t *testing.T) {
	tests := []struct {
		selector string
		provider string
		model    string
		variant  string
		wantErr  bool
	}{
		{"opencode/big-pickle", "opencode", "big-pickle", "", false},
		{"openrouter/anthropic/claude-sonnet-5.5#high", "openrouter", "anthropic/claude-sonnet-5.5", "high", false},
		{"bad", "", "", "", true},
	}
	for _, tc := range tests {
		provider, model, variant, err := parseModelRef(tc.selector)
		if (err != nil) != tc.wantErr {
			t.Errorf("parseModelRef(%q) error = %v, wantErr %v", tc.selector, err, tc.wantErr)
			continue
		}
		if provider != tc.provider || model != tc.model || variant != tc.variant {
			t.Errorf("parseModelRef(%q) = %q,%q,%q; want %q,%q,%q", tc.selector, provider, model, variant, tc.provider, tc.model, tc.variant)
		}
	}
}

func TestSessionPayload(t *testing.T) {
	data, err := sessionPayload("openrouter/anthropic/claude#high", `E:\test`)
	if err != nil {
		t.Fatal(err)
	}
	var payload struct {
		Model struct {
			ID         string `json:"id"`
			ProviderID string `json:"providerID"`
			Variant    string `json:"variant"`
		} `json:"model"`
		Location struct {
			Directory string `json:"directory"`
		} `json:"location"`
	}
	if err := json.Unmarshal(data, &payload); err != nil {
		t.Fatal(err)
	}
	if payload.Model.ProviderID != "openrouter" || payload.Model.ID != "anthropic/claude" || payload.Model.Variant != "high" {
		t.Fatalf("unexpected model ref: %+v", payload.Model)
	}
	if payload.Location.Directory != `E:\test` {
		t.Fatalf("unexpected location: %+v", payload.Location)
	}
}

func TestUpsertBlock(t *testing.T) {
	path := filepath.Join(t.TempDir(), "psmux.conf")
	initial := "set -g prefix C-a\n\n# my own binding\nbind-key x split-window\n"
	if err := os.WriteFile(path, []byte(initial), 0o644); err != nil {
		t.Fatal(err)
	}

	c := config{Exe: `E:/test/ocmodel.exe`}
	block := buildBlock(c, "m")
	if err := upsertBlock(path, block); err != nil {
		t.Fatal(err)
	}
	if err := upsertBlock(path, buildBlock(c, "n")); err != nil {
		t.Fatal(err)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	content := string(data)
	if strings.Count(content, blockBegin) != 1 || strings.Count(content, blockEnd) != 1 {
		t.Fatalf("expected one ocmodel block, got:\n%s", content)
	}
	if !strings.Contains(content, "# my own binding") {
		t.Fatalf("existing config was lost:\n%s", content)
	}
	if !strings.Contains(content, "bind-key -T prefix n new-window") {
		t.Fatalf("binding was not replaced:\n%s", content)
	}
}

func TestBuildBlockUsesPaneDirectory(t *testing.T) {
	c := config{Exe: `C:\Program Files\ocmodel\ocmodel.exe`}
	block := buildBlock(c, "m")
	if !strings.Contains(block, "set-option -g renumber-windows on") {
		t.Fatalf("window renumbering is not enabled:\n%s", block)
	}
	if !strings.Contains(block, "bind-key -T prefix m new-window") {
		t.Fatalf("missing binding:\n%s", block)
	}
	if !strings.Contains(block, "#{pane_current_path}") {
		t.Fatalf("binding does not carry the pane directory:\n%s", block)
	}
	if !strings.Contains(block, `"C:/Program Files/ocmodel/ocmodel.exe"`) {
		t.Fatalf("exe path is not quoted for the config parser:\n%s", block)
	}
	if strings.Contains(block, "run-shell") || strings.Contains(block, "& ") {
		t.Fatalf("binding still depends on a shell:\n%s", block)
	}
}

func TestResolvePsmux(t *testing.T) {
	onlyTmux := func(name string) (string, error) {
		if name == "tmux" {
			return "/usr/bin/tmux", nil
		}
		return "", errors.New("not found")
	}
	if got := resolvePsmux("windows", onlyTmux); got != "psmux" {
		t.Errorf("resolvePsmux(windows) = %q, want psmux", got)
	}
	if got := resolvePsmux("linux", onlyTmux); got != "tmux" {
		t.Errorf("resolvePsmux(linux) = %q, want tmux", got)
	}
	both := func(name string) (string, error) { return "/usr/bin/" + name, nil }
	if got := resolvePsmux("darwin", both); got != "psmux" {
		t.Errorf("resolvePsmux(darwin with psmux) = %q, want psmux", got)
	}
}

func TestModelWindowName(t *testing.T) {
	cases := map[string]string{
		"opencode/beam-free":               "oc-opencode/beam-free",
		"opencode/big-pickle":              "oc-opencode/big-pickle",
		"openrouter/anthropic/claude#high": "oc-openrouter/anthropic/claude-high",
	}
	for model, want := range cases {
		if got := modelWindowName(model); got != want {
			t.Errorf("modelWindowName(%q) = %q, want %q", model, got, want)
		}
	}
}

func TestBindingForwardsMiniMode(t *testing.T) {
	c := config{Exe: `E:/test/ocmodel.exe`, Psmux: "psmux", Opencode: "opencode", Mini: true}
	block := buildBlock(c, "m")
	if !strings.Contains(block, "pick --ephemeral") {
		t.Fatalf("missing picker invocation:\n%s", block)
	}
	if !strings.Contains(block, "--mini") {
		t.Fatalf("mini mode was not forwarded:\n%s", block)
	}
	c.Mini = false
	block = buildBlock(c, "m")
	if !strings.Contains(block, "--full") {
		t.Fatalf("full-mode opt-out was not forwarded:\n%s", block)
	}
}

func TestPickerArgsOmitsEmptyFlags(t *testing.T) {
	c := config{Psmux: "psmux", Opencode: "opencode"}
	o := options{Dir: `E:\test`}
	args := pickerArgs(c, `E:\test`, o)
	want := []string{"pick", "--dir", `E:\test`, "--psmux", "psmux", "--opencode", "opencode"}
	if strings.Join(args, "|") != strings.Join(want, "|") {
		t.Fatalf("pickerArgs() = %q, want %q", args, want)
	}
	for _, arg := range args {
		if arg == "" {
			t.Fatalf("pickerArgs() contains an empty argument: %q", args)
		}
	}
}

func TestFindFzfExplicit(t *testing.T) {
	path := filepath.Join(t.TempDir(), "fzf.exe")
	if err := os.WriteFile(path, []byte("fake"), 0o644); err != nil {
		t.Fatal(err)
	}
	got, err := findFzf(path)
	if err != nil || got != path {
		t.Fatalf("findFzf(%q) = %q, %v", path, got, err)
	}
	if _, err := findFzf(filepath.Join(t.TempDir(), "missing.exe")); err == nil {
		t.Fatal("expected an error for a missing explicit fzf path")
	}
}
