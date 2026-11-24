package dev.bingo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

public final class ModelService {
    private static final Duration FRESH = Duration.ofMinutes(30);
    private static final Duration MAX_STALE = Duration.ofHours(24);
    private static final Pattern SELECTOR = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*/[^\\s]+"),
            CODEX_SLUG = Pattern.compile("\\\"slug\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private final Config config;

    public ModelService(Config config) { this.config = config; }
    Config config() { return config; }

    public List<ModelChoice> list() throws Exception {
        return list(ignored -> { });
    }

    public List<ModelChoice> list(Consumer<List<ModelChoice>> onUpdate) throws Exception {
        Cache cache = readCache();
        if (cache != null) {
            long age = Math.max(0, System.currentTimeMillis() - cache.updated());
            if (age <= FRESH.toMillis()) { onUpdate.accept(cache.choices()); return cache.choices(); }
            if (age <= MAX_STALE.toMillis()) {
                refreshAsync();
                onUpdate.accept(cache.choices());
                return cache.choices();
            }
        }
        return discoverIncrementally(onUpdate);
    }

    private List<ModelChoice> discoverAndSave() throws Exception {
        return discoverIncrementally(ignored -> { });
    }

    private List<ModelChoice> discoverIncrementally(Consumer<List<ModelChoice>> onUpdate) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "bingo-model-discovery"); t.setDaemon(true); return t;
        });
        try {
            CompletionService<ProviderResult> completions = new ExecutorCompletionService<>(pool);
            for (Agent agent : Agent.values()) completions.submit(() -> {
                try { return new ProviderResult(discover(agent), null); }
                catch (Exception e) { return new ProviderResult(List.of(), e); }
            });
            List<ModelChoice> choices = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (int i = 0; i < Agent.values().length; i++) {
                Future<ProviderResult> task = completions.poll(50, TimeUnit.SECONDS);
                if (task == null) { errors.add("provider discovery timed out"); break; }
                try {
                    ProviderResult result = task.get();
                    choices.addAll(result.choices());
                    if (result.error() != null) errors.add(result.error().getMessage());
                    if (!choices.isEmpty()) {
                        List<ModelChoice> snapshot = choices.stream()
                                .sorted(Comparator.comparing(ModelChoice::display, String.CASE_INSENSITIVE_ORDER)).toList();
                        writeCache(snapshot, false);
                        onUpdate.accept(snapshot);
                    }
                } catch (ExecutionException e) { errors.add(e.getCause().getMessage()); }
            }
            choices.sort(Comparator.comparing(ModelChoice::display, String.CASE_INSENSITIVE_ORDER));
            if (choices.isEmpty()) throw new IOException("No models found. " + String.join("; ", errors));
            writeCache(choices, true);
            onUpdate.accept(List.copyOf(choices));
            return choices;
        } finally {
            pool.shutdownNow();
        }
    }

    private record ProviderResult(List<ModelChoice> choices, Exception error) { }

    private List<ModelChoice> discover(Agent agent) throws Exception {
        List<String> args = switch (agent) {
            case PI -> List.of("--list-models");
            case CODEX -> List.of("debug", "models");
            case AGY, OPENCODE -> List.of("models");
        };
        String output = run(config.executable(agent), args, Path.of(config.dir.isBlank() ? "." : config.dir));
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        switch (agent) {
            case PI -> {
                boolean table = false;
                for (String line : output.split("\\R")) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length >= 2 && fields[0].equals("provider") && fields[1].equals("model")) { table = true; continue; }
                    String selector = fields.length == 1 && SELECTOR.matcher(fields[0]).matches() ? fields[0]
                            : table && fields.length >= 6 && !fields[0].contains("/") ? fields[0] + "/" + fields[1] : "";
                    if (selector.endsWith(":free")) ids.add(selector);
                }
            }
            case CODEX -> {
                Matcher matcher = CODEX_SLUG.matcher(output);
                while (matcher.find()) ids.add(matcher.group(1));
            }
            case AGY -> {
                for (String line : output.split("\\R")) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length > 0 && fields[0].matches("[A-Za-z0-9][A-Za-z0-9._-]*")) ids.add(fields[0]);
                }
            }
            case OPENCODE -> {
                Matcher matcher = SELECTOR.matcher(output);
                while (matcher.find()) {
                    String selector = matcher.group();
                    if (isAllowedOpenCodeModel(selector)) ids.add(selector);
                }
            }
        }
        return ids.stream().sorted().map(id -> new ModelChoice(agent, id)).toList();
    }

    private static boolean isAllowedOpenCodeModel(String selector) {
        String normalized = selector.toLowerCase(Locale.ROOT);
        int slash = normalized.indexOf('/');
        if (slash < 1) return false;
        String provider = normalized.substring(0, slash);
        String model = normalized.substring(slash + 1);

        // OpenCode's own free catalog includes Big Pickle, whose ID has no
        // free suffix, plus models explicitly named with a -free suffix.
        if (provider.equals("opencode")) {
            return model.equals("big-pickle") || model.endsWith("-free") || model.endsWith(":free");
        }
        // Other providers, including Google and OpenRouter, identify free
        // variants with the :free selector suffix.
        return model.endsWith(":free");
    }

    private static String run(String executable, List<String> args, Path cwd) throws Exception {
        List<String> command = new ArrayList<>(); command.add(executable); command.addAll(args);
        Path stderrFile = Files.createTempFile("bingo-agent-stderr-", ".log");
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectError(stderrFile.toFile()).start();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try { return process.getInputStream().readAllBytes(); } catch (IOException e) { return new byte[0]; }
        });
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly(); Files.deleteIfExists(stderrFile);
            throw new IOException(String.join(" ", command) + " timed out");
        }
        String text = new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        String errorText = Files.readString(stderrFile, StandardCharsets.UTF_8).trim();
        Files.deleteIfExists(stderrFile);
        if (process.exitValue() != 0) throw new IOException(String.join(" ", command) + " failed: " + errorText);
        return text;
    }

    private Path cacheFile() throws Exception {
        String root = Config.isWindows() ? Config.env("LOCALAPPDATA", System.getProperty("user.home"))
                : Config.env("XDG_CACHE_HOME", Path.of(System.getProperty("user.home"), ".cache").toString());
        // Model catalogs belong to the configured CLIs, not to a project
        // directory. Sharing the cache avoids re-running providers such as
        // AGY (which may trigger Google verification) when switching folders.
        String identity = String.join("\n", config.pi, config.codex, config.agy, config.opencode);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
        return Path.of(root, "bingo", HexFormat.of().formatHex(digest) + ".cache");
    }

    private Cache readCache() {
        try {
            Properties p = new Properties();
            try (Reader reader = Files.newBufferedReader(cacheFile(), StandardCharsets.UTF_8)) { p.load(reader); }
            long updated = Long.parseLong(p.getProperty("updated"));
            List<ModelChoice> choices = new ArrayList<>();
            for (String line : p.getProperty("models", "").split(",")) {
                if (line.isBlank()) continue;
                String decoded = new String(Base64.getUrlDecoder().decode(line), StandardCharsets.UTF_8);
                int sep = decoded.indexOf('\t');
                Agent agent = Agent.valueOf(decoded.substring(0, sep));
                String model = decoded.substring(sep + 1);
                if (agent != Agent.OPENCODE || isAllowedOpenCodeModel(model)) choices.add(new ModelChoice(agent, model));
            }
            return choices.isEmpty() ? null : new Cache(updated, choices);
        } catch (Exception ignored) { return null; }
    }

    private void writeCache(List<ModelChoice> choices, boolean complete) {
        try {
            Path file = cacheFile(); Files.createDirectories(file.getParent());
            String values = choices.stream().map(c -> Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((c.agent().name() + "\t" + c.model()).getBytes(StandardCharsets.UTF_8)))
                    .reduce((a, b) -> a + "," + b).orElse("");
            long updated = System.currentTimeMillis() - (complete ? 0 : FRESH.toMillis() + 1);
            Properties p = new Properties(); p.setProperty("updated", Long.toString(updated)); p.setProperty("models", values);
            Path temp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { p.store(writer, "Bingo model cache"); }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception ignored) { /* Cache failures must not block the model picker. */ }
    }

    private void refreshAsync() {
        Thread t = new Thread(() -> { try { discoverAndSave(); } catch (Exception ignored) { } }, "bingo-cache-refresh");
        t.setDaemon(true); t.start();
    }

    public void refresh() throws Exception { discoverAndSave(); }
    private record Cache(long updated, List<ModelChoice> choices) { }
}
