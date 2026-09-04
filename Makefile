.PHONY: help all up down down-hard logs ps smoke generate build test test-db \
        test-db-down it train evaluate ablate adversarial hard-netting benchmark llm-proof demo serve clean images psql engine-classes
SHELL := /bin/bash
PY    ?= python
MVN   ?= ./mvnw
MERCHANT ?= data/merchant_a
COMPOSE ?= docker compose
SERVE_PORT ?= 8733

help:
	@echo "INFRA"
	@echo "  make up          docker compose up: postgres, migrate, seed, api, ml"
	@echo "  make down        stop the stack (keeps the volume)"
	@echo "  make logs        follow api logs"
	@echo "  make psql        psql shell into the running database"
	@echo "  make smoke       end-to-end assertions against the running stack"
	@echo ""
	@echo "BUILD AND TEST"
	@echo "  make build       compile both modules"
	@echo "  make test        unit tests (no docker, no database)"
	@echo "  make it          integration tests against real Postgres 16"
	@echo "  make test-db     start the local test database only"
	@echo ""
	@echo "PIPELINE"
	@echo "  make generate    synthesise merchants + ground truth, verify integrity"
	@echo "  make train       fit the pair scorer, export model.json"
	@echo "  make evaluate    metrics, ablation, calibration, held-out test"
	@echo "  make demo        scripted run with a live counter"
	@echo "  make adversarial ML ablation on a graph where amount is uninformative"
	@echo "  make hard-netting  partition cases the ordered-block hypothesis cannot solve"
	@echo "  make benchmark   ONE combined report across every dataset"
	@echo "  make llm-proof   real LLM investigation of a real exception (needs ollama)"
	@echo "  make serve       the UI on the standalone engine, no docker (port $(SERVE_PORT))"
	@echo ""
	@echo "  make all         generate -> build -> train -> evaluate"

# ----------------------------------------------------------------- infra
up:
	$(COMPOSE) up -d --build
	@echo "waiting for the API to report ready..."
	@for i in $$(seq 1 60); do \
	  if curl -fsS http://localhost:$${API_PORT:-8080}/actuator/health/readiness >/dev/null 2>&1; then \
	    echo "api ready"; break; fi; sleep 2; done
	@echo "API   http://localhost:$${API_PORT:-8080}/docs"
	@echo "ML    http://localhost:$${ML_PORT:-8000}/docs"

down:
	$(COMPOSE) down

# Removes the database volume too. Named explicitly so it cannot happen by accident.
down-hard:
	$(COMPOSE) down -v

smoke:
	@bash ops/smoke.sh

logs:
	$(COMPOSE) logs -f api

ps:
	$(COMPOSE) ps

psql:
	$(COMPOSE) exec postgres psql -U $${POSTGRES_USER:-settleiq} -d $${POSTGRES_DB:-settleiq}

images:
	$(COMPOSE) build

# ------------------------------------------------------------ build/test
build:
	$(MVN) -B -DskipTests install

test:
	$(MVN) -B -pl engine test

test-db:
	$(COMPOSE) -f docker-compose.test.yml up -d
	@for i in $$(seq 1 40); do \
	  if [ "$$($(COMPOSE) -f docker-compose.test.yml ps -q postgres-test | xargs -r docker inspect -f '{{.State.Health.Status}}')" = "healthy" ]; then \
	    echo "test database ready on 55432"; break; fi; sleep 1; done

test-db-down:
	$(COMPOSE) -f docker-compose.test.yml down

# Integration tests need a real database. TEST_DB_URL points at the one
# `make test-db` starts; without it the tests fall back to Testcontainers.
it: test-db
	TEST_DB_URL=jdbc:postgresql://localhost:55432/settleiq \
	TEST_DB_USER=settleiq TEST_DB_PASSWORD=settleiq \
	$(MVN) -B verify

# --------------------------------------------------------------- pipeline
generate:
	$(PY) -m datagen --out data
	$(PY) -m datagen --profile train_t1 --out data
	$(PY) -m datagen --profile train_t2 --out data
	$(PY) -m datagen --profile train_t3 --out data
	$(PY) verify_data.py data/merchant_a data/merchant_b_ood

# Classes for the standalone CLI path, compiled with plain javac to keep proving
# the engine needs nothing but a JDK.
engine-classes:
	@mkdir -p engine/out
	javac -d engine/out $$(find engine/src/main/java -name '*.java')

train: engine-classes
	@for m in train_t1 train_t2 train_t3 merchant_a; do \
	  java -cp engine/out com.settleiq.Main --merchant data/$$m --preset full \
	    --out reports/$$m --audit reports/tmp_$$m.jsonl \
	    --dump-candidates reports/$$m/candidates.csv >/dev/null; done
	$(PY) mlservice/train.py

evaluate: engine-classes
	@rm -f reports/audit_ledger.jsonl
	java -cp engine/out com.settleiq.Main --merchant $(MERCHANT) --preset full --out reports
	$(PY) evaluator/evaluate.py $(MERCHANT) full reports/full
	$(PY) evaluator/ablate.py $(MERCHANT)
	$(PY) evaluator/curves.py $(MERCHANT) reports/full
	@echo
	@echo "== out-of-distribution merchant =="
	@rm -f reports/audit_b.jsonl
	java -cp engine/out com.settleiq.Main --merchant data/merchant_b_ood --preset full \
	  --out reports/ood --audit reports/audit_b.jsonl >/dev/null
	$(PY) evaluator/evaluate.py data/merchant_b_ood full reports/ood/full

ablate: engine-classes
	$(PY) evaluator/ablate.py $(MERCHANT)

# ---------------------------------------------------- adversarial benchmarks
# Fixtures that make the hard paths reachable. Kept separate from the baseline
# dataset, which is never modified.
adversarial: engine-classes
	$(PY) datagen/adversarial.py data/adversarial
	@mkdir -p reports/adversarial
	java -cp engine/out com.settleiq.Main --merchant data/adversarial --preset full 	  --out reports/adversarial --audit reports/adversarial/audit.jsonl 	  --dump-candidates reports/adversarial/candidates.csv
	$(PY) evaluator/ml_ablation.py reports/adversarial/candidates.csv 	  data/adversarial/ground_truth/ground_truth_bank_links.csv 	  reports/metrics/ml_ablation.json

# One command, one report, every dataset. Engine runs use the deterministic
# planner so the numbers are reproducible; the LLM is evidenced separately.
benchmark: engine-classes
	$(PY) evaluator/combined.py

# Proof that a real model investigated a real exception. Needs `ollama serve`.
llm-proof: engine-classes
	java -cp engine/out com.settleiq.LlmProof --merchant data/merchant_a --type fee_variance

hard-netting: engine-classes
	$(PY) datagen/hard_netting.py data/hard_netting
	java -cp engine/out com.settleiq.Main --merchant data/hard_netting --preset full 	  --out reports/hard_netting --audit reports/hard_netting/audit.jsonl

demo: engine-classes
	$(PY) demo.py

# The same UI as the compose stack, served by the engine's own HTTP server over
# the same /api/v1 contract. No docker, no database.
serve: engine-classes
	java -cp engine/out com.settleiq.Main --merchant $(MERCHANT) --preset full \
	  --out reports --serve $(SERVE_PORT)

all: generate build train evaluate

clean:
	$(MVN) -B clean || true
	rm -rf engine/out reports data
