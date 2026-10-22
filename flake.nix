{
  description = "Seal: a Java-like programming language with value types and compile-time shapes";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
      date = self.lastModifiedDate or "19700101";
      version = "0-unstable-${builtins.substring 0 4 date}-${builtins.substring 4 2 date}-${builtins.substring 6 2 date}";
      # The compiler with the standard library beside it, and a seal command that runs it: package.nix,
      # the file nixpkgs would hold, built from this checkout rather than the commit it fetches.
      sealFor = pkgs: (pkgs.callPackage ./package.nix { }).overrideAttrs {
        inherit version;
        src = self;
      };
    in
    {
      # nix build, nix run . -- run examples/01-basics
      packages = forAllSystems (pkgs: {
        default = sealFor pkgs;
      });

      # nixpkgs.overlays = [ inputs.seal.overlays.default ]; in a configuration, and pkgs.seal is this
      # package, built with that configuration's nixpkgs.
      overlays.default = final: prev: {
        seal = sealFor final;
      };

      # nix develop (or direnv, with the .envrc): JDK 25, and the repository's seal script on PATH.
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [ pkgs.jdk25 ];
          shellHook = ''
            export PATH="$(git rev-parse --show-toplevel 2>/dev/null || pwd):$PATH"
            echo "Seal dev shell ($(java -version 2>&1 | head -1)): seal run examples/01-basics, ./test.sh"
          '';
        };
      });

      # nix flake check: build the package, and run every test suite.
      checks = forAllSystems (pkgs: {
        package = self.packages.${pkgs.stdenv.hostPlatform.system}.default;
        tests = pkgs.stdenv.mkDerivation {
          name = "seal-tests";
          src = self;
          nativeBuildInputs = [ pkgs.jdk25_headless ];
          buildPhase = ''
            runHook preBuild
            bash ./test.sh
            runHook postBuild
          '';
          installPhase = "touch $out";
        };
      });
    };
}
