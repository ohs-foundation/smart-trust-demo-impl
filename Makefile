.PHONY: build test ig-check stack-up stack-down stack-reset stack-check

build:
	./gradlew assemble

test:
	./gradlew check

ig-check:
	./ig/build.sh --check

stack-up:
	./stack/init-env.sh
	./stack/init-tls.sh
	./gradlew :services:vhl:installDist
	docker compose -f stack/compose.yaml up -d --build --wait

stack-down:
	docker compose -f stack/compose.yaml down

# Also the way to apply a realm change: Keycloak imports the realm only into an empty database.
stack-reset:
	docker compose -f stack/compose.yaml down -v
	rm -f stack/.env

stack-check:
	./stack/check.sh
