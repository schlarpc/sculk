# @babel/standalone, for sculk's script transpiler (core's Transpiler). The
# version is pinned in package.json and package-lock.json here, and
# importNpmLock fetches it checked against the integrity hash the lock
# records. Gradle runs `nix build .#babel` and packs the result (babel.min.js
# and its LICENSE) into each mod jar, and core's test resources, under
# sculk/babel/.
#
# To update, in this directory, pick a release at least two weeks old:
#   npm install --package-lock-only @babel/standalone@<version>
{
  lib,
  stdenvNoCC,
  importNpmLock,
  nodejs,
}:
let
  package = lib.importJSON ./package.json;
  packageLock = lib.importJSON ./package-lock.json;
  version = packageLock.packages."node_modules/@babel/standalone".version;
  nodeModules = importNpmLock.buildNodeModules {
    inherit package packageLock nodejs;
  };
in
stdenvNoCC.mkDerivation {
  pname = "babel-standalone";
  inherit version;
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out
    cp ${nodeModules}/node_modules/@babel/standalone/babel.min.js $out/
    cp ${nodeModules}/node_modules/@babel/standalone/LICENSE $out/LICENSE
    echo "@babel/standalone ${version} (MIT), from package-lock.json" > $out/VERSION
  '';
}
