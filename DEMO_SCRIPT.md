# DEMO_SCRIPT.md

Five minutes. Two terminals, one browser tab. Every number on screen is
computed at run time — nothing in the demo is hard-coded, including the closing
headline.

**Before you start**

```bash
make all        # ~40s from an empty checkout: generate, build, train, evaluate
```

Leave `make serve` running in a second terminal on port 8733.

---

## 0:00 — 0:40 · The problem, in their language

Terminal 1:

```bash
make demo
```

Read the opening block aloud while section 1 counts up. Then stop on this line
and let it sit:

```
  captured GMV                            62,31,853.54
  total credited by the bank              59,91,325.50
  unexplained gap                          2,40,528.04   <- the whole problem
```

> "This merchant sold ₹62.3 lakh and the bank paid them ₹59.9 lakh. Nobody in
> their finance team can tell you where the other ₹2.4 lakh went."

Point at the three sample narrations underneath. They are deliberately ugly:

```
UPI/249700040264/PAYOUT/RAZORPAY SOFTWARE PVT LTD
NEFT-HDFCN42025110495048853-RAZORPAY SOFT PVT L-HDFC
RTGS  CR HDFCR420251104341570 RAZORPAY SOFTWARE PRIV
```

> "That's what the bank actually wrote. Half of them have a truncated or
> mangled reference number."

## 0:40 — 1:40 · The residue falls

Section 2 runs the engine and animates the counters. The line to wait for:

```
  residue falling            2,40,528.04 -> 60,132.01 -> 10,931.88 -> 5,465.94
  residue after decomposition                          5,465.94
```

> "₹2.4 lakh of 'missing' money, down to ₹5,466. Not by guessing — every batch
> was decomposed into gross, fee, GST on the fee, TDS, netted refunds,
> chargebacks, dispute fees, rolling reserve held, reserve released."

Call out `batches exact to the paise: 39`.

## 1:40 — 2:40 · What the agent did with the rest

Section 3. Three lines matter:

```
  ambiguous_candidates        33        1,574.83
  fee_variance                24        1,619.62
  unexplained                  1        2,271.47

  auto-posted    14
  escalated      44
```

> "The agent named ₹3,194 of the residue as a fee variance — the platform
> quietly changed the card rate mid-month and the published rate card doesn't
> show it. It refused to name the last ₹2,271. There is genuinely no source
> document for it, so it says so instead of writing it off."

Then the refusal line:

> "It also refused to auto-match 33 batches. Two payments with the same amount,
> same method, same rate tier, in different batches settling the same day —
> swapping them changes no total. Arithmetic cannot separate them. Refusing is
> the correct answer."

## 2:40 — 3:10 · Run it twice

Section 4, on screen already:

```
  first run appended              58 hash-chained rows
  identical re-run appended        0 rows   <- zero duplicate postings
  audit: chain_valid=true
```

> "Reconciliation jobs get retried. Retrying this one posts nothing, because
> every posting is keyed by a hash of the facts it was computed from."

Optional, if someone challenges it — Terminal 1:

```bash
python - <<'EOF'
import io
p='reports/demo_audit.jsonl'
L=io.open(p,encoding='utf-8').read().split('\n')
L[4]=L[4].replace('"residue":"','"residue":"9')
io.open(p,'w',encoding='utf-8').write('\n'.join(L))
EOF
```

Then re-run and show `chain_valid=false`.

## 3:10 — 4:20 · The UI

Browser → `http://localhost:8733`

**Bank credits (0:20).** Click any row with a red residue. The waterfall
animates: gross at the top, then every deduction cascading down to the bank
credit, residue in red at the bottom.

> "This is the screen the merchant wanted. To the paise."

**Exceptions (0:25).** Expand one `fee_variance` item.

> "Four bounded nodes: classify, gather evidence, propose resolution, policy
> check. Hard cap of six steps, no loops."

Point at the evidence list — every line ends in a bracketed source id.

> "The agent may not state a fact without a row-level provenance id. And it
> never computes an amount. It picks a label and fills slots; the backend does
> the arithmetic."

Now expand an **escalated** item and read the failed policy arms.

> "Six arms. All must pass to auto-post. This one failed on confidence, so a
> human gets it — with the reason written down."

**Metrics (0:25).** The ablation table.

> "Each row is a real run with that stage switched off. UTR repair bought three
> bank links. The scorer bought eleven. The netting solver is what turns bank
> matching into payment-level reconciliation — 4,781 links and residue down two
> orders of magnitude."

Then point at the caveat under the table:

> "Global assignment bought nothing measurable here, and we say so rather than
> hiding it. Each credit has one viable partner, so greedy and Hungarian agree."

Click **RE-VERIFY CHAIN**.

## 4:20 — 5:00 · Close

Back to Terminal 1:

```
  62.32L reconciled | 2,271.47 genuinely unexplained | 58 honest exceptions
  engine 1699 ms | 5,223 records | residue 5,465.94 (0.0877% of GMV)
```

> "₹62.3 lakh reconciled. ₹5,466 of residue, of which ₹3,194 is named and
> evidenced and ₹2,271 is genuinely unexplainable — one batch, and we report it
> rather than absorbing it. Fifty-eight exceptions, fourteen auto-posted,
> forty-four escalated with reasons. One and a half seconds."
>
> "And the number I'd actually defend: zero false matches among auto-postable
> links, and zero ambiguous payments missed. That's the error that costs an
> analyst a week."

Finish on LIMITATIONS.md:

> "This is our own synthetic data. It proves the algorithm and the calibration.
> It does not prove real-world accuracy, and we've written down exactly which
> is which."

---

## If something goes wrong

| symptom | fix |
|---|---|
| exceptions all show `DUPLICATE SUPPRESSED` | the ledger is from a previous run — `rm reports/audit_ledger.jsonl` (this is correct behaviour, wrong view) |
| `make serve` port busy | `--serve 8734`, or kill the earlier `java` |
| demo counters print on one line | you are piping stdout; run it attached to a terminal |
| model not loaded | `make train` (engine falls back to a transparent prior, and says so) |
