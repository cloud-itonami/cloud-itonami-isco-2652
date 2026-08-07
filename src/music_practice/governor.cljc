(ns music-practice.governor
  "MusicGovernor — the independent license-policy/traceability layer for the
  ISCO-08 2652 independent musician/composer actor. Wired as its own
  `:govern` node in `music-practice.actor`'s StateGraph, downstream of
  `:advise` — the Advisor has no notion of catalog provenance or license
  policy, so this MUST be a separate system able to reject a proposal
  (itonami actor pattern, per ADR-2607011000 / CLAUDE.md Actors section).

  The license-policy core is NOT bespoke: it is `ongaku.policy` from the
  kotoba-lang `ongaku` craft lib (ADR-2607023000) — the same gate the
  private gftd catalog uses. `check` is a pure function of
  (request, context, proposal, store) -> verdict; it never mutates the
  store. The StateGraph's `:decide` node routes on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. catalog provenance — the request's track must be registered.
    2. no-actuation       — proposal :effect must be :propose.
    3. 権利の保存 — 受注（`:commission`）が保有していない権利を譲渡しようと
       している、既発の独占譲渡と衝突している、あるいは受注レコード自体が
       壊れている。**人間の署名でも覆せない** — 持っていない権利は署名しても
       自分のものにならないし、既に渡した独占は署名で取り戻せない。
    3b. 権利未確定 — track が `:work/held-rights` を一度も記録していない。
       これは「渡せる権利が無い」ではなく「まだ誰も決めていない」で、
       やることが違う（断るのではなく確定作業が残っている）。値としては
       どちらも空に見えるので、`rights-determined?` で区別する。
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off):
    4. any ongaku.policy error — raw public file exposure, AI-training
       use, Content ID/fingerprint registration, an unlicensed channel,
       or a usage context outside the render-only allowance. Automated
       advice can never grant these; a human-signed license can.
    5. 履行可能性 — 受注が provenance 的に作れない納品物を約束している
       （純 AI 生成の作品に譜面や MIDI）、または AI 関与作品の model-id /
       開示文が無い。**これは人間が解ける** — 採譜を別途手配すれば譜面は
       実在しうるし、開示文は人間が書ける。だから hold ではなく承認待ち。
    6. low confidence (< `confidence-floor`).

  受注の判定そのものは bespoke ではない: `ongaku.commission/validate`
  （kotoba-lang `ongaku` craft lib）が問題の vector を返し、**それを hard と
  escalate のどちらに振るかだけがこの職能の判断**。技芸は事実を返し、職能が
  処分を決める。

  重要: 受注の保有権利・provenance は **request ではなく store の track
  レコードから採る**。advisor も呼び出し側も「この作品の原盤権を持っている」
  と自己申告できない —— 申告できてしまうと権利保存の invariant が意味を失う。"
  (:require [music-practice.store :as store]
            [ongaku.commission :as commission]
            [ongaku.policy :as policy]
            [ongaku.work :as work]))

(def confidence-floor 0.6)

;; 受注の問題種別のうち、**人間が解けるものだけ**を列挙する。ここに無い種別は
;; すべて hard（hold）—— 未知の問題種別を黙って承認待ちに流すより、止めて
;; 分類を足させる方が安全側に倒れる。ongaku が新しい問題種別を足したときに
;; 気づけるよう `governor-test/every-known-problem-type-is-classified` が見張る。
;;
;; 解ける理由: 採譜を別途手配すれば譜面は実在しうる（:not-producible）、
;; 開示文と model-id は人間が書ける（:missing-disclosure / :missing-model-id）。
;; 解けない理由: 持っていない権利は署名しても自分のものにならず（:not-held）、
;; 既に渡した独占は署名で取り戻せない（:exclusive-conflict）。構造不備
;; （:missing-id 等）は受注レコードが壊れているので作り直させる。
(def escalatable-commission-problems
  #{:not-producible :missing-model-id :missing-disclosure})

(defn- hard-violations [{:keys [request proposal]} track-record]
  (cond-> []
    (nil? track-record)
    (conj {:rule :no-track :detail (str "未登録 track " (:track-id request))})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})))

;; --- 受注 ------------------------------------------------------------------

(defn- track->work
  "store の track レコードから `ongaku.work/work` を組む。

  保有権利も provenance も **track レコードが正本**で、request からは採らない。
  そうしないと呼び出し側が「原盤権を持っている」と自己申告できてしまい、
  権利保存の invariant が空文になる。"
  [track-record]
  (work/work {:id (:asset/id track-record)
              :title (:asset/title track-record)
              :provenance (or (:work/provenance track-record) :authored)
              :model-id (:work/model-id track-record)
              :disclosure (:work/disclosure track-record)
              :held-rights (:work/held-rights track-record)}))

(defn- existing-grants
  "この track について既に commit 済みの譲渡。独占の二重譲渡の検査に渡す。"
  [store track-id]
  (into [] (keep :right) (store/records-of store track-id)))

(defn rights-determined?
  "この track について**権利確定を一度でも通したか**。

  `:work/held-rights` が `[]` であることと、キー自体が無いことは別物。前者は
  『確定した結果、渡せるものが無かった』（DOVA 資産など）、後者は『まだ誰も
  決めていない』。値としてはどちらも「渡せない」に見えるが、**運用上やることが
  違う** —— 前者は仕様どおりなので受注を断る、後者は
  `store/register-catalog-track!` を通すか権利を明示する作業が残っている。

  `ongaku.holdings` が未知のライセンスを「制限が無い」と読み替えないのと同じ
  区別を、store 層でも立てる。"
  [track-record]
  (contains? track-record :work/held-rights))

(defn commission-problems
  "受注が付いていれば `ongaku.commission/validate` を回して問題を返す。
  受注が無ければ `nil`。

  権利確定を通っていない track は、craft 層へ渡す前にここで止める ——
  そのまま渡すと `:not-held`（渡せる権利が無い）として出てしまい、
  『まだ決めていない』が『決めた結果ゼロ』に化けるため。"
  [request track-record store]
  (when-let [c (:commission request)]
    (when track-record
      (if-not (rights-determined? track-record)
        [{:problem/type :rights-not-determined
          :problem/track-id (:track-id request)
          :problem/message
          (str "track " (:track-id request) " は権利確定を通っていない。"
               "『渡せる権利が無い』のではなく『まだ誰も決めていない』 —— "
               "store/register-catalog-track! を通すか :work/held-rights を明示すること")}]
        (commission/validate
         (commission/commission (assoc c :work (track->work track-record)))
         (existing-grants store (:track-id request)))))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `music-practice.store/Store`. Returns
  `{:ok? bool :violations [...] :policy-errors [...] :commission-problems [...]
    :confidence n :hard? bool :escalate? bool}`.

  `request` に `:commission` があれば受注も検査する（`ongaku.commission`）。
  問題は `escalatable-commission-problems` に載っているものだけが承認待ちで、
  **それ以外は未知の種別も含めて hold** —— 分類を知らない問題を黙って通すより、
  止めて分類を足させる方が安全側。"
  [request _context proposal store]
  (let [track-record (store/track store (:track-id request))
        base-hard (hard-violations {:request request :proposal proposal} track-record)
        comm-problems (vec (commission-problems request track-record store))
        comm-escalate (filterv #(escalatable-commission-problems (:problem/type %))
                               comm-problems)
        ;; escalate 集合に無いものは全部 hard（未知の種別を含む）
        comm-hard (filterv #(not (escalatable-commission-problems (:problem/type %)))
                           comm-problems)
        hard (into base-hard
                   (map (fn [p] {:rule (:problem/type p)
                                 :detail (or (:problem/message p) (pr-str p))}))
                   comm-hard)
        hard? (boolean (seq hard))
        policy-errs (when track-record
                      (policy/policy-errors
                       track-record
                       {:channel-id (:channel-id request)
                        :usage-context (or (:usage-context request)
                                           :youtube-background)
                        :expose-raw? (:expose-raw? request)
                        :ai-training? (:ai-training? request)
                        :content-id? (:content-id? request)}))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky? (boolean (seq policy-errs))
        unfulfillable? (boolean (seq comm-escalate))]
    {:ok? (and (not hard?) (not low?) (not risky?) (not unfulfillable?))
     :violations hard
     :policy-errors (vec (or policy-errs []))
     :commission-problems comm-problems
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky? unfulfillable?))}))
