#!/bin/sh

set -e -u

version=$(git describe --exact-match)
distdir="build/dist"
zipdir="${distdir}/wizzeye-${version}"

echo ":: Cleaning up"
rm -rf build app/build server/build

echo ":: Making dist directory"
mkdir -p "${zipdir}"
cp README.md "${zipdir}/README.md"
cp LICENSE "${zipdir}/LICENSE"

echo ":: Building server"
platforms="linux-amd64 linux-arm linux-arm64 darwin-amd64"
for p in $platforms; do
    export GOOS=$(echo $p | cut -d- -f1)
    export GOARCH=$(echo $p | cut -d- -f2)
    make -C server
    cp server/build/wizzeye-server-$GOOS-$GOARCH "${zipdir}"
done
cp -r server/webroot "${zipdir}/webroot"
cp server/config/config.toml "${zipdir}/config.toml"

echo ":: Building app"
./gradlew assembleRelease
if [ ! -e app/build/outputs/apk/release/app-release.apk ]; then
    echo "Release APK not created." >&2
    echo "Did you forget to set signing config in gradle.properties?" >&2
    exit 1
fi
cp app/build/outputs/apk/release/app-release.apk "${zipdir}/webroot/s/Wizzeye.apk"
cp app/build/outputs/apk/release/app-release.apk "${distdir}/Wizzeye-${version}.apk"

echo ":: Zipping package"
( cd "${distdir}" && zip -r "${zipdir##*/}.zip" ${zipdir##*/} )
