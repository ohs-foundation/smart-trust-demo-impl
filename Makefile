.PHONY: build test stack-up stack-down stack-check

build:
	./gradlew assemble

test:
	./gradlew check

stack-up:
	./stack/init-env.sh
	docker compose -f stack/compose.yaml up -d --build --wait

stack-down:
	docker compose -f stack/compose.yaml down

stack-check:
	./stack/check.sh
