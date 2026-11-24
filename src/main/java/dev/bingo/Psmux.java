package dev.bingo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class Psmux {
    private final Config config;
    public Psmux(Config config) { this.config = config; }

    private List<String> args(String... args) {
        List<String> result = new ArrayList<>();
        if (!config.namespace.isBlank()) result.addAll(List.of("-L", config.namespace));
        result.addAll(List.of(args)); return result;
    }

    private Result run(List<String> args, boolean inherit) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(); command.add(config.psmux); command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (inherit) builder.inheritIO();
        Process p = builder.start();
        byte[] output = inherit ? new byte[0] : p.getInputStream().readAllBytes();
        int code = p.waitFor();
        return new Result(code, new String(output, StandardCharsets.UTF_8).trim());
    }

    private String query(String... args) throws Exception {
        Result result = run(args(args), false);
        if (result.code() != 0) throw new IOException(result.output());
        return result.output();
    }

    public boolean inside() {
        return !System.getenv().getOrDefault("TMUX", "").isBlank() || !System.getenv().getOrDefault("TMUX_PANE", "").isBlank();
    }

    public String currentDir() {
        try {
            String path = query("display-message", "-p", "#{pane_current_path}");
            if (!path.isBlank() && !path.contains("#{")) return path;
        } catch (Exception ignored) { }
        return Path.of("").toAbsolutePath().toString();
    }

    public String currentSession() {
        try {
            String session = query("display-message", "-p", "#{session_name}");
            return session.contains("#{") ? "" : session;
        } catch (Exception ignored) { return ""; }
    }

    public String findWindow(String session, String name) {
        try {
            String output = query("list-windows", "-t", session, "-F", "#{window_index}|#{window_name}");
            for (String line : output.split("\\R")) {
                String[] parts = line.split("\\|", 2);
                if (parts.length == 2 && parts[1].trim().equals(name)) return session + ":" + parts[0].trim();
            }
        } catch (Exception ignored) { }
        return "";
    }

    public void selectWindow(String target) throws Exception {
        Result r = run(args("select-window", "-t", target), false);
        if (r.code() != 0) throw new IOException("psmux select-window failed: " + r.output());
    }

    public void newWindow(String name, String dir, List<String> command) throws Exception {
        List<String> values = new ArrayList<>(List.of("new-window", "-n", name, "-c", dir, "--"));
        values.addAll(command);
        Result r = run(args(values.toArray(String[]::new)), false);
        if (r.code() != 0) throw new IOException("psmux new-window failed: " + r.output());
    }

    public boolean sessionExists(String session) {
        try { query("has-session", "-t", session); return true; } catch (Exception ignored) { return false; }
    }

    public void attach(String session) throws Exception {
        List<String> values = args("attach", "-t", session);
        List<String> command = new ArrayList<>(); command.add(config.psmux); command.addAll(values);
        new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    public void createSession(String session, String dir, List<String> command) throws Exception {
        List<String> values = new ArrayList<>(List.of("new-session", "-d", "-s", session, "-n", "bingo", "-c", dir, "--"));
        values.addAll(command);
        Result r = run(args(values.toArray(String[]::new)), false);
        if (r.code() != 0) throw new IOException("psmux new-session failed: " + r.output());
    }

    public void runSource(Path path) throws Exception {
        Result r = run(args("source-file", path.toString()), false);
        if (r.code() != 0) throw new IOException(r.output());
    }

    private record Result(int code, String output) { }
}
