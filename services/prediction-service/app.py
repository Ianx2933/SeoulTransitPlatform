from flask import Flask, jsonify, request

from prediction.weight_service import WeightService


def create_app(predictor=None, weight_service=None) -> Flask:
    """Create the Flask application with injectable serving dependencies.

    Production uses the real model-backed Predictor. Tests can inject a small
    fake predictor so HTTP behavior is verified without committing binary model
    artifacts to the repository.
    """
    app = Flask(__name__)
    if predictor is None:
        # Import model-heavy dependencies only for the real runtime path.
        # HTTP-contract tests inject a stub and do not need binary artifacts.
        from prediction.predictor import Predictor

        active_predictor = Predictor()
    else:
        active_predictor = predictor

    active_weight_service = weight_service or WeightService()

    @app.get("/health")
    def health():
        """Health check endpoint."""
        return jsonify({"status": "ok"})

    @app.post("/predict")
    def predict():
        """Predict daily demand and distribute it into hourly values."""
        payload = request.get_json(silent=True) or {}
        try:
            return jsonify(active_predictor.predict(payload))
        except ValueError as error:
            return jsonify({"error": str(error)}), 400
        except Exception:
            app.logger.exception("Unexpected prediction service error")
            return jsonify({"error": "Unexpected prediction service error"}), 500

    @app.put("/weight/update")
    def update_weight():
        """Validate a weight update; persistence is intentionally not implemented."""
        payload = request.get_json(silent=True) or {}
        try:
            active_weight_service.update(payload)
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

    return app


if __name__ == "__main__":
    create_app().run(host="0.0.0.0", port=5000)
