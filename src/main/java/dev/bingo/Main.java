package dev.bingo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        try { run(args); }
        catch (Exception e) { System.err.println("bingo: " + e.getMessage()); System.exit(1); }
    }

    static void run(String[] args) throws Exception {
        String command = args.length > 0 && !args[0].startsWith("-") ? args[0]
                : args.length > 0 && (args[0].equals("--help") || args[0].equals("-h")) ? "help" : "bootstrap";
        Config config = Config.parse(args);
        Psmux psmux = new Psmux(config);
        switch (command) {
            case "help", "--help", "-h" -> usage();
            case "models" -> new ModelService(config).list().forEach(choice -> System.out.println(choice.display()));
            case "cache-refresh" -> new ModelService(config).refresh();
            case "install" -> new BindingInstaller(config, psmux).install();
            case "pick" -> pick(config, psmux);
            case "open" -> { config.ephemeral = true; open(config, psmux); }
            case "bootstrap" -> bootstrap(config, psmux);
            default -> throw new IllegalArgumentException("unknown command '" + command + "' (try 'help')");
        }
    }

    private static void bootstrap(Config c, Psmux psmux) throws Exception {
        if (psmux.inside()) { pick(c, psmux); return; }
        String dir = c.dir.isBlank() ? Path.of("").toAbsolutePath().toString() : c.dir;
        List<String> invocation = mavenInvocation(c, c.forwardedArgs("pick", true));
        if (psmux.sessionExists(c.session)) psmux.newWindow("bingo", dir, invocation);
        else psmux.createSession(c.session, dir, invocation);
        psmux.attach(c.session);
    }

    private static void open(Config c, Psmux psmux) throws Exception {
        String dir = c.dir.isBlank() ? psmux.currentDir() : c.dir;
        List<String> invocation = mavenInvocation(c, c.forwardedArgs("pick", true));
        psmux.newWindow("bingo", dir, invocation);
    }

    private static void pick(Config c, Psmux psmux) throws Exception {
        if (c.dir.isBlank()) c.dir = psmux.inside() ? psmux.currentDir() : Path.of("").toAbsolutePath().toString();
        ModelChoice choice = TuiPicker.choose(new ModelService(c));
        if (choice == null) return;
        String modelKey = choice.agent().name().toLowerCase(Locale.ROOT) + "-" + choice.model();
        String window = windowName(modelKey);
        String session = psmux.currentSession();
        if (!session.isBlank()) {
            String existing = psmux.findWindow(session, window);
            if (!existing.isBlank()) { psmux.selectWindow(existing); return; }
        }
        List<String> launch = switch (choice.agent()) {
            case PI -> List.of(c.pi, "--tui-mode", "fullscreen", "--model", choice.model());
            case CODEX -> List.of(c.codex, "--model", choice.model());
            case AGY -> List.of(c.agy, "--model", choice.model());
            case OPENCODE -> List.of(c.opencode, "mini", "--model", choice.model());
        };
        psmux.newWindow(window, c.dir, launch);
    }

    private static List<String> mavenInvocation(Config c, List<String> appArgs) {
        String execArgs = appArgs.stream().map(Main::quoteExecArg).reduce((a, b) -> a + " " + b).orElse("");
        return List.of(c.maven, "-q", "-f", c.project.resolve("pom.xml").toString(),
                "-Dbingo.project=" + c.project, "compile", "exec:java", "-Dexec.args=" + execArgs);
    }

    private static String quoteExecArg(String value) {
        if (value.matches("[^\\s\"]+")) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String windowName(String value) {
        String safe = "bingo-" + value.replace('.', '-').replace('#', '-');
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    private static void usage() {
        System.out.println("""
                Bingo model switcher
                Usage: mvn compile exec:java [-Dexec.args="[command] [flags]"]
                  commands: bootstrap (default), pick, open, install, models, help
                  flags: --session/-s, --key/-k, --dir/-d, --psmux, --namespace/-L,
                         --pi, --codex, --agy, --opencode, --maven, --ephemeral
                """);
    }
}
