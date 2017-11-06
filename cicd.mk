# Copyright (c) 2016 Spotify AB.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

.PHONY: update-setup-py-version update-pom-version update-changelog
.PHONY: py-cicd-test py-lint python-test pyflakes pep8
.PHONY: cicd-test cicd-release cicd-integration-test
.PHONY: maybe-upload-release-true maybe-upload-release-false
.PHONY: build-debs
.PHONY: helios-deploy java-deploy

###### Configuration variables
DEBIAN_PACKAGE_NAME?=spotify-$(shell basename $(PWD))
DEBIAN_DIR?=debian
PYTHON_BIN?=python3
PYFLAKES_BIN?=pyflakes3
PEP8_BIN?=pep8
NOSETESTS_BIN?=nosetests
MAVEN_BIN?=mvn
PIP_BIN?=pip3

# If this is a PYPI we will try to upload via pypi rather than as a debian
# package.
UPLOAD_PYPI?=false

WITH_VIRTUAL_ENV?=true

# Comes from the surrounding environment and defaults to 0 if not set.
BUILD_NUMBER?=0

###### Internal variables, do not modify
VERSION:=$(shell git describe --abbrev=0 || echo "0.0.0")
DATE_STR:=$(shell date +%Y-%m-%d)
SHORT_REV_HASH:=$(shell git rev-list HEAD -1 --abbrev-commit)

DEB_VERSION:=$(VERSION)-$(BUILD_NUMBER)-$(DATE_STR)-$(SHORT_REV_HASH)

# If TMPDIR has not been defined, set it to a run of mktemp.
# This directory will NOT be cleaned up, unfortunately, so the
# user must handle their TMPDIR's themselves if they want that.
#
# It is done this way (the if statement, instead of using "?="
# because of Makefile semantics around assignment.  "=" is really
# executing a function on each usage of the variable.  "?=" is the
# same, but only executes the function and assigned it if the variable
# does not have an existing value. ":=" eagerly evaluates the right
# hand side of the assignment and applies it to the variable.  This
# is important when the function we are using (calling mktemp) will
# create a new value on every invocation.  Unfortuantely, there exists
# no "?:=" assignment operator in Makefiles, so this is the way
# around it.
ifeq ($(origin TMPDIR), undefined)
	export TMPDIR:=$(shell mktemp -d)
endif

###### Public interface targets
py-prepare-for-release: update-setup-py-version

debian-prepare-for-release: update-changelog

java-prepare-for-release: update-pom-version

py-lint: pyflakes pep8

python-test:
	$(NOSETESTS_BIN) -v -s

java-test:
	$(MAVEN_BIN) test

# If there is any work to be done before a release, repo
# authors *must* specify dependencies for this in their
# Makefile
prepare-for-release:

###### Internal Python targets
update-setup-py-version: setup.py
	sed -i "s/{VERSION}/$(VERSION)/" setup.py

py-setup-virtual-env:
	mkdir $(TMPDIR)/venv
	virtualenv -p $(PYTHON_BIN) $(TMPDIR)/venv
	bash -c "source $(TMPDIR)/venv/bin/activate && $(PIP_BIN) install $(PIP_INSTALL_OPTIONS) -r requirements.txt"
	bash -c "source $(TMPDIR)/venv/bin/activate && $(PIP_BIN) install $(PIP_INSTALL_OPTIONS) -r requirements-test.txt"

py-cicd-test: py-setup-virtual-env
	bash -c "source $(TMPDIR)/venv/bin/activate && $(MAKE) test"

py-cicd-integration-test: py-setup-virtual-env
	bash -c "source $(TMPDIR)/venv/bin/activate && $(MAKE) integration-test"

pep8:
	$(PEP8_BIN) --ignore=E501 $(shell find * -maxdepth 1 -type d | grep -v venv)

pyflakes:
	$(PYFLAKES_BIN) $(shell find * -maxdepth 1 -type d | grep -v venv)

###### Internal Java targets
update-pom-version:
	find . -name pom.xml -exec sed -i "s/0\.0\.0-SNAPSHOT/$(VERSION)/" {} \;

###### Internal Debian packaging targets
update-changelog:
	echo "$(DEBIAN_PACKAGE_NAME) ($(VERSION)) stable; urgency=low" > $(DEBIAN_DIR)/changelog
	echo >> $(DEBIAN_DIR)/changelog
	echo "    * Useless information" >> $(DEBIAN_DIR)/changelog
	echo " -- Malcolm Matalka <malcolm@spotify.com>  Tue, 10 Aug 2015 17:10:00 +0200" >> $(DEBIAN_DIR)/changelog

# Upload if run wants to upload
maybe-upload-release-true: build-release
ifeq ($(UPLOAD_PYPI), false)
	cd build && sp-upload-debs --release $(RELEASE) --distribution trusty
else
	sp-pypi-upload --fail-on-duplicate
endif

# Do nothing if don't want to upload
maybe-upload-release-false: build-release

# Requires that the package author has created a target called
# "prepare-for-release". If this is not a PYPI upload then build a deb,
# otherwise do nothing other than test and prepare the release, because the
# package will really be uploaded to the pypi server.
build-release: prepare-for-release cicd-test
ifeq ($(UPLOAD_PYPI), false)
	echo "Building Debian package with version: $(DEB_VERSION)"
	ln -s $(DEBIAN_DIR) debian
	sp-build-debs --version $(DEB_VERSION) --resolver apt --distribution trusty --release $(RELEASE)
endif

###### CICD repo internal API

# cicd-release, and cicd-test (below, in the if) are the interface that all
# repos are required to implement.
cicd-release: maybe-upload-release-$(UPLOAD_DEBIAN_PACKAGE)

# If the repository author has specified it is not a virtual env
# repo then just run the make test.
ifeq ($(WITH_VIRTUAL_ENV),true)
cicd-test: py-cicd-test
cicd-integration-test: py-cicd-integration-test
else
###### Blessed stack targets
cicd-test:
	$(MAKE) test

cicd-integration-test:
	$(MAKE) integration-test
endif


java-integration-test:
	$(MAVEN_BIN) clean verify -Pcoverage checkstyle:checkstyle findbugs:findbugs

java-deploy:
	$(MAVEN_BIN) clean deploy -Pcoverage checkstyle:checkstyle findbugs:findbugs

helios-deploy:
	.spotify/scripts/deploy.sh
