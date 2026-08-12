# Phase 6 Prediction - dayOfWeek / isHoliday Refactor

Change record for the prediction feature schema change.

This is a point-in-time record. For how the prediction system currently works,
see `docs/architecture/overview.md`.

## What changed

- `dayType` removed from the model feature set.
- `dayOfWeek` added: Monday = 0 ... Sunday = 6.
- `isHoliday` added: 0 or 1.
- Previous `.pkl` artifacts, where available, were moved to
  `services/prediction-service/legacy_model/`.
- Retraining is required before running the Flask prediction service; old
  artifacts do not match the new feature list.

The active feature list is declared in two places and must stay in sync:

```text
services/prediction-service/prediction/feature_builder.py
services/prediction-service/prediction/model_loader.py
```

```text
routeNo  arsNo  dayOfWeek  isHoliday  month  prevWeek  prevMonth
```

Note that `weight_service.py` still reads a `dayType` payload field. That is the
hourly weighting axis and is separate from the model feature set — it was not
part of this refactor.

## Retrain

The database URL is read from an environment variable so that no credential is
stored in the repository.

bash:

```bash
export PIPELINE_DB_URL='postgresql://postgres:PASSWORD@localhost:5432/Seoul_Transit'

pip install -r pipelines/model_training/requirements.txt
python pipelines/model_training/train_xgboost_dayofweek.py \
  --db-url "$PIPELINE_DB_URL" \
  --output services/prediction-service/model
```

PowerShell:

```powershell
$env:PIPELINE_DB_URL='postgresql://postgres:PASSWORD@localhost:5432/Seoul_Transit'

pip install -r pipelines/model_training/requirements.txt
python pipelines/model_training/train_xgboost_dayofweek.py `
  --db-url $env:PIPELINE_DB_URL `
  --output services/prediction-service/model
```

Both `--db-url` and `--output` are required arguments.

## Test the Flask service

```bash
cd services/prediction-service
pip install -r requirements.txt
python app.py
```

```bash
curl -X POST http://localhost:5000/predict \
  -H "Content-Type: application/json" \
  -d '{"routeNo":"9401","arsNo":"01267","dayOfWeek":0,"isHoliday":0,"month":4,"prevWeek":100,"prevMonth":120}'
```

PowerShell:

```powershell
curl.exe -X POST http://localhost:5000/predict `
  -H "Content-Type: application/json" `
  -d '{\"routeNo\":\"9401\",\"arsNo\":\"01267\",\"dayOfWeek\":0,\"isHoliday\":0,\"month\":4,\"prevWeek\":100,\"prevMonth\":120}'
```

## Test through Spring

```http
GET /api/prediction/hourly?routeNo=9401&arsNo=01267&date=20260411
```

Spring derives `dayOfWeek` and `isHoliday` from `date`, so the caller supplies a
date rather than the raw features. Holiday classification reads the
`holiday_config` table.
