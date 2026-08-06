(ns music-practice.commission-gate-test
  "受注 gate — ongaku.commission の問題を hold と承認待ちに振り分ける層の検査。

  判定そのもの（権利の包含・期間の重なり・納品可能種別）は ongaku 側の
  suite が見ている。ここで見るのは**職能の判断**だけ:
  どれが人間の署名で覆せず、どれが覆せるか。"
  (:require [clojure.test :refer [deftest is testing]]
            [music-practice.governor :as governor]
            [music-practice.store :as store]
            [ongaku.rights :as rights]))

(def ^:private base-track
  {:asset/id "trk-1"
   :asset/title "Fixture Calm"
   :music/moods #{:calm}
   :music/channels #{"demo"}
   :credit/text "BGM: Fixture Calm / tester"
   :policy/render-only? true
   :policy/raw-public-access? false
   :policy/ai-training? false
   :policy/content-id-registration? false})

(def ^:private authored-track
  (assoc base-track
         :work/provenance :authored
         :work/held-rights [(rights/right {:kind :master :exclusive? true})
                            (rights/right {:kind :sync :exclusive? true})]))

(def ^:private generated-track
  (assoc base-track
         :work/provenance :generated
         :work/model-id "diffrhythm-1.2-ja"
         :work/disclosure "本作は ai.gftd.ongakuka.compose により生成されました。"
         :work/held-rights [(rights/right {:kind :master :exclusive? true})]))

(def ^:private catalog-backed-track
  ;; ongakuka 側のカタログ資産が素材。非独占の同期使用許諾しか持たない。
  (assoc base-track
         :work/provenance :authored
         :work/held-rights [(rights/right {:kind :sync :exclusive? false})]))

(defn- store-with [track & records]
  (let [s (-> (store/mem-store) (store/register-track! track))]
    (doseq [r records] (store/commit-record! s r))
    s))

(def ^:private ok-proposal
  {:op :commission :effect :propose :stake :low :confidence 0.95})

(defn- req [commission]
  {:track-id "trk-1" :op :commission :channel-id "demo"
   :usage-context :youtube-background
   :commission (merge {:id "c-0001" :client "株式会社ほげ" :brief "CM 30 秒"
                       :fee 300000 :deadline "2026-09-30"
                       :grants [] :deliverables [:master-audio]}
                      commission)})

;; --- 通る --------------------------------------------------------------------

(deftest clean-commission-commits
  (let [v (governor/check (req {:grants [(rights/right {:kind :sync :territory #{"JP"}
                                                        :exclusive? true})]
                                :deliverables [:master-audio :stems :midi :score :session]})
                          {} ok-proposal (store-with authored-track))]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))
    (is (empty? (:commission-problems v)))))

(deftest a-request-without-a-commission-is-unaffected
  (testing "受注が付いていない従来のライセンス要求は素通し"
    (let [v (governor/check {:track-id "trk-1" :op :license-track :channel-id "demo"
                             :usage-context :youtube-background}
                            {} ok-proposal (store-with authored-track))]
      (is (:ok? v))
      (is (empty? (:commission-problems v))))))

;; --- 人間の署名でも覆せない（hold） -------------------------------------------

(deftest selling-a-right-you-do-not-hold-is-hard
  (let [v (governor/check (req {:grants [(rights/right {:kind :master :exclusive? true})]})
                          {} ok-proposal (store-with catalog-backed-track))]
    (is (:hard? v) "持っていない権利の譲渡は hold であって承認待ちではない")
    (is (not (:escalate? v)))
    (is (some #(= :not-held (:rule %)) (:violations v)))))

(deftest double-exclusive-grant-is-hard
  (let [prior (rights/right {:kind :sync :territory #{"JP"} :exclusive? true
                             :term {:term/from "2026-01-01" :term/until "2031-01-01"}})
        v (governor/check
           (req {:grants [(rights/right {:kind :sync :territory #{"JP"} :exclusive? true
                                         :term {:term/from "2028-01-01" :term/until "2032-01-01"}})]})
           {} ok-proposal
           (store-with authored-track {:track-id "trk-1" :op :grant :right prior}))]
    (is (:hard? v) "既に渡した独占は署名で取り戻せない")
    (is (some #(= :exclusive-conflict (:rule %)) (:violations v)))))

(deftest malformed-commission-is-hard
  (let [v (governor/check (req {:id "" :client "" :fee -1})
                          {} ok-proposal (store-with authored-track))
        rules (set (map :rule (:violations v)))]
    (is (:hard? v))
    (is (contains? rules :missing-id))
    (is (contains? rules :missing-client))
    (is (contains? rules :negative-fee))))

;; --- 人間が解ける（承認待ち） --------------------------------------------------

(deftest promising-a-score-for-a-generated-work-escalates
  (let [v (governor/check (req {:grants [(rights/right {:kind :master :exclusive? true})]
                                :deliverables [:master-audio :stems :score :midi]})
                          {} ok-proposal (store-with generated-track))]
    (is (not (:hard? v)) "採譜を手配すれば譜面は実在しうるので hold ではない")
    (is (:escalate? v))
    (is (not (:ok? v)))
    (is (some #(= :not-producible (:problem/type %)) (:commission-problems v)))))

(deftest ai-work-missing-disclosure-escalates
  (let [track (dissoc generated-track :work/model-id :work/disclosure)
        v (governor/check (req {:grants [(rights/right {:kind :master :exclusive? true})]})
                          {} ok-proposal (store-with track))
        types (set (map :problem/type (:commission-problems v)))]
    (is (not (:hard? v)) "開示文と model-id は人間が書けるので hold ではない")
    (is (:escalate? v))
    (is (contains? types :missing-model-id))
    (is (contains? types :missing-disclosure))))

;; --- 分類の網羅（ongaku が種別を足したら落ちる） -------------------------------

(deftest every-known-problem-type-is-classified
  (testing "escalate 集合に無い種別は hard に倒れる（未知を黙って通さない）"
    (let [v (governor/check
             (req {:grants [(rights/right {:kind :merchandising})]})  ; 未知の権利種別
             {} ok-proposal (store-with authored-track))]
      (is (:hard? v))
      (is (some #(= :unknown-right-kind (:rule %)) (:violations v)))))
  (testing "escalate 集合は ongaku の問題種別の部分集合であり続ける"
    (is (= #{:not-producible :missing-model-id :missing-disclosure}
           governor/escalatable-commission-problems)
        (str "ongaku が問題種別を足したら、この集合に入れるか hard のままかを "
             "明示的に決めること"))))

;; --- 自己申告できないこと ------------------------------------------------------

(deftest held-rights-come-from-the-store-not-the-request
  (testing "request 側で保有権利を主張しても track レコードが正本"
    (let [v (governor/check
             (assoc-in (req {:grants [(rights/right {:kind :master :exclusive? true})]})
                       [:commission :work]
                       ;; 呼び出し側が「原盤権を持っている」と自己申告してくる
                       {:work/id "trk-1" :work/provenance :authored
                        :work/held-rights [(rights/right {:kind :master :exclusive? true})]})
             {} ok-proposal (store-with catalog-backed-track))]
      (is (:hard? v) "自己申告は無視され、store の非独占 sync だけが効く")
      (is (some #(= :not-held (:rule %)) (:violations v))))))
