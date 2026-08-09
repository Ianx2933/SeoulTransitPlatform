from flask import Flask, jsonify, request

from prediction.predictor import Predictor
from prediction.weight_service import WeightService

app = Flask(__name__)

# Load model and reference data once when the Flask server starts.
predictor = Predictor()
weight_service = WeightService()


@app.get("/health")
def health():
    """Health check endpoint."""
    return jsonify({"status": "ok"})


@app.post("/predict")
def predict():
    """
    Predict daily demand and distribute it into hourly boarding/alighting values.
    """
    payload = request.get_json(silent=True) or {}
    try:
        return jsonify(predictor.predict(payload))
    except ValueError as error:
        return jsonify({"error": str(error)}), 400
    except Exception:
        # Internal details go to the server log only — matching the Spring
        # GlobalExceptionHandler policy of never exposing 5xx internals.
        app.logger.exception("Unexpected prediction service error")
        return jsonify({"error": "Unexpected prediction service error"}), 500


@app.put("/weight/update")
def update_weight():
    """
    Validate a weight update request. Persistence is NOT implemented yet,
    so a valid payload gets 501 instead of a fake 204 success — callers must
    not believe a weight was stored when nothing was written anywhere.
    """
    payload = request.get_json(silent=True) or {}
    try:
        weight_service.update(payload)
        return (
            jsonify(
                {
                    "error": "Weight update validated but persistence is not implemented yet",
                }
            ),
            501,
        )
    except ValueError as error:
        return jsonify({"error": str(error)}), 400
    except Exception:
        app.logger.exception("Unexpected weight update error")
        return jsonify({"error": "Unexpected weight update error"}), 500


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
