.PHONY: help test integration-test check clean format run run-live

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

test: ## Run unit tests
	./gradlew test

check: ## Run all tests and checks
	./gradlew check

clean: ## Clean build artifacts
	./gradlew clean

format: ## Fix code formatting
	./gradlew spotlessApply

run: ## Run with mock adapters
	./gradlew bootRun

run-live: ## Run against live price-alert stack
	./gradlew bootRun --args='--spring.profiles.active=price-alert'

integration-test: ## Run integration tests (real services)
	./gradlew integrationTest
