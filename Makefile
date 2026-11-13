.PHONY: build test

build:
	./gradlew assemble

test:
	./gradlew check
