(ns music-practice.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [music-practice.governor :as governor]
            [music-practice.store :as store]))

(def clean-track
  {:asset/id "trk-1"
   :asset/title "Fixture Calm"
   :music/moods #{:calm}
   :music/channels #{"demo"}
   :credit/text "BGM: Fixture Calm / tester"
   :policy/render-only? true
   :policy/raw-public-access? false
   :policy/ai-training? false
   :policy/content-id-registration? false})

(defn- fresh-store []
  (-> (store/mem-store)
      (store/register-track! clean-track)))

(def ^:private ok-proposal
  {:op :license-track :effect :propose :stake :low :confidence 0.95})

(defn- req
  ([] (req {}))
  ([extra] (merge {:track-id "trk-1" :op :license-track :channel-id "demo"
                   :usage-context :youtube-background}
                  extra)))

(deftest ok-on-clean-request
  (let [v (governor/check (req) {} ok-proposal (fresh-store))]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))
    (is (empty? (:policy-errors v)))))

(deftest hard-holds
  (testing "unregistered track"
    (let [v (governor/check (req {:track-id "no-such-track"}) {} ok-proposal (fresh-store))]
      (is (:hard? v))
      (is (some #(= :no-track (:rule %)) (:violations v)))))
  (testing "non-propose effect (actuation attempt)"
    (let [v (governor/check (req) {} (assoc ok-proposal :effect :write!) (fresh-store))]
      (is (:hard? v))
      (is (some #(= :no-actuation (:rule %)) (:violations v))))))

(deftest license-policy-escalations
  (doseq [[label extra] {"AI-training use" {:ai-training? true}
                         "Content ID registration" {:content-id? true}
                         "raw public file exposure" {:expose-raw? true}
                         "unlicensed channel" {:channel-id "not-a-licensed-channel"}
                         "usage context outside allowance" {:usage-context :broadcast-tv}}]
    (testing label
      (let [v (governor/check (req extra) {} ok-proposal (fresh-store))]
        (is (not (:hard? v)) label)
        (is (:escalate? v) label)
        (is (seq (:policy-errors v)) label)))))

(deftest low-confidence-escalates
  (let [v (governor/check (req) {} (assoc ok-proposal :confidence 0.2) (fresh-store))]
    (is (not (:hard? v)))
    (is (:escalate? v))
    (is (empty? (:policy-errors v)))))
