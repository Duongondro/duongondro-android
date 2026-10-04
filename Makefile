# Builds use Android Studio's bundled JDK when it is installed (the path has a
# space, so it is found with the shell rather than make's wildcard).
STUDIO_JBR := $(shell for d in "$$HOME/Applications/Android Studio.app" "/Applications/Android Studio.app"; do \
  j="$$d/Contents/jbr/Contents/Home"; [ -x "$$j/bin/java" ] && { echo "$$j"; break; }; done)
ifneq ($(STUDIO_JBR),)
export JAVA_HOME := $(STUDIO_JBR)
endif
GRADLE := ./gradlew --console=plain

.PHONY: test core-test build install release

core-test:            ## Core unit and conformance tests on the JVM (no emulator)
	$(GRADLE) :core:test

test: core-test       ## Everything that runs without an emulator
	$(GRADLE) :app:testDebugUnitTest

build:                ## Debug APK (dirty tree allowed; shows -dirty in Settings)
	$(GRADLE) :app:assembleDebug

install: build        ## Install the debug APK on the running emulator
	$(GRADLE) :app:installDebug

release:              ## Release bundle; refuses a dirty tree
	$(GRADLE) :app:bundleRelease
