# syntax=docker/dockerfile:1.7
FROM python:3.12-slim AS runtime

RUN groupadd --system --gid 10002 mlsvc \
 && useradd  --system --uid 10002 --gid mlsvc --home /app mlsvc \
 && apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY ml/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY --chown=mlsvc:mlsvc ml/app ./app

USER mlsvc
EXPOSE 8000
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1

HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=5 \
  CMD curl -fsS http://localhost:8000/health/ready || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
