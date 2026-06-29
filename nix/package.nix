{ lib, stdenv, clojure, jdk, makeWrapper }:

let
  version = "0.1.0";

  # ---------------------------------------------------------------------------
  # Fixed-output derivation that downloads every Maven dependency once.
  #
  # Because the set of dependencies is fixed, this is the one place Nix allows
  # network access. The FIRST `nix build` will fail with a hash mismatch and
  # print the correct value — copy it into `outputHash` below and build again.
  # Re-run this dance whenever you change deps.edn.
  # ---------------------------------------------------------------------------
  maven-deps = stdenv.mkDerivation {
    pname = "kwickchat-maven-deps";
    inherit version;

    # Only the dependency manifests matter for prefetching.
    src = lib.cleanSourceWith {
      src = ../.;
      filter = path: _type:
        let base = baseNameOf path;
        in base == "deps.edn" || base == "build.clj";
    };

    nativeBuildInputs = [ clojure jdk ];

    buildPhase = ''
      export HOME="$TMPDIR"
      export JAVA_TOOL_OPTIONS=
      export SOURCE_DATE_EPOCH=1
      # Runtime deps (default basis) + the build toolchain (tools.build + cljs).
      clojure -P
      clojure -P -T:build
    '';

    installPhase = ''
      mkdir -p "$out"
      # Normalize permissions and timestamps for reproducibility
      find "$HOME/.m2/repository" -type f -exec chmod 644 {} +
      find "$HOME/.m2/repository" -type d -exec chmod 755 {} +
      find "$HOME/.m2/repository" -exec touch -t 197001010000.00 {} +
      # Sort files to ensure consistent ordering
      (cd "$HOME/.m2" && find repository -type f | LC_ALL=C sort | tar -cf "$TMPDIR/repo.tar" -T -)
      mkdir -p "$out"
      tar -xf "$TMPDIR/repo.tar" -C "$out"
    '';

    dontFixup = true;
    outputHashMode = "recursive";
    outputHashAlgo = "sha256";
    outputHash = "sha256-Brtagtai5FF6K/4/EtdJT3hVS14KzO2vesCzSbs7JiY=";
  };
in stdenv.mkDerivation {
  pname = "kwickchat";
  inherit version;

  src = lib.cleanSource ../.;

  nativeBuildInputs = [ clojure jdk makeWrapper ];

  buildPhase = ''
    runHook preBuild
    export HOME="$TMPDIR"
    export JAVA_TOOL_OPTIONS=
    mkdir -p "$HOME/.m2"
    cp -r "${maven-deps}/repository" "$HOME/.m2/repository"
    chmod -R u+w "$HOME/.m2"
    # Compile ClojureScript + assemble the uberjar, strictly offline.
    clojure -T:build uber
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    mkdir -p "$out/share/kwickchat"
    cp target/kwickchat.jar "$out/share/kwickchat/kwickchat.jar"
    makeWrapper "${jdk}/bin/java" "$out/bin/kwickchat" \
      --add-flags "-jar $out/share/kwickchat/kwickchat.jar"
    runHook postInstall
  '';

  meta = {
    description = "A tiny pastebin-for-chat where every link is its own room";
    mainProgram = "kwickchat";
    platforms = [ "x86_64-linux" "aarch64-linux" ];
  };
}
