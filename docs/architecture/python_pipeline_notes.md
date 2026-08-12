# 04. Python Pipeline Notes

## Goal

Record the agreed Python pipeline baseline and contract.

---

## Python Responsibility Boundary

Python is responsible for:

- raw CSV normalization
- reference-based 1st-stage mapping
- route ID mapping
- standard code mapping
- fallback fills
- curated CSV generation
- inspection/debug scripts for validation

Python is not responsible for:

- final operational serving
- all admin correction logic
- virtual stop domain rules that require Spring/DB context
- post-load serving APIs

---

## Current Baseline

Main pipeline:
- `od_correction_pipeline.py`

Inspection script:
- curated output inspection script for null route IDs / ARS / standard codes

---

## Output Contract

Curated CSV columns:

- `기준일자`
- `노선명`
- `전환_노선ID`
- `승차_정류장순번`
- `승차_정류장ARS`
- `승차_정류장표준코드`
- `승차_정류장명`
- `하차_정류장순번`
- `하차_정류장ARS`
- `하차_정류장표준코드`
- `하차_정류장명`
- `승객수`

---

## Current Python Design Notes

### delimiter / encoding
Use separate config for:
- raw delimiter
- reference delimiter
- output delimiter
- raw encoding
- reference encoding
- output encoding

### route mapping
Current route matching is based on:
- route name
- boarding sequence
- alighting sequence

### formatting
- initial normalization removes `.0`, trims spaces
- zero-padding is applied only at the final formatting stage
- route names are not padded

### known route-name overrides
Known corrupted route labels are repaired before matching.

Examples:
- `411 -> 0411`
- `40 -> 040`
- `Jan-01 -> 101`

---

## Archive Policy

Experimental or superseded scripts should be moved to:

```text
pipelines/od_correction/archive/
```

The active baseline should remain under a single canonical name.

---

## Spring Handoff Notes

Spring is expected to handle:
- curated CSV ingestion and serving
- stop master based correction / master lookup
- virtual stop handling
- depot or garage style cases
- sequence correction
- same-stop OD correction
- admin or verification APIs where still needed