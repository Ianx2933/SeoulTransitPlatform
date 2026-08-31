"""Tests for model-independent prediction-service contracts and Flask HTTP behavior."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest


PREDICTION_ROOT = Path(__file__).resolve().parents[1] / "services" / "prediction-service"
if str(PREDICTION_ROOT) not in sys.path:
    # `prediction` is a unique package name; unlike the legacy pipeline
    # `common.py` modules this path cannot silently shadow sibling helpers.
    sys.path.insert(0, str(PREDICTION_ROOT))

from prediction.feature_builder import FeatureBuilder  # noqa: E402
from prediction.hourly_ratio import HourlyRatioProvider  # noqa: E402
from prediction.weight_service import WeightService  # noqa: E402


def load_app_module():
    pytest.importorskip("flask")
    spec = importlib.util.spec_from_file_location(
        "seoul_transit_prediction_app",
        PREDICTION_ROOT / "app.py",
    )
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def valid_payload() -> dict:
    return {
        "routeNo": "146",
        "arsNo": "7616",
        "dayOfWeek": 0,
        "isHoliday": 0,
        "month": 8,
        "prevWeek": 120.0,
        "prevMonth": 100.0,
    }


def test_feature_builder_rejects_missing_required_fields():
    builder = FeatureBuilder(
        {"146": 1},
        {"07616": 2},
        ["dayOfWeek", "routeCode", "stopCode"],
    )

    with pytest.raises(ValueError, match="Missing required fields"):
        builder.build({"routeNo": "146"})


def test_feature_builder_normalizes_ars_and_uses_saved_defaults():
    columns = [
        "dayOfWeek",
        "isWeekend",
        "dayGroup",
        "isHoliday",
        "month",
        "routeCode",
        "stopCode",
        "prevWeek",
        "prevMonth",
        "rolling7",
    ]
    builder = FeatureBuilder(
        {"146": 7},
        {"07616": 11},
        columns,
        {"rolling7": 88.5},
    )

    row = builder.build(valid_payload()).iloc[0]

    assert row["routeCode"] == 7
    assert row["stopCode"] == 11
    assert row["isWeekend"] == 0
    assert row["dayGroup"] == 0
    assert row["rolling7"] == pytest.approx(88.5)


def test_hourly_distribution_preserves_daily_boarding_and_alighting_totals():
    provider = HourlyRatioProvider(hourly_ratio_df=None)

    hourly = provider.distribute(1000.0, "146", "07616")

    assert len(hourly) == 24
    assert sum(row["boarding"] for row in hourly) == pytest.approx(1000.0, abs=0.15)
    assert sum(row["alighting"] for row in hourly) == pytest.approx(1000.0, abs=0.15)


def test_hourly_ratio_validation_rejects_invalid_distributions():
    with pytest.raises(ValueError, match="24 values"):
        HourlyRatioProvider._normalize_ratios([1.0] * 23)
    with pytest.raises(ValueError, match="negative"):
        HourlyRatioProvider._normalize_ratios([1.0] * 23 + [-1.0])
    with pytest.raises(ValueError, match="positive"):
        HourlyRatioProvider._normalize_ratios([0.0] * 24)


def test_weight_service_rejects_invalid_hour_and_negative_weight():
    service = WeightService()

    with pytest.raises(ValueError, match="hour must be between"):
        service.update({"dayType": "weekday", "hour": 24, "weight": 1.0})
    with pytest.raises(ValueError, match="non-negative"):
        service.update({"dayType": "weekday", "hour": 8, "weight": -0.1})


def test_flask_health_and_predict_contract_without_binary_model_artifacts():
    module = load_app_module()

    class StubPredictor:
        def predict(self, payload):
            if "routeNo" not in payload:
                raise ValueError("routeNo is required")
            return {"dailyPrediction": 42.0, "hourlyPrediction": []}

    app = module.create_app(
        predictor=StubPredictor(),
        weight_service=WeightService(),
    )
    client = app.test_client()

    health = client.get("/health")
    assert health.status_code == 200
    assert health.get_json() == {"status": "ok"}

    bad = client.post("/predict", json={})
    assert bad.status_code == 400
    assert bad.get_json() == {"error": "routeNo is required"}

    ok = client.post("/predict", json={"routeNo": "146"})
    assert ok.status_code == 200
    assert ok.get_json()["dailyPrediction"] == 42.0


def test_flask_weight_endpoint_exposes_not_implemented_state_instead_of_fake_success():
    module = load_app_module()

    class StubPredictor:
        def predict(self, payload):
            return {}

    app = module.create_app(
        predictor=StubPredictor(),
        weight_service=WeightService(),
    )
    client = app.test_client()

    response = client.put(
        "/weight/update",
        json={"dayType": "weekday", "hour": 8, "weight": 1.0},
    )

    assert response.status_code == 501
    assert "not implemented" in response.get_json()["error"]
