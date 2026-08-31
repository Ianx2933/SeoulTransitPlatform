# Prediction service

Flask microservice for XGBoost-based daily demand inference and hourly
distribution.

## Model-artifact boundary

The trained binary artifacts are intentionally **not committed** to this
portfolio repository. Files ending in `.PLACEHOLDER.txt` document the artifact
names expected at runtime; they are not runnable models.

To run `/predict`, train/export the model into the service model directory:

```bash
python pipelines/model_training/train_xgboost_dayofweek.py \
  --db-url "postgresql://USER:PASSWORD@HOST:5432/Seoul_Transit" \
  --output services/prediction-service/model
```

The generated filenames match those expected by `prediction/model_loader.py`.

The repository still tests the deterministic serving logic that does not require
the private/binary model artifact (feature validation, hourly distribution, and
weight validation). The production container requires the real model artifacts.

## Responsibilities

- XGBoost inference
- API payload → model feature construction
- Hourly boarding/alighting distribution
- Validation of operational weight updates

The service is separated from the Spring API so inference can be scaled or
replaced independently.
