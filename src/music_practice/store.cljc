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
               disposition, regardless of outcome (commit or hold)")

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
