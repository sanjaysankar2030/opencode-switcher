package dev.bingo;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class BindingInstaller {
    static final String BEGIN = "# >>> bingo >>>";
    static final String END = "# <<< bingo <<<";
    private static final String LEGACY_BEGIN = "# >>> ocmodel >>>";
    private static final String LEGACY_END = "# <<< ocmodel <<<";
    private final Config config;
    private final Psmux psmux;

    public BindingInstaller(Config config, Psmux psmux) { this.config = config; this.psmux = psmux; }

    public void install() throws Exception {
        Path path = configFile();
        String old = Files.exists(path) ? Files.readString(path) : "";
        String block = block();
        // The previous Go launcher installed a separate ocmodel block. Replace
        // that block during migration so its stale executable path cannot keep
        // being invoked by the same prefix binding.
        int begin = old.indexOf(BEGIN), end = old.indexOf(END);
        if (begin < 0 || end <= begin) {
            begin = old.indexOf(LEGACY_BEGIN);
            end = old.indexOf(LEGACY_END);
        }
        String updated;
        if (begin >= 0 && end > begin) {
            end += old.startsWith(LEGACY_BEGIN, begin) ? LEGACY_END.length() : END.length();
            if (end < old.length() && old.charAt(end) == '\r') end++;
            if (end < old.length() && old.charAt(end) == '\n') end++;
            updated = old.substring(0, begin) + block + old.substring(end);
        } else updated = old + (old.isEmpty() || old.endsWith("\n") ? "" : "\n") + (old.isEmpty() ? "" : "\n") + block;
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, updated, StandardCharsets.UTF_8);
        System.out.println("installed prefix+" + config.key + " in " + path);
        try { psmux.runSource(path); } catch (Exception e) { System.err.println("bingo: could not apply binding live: " + e.getMessage()); }
    }

    private Path configFile() {
        String explicit = System.getenv("PSMUX_CONFIG"); if (explicit != null && !explicit.isBlank()) return Path.of(explicit);
        Path home = Path.of(System.getProperty("user.home"));
        if (Config.isWindows()) {
            for (Path candidate : List.of(home.resolve(".psmux.conf"), home.resolve(".psmuxrc"), home.resolve(".tmux.conf"), home.resolve(".config/psmux/psmux.conf")))
                if (Files.exists(candidate)) return candidate;
            return home.resolve(".psmux.conf");
        }
        for (Path candidate : List.of(home.resolve(".tmux.conf"), home.resolve(".config/tmux/tmux.conf"))) if (Files.exists(candidate)) return candidate;
        return home.resolve(".tmux.conf");
    }

    private String block() {
        String prefix = binding("bind-key -T prefix " + config.key);
        String f8 = binding("bind-key -n F8");
        return String.join("\n", BEGIN, "set-option -g renumber-windows on", "set-option -g extended-keys on",
                "set-option -g mouse on",
                "# prefix+" + config.key + ": pick a Pi, Codex, AGY, or OpenCode model",
                prefix, "# F8: open Bingo from full-screen agent applications", f8, END, "");
    }

    private String binding(String bind) {
        String execArgs = config.forwardedArgs("pick", false).stream()
                .map(BindingInstaller::quoteExecArg).reduce((a, b) -> a + " " + b).orElse("");
        List<String> mavenArgs = List.of(config.maven, "-q", "-o", "-f", config.project.resolve("pom.xml").toString(),
                "-Dbingo.project=" + config.project, "exec:java", "-Dexec.args=" + execArgs);
        String command;
        if (Config.isWindows()) {
            // psmux executes multiple tokens after `--` directly. Start
            // PowerShell explicitly because mvn.cmd cannot be CreateProcessW'd
            // as an executable. Pass Maven's complete exec.args value as one
            // PowerShell argument instead of nesting shell quoting layers.
            String script = "& " + mavenArgs.stream().map(BindingInstaller::powerShellQuote)
                    .reduce((a, b) -> a + " " + b).orElse("");
            command = "powershell.exe -NoLogo -NoProfile -Command " + quoteConfig(script);
        } else {
            command = shellJoin(mavenArgs);
        }
        return bind + " new-window -n bingo -c '#{pane_current_path}' -- " + command;
    }

    private static String shellJoin(List<String> args) {
        return String.join(" ", args.stream().map(BindingInstaller::shellQuote).toList());
    }
    private static String shellQuote(String value) {
        if (Config.isWindows()) return "'" + value.replace("'", "''") + "'";
        return "'" + value.replace("'", "'\\''") + "'";
    }
    private static String powerShellQuote(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static String quoteExecArg(String value) {
        if (value.matches("[^\\s\"]+")) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    private static String quoteConfig(String value) { return "\"" + value.replace("\\", "/").replace("\"", "\\\"") + "\""; }
}
