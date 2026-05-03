# Phase 6 Prediction - dayOfWeek/isHoliday Refactor

## Changed
- `dayType` removed.
- `dayOfWeek` added: Monday=0 ... Sunday=6.
- `isHoliday` added: 0/1.
- Existing old pkl files are stored in `legacy_model/` if available.
- Retrain before running Flask prediction-service.

## Retrain
```bash
pip install -r pipelines/model_training/requirements.txt
python pipelines/model_training/train_xgboost_dayofweek.py ^
  --db-url "postgresql://postgres:330218@localhost:5432/Seoul_Transit" ^
  --output services/prediction-service/model
```

## Test Flask
```bash
cd services/prediction-service
pip install -r requirements.txt
python app.py
```

```bash
curl -X POST http://localhost:5000/predict ^
  -H "Content-Type: application/json" ^
  -d "{"routeNo":"9401","arsNo":"01267","dayOfWeek":0,"isHoliday":0,"month":4,"prevWeek":100,"prevMonth":120}"
```

## Test Spring
```http
GET /api/prediction/hourly?routeNo=9401&arsNo=01267&date=20260411
```
