DEBIAN_PACKAGE_NAME:=cassandra-reaper

WITH_VIRTUAL_ENV=false

DEBIAN_DIR=src/packaging/debian

test: java-test

prepare-for-release: update-pom-version update-changelog

prepare-for-release: debian-prepare-for-release

include cicd.mk
