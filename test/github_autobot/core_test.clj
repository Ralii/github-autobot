(ns github-autobot.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [github-autobot.core :as core]))

(deftest config-test
  (testing "config has required keys"
    (let [cfg @core/config]
      (is (contains? cfg :repo))
      (is (contains? cfg :working-dir))
      (is (contains? cfg :poll-interval-ms))
      (is (contains? cfg :autobot-tag))))

  (testing "poll-interval-ms is a positive number"
    (is (pos? (:poll-interval-ms @core/config)))))

(deftest state-test
  (testing "state has required keys"
    (let [s @core/state]
      (is (contains? s :watched-prs))
      (is (contains? s :processed-issues))
      (is (contains? s :processed-comments))))

  (testing "state collections are the right types"
    (let [s @core/state]
      (is (map? (:watched-prs s)))
      (is (set? (:processed-issues s)))
      (is (set? (:processed-comments s))))))

(deftest work-queue-test
  (testing "work-queue is a channel"
    (is (some? core/work-queue))))
