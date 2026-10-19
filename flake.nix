{
  description = "Seal: a Java-like programming language with value types and compile-time shapes";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
      version = "0-unstable-" + builtins.substring 0 8 (self.lastModifiedDate or "19700101");
    in
    {
      # nix build, nix run . -- run examples/01-basics
      # The compiler with the standard library beside it, and a seal command that runs it.
      packages = forAllSystems (pkgs: {
        default = pkgs.stdenv.mkDerivation {
          pname = "seal";
          inherit version;
          src = self;
          nativeBuildInputs = [ pkgs.jdk25_headless pkgs.makeWrapper ];

          buildPhase = ''
            runHook preBuild
            javac -d classes $(find src -name '*.java')
            runHook postBuild
          '';

          installPhase = ''
            runHook preInstall
            mkdir -p $out/share/seal $out/bin
            cp -r classes $out/share/seal/classes
            cp -r stdlib $out/share/seal/stdlib
            makeWrapper ${pkgs.jdk25_headless}/bin/java $out/bin/seal \
              --add-flags "-Dseal.stdlib=$out/share/seal/stdlib" \
              --add-flags "-cp $out/share/seal/classes io.github.thelinuxseal.seal.Main"
            runHook postInstall
          '';

          meta = {
            description = "Compiler for Seal, a Java-like programming language, to JVM class files";
            homepage = "https://github.com/TheLinuxSeal/seal-lang";
            license = pkgs.lib.licenses.gpl3Only;
            mainProgram = "seal";
            platforms = pkgs.lib.platforms.unix;
          };
        };
      });

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
            # Tests make temporary folders; the build sandbox's is $TMPDIR, not /tmp.
            export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR"
            bash ./test.sh
            runHook postBuild
          '';
          installPhase = "touch $out";
        };
      });
    };
}
