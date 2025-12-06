(ns github-autobot.core
  "GitHub Autobot - Automated GitHub Issue to PR pipeline using Claude.

   Sessions:
   - Each issue gets its own session (persisted)
   - When comments come in, we resume that session
   - Claude remembers what it did and why

   ┌─────────────────────────────────────────────────────────────┐
   │                      Work Queue (chan 1)                    │
   └─────────────────────────────────────────────────────────────┘
                              │
                              ▼
                     ┌─────────────────┐
                     │  Single Worker  │
                     │                 │
                     │  issue-42 ────► claude --resume issue-42 -p ...
                     │  issue-43 ────► claude --resume issue-43 -p ...
                     └─────────────────┘"
  (:require [clojure.core.async :as async
             :refer [chan go go-loop <! >! >!! <!! close! timeout]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]
            [clojure.string :as str]))

;;; =============================================================================
;;; Config & State
;;; =============================================================================

(def config
  (atom {:repo "owner/repo"
         :working-dir "/path/to/repo"
         :poll-interval-ms 60000
         :autobot-tag "@autobot"}))

(defonce state
  (atom {:watched-prs {}         ; {pr-number -> {:issue-number :session-name}}
         :processed-issues #{}
         :processed-comments #{}}))

(defonce work-queue (chan 1))

;;; =============================================================================
;;; Claude with Sessions
;;; =============================================================================

(defn claude-run
  "Execute Claude with a named session.
   Session persists conversation history."
  [session-name task]
  (println "🤖 Claude session:" session-name)
  (let [result (sh "claude"
                   "--resume" session-name    ; Named session - persists!
                   "-p" task
                   :dir (:working-dir @config))]
    {:success (zero? (:exit result))
     :output (:out result)
     :error (:err result)}))

(defn gh [& args]
  (let [result (apply sh "gh" args)]
    (when (zero? (:exit result))
      (let [out (str/trim (:out result))]
        (when-not (str/blank? out)
          (try (json/parse-string out true)
               (catch Exception _ out)))))))

(defn has-write-access?
  "Check if a user has write access to the repo.
   Uses gh api to check collaborator permission level."
  [username]
  (let [repo (:repo @config)
        result (gh "api" (str "/repos/" repo "/collaborators/" username "/permission"))]
    (contains? #{"admin" "write"} (:permission result))))

;;; =============================================================================
;;; Task Processing
;;; =============================================================================

(defmulti process-task first)

(defmethod process-task :create-pr
  [[_ {:keys [number title body]}]]
  (let [repo (:repo @config)
        session-name (str "autobot-issue-" number)  ; Session per issue

        result (claude-run session-name
                 (str "Implement GitHub issue #" number " for repo " repo "\n\n"
                      "Title: " title "\n"
                      "Description: " body "\n\n"
                      "1. First, checkout main and pull latest: git checkout main && git pull origin main\n"
                      "2. Create branch 'autobot/issue-" number "' from main\n"
                      "3. Implement the fix/feature\n"
                      "4. Run tests if they exist\n"
                      "5. Create PR with 'gh pr create' referencing issue #" number))]

    (when (:success result)
      ;; Find the created PR and track it with session name
      (when-let [prs (gh "pr" "list" "-R" repo
                         "--head" (str "autobot/issue-" number)
                         "--json" "number")]
        (when-let [pr-num (:number (first prs))]
          (swap! state update :watched-prs assoc pr-num
                 {:issue-number number
                  :session-name session-name})))  ; Remember session!
      (swap! state update :processed-issues conj number))))

(defmethod process-task :implement-comment
  [[_ {:keys [id pr-number body author]}]]
  (let [repo (:repo @config)
        ;; Get the session for this PR
        {:keys [session-name]} (get-in @state [:watched-prs pr-number])

        pr-info (gh "pr" "view" (str pr-number) "-R" repo "--json" "headRefName")
        branch (:headRefName pr-info)]

    (if session-name
      ;; Resume the same session - Claude remembers everything!
      (let [result (claude-run session-name
                     (str "Review comment from " author ":\n"
                          "\"" body "\"\n\n"
                          "Address this feedback on branch '" branch "', "
                          "commit, and push."))]
        (when (:success result)
          (swap! state update :processed-comments conj id)))

      ;; No session found - shouldn't happen, but handle gracefully
      (println "⚠️ No session found for PR" pr-number))))

;;; =============================================================================
;;; Worker & Feeders
;;; =============================================================================

(defn start-worker! []
  (go-loop []
    (when-let [task (<! work-queue)]
      (try
        (process-task task)
        (catch Exception e
          (println "❌ Error:" (.getMessage e))))
      (recur))))

(defn start-issue-feeder! []
  (go-loop []
    (let [issues (gh "issue" "list" "-R" (:repo @config)
                     "--state" "open" "--json" "number,title,body,author" "--limit" "5")]
      (doseq [{:keys [number author] :as issue} issues]
        (when-not (contains? (:processed-issues @state) number)
          (let [username (:login author)]
            (if (has-write-access? username)
              (>! work-queue [:create-pr issue])
              (println "⏭️ Skipping issue #" number "- author" username "lacks write access"))))))
    (<! (timeout (:poll-interval-ms @config)))
    (recur)))

(defn start-comment-feeder! []
  (go-loop []
    (let [repo (:repo @config)
          tag (:autobot-tag @config)]
      (doseq [[pr-number _] (:watched-prs @state)]
        (let [pr-data (gh "pr" "view" (str pr-number) "-R" repo
                          "--json" "comments,reviews,state")]
          ;; Remove merged/closed PRs
          (when (#{"MERGED" "CLOSED"} (:state pr-data))
            (swap! state update :watched-prs dissoc pr-number))

          ;; Queue new @autobot comments from users with write access
          (let [comments (->> (concat (:comments pr-data)
                                      (mapcat :comments (:reviews pr-data)))
                              (filter #(str/includes? (:body % "") tag))
                              (remove #(contains? (:processed-comments @state) (:id %))))]
            (doseq [c comments]
              (let [author (get-in c [:author :login])]
                (if (has-write-access? author)
                  (>! work-queue [:implement-comment
                                  {:id (:id c)
                                   :pr-number pr-number
                                   :body (:body c)
                                   :author author}])
                  (println "⏭️ Skipping comment from" author "- lacks write access"))))))))
    (<! (timeout (:poll-interval-ms @config)))
    (recur)))

;;; =============================================================================
;;; Lifecycle
;;; =============================================================================

(defn start! [cfg]
  (swap! config merge cfg)
  (start-worker!)
  (start-issue-feeder!)
  (start-comment-feeder!)
  (println "🤖 GitHub Autobot started for" (:repo @config))
  (println "   Working dir:" (:working-dir @config))
  (println "   Poll interval:" (/ (:poll-interval-ms @config) 1000) "seconds")
  (println "   Listening for:" (:autobot-tag @config) "comments"))

(defn stop! []
  (close! work-queue))

;;; =============================================================================
;;; Auto-detection & CLI
;;; =============================================================================

(defn detect-working-dir
  "Get current working directory."
  []
  (System/getProperty "user.dir"))

(defn detect-github-repo
  "Detect GitHub repo from git remote origin."
  [working-dir]
  (let [result (sh "git" "remote" "get-url" "origin" :dir working-dir)]
    (when (zero? (:exit result))
      (let [url (str/trim (:out result))]
        (cond
          ;; SSH format: git@github.com:owner/repo.git
          (str/starts-with? url "git@github.com:")
          (-> url
              (str/replace #"^git@github.com:" "")
              (str/replace #"\.git$" ""))

          ;; HTTPS format: https://github.com/owner/repo.git
          (str/includes? url "github.com/")
          (-> url
              (str/replace #"^https://github.com/" "")
              (str/replace #"\.git$" ""))

          :else nil)))))

(defn parse-args
  "Parse command line arguments.
   Returns {:working-dir path :poll-interval-ms ms}"
  [args]
  (loop [args args
         opts {:poll-interval-ms 60000}]
    (if (empty? args)
      opts
      (let [arg (first args)]
        (cond
          ;; If it's a number, treat as poll interval
          (re-matches #"\d+" arg)
          (recur (rest args) (assoc opts :poll-interval-ms (* 1000 (Integer/parseInt arg))))

          ;; Otherwise treat as path
          :else
          (recur (rest args) (assoc opts :working-dir arg)))))))

(defn -main
  "Start GitHub Autobot for a repository.
   Auto-detects GitHub repo from git remote.

   Usage: clj -M:run [path] [poll-interval-seconds]

   Examples:
     clj -M:run                           # Current dir, 60s poll
     clj -M:run /path/to/repo             # Specific repo, 60s poll
     clj -M:run /path/to/repo 30          # Specific repo, 30s poll
     clj -M:run 30                        # Current dir, 30s poll"
  [& args]
  (let [{:keys [working-dir poll-interval-ms]} (parse-args args)
        working-dir (or working-dir (detect-working-dir))
        repo (detect-github-repo working-dir)]
    (if repo
      (do
        (start! {:repo repo
                 :working-dir working-dir
                 :poll-interval-ms poll-interval-ms})
        ;; Keep the process alive
        @(promise))
      (do
        (println "❌ Error: Could not detect GitHub repo.")
        (println "   Make sure the path is a git repository with a GitHub remote.")
        (println "   Path:" working-dir)
        (System/exit 1)))))

;;; =============================================================================
;;; REPL
;;; =============================================================================

(comment
  ;; Manual start with explicit config
  (start! {:repo "user/repo" :working-dir "/path/to/repo"})

  ;; Or auto-detect (like -main does)
  (start! {:repo (detect-github-repo (detect-working-dir))
           :working-dir (detect-working-dir)})

  @state
  ;; {:watched-prs {123 {:issue-number 42 :session-name "autobot-issue-42"}}
  ;;  :processed-issues #{42}
  ;;  :processed-comments #{"abc"}}

  (stop!))
