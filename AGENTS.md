# AGENTS.md

Operational guide for AI coding agents working in this repository.

## Project Overview

Performance test infrastructure for reproducing BigQuery Storage Write API
"Noisy Neighbor" throughput degradation with Dataflow. Dual-language (Python
and Java), dual-source (Pub/Sub and Kafka). Both SDKs use Apache Beam 2.70.0.

## Build / Lint / Test

All Python commands use `uv`. Do NOT use `pip` or `poetry`.

```bash
# Install / sync dependencies
uv sync

# Lint (ruff defaults, no custom config)
uv run ruff check .
uv run ruff format --check .

# Run all tests (26 tests)
uv run pytest

# Run a single test file
uv run pytest tests/test_raw_json.py

# Run a single test function
uv run pytest tests/test_raw_json.py::test_process_valid_message

# Run tests matching a keyword
uv run pytest -k "avro"
```

### Java (in `java/` directory)

```bash
# Compile
mvn compile

# Run tests (20 tests)
mvn test

# Run a single test class
mvn test -Dtest=SyntheticAvroGeneratorTest

# Build uber-JAR (skip tests)
mvn package -DskipTests
```

### Shell Scripts

Scripts live at the repo root (not `scripts/`).

```bash
# JSON pipeline (rounds 1-4)
./run_perf_test.sh setup              # create GCP resources
./run_perf_test.sh publish            # launch JSON publisher
./run_perf_test.sh job a              # launch Python consumer Job A
./run_perf_test.sh java-job a         # launch Java JSON consumer Job A

# Avro pipeline (round 5)
./run_perf_test.sh setup-avro         # create typed BQ tables + build all
./run_perf_test.sh publish-avro       # launch Avro publisher
./run_perf_test.sh java-avro-job a    # launch Java Avro consumer Job A

# Kafka pipeline (round 6)
./run_perf_test.sh setup-kafka        # create Managed Kafka cluster + topic
./run_perf_test.sh kafka-publish      # launch Kafka Avro publisher
./run_perf_test.sh kafka-avro-job a   # launch Kafka Avro consumer Job A

# Common
./run_perf_test.sh monitor            # print monitoring URLs
./cleanup_perf_test.sh                # tear down GCP resources
```

## Project Structure

```
.
├── run_perf_test.sh                  # Test orchestrator (Pub/Sub + Kafka)
├── cleanup_perf_test.sh              # GCP resource teardown
├── dataflow_pubsub_to_bq/           # Python package (name must NOT change)
│   ├── pipeline_json.py             # Consumer pipeline (Pub/Sub -> BQ)
│   ├── pipeline_options.py          # Consumer options (--use_at_least_once)
│   ├── pipeline_publisher.py        # Synthetic message publisher
│   ├── pipeline_publisher_options.py
│   └── transforms/
│       ├── raw_json.py              # ParsePubSubMessageToRawJson DoFn
│       └── synthetic_messages.py    # Message generation (Avro schema inline)
├── tests/                           # pytest (no __init__.py, no conftest.py)
├── java/
│   ├── pom.xml
│   └── src/main/java/.../
│       ├── PubSubToBigQueryJson.java      # Pub/Sub JSON consumer
│       ├── PubSubToBigQueryAvro.java      # Pub/Sub Avro consumer
│       ├── KafkaToBigQueryAvro.java       # Kafka Avro consumer
│       ├── KafkaAvroPublisher.java        # Kafka Avro publisher
│       ├── schemas/                       # BQ schema definitions
│       └── transforms/                    # DoFn implementations
│   └── src/main/resources/
│       └── taxi_ride_v1.avsc              # Avro schema (Java source of truth)
└── docs/                            # Strategy doc + 6 round result docs
```

**Critical:** The package name `dataflow_pubsub_to_bq` must not be renamed.
`run_perf_test.sh` invokes it via `uv run python -m dataflow_pubsub_to_bq.pipeline_json`.

## Python Code Style

Python 3.12. Use modern syntax exclusively.

**Type hints:** Use builtin generics and union syntax. Only import `Any` from `typing`.

```python
# Good
def run(argv: list[str] | None = None) -> None: ...

# Bad -- legacy typing imports
from typing import List, Optional, Dict
```

**Imports:** Three groups separated by blank lines: stdlib, third-party, local.
Alphabetically sorted within each group.

**Docstrings:** Google-style with `Args:`, `Returns:`, `Raises:` sections.
Required on all public functions, classes, and modules.

**Naming:**

| Element | Convention | Example |
|---------|-----------|---------|
| Functions, variables | `snake_case` | `generate_message`, `pool_size` |
| Classes | `PascalCase` | `ParsePubSubMessageToRawJson` |
| Constants (module-level) | `_UPPER_SNAKE_CASE` | `_BQ_METADATA_OVERHEAD_BYTES` |

**Logging:** Use `%s` lazy formatting. Do NOT use f-strings in log calls.

```python
logging.info("Processing %d messages for topic %s", count, topic)
```

**Error handling:**

- Validation errors: raise `ValueError` with a descriptive message.
- DoFn processing errors: try/except with DLQ routing via `TaggedOutput("dlq", ...)`.
  Capture full stack trace with `traceback.format_exc()`.

**Testing:**

- Framework: `pytest` with standalone functions (no classes, no unittest).
- File naming: `test_<module>.py` in `tests/`.
- Function naming: `test_<function>_<scenario>`.
- Every test function has a one-line docstring.
- Assertions: bare `assert` statements. Use `pytest.raises(ValueError, match="...")`.
- Beam tests: use `TestPipeline`, `assert_that`, `equal_to` from `apache_beam.testing`.
- No mocks, no fixtures. Prefer in-memory test data.

## Java Code Style

- Java 17, Google Java Style (2-space indent).
- Beam 2.70.0 (must match Python version).
- Maven build with Shade Plugin for uber-JAR.
- JUnit 4 with `TestPipeline` rule (`@Rule`).
- Test naming: `test<Scenario>` camelCase.
- Logging: SLF4J with `{}` placeholder pattern.
- Pipeline options: interfaces extending `PipelineOptions` with `@Description` annotations.
- Error handling: try/catch with DLQ routing via `TupleTag`.

## Shell Script Style

- Shebang: `#!/bin/bash` with `set -e`.
- 4-space indentation.
- Quote all variables: `"${var}"`.
- Use `[[ ]]` for tests, `$(command)` for substitution, `(( ))` for arithmetic.
- Use `local` for function variables.
- Function naming: `do_<action>` prefix convention.
- macOS compatibility: use `tr '[:lower:]' '[:upper:]'` instead of `${var^^}`.

## Key Constraints

- **Package name:** `dataflow_pubsub_to_bq` -- do not rename (breaks shell script imports).
- **Beam version:** Pinned at 2.70.0 for both Python and Java. Must stay in sync.
- **Avro schema:** Embedded inline in Python (`synthetic_messages.py`), loaded from
  classpath in Java (`java/src/main/resources/taxi_ride_v1.avsc`). Both must stay in sync.
- **Scripts at repo root:** `run_perf_test.sh` and `cleanup_perf_test.sh` are at root, not in `scripts/`.
- **Dependencies:** Managed via `uv` and `pyproject.toml`. No `requirements.txt`.
- **No CI/CD:** Tests and lint are run manually.
