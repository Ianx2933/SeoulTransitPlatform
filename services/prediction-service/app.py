from flask import Flask, jsonify, request

from prediction.predictor import Predictor
from prediction.weight_service import WeightService

app = Flask(__name__)

# EN: Load model and reference data once when the Flask server starts.
# KR: Flask 서버 시작 시 모델과 참조 데이터를 한 번만 로드한다.
predictor = Predictor()
weight_service = WeightService()


@app.get("/health")
def health():
    """EN: Health check endpoint. / KR: 헬스체크 엔드포인트."""
    return jsonify({"status": "ok"})


@app.post("/predict")
def predict():
    """
    EN: Predict daily demand and distribute it into hourly boarding/alighting values.
    KR: 일별 수요를 예측한 뒤 시간대별 승차/하차 값으로 분배한다.
    """
    payload = request.get_json(silent=True) or {}
    try:
        return jsonify(predictor.predict(payload))
    except ValueError as error:
        return jsonify({"error": str(error)}), 400
    except Exception as error:
        return jsonify({"error": "Unexpected prediction service error", "detail": str(error)}), 500


@app.put("/weight/update")
def update_weight():
    """
    EN: Update hourly weight configuration.
    KR: 시간대별 가중치 설정을 수정한다.
    """
    payload = request.get_json(silent=True) or {}
    try:
        weight_service.update(payload)
        return "", 204
    except ValueError as error:
        return jsonify({"error": str(error)}), 400
    except Exception as error:
        return jsonify({"error": "Unexpected weight update error", "detail": str(error)}), 500


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
