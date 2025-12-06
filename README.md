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

### Quick Start (Command Line)

Run from the github-autobot directory, pointing to any git repository:

```bash
# From the github-autobot directory:
clj -M:run /path/to/target-repo          # Watch that repo, 60s poll
clj -M:run /path/to/target-repo 30       # Watch that repo, 30s poll
clj -M:run                               # Watch current dir, 60s poll
clj -M:run 30                            # Watch current dir, 30s poll
```

Output:
```
🤖 GitHub Autobot started for owner/repo
   Working dir: /path/to/your/repo
   Poll interval: 60 seconds
   Listening for: @autobot comments
```

### REPL Usage

For more control, you can start from the REPL:

```clojure
(require '[github-autobot.core :as autobot])

;; Auto-detect repo from current directory
(autobot/start! {:repo (autobot/detect-github-repo (autobot/detect-working-dir))
                 :working-dir (autobot/detect-working-dir)})

;; Or manually configure
(autobot/start! {:repo "owner/repo"
                 :working-dir "/path/to/local/repo"
                 :poll-interval-ms 60000        ; optional, default 60s
                 :autobot-tag "@autobot"})      ; optional, trigger for comments

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

## Session Persistence

One of the key features of github-autobot is session persistence. Each GitHub issue gets its own named Claude session (e.g., `autobot-issue-42`), which persists the full conversation history.

### How Sessions Work

- When a new issue is picked up, a session is created with the name `autobot-issue-{number}`
- The session is stored and associated with the resulting PR
- When review comments mention `@autobot`, the bot resumes the *same* session
- Claude retains full context: the original issue, implementation decisions, and all previous feedback

### Benefits

- **Continuous context**: Claude remembers why it made certain implementation choices
- **Coherent iterations**: Follow-up requests don't need to re-explain the problem
- **Efficient feedback loops**: Review comments are addressed with full awareness of prior work

### Example Flow

```
Issue #42 opened
  └─> Session "autobot-issue-42" created
      └─> Claude implements feature, creates PR #99

PR #99 receives comment: "@autobot add error handling"
  └─> Session "autobot-issue-42" resumed
      └─> Claude adds error handling with full context

PR #99 receives comment: "@autobot also add tests"
  └─> Session "autobot-issue-42" resumed again
      └─> Claude adds tests, knowing what was implemented
```

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
