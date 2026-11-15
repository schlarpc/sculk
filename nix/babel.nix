# @babel/standalone, for sculk's script transpiler (core's Transpiler): the
# npm release tarball, checked against npm's own integrity hash, unpacked to
# babel.min.js and its LICENSE. Gradle runs `nix build .#babel` and packs the
# result into each mod jar (and core's test resources) under sculk/babel/.
#
# To update: pick a release at least two weeks old, then take its tarball and
# integrity from `curl -s https://registry.npmjs.org/@babel/standalone/<version>`
# (dist.tarball, dist.integrity).
{ stdenvNoCC, fetchurl }:
stdenvNoCC.mkDerivation rec {
  pname = "babel-standalone";
  version = "7.29.12";
  src = fetchurl {
    url = "https://registry.npmjs.org/@babel/standalone/-/standalone-${version}.tgz";
    hash = "sha512-+ZbUA81jL9HXWVdysgup3TAgnE8dayhAlzRTOlb2GCMB2+4ohLVHAtvG7nVbLwqBlgM2hXesYlB34RvVMk3jBA==";
  };
  sourceRoot = "package";
  installPhase = ''
    mkdir -p $out
    cp babel.min.js $out/
    cp LICENSE $out/LICENSE
    echo "@babel/standalone ${version} (MIT), from ${src.url}" > $out/VERSION
  '';
}
