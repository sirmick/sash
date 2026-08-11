# latch — the vault: a fork of Passchain (codeberg.org/s1m/hw-fido2-provider,
# Apache-2.0) that keeps its hardware FIDO2 and adds passwords as one encrypted
# file per credential, in a folder Syncthing carries. See ../pane/docs/VAULT.md.
#
# Vendored as a squashed subtree, so upstream is still tracked:
#   git subtree pull --prefix=latch --squash \
#     https://codeberg.org/s1m/hw-fido2-provider.git main

SHELL := /usr/bin/env bash
TOOLCHAIN ?= $(HOME)/.local/wash-toolchain/android
ADB := $(TOOLCHAIN)/sdk/platform-tools/adb
GRADLE := JAVA_HOME=$(TOOLCHAIN)/jdk ANDROID_HOME=$(TOOLCHAIN)/sdk $(TOOLCHAIN)/gradle/bin/gradle --console=plain
APK := app/build/outputs/apk/debug/app-debug.apk

# The debug build takes an .debug applicationIdSuffix, so it coexists with a
# real Passchain install rather than replacing it.
PKG := s1m.hwfido2provider.debug
SVC := $(PKG)/s1m.hwfido2provider.ProviderService

.PHONY: help
help:
	@echo "latch targets:"
	@echo "  make apk       build the debug APK"
	@echo "  make install   build and install on the running device"
	@echo "  make run       install, enable the provider, open the app"
	@echo "  make enable    register as the system credential provider"
	@echo "  make logs      follow provider + credential-manager logs"
	@echo "  make shot      screenshot to latch.png"

# Syncthing ships inside the APK and is executed, not loaded. Built here rather
# than committed: two static binaries are 50MB, and reproducing them is one
# command. GOOS=linux with CGO off produces a binary Android can run;
# `noassets` drops the web GUI, which we never show -- latch drives the REST API.
SYNCTHING_REPO ?= https://github.com/syncthing/syncthing.git
# 1.x, not 2.x: Syncthing 2 replaced LevelDB with pure-Go SQLite, whose libc
# shim issues a raw lstat that Android seccomp rejects. See docs/VAULT.md.
SYNCTHING_TAG ?= v1.30.0
SYNCTHING_SRC ?= $(CURDIR)/.syncthing-src
JNI := app/src/main/jniLibs

.PHONY: sync-binary
sync-binary: $(JNI)/x86_64/libsyncthing.so $(JNI)/arm64-v8a/libsyncthing.so

$(SYNCTHING_SRC):
	@git clone --depth 1 --branch $(SYNCTHING_TAG) $(SYNCTHING_REPO) $(SYNCTHING_SRC)

$(JNI)/x86_64/libsyncthing.so: | $(SYNCTHING_SRC)
	@mkdir -p $(@D)
	@cd $(SYNCTHING_SRC) && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
	  go build -tags noassets -trimpath -ldflags="-s -w" -o $@ ./cmd/syncthing

$(JNI)/arm64-v8a/libsyncthing.so: | $(SYNCTHING_SRC)
	@mkdir -p $(@D)
	@cd $(SYNCTHING_SRC) && CGO_ENABLED=0 GOOS=linux GOARCH=arm64 \
	  go build -tags noassets -trimpath -ldflags="-s -w" -o $@ ./cmd/syncthing

.PHONY: apk
apk: sync-binary
	@$(GRADLE) assembleDebug

# The vault's crypto is pure Java (Bouncy Castle Argon2id, Tink AES-GCM), so
# these run on the JVM in seconds rather than needing the device.
.PHONY: test
test:
	@$(GRADLE) :app:testDebugUnitTest

.PHONY: install
install: apk
	@$(ADB) install -r $(APK)

# Android normally routes this through Settings > Passwords & accounts, which
# needs a human. Setting it directly is what makes the loop scriptable.
.PHONY: enable
enable:
	@$(ADB) shell settings put secure credential_service "$(SVC)"
	@$(ADB) shell settings put secure credential_service_primary "$(SVC)"
	@echo "provider enabled: $$($(ADB) shell settings get secure credential_service)"

.PHONY: run
run: install enable
	@$(ADB) shell am force-stop $(PKG)
	@$(ADB) shell am start -n $(PKG)/s1m.hwfido2provider.MainActivity >/dev/null
	@echo "latch started"

.PHONY: logs
logs:
	@$(ADB) logcat -s ProviderService:V Utils:V latch:V CredentialManager:I AndroidRuntime:E

.PHONY: shot
shot:
	@$(ADB) shell screencap -p /sdcard/latch.png
	@$(ADB) pull /sdcard/latch.png ./latch.png >/dev/null
	@echo "wrote latch.png"
