FROM python:3.11-slim
WORKDIR /app

# Container logs are only useful if they arrive while the job runs. Python
# buffers stdout when it is not a terminal, so a long job appears to produce
# no output at all until it exits.
ENV PYTHONUNBUFFERED=1

COPY pipelines/hourly_od_estimation/requirements_phase6_2.txt .
RUN pip install --no-cache-dir -r requirements_phase6_2.txt

COPY pipelines ./pipelines

# Relative imports in the module require package execution, so the entrypoint
# is "python -m" rather than a direct script path. Cloud Run Jobs appends
# --use-ym and friends through --args.
ENTRYPOINT ["python", "-m", "pipelines.hourly_od_estimation.load_hourly_boarding"]
