# The control server's web UI: TypeScript type-checked by tsc and bundled by
# esbuild, plus Monocraft (OFL-1.1) subset to woff2. No npm dependencies, so
# no lock file and no network access beyond fixed-output fetches.
#
#   nix build .#web
{
  lib,
  stdenvNoCC,
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
      ./src
      ./static
      ./tools
      ./tsconfig.json
    ];
  };

  nativeBuildInputs = [
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
    runHook postInstall
  '';

  meta.description = "Web UI for sculk's control server";
}
