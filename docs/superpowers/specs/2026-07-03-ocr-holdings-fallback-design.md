# OCR Holdings Fallback Design

## Goal

Prevent `/api/fida/orders/from-image` from producing `SELL` orders with `holdings=0` when the image clearly shows a non-zero cumulative or holding quantity.

## Approach

- Strengthen the Gemini prompt so it returns separate quantity candidates instead of a single ambiguous `holdings` value.
- Extend the internal Gemini JSON DTO with:
  - `holding_qty`
  - `cumulative_qty`
  - `buy_qty`
- In Java post-processing, resolve final holdings with this priority:
  - `holding_qty` if positive
  - `cumulative_qty` if positive
  - legacy `holdings`
  - otherwise `0`
- Ignore `buy_qty` for final holdings calculation. It is only diagnostic.
- Keep the existing KISTA validation that blocks `SELL` with zero holdings as the final defense.

## Why

- The failure image shows `매도가 39.73 / 남은전부` and `누적개수 27`.
- Production logs show Gemini returned `sell=[39.73, ALL]` with `holdings=0`.
- This means the bug is not in KISTA validation. The OCR result is inconsistent.
- A single `holdings` field is too fragile when the model confuses `매수개수`, `누적개수`, and `보유개수`.

## Test Plan

- Add a failing regression test for the production-shaped OCR response where:
  - `sell` contains `ALL`
  - `holdings=0`
  - `cumulative_qty=27`
- Verify the adapter resolves final holdings to `27`.
- Re-run OCR adapter tests and KISTA adapter tests.
