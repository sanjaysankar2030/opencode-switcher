package dev.bingo;

public enum Agent {
    PI("Pi", "pi"), CODEX("Codex", "codex"), AGY("AGY", "agy"), OPENCODE("OpenCode", "opencode");

    private final String label;
    private final String executable;

    Agent(String label, String executable) {
        this.label = label;
        this.executable = executable;
    }

    public String label() { return label; }
    public String executable() { return executable; }
}
