package main

import (
	"encoding/json"
	"fmt"
	"os/exec"
	"regexp"
	"sort"
	"strings"
)

var modelLineRE = regexp.MustCompile(`^[A-Za-z0-9_][A-Za-z0-9_.-]*/[^\s]+$`)

// parseModels keeps only provider/model lines from `opencode models`, ignoring
// headings, warnings and empty lines.
func parseModels(output string) []string {
	seen := make(map[string]bool)
	var models []string
	for _, line := range strings.Split(output, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || seen[line] || !modelLineRE.MatchString(line) {
			continue
		}
		seen[line] = true
		models = append(models, line)
	}
	sort.Strings(models)
	return models
}

// parseModelRef splits provider/model#variant into the shape used by the
// OpenCode session API (Model.Ref).
func parseModelRef(selector string) (providerID, modelID, variant string, err error) {
	variant = ""
	if i := strings.IndexByte(selector, '#'); i >= 0 {
		variant = selector[i+1:]
		selector = selector[:i]
	}
	i := strings.IndexByte(selector, '/')
	if i <= 0 || i == len(selector)-1 {
		return "", "", "", fmt.Errorf("invalid model %q: expected provider/model", selector)
	}
	return selector[:i], selector[i+1:], variant, nil
}

func listModels(c config) ([]string, error) {
	cmd := execCommand(c.Opencode, "models")
	out, err := cmd.Output()
	if err != nil {
		detail := ""
		if exitErr, ok := err.(*exec.ExitError); ok {
			detail = strings.TrimSpace(string(exitErr.Stderr))
		}
		if detail == "" {
			detail = err.Error()
		}
		return nil, fmt.Errorf("opencode models failed: %s", detail)
	}
	models := parseModels(string(out))
	if len(models) == 0 {
		return nil, fmt.Errorf("opencode models returned no models")
	}
	return models, nil
}

func sessionPayload(model, dir string) ([]byte, error) {
	providerID, modelID, variant, err := parseModelRef(model)
	if err != nil {
		return nil, err
	}
	ref := map[string]any{"id": modelID, "providerID": providerID}
	if variant != "" {
		ref["variant"] = variant
	}
	payload := map[string]any{
		"model":    ref,
		"location": map[string]string{"directory": dir},
		"title":    model,
	}
	return json.Marshal(payload)
}

// createSessionWithModel makes a session whose model is already selected; the
// full OpenCode TUI then opens that session with --session.
func createSession(c config, model, dir string) (string, error) {
	body, err := sessionPayload(model, dir)
	if err != nil {
		return "", err
	}
	cmd := execCommand(c.Opencode, "api", "post", "/api/session", "--data", string(body))
	cmd.Dir = dir
	out, err := cmd.Output()
	if err != nil {
		// `opencode api` prints JSON errors on stdout, so include both streams.
		detail := strings.TrimSpace(string(out))
		if exitErr, ok := err.(*exec.ExitError); ok {
			if stderr := strings.TrimSpace(string(exitErr.Stderr)); stderr != "" {
				if detail != "" {
					detail += ": "
				}
				detail += stderr
			}
		}
		if detail == "" {
			detail = err.Error()
		}
		return "", fmt.Errorf("%s", detail)
	}
	var response struct {
		Data struct {
			ID string `json:"id"`
		} `json:"data"`
	}
	if err := json.Unmarshal(out, &response); err != nil {
		return "", fmt.Errorf("parse session response: %w: %s", err, strings.TrimSpace(string(out)))
	}
	if response.Data.ID == "" {
		return "", fmt.Errorf("no session id in response: %s", strings.TrimSpace(string(out)))
	}
	return response.Data.ID, nil
}
