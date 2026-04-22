# OD Correction Pipeline
## 1. Overview

This project implements a reference-based OD (Origin-Destination) data correction pipeline for bus transit data.

The pipeline processes raw OD data and enriches it using a validated reference dataset to produce a curated, analysis-ready dataset.

## 2. Pipeline Architecture
Raw CSV (External Source)
        ↓
Python Pipeline (This Project)
        ↓
Curated CSV
        ↓
DB Bulk Insert
        ↓
Spring Correction Layer
        ↓
Serving (API / Analytics)

## 3. Responsibility Separation
### Python (This Pipeline)

Handles pre-load data processing:

raw/reference data loading
data normalization
route ID mapping
standard code mapping
fallback-based missing value filling
output validation
curated CSV generation

### Spring (Post-load Correction)

Handles post-load data correction in DB:

virtual stop handling (00000 ARS)
sequence correction
same-stop OD correction
deduplication & aggregation
anomaly handling

### Airflow (Orchestration)

Handles execution flow:

pipeline scheduling
task orchestration
retry & failure handling

## 4. Input / Output Contract
Input
Raw OD CSV
external source (manual download)
contains ARS, stop name, sequence, passenger count
Reference CSV
validated OD dataset (e.g. od_reference_251014.csv)
contains:
converted route ID
standard stop codes
validated mappings
Output
Curated CSV
standardized format
missing values filled (as much as possible)
ready for DB ingestion

## 5. Processing Steps

### 5-1. Normalization
date → yymmdd
ARS → 5-digit string
standard code → 9-digit string
sequence → integer
route name → string

### 5-2. Route ID Mapping
노선명 → 전환_노선ID
mapping based on reference dataset

### 5-3. Standard Code Mapping
mapping based on (전환_노선ID + 순번)
primary mapping step

### 5-4. Fallback Filling

Executed sequentially:

#### 5-4(1) Same ARS Fill
- Fill missing standard codes using identical ARS values  
- Highest-confidence fallback method  

#### 5-4(2) Stop Name Fill
- Fill missing standard codes using identical stop names  
- Lower confidence than ARS-based matching  

#### 5-4(3) Cross Reference Fill
- Fill missing values using boarding ↔ alighting cross-reference  
- Final fallback step  

---

## 6. Scope Boundary

### Included (Python)

- data normalization  
- reference-based mapping  
- fallback filling  
- CSV output generation  

---

### Excluded (Handled by Spring)

- virtual stop handling (`00000 ARS`)  
- sequence correction  
- same-stop OD correction  
- deduplication & aggregation  
- DB-level correction logic  

---

## 7. Design Principles

### 7-1. Separation of Concerns
- Python: data preparation  
- Spring: data correction  
- Airflow: orchestration  

---

### 7-2. Reference-driven Architecture
- All corrections are based on the reference dataset  

---

### 7-3. Deterministic Processing
- Same input → same output  

---

### 7-4. Idempotency
- Re-running the pipeline produces the same result  

---

## 8. Execution

```Bash
python od_correction_pipeline.py \
  --raw data/raw/xxx.csv \
  --reference data/reference/od_reference_251014.csv \
  --output data/curated/od_curated_xxx.csv \
  --delimiter "|" \
  --encoding "utf-8-sig"
```

## 9. Notes
Raw data ingestion is manual (no API available)
The pipeline is designed for batch processing
Post-load correction is mandatory (Spring layer)

## 10. Future Improvements
Airflow DAG integration
automated raw file detection
metrics & monitoring (data quality tracking)
partial reprocessing support