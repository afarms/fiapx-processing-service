MVNW := bash ./mvnw
ENV_FILE := $(CURDIR)/.env
.DEFAULT_GOAL := verify

.PHONY: harness-image harness-check harness-config harness-fixtures
harness-image:
	docker build --target aws-harness -t fiapx-processing-aws-harness:local .

harness-check:
	python -m unittest discover -s scripts/tests -p 'test_*.py'

harness-config:
	docker compose --env-file "$(HARNESS_RUN_DIRECTORY)/compose.env" -f compose.aws-harness.yml config --quiet

harness-fixtures:
	bash scripts/harness-fixtures.sh
.PHONY: verify install package image config-check up down run integration-bootstrap integration integration-media

verify:
	$(MVNW) -B -ntp clean verify

integration:
	bash scripts/test-postgres.sh

integration-media:
	bash -c 'mkdir -p target/media-reports'
	docker build --target media-test -t fiapx-processing-media-test:local .
	MSYS_NO_PATHCONV=1 docker run --rm --init --cpus=2 --memory=1g --mount type=volume,source=fiapx-processing-media-maven,target=/root/.m2 --mount "type=bind,source=$(CURDIR)/target/media-reports,target=/reports" fiapx-processing-media-test:local sh -c './mvnw -B -ntp -Pmedia-integration verify; result=$$?; cp -R target/failsafe-reports target/site/jacoco /reports/; exit $$result'

install:
	$(MVNW) -B -ntp clean install

package:
	$(MVNW) -B -ntp clean package

image:
	docker build -t fiapx-processing-service:local .

config-check:
	docker compose config --quiet

# Requires make up; stops and restores only this project's database.
integration-bootstrap:
	bash scripts/test-bootstrap.sh

up:
	docker compose up --build -d --wait

down:
	docker compose down

run:
	@bash -ec 'test -f "$(ENV_FILE)" || { echo "Configure .env antes de executar"; exit 1; }; set -a; . "$(ENV_FILE)"; set +a; exec $(MVNW) spring-boot:run'
