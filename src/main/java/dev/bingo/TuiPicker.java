package dev.bingo;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.list.ListItem;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.widgets.list.ListWidget;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.style.Style;

import java.util.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class TuiPicker {
    private static final long SPLASH_DURATION_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final long startedAtNanos = System.nanoTime();
    private volatile List<ModelChoice> latestChoices = List.of();
    private List<ModelChoice> all = List.of();
    private final StringBuilder query = new StringBuilder();
    private final ListState state = new ListState();
    private List<ModelChoice> visible;
    private ModelChoice selected;
    private final CompletableFuture<List<ModelChoice>> loadTask;
    private boolean loading = true;
    private String loadError = "";
    private int animationFrame;

    private TuiPicker(ModelService service) {
        this.visible = List.of();
        this.loadTask = CompletableFuture.supplyAsync(() -> {
            try { return service.list(choices -> latestChoices = List.copyOf(choices)); }
            catch (Exception e) { throw new CompletionException(e); }
        });
    }

    public static ModelChoice choose(ModelService service) throws Exception {
        TuiPicker app = new TuiPicker(service);
        TuiConfig config = TuiConfig.builder().tickRate(Duration.ofMillis(120)).build();
        try (TuiRunner tui = TuiRunner.create(config)) {
            tui.run(app::handle, app::render);
        }
        return app.selected;
    }

    private boolean handle(Event event, TuiRunner tui) {
        acceptUpdates();
        if (event instanceof TickEvent) { animationFrame++; return true; }
        if (!(event instanceof KeyEvent key) || !key.isPressOrRepeat()) return false;
        if (key.isCtrlC() || key.isCancel()) { tui.quit(); return true; }
        if (loading && visible.isEmpty()) return true;
        if (key.isUp()) { if (!visible.isEmpty()) state.selectPrevious(); return true; }
        if (key.isDown()) { if (!visible.isEmpty()) state.selectNext(visible.size()); return true; }
        if (key.isPageUp()) { if (!visible.isEmpty()) state.select(Math.max(0, selectedIndex() - 10)); return true; }
        if (key.isPageDown()) { if (!visible.isEmpty()) state.select(Math.min(visible.size() - 1, selectedIndex() + 10)); return true; }
        if (key.isConfirm()) {
            if (!visible.isEmpty() && selectedIndex() >= 0 && selectedIndex() < visible.size()) selected = visible.get(selectedIndex());
            tui.quit();
            return true;
        }
        if (key.isDeleteBackward()) {
            if (!query.isEmpty()) { query.deleteCharAt(query.length() - 1); updateFilter(); }
            return true;
        }
        if (!key.hasNonShiftModifier() && !key.text().isEmpty()) {
            query.append(key.text()); updateFilter(); return true;
        }
        return false;
    }

    private void acceptUpdates() {
        List<ModelChoice> incoming = latestChoices;
        if (!incoming.equals(all)) {
            all = incoming;
            visible = filter();
            if (visible.isEmpty()) state.select(null); else state.selectFirst();
        }
        if (loading && loadTask.isDone()) {
            try {
                all = loadTask.join();
                visible = filter();
                if (!visible.isEmpty()) state.selectFirst();
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                loadError = cause.getMessage() == null ? cause.toString() : cause.getMessage();
            }
            loading = false;
        }
    }

    private void updateFilter() {
        visible = filter();
        if (visible.isEmpty()) state.select(null); else state.selectFirst();
    }

    private int selectedIndex() { return state.selected() == null ? -1 : state.selected(); }

    private String row(ModelChoice choice) {
        return choice.display();
    }

    private List<ModelChoice> filter() {
        String needle = query.toString().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return all;
        return all.stream().map(choice -> Map.entry(choice, fuzzyScore(choice.display().toLowerCase(Locale.ROOT), needle)))
                .filter(entry -> entry.getValue() >= 0)
                .sorted(Comparator.<Map.Entry<ModelChoice, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(entry -> entry.getKey().display(), String.CASE_INSENSITIVE_ORDER))
                .map(Map.Entry::getKey).toList();
    }

    // A subsequence matcher gives forgiving fuzzy filtering without a child process.
    private static int fuzzyScore(String candidate, String needle) {
        int at = 0, score = 0, previous = -2;
        for (int i = 0; i < candidate.length() && at < needle.length(); i++) {
            if (candidate.charAt(i) != needle.charAt(at)) continue;
            score += i == previous + 1 ? 5 : 1;
            if (i == 0 || candidate.charAt(i - 1) == '/' || candidate.charAt(i - 1) == '-' || candidate.charAt(i - 1) == ' ') score += 3;
            previous = i; at++;
        }
        return at == needle.length() ? score : -1;
    }

    private void render(Frame frame) {
        acceptUpdates();
        // Keep the splash on screen for a useful minimum while discovery runs.
        // Fast cache hits should not make the splash flash, and slow providers
        // should still reveal the searchable picker after five seconds.
        if (System.nanoTime() - startedAtNanos < SPLASH_DURATION_NANOS) {
            String[] letters = {
                    "BBBBB  IIIII  N   N  GGGGG   OOO",
                    "B   B    I    NN  N  G       O   O",
                    "BBBBB    I    N N N  G  GGG  O   O",
                    "B   B    I    N  NN  G   G   O   O",
                    "BBBBB  IIIII  N   N  GGGGG   OOO"
            };
            String logo = String.join("\n", letters);
            String count = all.isEmpty()
                    ? (loading ? "Waiting for model catalogs" : "No models found")
                    : all.size() + (loading ? " models found so far" : " models loaded");
            String bar = switch (animationFrame % 4) {
                case 0 -> "[###..........]";
                case 1 -> "[...###.......]";
                case 2 -> "[......###....]";
                default -> "[.........###.]";
            };
            Paragraph.from(logo + "\n\n"
                    + "                 Model switcher\n\n"
                    + "     " + bar + "  " + count + "\n\n"
                    + "     Loading provider models in the background.\n"
                    + "     AGY may take a little longer when Google verification is needed.\n\n"
                    + "     Press Esc to cancel")
                    .render(frame.area(), frame.buffer());
            return;
        }
        List<dev.tamboui.layout.Rect> areas = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.length(3), Constraint.fill(), Constraint.length(1))
                .split(frame.area());
        Paragraph.from("Bingo  •  Model switcher").render(areas.get(0), frame.buffer());
        Paragraph.from("Search: " + query + "_").render(areas.get(1), frame.buffer());
        List<ListItem> items = visible.stream().map(item -> ListItem.from(row(item))).toList();
        if (items.isEmpty()) {
            String emptyText = !loadError.isBlank() ? loadError : loading ? loadingMessage() : "No matching models";
            items = List.of(ListItem.from(emptyText));
        }
        ListWidget list = ListWidget.builder().items(items.toArray(ListItem[]::new))
                .highlightSymbol("▶ ").highlightStyle(Style.EMPTY.reversed())
                .block(Block.builder().title(loading && !visible.isEmpty() ? "Available models (more loading)" : "Available models")
                        .borders(Borders.ALL).build()).build();
        list.render(areas.get(2), frame.buffer(), state);
        String footer = loading ? loadingMessage() + "  •  Esc cancel"
                : "↑/↓ move  •  Enter launch  •  Esc cancel";
        Paragraph.from(footer)
                .render(areas.get(3), frame.buffer());
    }

    private String loadingMessage() {
        String[] frames = {"|", "/", "—", "\\"};
        if (!loadError.isBlank()) return "Model loading failed: " + loadError + "  •  Esc cancel";
        String count = all.isEmpty() ? "" : " (" + all.size() + " found; updating)";
        return "Loading model catalogs " + frames[animationFrame % frames.length] + count;
    }
}
