FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
COPY prediction ./prediction
COPY model ./model

# Documentation only; Cloud Run assigns the real port through $PORT.
EXPOSE 5000
ENV PORT=5000

# Shell form so $PORT expands; exec so gunicorn becomes PID 1 and receives
# SIGTERM directly, which lets Cloud Run shut the instance down cleanly.
# --workers 1 --threads 8 suits a small-memory instance; --timeout 0 lets
# Cloud Run's own request timeout govern instead of gunicorn killing workers.
CMD exec gunicorn --bind 0.0.0.0:$PORT --workers 1 --threads 8 --timeout 0 "app:create_app()"
