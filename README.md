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

Craft library (public, kotoba-lang):
[`ongaku`](https://github.com/kotoba-lang/ongaku) — BGM selection and
license-policy gating for render pipelines. The private reference
implementation is gftdcojp's `ongakuka` catalog (ADR-2607023000: コードは
kotoba-lang、職能は cloud-itonami-isco、商売は gftdcojp).

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
