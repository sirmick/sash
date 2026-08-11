# pane — web apps that behave like apps: one Activity, one jar, one fence.
#
# Reuses the toolchain and Cuttlefish setup built for the archived hush branch;
# see ../hush/docs/SALVAGE.md for what else carried over and why this exists.

SHELL := /usr/bin/env bash
TOOLCHAIN ?= $(HOME)/.local/wash-toolchain/android
ADB := $(TOOLCHAIN)/sdk/platform-tools/adb
GRADLE := JAVA_HOME=$(TOOLCHAIN)/jdk ANDROID_HOME=$(TOOLCHAIN)/sdk $(TOOLCHAIN)/gradle/bin/gradle -p android --console=plain
APK := android/app/build/outputs/apk/debug/app-debug.apk

.PHONY: help
help:
	@echo "pane targets:"
	@echo "  make apk        build the debug APK"
	@echo "  make install    build and install on the running device"
	@echo "  make run        install and open the launcher"
	@echo "  make logs       follow pane + Gecko logs"
	@echo "  make shot       screenshot to pane.png"
	@echo "  make cf-start   boot a Cuttlefish device (see ../hush/docs/ANDROID.md)"

.PHONY: apk
apk:
	@$(GRADLE) assembleDebug

.PHONY: install
install: apk
	@$(ADB) install -r $(APK)

.PHONY: run
run: install
	@$(ADB) shell am start -n com.pane/.LauncherActivity >/dev/null
	@echo "launcher started"

# One app, straight to its own task — the way a pinned shortcut starts it.
#   make open APP=gmail
.PHONY: open
open:
	@$(ADB) shell am start -a android.intent.action.VIEW -d "pane://app/$(APP)" \
	  -n com.pane/.AppActivity --es app "$(APP)" >/dev/null
	@echo "opened $(APP)"

.PHONY: logs
logs:
	@$(ADB) logcat -s pane:V GeckoRuntime:W Gecko:W

.PHONY: shot
shot:
	@$(ADB) shell screencap -p /sdcard/pane.png
	@$(ADB) pull /sdcard/pane.png ./pane.png >/dev/null
	@echo "wrote pane.png"

# ----- Cuttlefish -------------------------------------------------------
# Inherited wholesale from the hush branch, including the two things that stop
# it booting: EGL_PLATFORM=surfaceless on a headless host, and `cvd create`
# rather than `cvd start` for a device that does not exist yet.
CF_DIR ?= $(HOME)/.local/cuttlefish
CF_ENV = HOME=$(CF_DIR) EGL_PLATFORM=surfaceless
CF_CONFIG ?= phone

.PHONY: cf-start
cf-start:
	cd $(CF_DIR) && $(CF_ENV) cvd create --gpu_mode=gfxstream --config=$(CF_CONFIG)

.PHONY: cf-stop
cf-stop:
	-cd $(CF_DIR) && $(CF_ENV) cvd stop
