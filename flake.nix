{
  description = "sculk: scriptable protocol interception and injection for modded Minecraft";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    # Rust (rust/), built one crate per derivation; nix/rust.nix.
    breakbulk = {
      url = "github:schlarpc/breakbulk";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    # The remote access shell (https://sculk-ui.github.io/), for its Nostr
    # relay list, which the mod bakes in as the fallback for the one it
    # fetches from the site (docs/REMOTE.md).
    sculk-ui = {
      # git+https, not github:, which would send the configured API token.
      url = "git+https://github.com/sculk-ui/sculk-ui.github.io";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      breakbulk,
      rust-overlay,
      sculk-ui,
    }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAllSystems (
        pkgs:
        let
          inherit (pkgs) lib stdenv;

          # Gradle itself runs on the newest JDK; each subproject compiles with
          # a Gradle toolchain resolved from the JAVA*_HOME variables below
          # (see org.gradle.java.installations.fromEnv in gradle.properties).
          gradle = pkgs.gradle_9.override { java = pkgs.jdk25; };

          # Native libraries the Minecraft client loads at runtime via LWJGL
          # (runClient). Not needed for compiling or for runServer.
          clientRuntimeLibs = lib.optionals stdenv.hostPlatform.isLinux (
            with pkgs;
            [
              libGL
              libpulseaudio
              openal
              alsa-lib
              udev
              flite
              wayland
              libxkbcommon
              libx11
              libxcursor
              libxrandr
              libxxf86vm
              libxext
              libxi
            ]
          );
        in
        {
          default = pkgs.mkShell {
            packages = [
              gradle
              pkgs.jdk25
              pkgs.python3 # scripts/*.py
              # web/: `npm ci` once, then `tsc -p web --watch`, `esbuild --watch` while working on the UI
              pkgs.typescript
              pkgs.esbuild
              pkgs.nodejs
            ]
            ++ lib.optionals stdenv.hostPlatform.isLinux [
              # headless clients for integration tests, and screenshots of them
              pkgs.xorg-server
              pkgs.xwd
              pkgs.imagemagick
            ];

            JAVA17_HOME = pkgs.jdk17.home;
            JAVA21_HOME = pkgs.jdk21.home;
            JAVA25_HOME = pkgs.jdk25.home;
            JAVA_HOME = pkgs.jdk25.home;

            LD_LIBRARY_PATH = lib.makeLibraryPath clientRuntimeLibs;

            # Software OpenGL for headless clients. scripts/integ.py points only
            # the clients it launches at this; normal runClient uses the host GPU.
            SCULK_MESA = lib.optionalString stdenv.hostPlatform.isLinux "${pkgs.mesa}";
          };
        }
      );

      packages = forAllSystems (
        pkgs:
        let
          rust =
            (breakbulk.lib.mkRepository {
              pkgs = pkgs.extend rust-overlay.overlays.default;
              root = self;
              modules = [ ./nix/rust.nix ];
            }).workspaces.rust;
        in
        {
          # The control server's web UI; Gradle runs `nix build .#web` and packs
          # the result into each mod jar under sculk/web/.
          web = pkgs.callPackage ./web { };
          # @babel/standalone for the script transpiler, packed under sculk/babel/.
          babel = pkgs.callPackage ./nix/babel { };
        # The relay list as of the pinned shell, packed under sculk/remote/.
        relays = sculk-ui.packages.${pkgs.stdenv.hostPlatform.system}.relays;
          # str0m behind a sans-IO interface (rust/sculk-webrtc), as the wasm
          # module the mod runs under Chicory for remote access.
          webrtc = rust.packages.sculk-webrtc;
          # str0m data channel benchmark (rust/sculk-webrtc-bench), as wasm for Chicory
          # (bench/webrtc) and natively for comparison.
          webrtc-bench = rust.packages.sculk-webrtc-bench;
          webrtc-bench-native = rust.target.${pkgs.stdenv.hostPlatform.rust.rustcTarget}.packages.sculk-webrtc-bench;
        }
      );

      formatter = forAllSystems (pkgs: pkgs.nixfmt);
    };
}
