# github-autobot

Automated GitHub Issue to PR pipeline using Claude. When issues are created in your repository, github-autobot automatically implements them and creates pull requests.

## Features

- **Automatic PR creation**: Watches for new issues and creates implementation PRs
- **Session persistence**: Each issue gets its own Claude session, maintaining context across interactions
- **Review comment handling**: Responds to `@autobot` mentions in PR comments to address feedback
- **Single worker queue**: Processes tasks sequentially via core.async channels

## Requirements

- Clojure 1.12+
- [Claude CLI](https://github.com/anthropics/claude-code) installed
- [GitHub CLI](https://cli.github.com/) (`gh`) authenticated

## Usage

### Configuration

Configure the autobot with your repository settings:

```clojure
(require '[github-autobot.core :as autobot])

(autobot/start! {:repo "owner/repo"
                 :working-dir "/path/to/local/repo"
                 :poll-interval-ms 60000        ; optional, default 60s
                 :autobot-tag "@autobot"        ; optional, trigger for comments
                 :claude-path "/path/to/claude" ; optional
                 :gh-path "/path/to/gh"})       ; optional
```

### Starting the Bot

```clojure
;; Start watching issues and processing them
(autobot/start! {:repo "user/my-project"
                 :working-dir "/home/user/my-project"})

;; Check current state
@autobot/state

;; Stop the bot
(autobot/stop!)
```

### How It Works

1. **Issue Feeder**: Polls for open issues every poll-interval
2. **Worker**: Picks up issues and runs Claude to implement them
3. **PR Creation**: Claude creates a branch, implements the feature, runs tests, and opens a PR
4. **Comment Feeder**: Watches PRs for `@autobot` mentions
5. **Feedback Loop**: Resumes the Claude session to address review comments

## Development

Start a REPL with nREPL support:

```bash
clj -M:dev -m nrepl.cmdline
```

Run tests:

```bash
clj -M:test -m cognitect.test-runner
```

## License

MIT
