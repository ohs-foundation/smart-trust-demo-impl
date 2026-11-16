.PHONY: build test ig-check hcert-js stack-up stack-down stack-reset stack-check qa

build:
	./gradlew assemble

test:
	./gradlew check

ig-check:
	./ig/build.sh --check

hcert-js:
	./stack/build-hcert-js.sh

stack-up: hcert-js
	./stack/init-env.sh
	./stack/init-tls.sh
	mkdir -p stack/taifafeki/secrets
	touch stack/taifafeki/secrets/medplum-client-id stack/taifafeki/secrets/clinic-viewer.key stack/taifafeki/secrets/clinic-viewer.crt
	chmod 600 stack/taifafeki/secrets/clinic-viewer.key
	./gradlew :services:vhl:installDist :services:issuer:installDist
	docker compose -f stack/compose.yaml up -d --build --wait
	docker compose -f stack/compose.yaml restart proxy

stack-down:
	docker compose -f stack/compose.yaml down

# Also the way to apply a realm change: Keycloak imports the realm only into an empty database.
stack-reset:
	docker compose -f stack/compose.yaml down -v
	rm -f stack/.env

stack-check:
	./stack/check.sh

qa:
	./tools/qa/qa.sh
