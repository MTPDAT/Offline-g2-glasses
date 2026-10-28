# One entry point for the G2 glasses app: models, build, tests, phone, Docker.
#
#   make help        list the targets
#   make up          start everything: fetch models, build, test, install on the phone,
#                    push the Whisper model, launch the app and follow its logs
#
# Works from WSL (with the repo on C:, it drives the Windows Android Studio toolchain, like
# scripts/deploy_phone.ps1), Linux and macOS (./gradlew and adb on PATH), Docker and CI.
# Force one with TOOLCHAIN=windows or TOOLCHAIN=native. See docs/DEVELOPMENT.md.

SHELL := /bin/bash
.DEFAULT_GOAL := help

PROJECT       := G2glasses
PACKAGE       := com.example.g2glasses
APK           := $(PROJECT)/app/build/outputs/apk/debug/app-debug.apk
PYTHON        ?= python3
WHISPER_MODEL ?= base
PARAKEET      ?= 0
SERIAL        ?=
METRICS       ?= $(lastword $(sort $(wildcard metrics_*.txt)))
IMAGE         ?= g2glasses-build
LOG_TAGS      := Metrics:V TranslationPipeline:V CaptionSink:V G2Gateway:V WhisperJni:V AndroidRuntime:E

# ------------------------------------------------------------------- toolchain
IS_WSL     := $(shell grep -qi microsoft /proc/version 2>/dev/null && echo 1)
ON_WINDOWS := $(if $(filter /mnt/%,$(CURDIR)),1)
TOOLCHAIN  ?= $(if $(and $(IS_WSL),$(ON_WINDOWS)),windows,native)

ifeq ($(TOOLCHAIN),windows)
  WIN_LOCALAPPDATA := $(shell cmd.exe /c "echo %LOCALAPPDATA%" 2>/dev/null | tr -d '\r')
  WIN_SDK       ?= $(WIN_LOCALAPPDATA)\Android\Sdk
  WIN_JAVA_HOME ?= C:\Program Files\Android\Android Studio\jbr
  # Gradle under WSL interop fails with "Unable to establish loopback connection" unless its
  # unix-domain sockets live in a plain Windows folder.
  WIN_JTMP      ?= C:\dev\.jtmp
  ADB           ?= $(shell wslpath -u '$(WIN_SDK)')/platform-tools/adb.exe
  GRADLE = mkdir -p "$$(wslpath -u '$(WIN_JTMP)')" && cd $(PROJECT) && \
    JAVA_HOME='$(WIN_JAVA_HOME)' ANDROID_HOME='$(WIN_SDK)' \
    JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=$(WIN_JTMP)' \
    WSLENV="$${WSLENV:+$$WSLENV:}JAVA_HOME/w:ANDROID_HOME/w:JAVA_TOOL_OPTIONS/w" \
    cmd.exe /c .\\gradlew.bat
  PS = powershell.exe -NoProfile -ExecutionPolicy Bypass -File
  FETCH_MODELS  = $(PS) scripts\\fetch_models.ps1
  PUSH_WHISPER  = $(PS) scripts\\fetch_whisper.ps1 -Model $(WHISPER_MODEL) $(if $(SERIAL),-Serial $(SERIAL))
  PUSH_PARAKEET = $(PS) scripts\\fetch_parakeet.ps1 $(if $(SERIAL),-Serial $(SERIAL))
else
  ADB    ?= adb
  GRADLE  = cd $(PROJECT) && sh ./gradlew
  FETCH_MODELS  = bash scripts/fetch_models.sh
  PUSH_WHISPER  = ADB="$(ADB)" bash scripts/fetch_whisper.sh --model $(WHISPER_MODEL) $(if $(SERIAL),--serial $(SERIAL))
  PUSH_PARAKEET = bash scripts/fetch_parakeet.sh $(if $(SERIAL),--serial $(SERIAL))
endif
ADB_S := "$(ADB)" $(if $(SERIAL),-s $(SERIAL))

# Docker: from WSL the CLI may be Docker Desktop's docker.exe, which wants Windows paths.
DOCKER     ?= $(if $(shell command -v docker 2>/dev/null),docker,docker.exe)
HOST_PATH   = $(if $(filter docker.exe,$(DOCKER)),$(shell wslpath -w '$(1)'),$(1))

.PHONY: help doctor up setup build test unit-test script-test lint install launch push-whisper \
        push-parakeet logs metrics perf docker-image docker-build docker-shell clean

help: ## List the targets
	@printf 'Toolchain: %s   (override with TOOLCHAIN=windows|native)\n\n' "$(TOOLCHAIN)"
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-14s %s\n", $$1, $$2}'
	@printf '\nVariables: SERIAL=<adb serial>  WHISPER_MODEL=tiny|base|small|large-v3-turbo  PARAKEET=1  METRICS=<file>\n'

doctor: ## Show which JDK, Gradle, SDK and phone this machine would use
	@echo "Toolchain:  $(TOOLCHAIN)"
	@echo "adb:        $(ADB)"
	$(GRADLE) --version
	-$(ADB_S) devices -l

# ------------------------------------------------------------------- build and test
up: setup build test install push-whisper $(if $(filter 1,$(PARAKEET)),push-parakeet) launch logs ## Start everything (models, build, test, install, launch, logs)

setup: ## Fetch the Vosk model and the sherpa-onnx AAR (not in git)
	$(FETCH_MODELS)

build: ## Build the debug APK (includes the whisper.cpp NDK build)
	$(GRADLE) assembleDebug

test: unit-test script-test ## All tests that need no phone

unit-test: ## Kotlin unit tests (JVM)
	$(GRADLE) testDebugUnitTest

script-test: ## Python tests for scripts/
	$(PYTHON) -m unittest discover -s scripts/tests

lint: ## Android lint (report in G2glasses/app/build/reports/)
	$(GRADLE) lintDebug

# ------------------------------------------------------------------- phone
install: ## Install the debug APK on the phone
	@test -f $(APK) || { echo "No APK yet: run make build"; exit 1; }
	$(ADB_S) install -r $(APK)

launch: ## Start the app on the phone
	$(ADB_S) shell am start -n $(PACKAGE)/.MainActivity

push-whisper: ## Download the Whisper model (WHISPER_MODEL=base) and push it to the phone
	$(PUSH_WHISPER)

push-parakeet: ## Download the Parakeet model (~490 MB) and push it to the phone
	$(PUSH_PARAKEET)

logs: ## Follow the app's logs (captions, metrics, glasses, Whisper); Ctrl+C to stop
	$(ADB_S) logcat -c
	$(ADB_S) logcat -v time -s $(LOG_TAGS)

metrics: ## Save the phone's Metrics log, then score it and check the performance budgets
	$(eval OUT := metrics_$(shell date +%Y%m%d_%H%M%S).txt)
	$(ADB_S) logcat -d -s Metrics:V > $(OUT)
	$(PYTHON) scripts/score_metrics.py $(OUT)
	$(PYTHON) scripts/validate_perf.py $(OUT)

perf: ## Check a saved metrics file against test_audio/perf_budgets.json (METRICS=<file>, default: newest)
	@test -n "$(METRICS)" || { echo "No metrics_*.txt here; run make metrics or pass METRICS=<file>"; exit 1; }
	$(PYTHON) scripts/validate_perf.py $(METRICS)

# ------------------------------------------------------------------- Docker
docker-image: ## Build the build image (JDK 25, SDK 37, NDK 28, CMake 3.31)
	$(DOCKER) build -t $(IMAGE) docker

docker-build: docker-image ## Fetch models, build and test inside Docker; APK and reports in docker/out/
	@mkdir -p docker/out
	$(DOCKER) run --rm \
	  -v "$(call HOST_PATH,$(CURDIR))":/src:ro \
	  -v "$(call HOST_PATH,$(CURDIR)/docker/out)":/out \
	  -v g2glasses-gradle:/gradle -v g2glasses-models:/build/scripts/.cache \
	  $(IMAGE) bash /src/docker/run_in_container.sh setup build test

docker-shell: docker-image ## Shell in the build image with the repo at /src (read-only)
	$(DOCKER) run --rm -it -v "$(call HOST_PATH,$(CURDIR))":/src:ro -v g2glasses-gradle:/gradle $(IMAGE) bash

clean: ## Remove build outputs (Gradle and docker/out)
	$(GRADLE) clean
	rm -rf docker/out
