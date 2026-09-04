-- The demo merchant.
--
-- Deliberately separate from the seeded merchants: ingesting this dataset into
-- merchant_1 would mix 13 hand-checked payments into 4,781 generated ones and
-- neither set would be legible on screen afterwards.
--
-- The rate card uses round percentages so every deduction in the money trail can
-- be checked in your head: 2% of 60,000 is 1,200, and 18% of that is 216.
INSERT INTO merchant (merchant_id, name, rate_card) VALUES (
  'merchant_3',
  'Demo Retail Pvt Ltd',
  '{
     "merchant_id": "merchant_3",
     "name": "Demo Retail Pvt Ltd",
     "methods": {
       "upi":        [{"pct": "0.00", "flat_paise": 0,    "effective_from": "2025-11-01"}],
       "card":       [{"pct": "2.00", "flat_paise": 0,    "effective_from": "2025-11-01"}],
       "wallet":     [{"pct": "1.80", "flat_paise": 0,    "effective_from": "2025-11-01"}],
       "netbanking": [{"pct": "0.00", "flat_paise": 1500, "effective_from": "2025-11-01"}]
     },
     "gst_rate_pct": "18.0",
     "tds_rate_pct": "1.0",
     "dispute_fee": "300.00",
     "reserve_methods": ["card", "wallet"],
     "reserve_rate_pct": "5.0",
     "reserve_hold_days": 90,
     "instant_surcharge_pct": "0.25",
     "tolerance_paise": 100
   }'::jsonb
)
ON CONFLICT (merchant_id) DO UPDATE
  SET rate_card = EXCLUDED.rate_card, name = EXCLUDED.name;
