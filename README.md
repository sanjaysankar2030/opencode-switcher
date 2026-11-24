# Bingo

Bingo is a Java/TamboUI rewrite of the model switcher. It keeps the Pi, Codex,
Antigravity (`agy`), and OpenCode model discovery and launch paths, plus model
caching, psmux/tmux tabs, duplicate-window selection, and prefix+key/F8 binding
installation. The interactive selector is implemented in TamboUI, with live
fuzzy filtering and arrow-key navigation without an external picker process.

## Run

From this directory:

```shell
mvn compile exec:java
```

That starts the default flow: pick a model inside psmux/tmux, or bootstrap and
attach to the `bingo` session when run outside a multiplexer. Commands can
be supplied to the application through Maven:

```shell
mvn compile exec:java -Dexec.args="help"
mvn compile exec:java -Dexec.args="install"
mvn compile exec:java -Dexec.args="models"
mvn compile exec:java -Dexec.args="pick --dir ."
```

After upgrading from the former `ocmodel` launcher, run the `install` command
once to replace its old psmux key binding with Bingo's current Maven launcher.

Use `--session`, `--key`, `--dir`, `--pi`, `--codex`, `--agy`, `--opencode`,
`--psmux`, `--namespace`, and `--maven` to override defaults. Environment overrides are
`BINGO_PI`, `BINGO_CODEX`, `BINGO_AGY`, `BINGO_OPENCODE`,
`BINGO_SESSION`, `BINGO_KEY`, `BINGO_PSMUX`, `BINGO_NAMESPACE`, and
`BINGO_MAVEN`.

The picker opens its TamboUI loading view before model discovery starts. On a
cache miss, agent CLIs are queried concurrently and each provider's models
appear as soon as that provider responds, so you can select without waiting for
the slowest CLI. Partial results are cached for reuse and refreshed in the
background. The cache is per user, CLI configuration, and working directory;
complete results are fresh for two minutes.

Bingo launches Pi in fullscreen TUI mode so the mouse wheel scrolls the chat
transcript inside Pi. Pi's regular mode uses terminal scrollback instead.

TamboUI currently publishes snapshot releases, so the Maven project uses its
official Central Portal snapshot repository and pins the documented
`0.8.0-SNAPSHOT` BOM.
