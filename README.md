# cloud-itonami-isco-2652

Open Occupation Blueprint for **ISCO-08 2652**: Musicians, Singers and
Composers (音楽家).

This repository designs a forkable OSS business for an independent musician /
composer: a BGM and commission practice where the musician keeps their own
catalog, licensing terms and usage records instead of renting a closed
music-distribution SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a studio automation robot performs physical
console and instrument setup, recording-session capture and equipment handling
under an actor that proposes actions and an independent **Music Governor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions (such as licensing a track for AI training,
registering a work with a fingerprinting/Content ID system, or exposing raw
master files publicly) require human sign-off.

A live sample of the operator console (robotics safety console, shared
template) is rendered in
[docs/samples/operator-console.html](docs/samples/operator-console.html) —
pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
commission brief + mood/duration constraints + usage context
        |
        v
Music Advisor -> Music Governor -> compose/license/deliver, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, license
a use the catalog policy forbids (raw public file exposure, AI training,
Content ID registration), or suppress a usage record.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `2652`). Required capabilities:

- :robotics
- :identity
- :forms
- :dmn
- :bpmn
- :audit-ledger

Craft libraries (public, kotoba-lang) — ISCO-08 2652 bundles Musicians,
Singers **and Composers**, so this occupation spans three crafts:

- **選曲 / licensing** — [`ongaku`](https://github.com/kotoba-lang/ongaku):
  BGM selection and license-policy gating for render pipelines.
- **作曲（人が書く経路）** — [`kami-ongaku-notation`](https://github.com/kotoba-lang/kami-ongaku-notation)
  (score IR + MusicXML), [`kami-ongaku-sequencer`](https://github.com/kotoba-lang/kami-ongaku-sequencer)
  (MIDI/SMF), [`kami-ongaku-project`](https://github.com/kotoba-lang/kami-ongaku-project)
  (DAW session), plus `-plugin-host` / `-sampler` (ADR-2607121400).
- **作曲（生成する経路）** — [`composer`](https://github.com/kotoba-lang/composer):
  the `ai.gftd.ongakuka.*` compose/track/stem/style/generation contract
  (ADR-2607031510).

The occupation is one; the **business layer is two**, because the revenue
mechanics differ (ADR-2607023000: コードは kotoba-lang、職能は
cloud-itonami-isco、商売は -ka repo):

| repo | 商売 | 権利 |
|---|---|---|
| [`ongakuka`](https://github.com/cloud-itonami/ongakuka) | 既製 BGM カタログの選定とライセンス運用 | 第三者の音源。原盤権は持たない |
| [`sakkyokuka`](https://github.com/cloud-itonami/sakkyokuka) | 受注制作 | 原盤権・著作権・出版権を自分が持つ |

ISCO is not split — the same shape as `isco-2651` (painters / sculptors /
cartoonists) hosting only `mangaka`.

## Reference actor (`:maturity :implemented`)

Like [`cloud-itonami-isco-6130`](https://github.com/cloud-itonami/cloud-itonami-isco-6130),
this repository implements the **full itonami Actor pattern**: a real
[`kotoba-lang/langgraph`](https://github.com/kotoba-lang/langgraph)
`StateGraph` with the Advisor and Governor as distinct graph nodes and
human-in-the-loop interrupt/resume via checkpointing. It is the first
reference actor whose governor core is a kotoba-lang **craft lib**: the
license-policy gate is [`ongaku.policy`](https://github.com/kotoba-lang/ongaku)
— the same gate the private gftd catalog uses (ADR-2607023000).

```text
:intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                           +-> :request-approval   (:escalate? true, interrupt-before)
                                           +-> :hold               (:hard? true)
```

- `src/music_practice/store.kotoba` — `Store` protocol + `MemStore`:
  registered catalog tracks (each track map IS an ongaku catalog asset with
  its license policy flags), committed records, an append-only audit ledger.
- `src/music_practice/advisor.kotoba` — `Advisor` protocol; `mock-advisor`
  (deterministic, default) proposes a music operation from a request;
  `llm-advisor` wraps a `langchain.model/ChatModel` — either way the advisor
  only ever produces a `:propose`-effect proposal, and LLM parse failures
  always yield `confidence 0.0` (forces escalation, never fabricated
  confidence).
- `src/music_practice/governor.kotoba` — `MusicGovernor/check`: a pure
  function, wired as its own `:govern` node. Hard invariants (unregistered
  track, a proposal whose `:effect` isn't `:propose`) always route to
  `:hold`. Escalation invariants (any `ongaku.policy` error — raw public
  exposure, AI-training use, Content ID registration, unlicensed channel or
  context — and low confidence) always route to `:request-approval`; the
  human resume IS the human-signed license.
- 受注 gate — request に `:commission` があれば
  [`ongaku.commission`](https://github.com/kotoba-lang/ongaku) も回す。
  ISCO-08 2652 は Musicians, Singers **and Composers** なので、gate は
  **使い方（policy、下流）と渡し方（commission、上流）の両方**を持つ。
  判定は craft 側にあり、**この職能が決めるのは処分の振り分けだけ**:

  | 問題 | 処分 | なぜ |
  |---|---|---|
  | `:rights-not-determined` | **hold** | まだ誰も決めていない。断るのではなく確定作業が残っている |
  | `:not-held` | **hold** | 持っていない権利は署名しても自分のものにならない |
  | `:exclusive-conflict` | **hold** | 既に渡した独占は署名で取り戻せない |
  | 構造不備 (`:missing-id` 等) | **hold** | 受注レコードが壊れている。作り直させる |
  | `:not-producible` | 承認待ち | 採譜を手配すれば譜面は実在しうる |
  | `:missing-model-id` / `:missing-disclosure` | 承認待ち | 人間が書ける |

  `escalatable-commission-problems` に無い種別はすべて hold —— 未知の問題を
  黙って承認待ちに流さない。**保有権利と provenance は request ではなく
  store の track レコードから採る**ので、呼び出し側が「原盤権を持っている」と
  自己申告することはできない（`commission-gate-test` がこれを実際に試す）。
  台帳の側は [`cloud-itonami/sakkyokuka`](https://github.com/cloud-itonami/sakkyokuka)。
- `store/register-catalog-track!` — ongaku カタログ資産を track として登録し、
  **ライセンスから何を渡せるか**（`ongaku.holdings`）と**手元に何が実在するか**
  （`:work/provenance :licensed` = 音声 1 本）を登録時に確定させる。

  | 資産 | 渡せる | 実在する |
  |---|---|---|
  | DOVA-SYNDROME | **なし**（使用許諾であってサブライセンスではない） | 音声のみ |
  | CC BY 4.0 | 非独占 `:sync` のみ（独占は原理的に不可） | 音声のみ |
  | 未知 / 未記載 | **なし**（fail-closed） | 音声のみ |
  | path B 自前生成 | 資産の宣言どおり（表からは導けない） | 宣言どおり |

  導出を**登録時の明示的な 1 回**にしてあるのは意図的で、`track->work` の既定を
  fail-closed のまま保つため。実行時に黙って導出すると gate が静かに開く方向へ
  倒れる。`store/why-not-grantable` が拒否の理由（根拠・確認日・出典つき）を返す。

  **「まだ決めていない」と「決めた結果ゼロ」は区別する。** `:work/held-rights`
  が `[]` なのと、キー自体が無いのは別物 —— 前者は仕様どおり（DOVA 資産は本当に
  渡せるものが無い）なので受注を断る、後者は確定作業が残っている。値としては
  どちらも「渡せない」に見えるが**やることが違う**ので、`rights-determined?` で
  分けて `:rights-not-determined` を返す。`ongaku.holdings` が未知のライセンスを
  「制限が無い」と読み替えないのと同じ区別を store 層でも立てている。
- `src/music_practice/actor.kotoba` — the StateGraph; committed license
  records carry the track's credit text and render-only flag.

Run the tests:

```bash
clojure -M:test
```

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
