# breakbulk configuration for the Rust workspace under rust/: code that runs
# in the mod as WebAssembly, under Chicory (libs/chicory). Each crate is built
# for wasm32-wasip1 (what the mod loads) and natively (to compare against).
{ pkgs, ... }:
let
  native = pkgs.stdenv.hostPlatform.rust.rustcTarget;
  rust = pkgs.rust-bin.stable.latest.minimal.override { targets = [ "wasm32-wasip1" ]; };
in
{
  workspaces.rust = {
    path = "rust";
    toolchain = {
      rustc = rust;
      cargo = rust;
    };
    targets = {
      wasm32-wasip1 = { };
      ${native} = { };
    };
    defaultTarget = "wasm32-wasip1";
  };
}
