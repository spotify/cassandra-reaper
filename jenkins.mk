.PHONY: prepare-for-release pom-version changelog
.PHONY: jenkins-release
.PHONY: maybe-upload-release-true maybe-upload-release-false
.PHONY: build-debs

DEBIAN_PACKAGE_NAME?=spotify-$(shell basename $(PWD))
VERSION:=$(shell git describe || echo "0.0.0")

DATE_STR:=$(shell date +%Y-%m-%d)
SHORT_REV_HASH:=$(shell git rev-list HEAD -1 --abbrev-commit)
DEB_VERSION:=$(VERSION)-$(BUILD_NUMBER)-$(DATE_STR)-$(SHORT_REV_HASH)

prepare-for-release: pom-version changelog

pom-version:
	find . -name pom.xml -exec sed -i "s/0\.0\.0-SNAPSHOT/$(VERSION)/" {} \;

changelog:
	echo "$(DEBIAN_PACKAGE_NAME) ($(VERSION)) stable; urgency=low" > debian/changelog
	echo >> debian/changelog
	echo "    * Useless information" >> debian/changelog
	echo " -- Malcolm Matalka <malcolm@spotify.com>  Tue, 10 Aug 2015 17:10:00 +0200" >> debian/changelog

jenkins-release: maybe-upload-release-$(UPLOAD_DEBIAN_PACKAGE)

maybe-upload-release-true: build-debs
	cd build && sp-upload-debs --release $(RELEASE) --distribution trusty

maybe-upload-release-false: build-debs

build-debs: prepare-for-release jenkins-test
	echo "Building Debian package with version: $(DEB_VERSION)"
	sp-build-debs --version $(DEB_VERSION) --resolver apt --distribution trusty --release $(RELEASE)
