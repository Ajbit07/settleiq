# Demo dataset

Fourteen payments and nine settlements for `merchant_3`, built so that **every
reconciliation outcome the engine can reach appears exactly once**. Small enough
that each figure on screen can be checked in your head while you talk.

```bash
bash demo/load.sh
```

Creates the merchant, clears its previous source rows, ingests seven CSVs,
reconciles, and checks the result against what the data was designed to produce.
Re-runnable. The audit ledger is deliberately **not** cleared — the database
refuses `DELETE` on it, which is the property the Audit screen demonstrates.

Then open <http://localhost:8088> and pick **merchant_3** in the merchant list.

## One example of every feature

Twelve settlements, seventeen payments. Open a row in **Exceptions** to see it.

### Outcomes

| Show this | Settlement | Residue | What it proves |
|---|---|---:|---|
| Reconciled, no exception | `0001` | 0.00 | closed to the paise, nothing to decide |
| **POSTED** automatically | `0009` | −60.00 | all six gates passed |
| **HELD** — over the ceiling | `0002` | −250.00 | same break as `0009`, too big to post |
| **HELD** — not on the allowlist | `0007` | −450.00 | `unexplained` can never auto-post |
| **HELD** — ambiguous | `0005` `0006` | 0.00 | perfect arithmetic, still refused |

### Classifications the investigation can reach

| Type | Settlement | Named by |
|---|---|---|
| `fee_variance` | `0002` `0009` | rate card vs effective rate |
| `netted_refund` | `0003` | a refund row of the same size |
| `chargeback_debit` | `0004` | dispute amount + fee |
| `reserve_movement` | `0008` | a reserve released that day |
| `ambiguous_candidates` | `0005` `0006` | swap-invariance check |
| `duplicate_credit` | `0010` | no bank credit links at all |
| `partition_unproven` | `0011` | reported gross exceeds available captures |
| `unexplained` | `0007` `0013` | nothing on file accounts for it |

### How the credit was matched — shown in the case pane

| Method | Settlement | Confidence |
|---|---|---:|
| **Exact reference** — UTR in the narration, no model | most | 1.000 |
| **Scored match** — learned pair scorer + assignment solver | `0013` | 0.955 |
| **No credit linked** | `0010` | 0.000 |

`0013` is the ML one. Its narration reads `IMPS PAYOUT CREDIT RETAIL` — every
token under ten characters, so nothing in it can be mistaken for a reference.
The exact-UTR stage finds nothing and the scorer has to decide the link.

### Who planned the investigation

Every case here is planned by the model, because the budget (72 calls, 6 per
case) comfortably covers twelve. To demo the **deterministic** planner instead,
drop `SETTLEIQ_LLM_MAX_CALLS` in `.env` to `36` and re-run: the six largest
unexplained residues get the model, the rest fall back, and the case pane says
which is which. Verdicts are identical either way.

### Ingestion validation

`data/payments_with_errors.csv` is **not** loaded by `load.sh`. Upload it by hand
on the **Ingest** screen to show per-row validation: seven rows, five refused
with a reason (three-decimal amount, missing column, unparseable timestamp,
blank id, negative amount) and two accepted. The two good rows are dated
2025-12-22, which no settlement claims, so accepting them changes no case above.

### Not reachable, and why

Two entries in the taxonomy cannot be produced by any dataset:

- **`timing_offset`** needs a residue within tolerance on a matched, proven,
  unambiguous batch — but that combination never raises an exception in the
  first place, so the branch is unreachable.
- **`rounding_drift`** appears in the auto-post allowlist but not in the
  taxonomy, so nothing can ever be labelled with it.

Neither is a bug in a decision; both are dead branches. Worth knowing before a
judge asks you to show all ten.

## A five-minute run

**Overview** — twelve batches, eleven held, one posted. "What needs my attention?"

**`setl_DEMO_0009`** — the posted one, first. Green POSTED badge, small residue,
every gate satisfied. Establishes that the system does post when it can prove it.

**`setl_DEMO_0002`** — the money trail. Payments in, deductions branching right,
Expected Net, Bank Credit, then the residue. Click **Expected Net** for the exact
reconstruction: 100,000.00 gross, 2,000.00 fee, 360.00 GST, 1,000.00 TDS,
4,882.00 reserve, and a 250.00 gap the rate card explains.

**Control Trace on the same case** — six `LLM → TOOL` pairs with the model's own
reasons, then `check_rate_card` marked *required by the engine: the planner did
not reach it*, then `POLICY: automatic posting blocked`. The whole thesis on one
screen: the model chooses the order, the engine guarantees the questions, policy
decides.

**`setl_DEMO_0005`** — zero residue, still refused. The strongest single moment.

**Audit** — CHAIN VERIFIED over every entry, 0 integrity violations.

## Changing it

Edit `build_demo_data.py`, then `bash demo/load.sh`. The generator computes fees,
GST, reserve and predicted payout the same way the engine does, and `verify.py`
fails loudly if the run disagrees — so the table above cannot quietly drift out
of date.

Two things that bite when editing:

- **The banking calendar counts ordinary Saturdays as working days.** Only
  Sundays and the 2nd/4th Saturday are holidays. A regular batch settling on `D`
  draws captures from `D − 2 banking days`, so `capture Thu 04 → settle Sat 06`.
  Get this wrong and the batch's candidate pool is empty, membership goes
  unproven, and the case classifies as `partition_unproven` instead of whatever
  you intended.
- **A refund is only visible to the investigation when `netted_flag` is true**
  and its payment is a proven member of the batch. An unflagged refund is
  correctly invisible, and the break classifies as `unexplained`.
