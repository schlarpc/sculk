# The control server's web UI: TypeScript type-checked by tsc and bundled by
# esbuild, plus Monocraft (OFL-1.1) subset to woff2. Runtime libraries
# (CodeMirror, for the script editor) come from package-lock.json through a
# fixed-output fetch; tsc and esbuild come from nixpkgs. After changing
# package.json, run `npm install --package-lock-only` and update npmDepsHash
# (the build prints the right one).
#
#   nix build .#web
{
  lib,
  stdenvNoCC,
  fetchNpmDeps,
  npmHooks,
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
      ./static
      ./tools
      ./tsconfig.json
    ];
  };

  npmDeps = fetchNpmDeps {
    name = "sculk-web-npm-deps";
    src = lib.fileset.toSource {
      root = ./.;
      fileset = lib.fileset.unions [
        ./package.json
        ./package-lock.json
      ];
    };
    hash = "sha256-Oh3vqxYOSQZ7Z/colEflKeb90cAYjV4CaLNuQCvTqZk=";
  };

  nativeBuildInputs = [
    nodejs
    npmHooks.npmConfigHook
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
