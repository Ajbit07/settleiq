-- V8 records WHO chose each investigation step, not just which steps ran.
--
-- THE GAP THIS CLOSES
--
-- recon_exception stored `agent_steps` as a ">"-separated list of tool names.
-- That is enough to redraw the investigation path and nothing else. It cannot
-- answer the question a reviewer actually asks about an AI-assisted decision:
-- did a model choose this, or did the deterministic planner?
--
-- Without it the UI could only describe what each tool READS -- a static fact
-- about the tool -- and had to stay silent about why it was run, because the
-- reasoning was never persisted. The product could make a real LLM call and
-- then be structurally unable to show it happened.
--
-- WHAT IS STORED, AND WHAT DELIBERATELY IS NOT
--
-- agent_plan holds one row per step: the tool, who chose it, and the chooser's
-- stated reason. When that chooser is a model the reason is ITS OWN TEXT, kept
-- verbatim so nobody has to take the UI's word for what it said.
--
-- The reason is prose from a language model and is stored as evidence of the
-- CHOICE, never as an input to any figure. Every amount is computed by the
-- engine and re-derived by the policy engine from source rows; nothing in this
-- column is read back by anything that touches money.

ALTER TABLE recon_exception ADD COLUMN llm_used   BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE recon_exception ADD COLUMN llm_model  TEXT;
ALTER TABLE recon_exception ADD COLUMN llm_calls  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE recon_exception ADD COLUMN agent_plan JSONB   NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN recon_exception.agent_plan IS
  'Ordered investigation steps: [{"n":1,"tool":"get_settlement","chosen_by":"llm:phi4-mini",'
  '"reason":"...","facts":2}]. chosen_by is "deterministic" or "llm:<model>". The reason is '
  'the chooser''s own words, kept as provenance for the CHOICE and never used in arithmetic.';

COMMENT ON COLUMN recon_exception.llm_used IS
  'True only when a model actually answered and its choice was accepted. A configured but '
  'unreachable model leaves this false, so the record never implies a consultation that '
  'did not happen.';
