package dev.bingo;

import java.nio.file.Path;
import java.util.*;

public final class Config {
    String session = env("BINGO_SESSION", "bingo");
    String key = env("BINGO_KEY", "m");
    String psmux = env("BINGO_PSMUX", isWindows() ? "psmux" : "tmux");
    String namespace = env("BINGO_NAMESPACE", env("PSMUX_NAMESPACE", ""));
    String pi = env("BINGO_PI", "pi");
    String codex = env("BINGO_CODEX", "codex");
    String agy = env("BINGO_AGY", "agy");
    String opencode = env("BINGO_OPENCODE", "opencode");
    String maven = env("BINGO_MAVEN", isWindows() ? "mvn.cmd" : "mvn");
    String dir = "";
    boolean ephemeral;
    final Path project = Path.of(System.getProperty("bingo.project", ".")).toAbsolutePath().normalize();

    String executable(Agent agent) {
        return switch (agent) {
            case PI -> pi;
            case CODEX -> codex;
            case AGY -> agy;
            case OPENCODE -> opencode;
        };
    }

    static Config parse(String[] args) {
        Config c = new Config();
        int start = args.length > 0 && !args[0].startsWith("-") ? 1 : 0;
        for (int i = start; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) continue;
            if (arg.equals("--ephemeral")) { c.ephemeral = true; continue; }
            if (!arg.startsWith("-") || i + 1 >= args.length) throw new IllegalArgumentException("invalid option: " + arg);
            String value = args[++i];
            switch (arg) {
                case "--session", "-s" -> c.session = value;
                case "--key", "-k" -> c.key = value;
                case "--dir", "-d" -> c.dir = value;
                case "--psmux" -> c.psmux = value;
                case "--namespace", "-L" -> c.namespace = value;
                case "--pi" -> c.pi = value;
                case "--codex" -> c.codex = value;
                case "--agy" -> c.agy = value;
                case "--opencode" -> c.opencode = value;
                case "--maven" -> c.maven = value;
                default -> throw new IllegalArgumentException("unknown option: " + arg);
            }
        }
        return c;
    }

    List<String> forwardedArgs(String subcommand, boolean includeDir) {
        List<String> result = new ArrayList<>(List.of(subcommand));
        if (includeDir && !dir.isBlank()) result.addAll(List.of("--dir", dir));
        if (ephemeral) result.add("--ephemeral");
        String[][] pairs = {{"--session", session}, {"--key", key}, {"--psmux", psmux},
                {"--namespace", namespace}, {"--pi", pi}, {"--codex", codex}, {"--agy", agy},
                {"--opencode", opencode}, {"--maven", maven}};
        for (String[] pair : pairs) {
            if (pair[1] != null && !pair[1].isBlank()) result.addAll(List.of(pair));
        }
        return result;
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
    static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
}
