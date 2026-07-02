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
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off):
    3. any ongaku.policy error — raw public file exposure, AI-training
       use, Content ID/fingerprint registration, an unlicensed channel,
       or a usage context outside the render-only allowance. Automated
       advice can never grant these; a human-signed license can.
    4. low confidence (< `confidence-floor`)."
  (:require [music-practice.store :as store]
            [ongaku.policy :as policy]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [request proposal]} track-record]
  (cond-> []
    (nil? track-record)
    (conj {:rule :no-track :detail (str "未登録 track " (:track-id request))})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `music-practice.store/Store`. Returns
  `{:ok? bool :violations [...] :policy-errors [...] :confidence n
    :hard? bool :escalate? bool}`."
  [request _context proposal store]
  (let [track-record (store/track store (:track-id request))
        hard (hard-violations {:request request :proposal proposal} track-record)
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
        risky? (boolean (seq policy-errs))]
    {:ok? (and (not hard?) (not low?) (not risky?))
     :violations hard
     :policy-errors (vec (or policy-errs []))
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky?))}))
