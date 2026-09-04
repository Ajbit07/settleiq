# Demo script — 5 minutes

Written against **Track 04, AI Finance Controller**, whose bar is:

> Closes one finance-ops loop across a 50+ record batch, reporting its match rate
> and the exceptions it could not resolve.
> **Throughput plus measured accuracy plus an honest exception list. One
> cherry-picked match proves nothing.**

So this script leads with numbers, not with the investigation UI. The first
sixty seconds answer the bar completely; everything after that is evidence.

## Before recording

```bash
docker compose up -d
ollama pull phi4-mini:latest      # optional, but it is the differentiator
```

Set `SETTLEIQ_LLM_MAX_CALLS=12` in `.env` if you might click **Reconcile** on
camera — it takes the run from ~128s to ~20s. Verdicts are identical either way.

Use **merchant_1** (4,781 payments). Do not lead with merchant_3 — 17 payments
is under the track's 50-record floor.

Nothing else should be talking to Ollama while you record. It serialises a single
model, and a competing process makes every call about 8× slower.

---

## 0:00 – 0:45 · The loop and the numbers

**Overview, merchant_1.** Read the metric rail aloud.

> "Reconciliation across three sources that don't agree: a payment gateway, a
> settlement report, and a bank statement. **4,781 payments reconciled in 69
> milliseconds. 100% of bank credits matched, 86 of 86. 99.59% of settled value
> accounted for. And ₹7,139 across 8 cases it could not resolve.** That last
> number is the one we care most about, because it's the one most systems hide."

## 0:45 – 1:25 · The honest exception list

**Exceptions.** Point at *Named and evidenced* against *No source document exists*.

> "60 exceptions. **52 have a named cause and the source row that proves it.**
> 8 don't — no refund, no chargeback, no reserve movement, nothing in the rate
> card explains them. So we label them `unexplained` and escalate. We don't
> attach the nearest plausible document and call it closed."

Scroll the queue so it is visibly a batch, not one case.

## 1:25 – 2:25 · Not cherry-picked

**Metrics.** This scene exists to answer *"one cherry-picked match proves nothing."*

Point at the disclaimer first — it is unprompted and most entries won't have one:

> "These are offline results against held-out ground truth the running service
> cannot read."

Then read the **Residue %** column of the ablation:

> "Every row is a real run with a stage switched off. Exact reference matching
> alone gets 72 of 86 credits and leaves **19.75%** of value unexplained. UTR
> repair takes it to 75. **The ML pair scorer closes the last 11 and drops
> residue to 7.30%.** Netting proves batch membership and takes it to **0.09%**.
> Amount-weighted F1 100, false-match rate **0.000%** against a 0.5% budget,
> **2,845 records a second**."

If asked about the ML specifically: *amount alone reaches 83.33% top-1, the full
feature set 100% — it changes 4 decisions, all 4 to the correct settlement.*

## 2:25 – 3:35 · The loop closing on one case

**Exceptions → open the largest fee variance.** Walk the money trail down, then
click **Expected Net**:

```
100,000.00  payments captured
 −2,000.00  platform fee (2% published card rate)
   −360.00  GST on fee
 −1,000.00  TDS under 194-O
 −4,882.00  rolling reserve
─────────────
 91,758.00  expected net    vs   91,508.00 paid    →  −250.00
```

> "Integer paise end to end — no floating point anywhere near the money. The
> platform charged an effective 2.25% against a published 2.00%."

## 3:35 – 4:20 · Where the AI stops

**Control Trace**, same case. This is the differentiator.

> "Six lookups, each chosen by phi4-mini, each with the model's own reason."

Then point at the bare `TOOL` row:

> "And `check_rate_card` — which the model never asked for. The engine runs it
> regardless. **We tested letting the model plan alone: it spent all six steps
> going broad, never checked the rate card, and three fee variances we had
> already explained collapsed back to 'unexplained'.** More evidence gathered,
> less known. So the model picks the order; the engine guarantees the questions."

Land on `POLICY: automatic posting blocked`.

> "The model never reaches this line. It cannot compute an amount and it cannot
> write to the ledger."

## 4:20 – 5:00 · Close

**Audit.** CHAIN VERIFIED, then press **Verify**.

> "SHA-256 over previous-hash plus the canonical row, so editing a payload or a
> verdict is caught — not just cutting a link. The database refuses UPDATE and
> DELETE outright. And re-run the same batch: **78 duplicate postings suppressed,
> zero rows appended.** Postings are keyed by a hash of the facts they were
> computed from."

---

## Do not

- **Don't run Reconcile live** at the default budget — 128 seconds of dead air.
- **Don't lead with the money trail.** It is scene four. The bar is throughput
  and accuracy.
- **Don't quote wall-clock as reconciliation time.** 4,781 payments reconcile in
  **69 ms**; the rest of the wall clock is the model investigating, serialised,
  under a budget you chose.
- **Don't skip the could-not-resolve number.** It is named in the brief, and
  volunteering your own failure count is the most credible thing in the video.

## If you have 30 seconds spare

Upload `demo/upload/8_PAYMENTS_with_errors.csv` on **Ingest** as PAYMENTS:
**2 accepted, 5 refused**, each with a reason code and its original text. Shows
the loop begins with validation rather than with trusting the feed.

## The question they will ask

> **"What breaks at 2am?"**

> "The most dangerous failure wasn't a crash — it was the model quietly making
> things worse. When we let it plan more cases, three fee variances we had
> already explained collapsed to 'unexplained'. Nothing errored. The number just
> got worse, silently. We fixed it by making the decisive checks non-skippable.
>
> If the model is down: the deterministic planner takes over, the trace records
> `llm_used=false`, and because of that fix the classification is identical. The
> AI dying at 2am changes nothing about the money.
>
> If the job runs twice: postings are keyed by a hash of the facts, so a retry
> appends zero rows. Two workers can't claim the same job, and a dead worker's
> job is requeued — both tested.
>
> What we don't have: alerting, a retry queue, and it's single-node. If the
> service is down at 2am nobody gets paged. That's next. The guarantee we do
> have is that nothing wrong gets *written* while we're not looking."
