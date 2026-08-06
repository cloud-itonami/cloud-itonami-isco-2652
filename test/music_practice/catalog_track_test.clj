(ns music-practice.catalog-track-test
  "カタログ資産を track として登録したときに、渡せる権利と実在する成果物が
  正しく確定するか。

  これが無かった頃、既存の track は `:work/held-rights` を持たず、gate は
  fail-closed で何も譲渡できなかった（安全ではあるが使えない）。埋めるにあたって
  『使用許諾を持っていること』と『それを渡せること』が別物だと分かったので、
  ここで確かめるのはその区別が実際に効いていること。"
  (:require [clojure.test :refer [deftest is testing]]
            [music-practice.governor :as governor]
            [music-practice.store :as store]
            [ongaku.rights :as rights]))

;; ongakuka の実カタログ（resources/catalog.edn v2）から採った形
(def dova-asset
  {:asset/id "dova-12420-10deg"
   :asset/title "10℃"
   :asset/source :source/dova-syndrome
   :music/channels #{"demo"}
   :credit/text "BGM: 10℃ / しゃろう (DOVA-SYNDROME)"
   :license/id :license/dova-syndrome-license
   :license/attribution-required? false
   :policy/render-only? true
   :policy/raw-public-access? false
   :policy/ai-training? false
   :policy/content-id-registration? false})

(def cc-by-asset
  (assoc dova-asset
         :asset/id "incompetech-1"
         :asset/source :source/incompetech
         :license/id :license/cc-by-4.0
         :license/attribution-required? true))

;; ongakuka path B（自前生成）。ライセンス表からは導けないので明示宣言する。
(def self-generated-asset
  (assoc dova-asset
         :asset/id "murakumo-1"
         :license/id nil
         :work/provenance :generated
         :work/model-id "musicgen-small"
         :work/disclosure "本作は murakumo fleet の MusicGen により生成されました。"
         :work/held-rights [(rights/right {:kind :master :exclusive? true})
                            (rights/right {:kind :composition :exclusive? true})]))

(def ^:private ok-proposal
  {:op :commission :effect :propose :stake :low :confidence 0.95})

(defn- req [track-id commission]
  {:track-id track-id :op :commission :channel-id "demo"
   :usage-context :youtube-background
   :commission (merge {:id "c-1" :client "株式会社ほげ" :fee 100000
                       :grants [] :deliverables [:master-audio]}
                      commission)})

;; --- 登録時に確定するもの ------------------------------------------------------

(deftest dova-track-holds-nothing-grantable
  (let [s (store/register-catalog-track! (store/mem-store) dova-asset)
        t (store/track s "dova-12420-10deg")]
    (is (= [] (:work/held-rights t)))
    (is (= :licensed (:work/provenance t)))))

(deftest cc-by-track-holds-non-exclusive-sync
  (let [s (store/register-catalog-track! (store/mem-store) cc-by-asset)
        t (store/track s "incompetech-1")]
    (is (= 1 (count (:work/held-rights t))))
    (is (= :sync (:right/kind (first (:work/held-rights t)))))
    (is (false? (:right/exclusive? (first (:work/held-rights t)))))))

(deftest explicit-declarations-win
  (testing "path B（自前生成）は表から導けないので、資産の宣言をそのまま使う"
    (let [s (store/register-catalog-track! (store/mem-store) self-generated-asset)
          t (store/track s "murakumo-1")]
      (is (= :generated (:work/provenance t)))
      (is (= 2 (count (:work/held-rights t))))
      (is (some #(= :master (:right/kind %)) (:work/held-rights t))))))

;; --- gate に繋いだときの帰結 ----------------------------------------------------

(deftest a-dova-backed-commission-is-held-not-escalated
  (testing "使用許諾しか無い資産の権利譲渡は、人間の署名では覆せない"
    (let [s (store/register-catalog-track! (store/mem-store) dova-asset)
          v (governor/check (req "dova-12420-10deg"
                                 {:grants [(rights/right {:kind :sync :exclusive? false})]})
                            {} ok-proposal s)]
      (is (:hard? v))
      (is (not (:escalate? v)))
      (is (some #(= :not-held (:rule %)) (:violations v))))))

(deftest a-cc-by-backed-commission-passes-non-exclusive-sync
  (let [s (store/register-catalog-track! (store/mem-store) cc-by-asset)]
    (testing "非独占の同期使用は通る"
      (is (:ok? (governor/check (req "incompetech-1"
                                     {:grants [(rights/right {:kind :sync :exclusive? false})]})
                                {} ok-proposal s))))
    (testing "独占にした途端に落ちる"
      (is (:hard? (governor/check (req "incompetech-1"
                                       {:grants [(rights/right {:kind :sync :exclusive? true})]})
                                  {} ok-proposal s))))))

(deftest a-licensed-track-cannot-promise-a-score
  (testing "手元に在るのは音声 1 本なので、譜面も stem も約束できない"
    (let [s (store/register-catalog-track! (store/mem-store) cc-by-asset)
          v (governor/check (req "incompetech-1"
                                 {:grants [(rights/right {:kind :sync :exclusive? false})]
                                  :deliverables [:master-audio :stems :score]})
                            {} ok-proposal s)]
      (is (not (:hard? v)) "採譜の手配で解けるので hold ではない")
      (is (:escalate? v))
      (is (some #(= :not-producible (:problem/type %)) (:commission-problems v))))))

;; --- 説明可能性 ----------------------------------------------------------------

(deftest why-not-grantable-explains-the-refusal
  (let [s (store/register-catalog-track! (store/mem-store) dova-asset)
        e (store/why-not-grantable s "dova-12420-10deg")]
    (is (= :license/dova-syndrome-license (:license/id e)))
    (is (= [] (:grantable e)))
    (is (seq (:reason e)))
    (is (string? (:source-url e)))))
