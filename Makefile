MVNW := bash ./mvnw
ENV_FILE := $(CURDIR)/.env
.DEFAULT_GOAL := verify
.PHONY: verify install package image config-check up down run integration-bootstrap integration

verify:
	$(MVNW) -B -ntp clean verify

integration:
	bash scripts/test-postgres.sh

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
