(ns music-practice.store
  "SSoT for the ISCO-08 2652 independent musician/composer sole-proprietor
  actor. Store is a protocol injected into the `music-practice.actor`
  StateGraph — `MemStore` is the default, deterministic, zero-dep backend; a
  Datomic/kotoba-server-backed implementation can be swapped in without
  touching the actor or governor (itonami actor pattern, per ADR-2607011000 /
  CLAUDE.md Actors section).

  Domain:

    track    — a registered catalog track. The track map IS an
               ongaku catalog asset (kotoba-lang/ongaku): :asset/id,
               :music/moods, :music/channels, :credit/text and the license
               policy flags (:policy/render-only? :policy/raw-public-access?
               :policy/ai-training? :policy/content-id-registration?).
    record   — a committed operating record under a track (license grant,
               commission delivery, release) — written ONLY via
               commit-record!, never mutated in place
    ledger   — an append-only audit trail of every proposal/verdict/
               disposition, regardless of outcome (commit or hold)"
  (:require [ongaku.holdings :as holdings]))

(defprotocol Store
  (track [s track-id])
  (records-of [s track-id])
  (ledger [s])
  (register-track! [s track])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (track [_ track-id] (get-in @a [:tracks track-id]))
  (records-of [_ track-id] (filter #(= track-id (:track-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-track! [s track]
    (swap! a assoc-in [:tracks (:asset/id track)] track) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:tracks {} :records [] :ledger []} seed)))))

;; --- カタログ資産の登録 ------------------------------------------------------

(defn register-catalog-track!
  "ongaku カタログ資産を track として登録し、**そのライセンスから何を渡せるか**と
  **手元に何が実在するか**をこの時点で確定させる。

  この2つを登録時に一度だけ確定させるのは意図的で、`governor/track->work` の
  既定を fail-closed（`:work/held-rights` が無ければ何も渡せない）のまま
  保つため。実行時に黙って導出すると、gate が静かに開く方向へ倒れる。

  - `:work/held-rights` — `ongaku.holdings/grantable-rights`。DOVA は空、
    CC BY 4.0 は非独占 `:sync` 1 件、未知・未記載も空（fail-closed）。
  - `:work/provenance`  — 既定 `:licensed`（第三者の録音を license で使って
    いる ＝ 手元に在るのは音声 1 本）。

  資産が既に `:work/held-rights` / `:work/provenance` を宣言していればそれを
  尊重する —— ongakuka の path B（自前生成）由来の資産は原盤を保有しており、
  ライセンス表からは導けないため。"
  [s asset]
  (register-track!
   s
   (cond-> asset
     (not (contains? asset :work/held-rights))
     (assoc :work/held-rights (holdings/grantable-rights asset))

     (not (contains? asset :work/provenance))
     (assoc :work/provenance :licensed))))

(defn why-not-grantable
  "その track について何をなぜ渡せないのかを人が読める形で返す。
  受注が `:not-held` で落ちたときの説明に使う。"
  [s track-id]
  (some-> (track s track-id) holdings/explain))
