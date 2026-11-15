# The control server's web UI: TypeScript type-checked by tsc and bundled by
# esbuild, plus Monocraft (OFL-1.1) subset to woff2. Runtime libraries
# (CodeMirror, for the script editor) come from package-lock.json through
# importNpmLock: each package is fetched and checked against the integrity
# hash the lock records, so there is no aggregate hash to keep up to date.
# tsc and esbuild come from nixpkgs. After changing package.json, run
# `npm install --package-lock-only` and commit the lock.
#
#   nix build .#web
{
  lib,
  stdenvNoCC,
  importNpmLock,
  nodejs,
  esbuild,
  typescript,
  python3,
  monocraft,
  fetchurl,
}:
let
  fonttools = python3.withPackages (ps: [
    ps.fonttools
    ps.brotli
  ]);
  monocraftLicense = fetchurl {
    url = "https://raw.githubusercontent.com/IdreesInc/Monocraft/v4.2.1/LICENSE";
    hash = "sha256-9pwUcAPgUtvJ2WxAqfc2R+cnZs/alaWXuU7YJ/4lrLE=";
  };
in
stdenvNoCC.mkDerivation {
  pname = "sculk-web";
  version = "0.1.0";

  src = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      ./package.json
      ./package-lock.json
      ./src
      ./test
      ./static
      ./tools
      ./tsconfig.json
    ];
  };

  npmDeps = importNpmLock {
    package = lib.importJSON ./package.json;
    packageLock = lib.importJSON ./package-lock.json;
  };

  nativeBuildInputs = [
    nodejs
    importNpmLock.npmConfigHook
    esbuild
    typescript
    fonttools
  ];

  buildPhase = ''
    runHook preBuild
    tsc -p tsconfig.json
    esbuild src/main.ts --bundle --minify --sourcemap --target=es2022 \
      --legal-comments=none --outfile=dist/app.js
    python3 tools/subset-font.py ${monocraft}/share/fonts/truetype/*Monocraft-no-ligatures.ttc dist
    runHook postBuild
  '';

  # Unit tests (web/test): bundled by esbuild, run by Node's own test runner.
  doCheck = true;
  checkPhase = ''
    runHook preCheck
    tsc -p test/tsconfig.json
    esbuild test/*.test.ts --bundle --platform=node --format=esm --outdir=build-test \
      --out-extension:.js=.mjs --log-level=warning
    node --test build-test/*.mjs
    runHook postCheck
  '';

  installPhase = ''
    runHook preInstall
    mkdir -p $out
    cp -r static/. dist/. $out/
    cp ${monocraftLicense} $out/monocraft-LICENSE.txt
    # The bundled npm packages' licences (CodeMirror and Lezer: MIT).
    for d in node_modules/@codemirror/* node_modules/@lezer/* node_modules/*; do
      for f in "$d"/LICENSE*; do
        [ -f "$f" ] || continue
        printf '==== %s ====\n' "''${d#node_modules/}" >> $out/third-party-LICENSES.txt
        cat "$f" >> $out/third-party-LICENSES.txt
        printf '\n' >> $out/third-party-LICENSES.txt
      done
    done
    runHook postInstall
  '';

  meta.description = "Web UI for sculk's control server";
}
