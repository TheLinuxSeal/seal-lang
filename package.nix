{
  lib,
  stdenv,
  fetchFromGitHub,
  jdk25_headless,
  makeWrapper,
  nix-update-script,
}:

# The Seal compiler, written as nixpkgs' pkgs/by-name/se/seal/package.nix would hold it. The flake
# builds this same file from the repository's own source (flake.nix).
stdenv.mkDerivation (finalAttrs: {
  pname = "seal";
  version = "0-unstable-2026-10-22";

  src = fetchFromGitHub {
    owner = "TheLinuxSeal";
    repo = "seal-lang";
    rev = "32f1e7b2b75ca8878e9a442d5a1565ffd33c929c";
    hash = "sha256-jZ2rcYPeGAsLiyw/BK309Sb5Zt+e0GzKw1g30efiZXM=";
  };

  strictDeps = true;
  nativeBuildInputs = [
    jdk25_headless
    makeWrapper
  ];

  buildPhase = ''
    runHook preBuild
    javac -d classes $(find src -name '*.java')
    runHook postBuild
  '';

  # A JDK, not a JRE, runs it: it compiles the .java files a program holds with the JDK's javac.
  installPhase = ''
    runHook preInstall
    mkdir -p $out/share/seal
    cp -r classes stdlib $out/share/seal/
    makeWrapper ${lib.getExe' jdk25_headless "java"} $out/bin/seal \
      --add-flags "-Dseal.stdlib=$out/share/seal/stdlib" \
      --add-flags "-cp $out/share/seal/classes io.github.thelinuxseal.seal.Main"
    runHook postInstall
  '';

  doInstallCheck = true;
  installCheckPhase = ''
    runHook preInstallCheck
    mkdir hello
    cat > hello/Main.seal <<'EOF'
    class Main {
        static int main() {
            int<<8>> small = 127;
            small++;
            System.stdout.println("hello from Seal " + small);
            return 0;
        }
    }
    EOF
    [ "$($out/bin/seal run hello)" = "hello from Seal -128" ]
    runHook postInstallCheck
  '';

  passthru.updateScript = nix-update-script { extraArgs = [ "--version=branch" ]; };

  meta = {
    description = "Java-like programming language with value types and compile-time shapes, compiled to JVM class files";
    homepage = "https://github.com/TheLinuxSeal/seal-lang";
    license = lib.licenses.gpl3Only;
    mainProgram = "seal";
    maintainers = [ ];
    platforms = jdk25_headless.meta.platforms;
    sourceProvenance = [ lib.sourceTypes.fromSource ];
  };
})
