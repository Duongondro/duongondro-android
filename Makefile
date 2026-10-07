# Builds use Android Studio's bundled JDK when it is installed (the path has a
# space, so it is found with the shell rather than make's wildcard).
STUDIO_JBR := $(shell for d in "$$HOME/Applications/Android Studio.app" "/Applications/Android Studio.app"; do \
  j="$$d/Contents/jbr/Contents/Home"; [ -x "$$j/bin/java" ] && { echo "$$j"; break; }; done)
ifneq ($(STUDIO_JBR),)
export JAVA_HOME := $(STUDIO_JBR)
endif
GRADLE := ./gradlew --console=plain

.PHONY: test core-test device-test build install release apk

core-test:            ## Core unit and conformance tests on the JVM (no emulator)
	$(GRADLE) :core:test

test: core-test       ## Everything that runs without an emulator
	$(GRADLE) :app:testDebugUnitTest

build:                ## Debug APK (dirty tree allowed; shows -dirty in Settings)
	$(GRADLE) :app:assembleDebug

device-test:          ## Instrumented tests on the running emulator (storage)
	$(GRADLE) :app:connectedDebugAndroidTest

install: build        ## Install the debug APK on the running emulator
	$(GRADLE) :app:installDebug

release:              ## Release bundle; refuses a dirty tree
	$(GRADLE) :app:bundleRelease

apk:                  ## Signed release APK for sideloading; refuses a dirty tree (signing: app/build.gradle.kts)
	$(GRADLE) :app:assembleRelease
	@ls app/build/outputs/apk/release/*.apk
