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
      cp -r "$HOME/.m2/repository" "$out/repository"

      # Drop Maven/deps bookkeeping that is NOT part of the artifacts and
      # differs from machine to machine (and run to run): per-host repository
      # ids, download timestamps, and resolver status. Leaving these in is what
      # makes the fixed-output hash drift between, e.g., a dev box and the
      # deploy server even though the actual jars are identical.
      find "$out" -type f \( \
           -name '_remote.repositories' \
        -o -name '_maven.repositories' \
        -o -name 'resolver-status.properties' \
        -o -name '*.lastUpdated' \
      \) -delete

      # Normalize permissions so the hash doesn't depend on the builder's umask.
      # (The recursive/NAR hash ignores mtimes, so there is no need to touch
      # timestamps here.)
      find "$out" -type d -exec chmod 755 {} +
      find "$out" -type f -exec chmod 644 {} +
    '';

    dontFixup = true;
    outputHashMode = "recursive";
    outputHashAlgo = "sha256";
    outputHash = "sha256-fp4Dkfu3guMqrs94sBJ9u2vaBKMa3AShA3X5RGroJLs=";
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
