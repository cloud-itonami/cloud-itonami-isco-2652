(ns music-practice.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [music-practice.actor :as actor]
            [music-practice.store :as store]))

(defn- fresh-store []
  (-> (store/mem-store)
      (store/register-track! {:asset/id "trk-1"
                              :asset/title "Fixture Calm"
                              :music/moods #{:calm}
                              :music/channels #{"demo"}
                              :credit/text "BGM: Fixture Calm / tester"
                              :policy/render-only? true
                              :policy/raw-public-access? false
                              :policy/ai-training? false
                              :policy/content-id-registration? false})))

(deftest commits-a-clean-license-request
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:track-id "trk-1" :op :license-track :channel-id "demo"
                 :usage-context :youtube-background :stake :low}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (let [record (get-in result [:state :record])]
      (is (some? record))
      (is (= "BGM: Fixture Calm / tester" (:credit record)))
      (is (true? (:render-only? record))))
    (is (= 1 (count (store/records-of st "trk-1"))))
    (is (= [:commit] (map :disposition (store/ledger st))))))

(deftest holds-on-unregistered-track-without-committing
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:track-id "no-such-track" :op :license-track :stake :low}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])))
    (is (empty? (store/records-of st "no-such-track")))
    (is (= :hold (:disposition (:state result))))
    (is (= [:hold] (map :disposition (store/ledger st))))))

(deftest interrupts-then-commits-on-human-signed-license
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        ;; AI-training use always escalates (ongaku.policy invariant)
        request {:track-id "trk-1" :op :license-track :channel-id "demo"
                 :usage-context :youtube-background :ai-training? true
                 :stake :medium}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "trk-1")))
    (testing "approval (= human-signed license) resumes to commit"
      (let [resumed (actor/approve! graph "thread-3")]
        (is (= :done (:status resumed)))
        (is (some? (get-in resumed [:state :record])))
        (is (= 1 (count (store/records-of st "trk-1"))))))))
