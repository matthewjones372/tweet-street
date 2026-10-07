# The bank from source (bank spec 0010): lark, pelican and bank-access's client built in the same Gradle run as a
# composite build, so nothing comes from mavenLocal. Every other dependency is pinned in deps.json, which `nix run .#bank.mitmCache
# .updateScript` rewrites when a dependency changes.
{ lib, stdenv, gradle_9, jdk25_headless, jdk21_headless, protobuf, makeWrapper, src, lark, pelican, access }:

let
  # On the same JDK as the build: Gradle's package otherwise runs on its own 21 and names it JAVA_HOME, which the
  # toolchains find first, at a root with no class library.
  gradle = gradle_9.override { java = jdk25_headless; };
  jdk = jdk25_headless;
in
stdenv.mkDerivation (finalAttrs: {
  pname = "lark-bank";
  version = "0.1.0";
  inherit src;

  nativeBuildInputs = [ gradle makeWrapper ];
  # The events' classes (spec 0015), from Nix's protoc: Maven's binary cannot run in the sandbox.
  env.PROTOC = "${protobuf}/bin/protoc";

  mitmCache = gradle.fetchDeps {
    pkg = finalAttrs.finalPackage;
    data = ./deps.json;
  };

  # Beside the bank's source, where -PlarkSource=../lark finds it, and writable: Gradle writes into an included build.
  postUnpack = ''
    cp -r ${lark} lark
    cp -r ${pelican} pelican
    cp -r ${access} bank-access
    chmod -R u+w lark pelican bank-access
  '';

  gradleFlags = [
    "-PlarkSource=../lark"
    "-PpelicanSource=../pelican"
    "-PaccessSource=../bank-access"
    # The toolchains the projects ask for, and nothing downloaded: 25 for everything, 21 for the Gradle plugins, which
    # run inside Gradle. Each under lib/openjdk, where the JDK itself is: the package root holds no class library.
    "-Porg.gradle.java.installations.auto-download=false"
    "-Porg.gradle.java.installations.paths=${jdk}/lib/openjdk,${jdk21_headless}/lib/openjdk"
    "-Dorg.gradle.java.home=${jdk}"
  ];
  gradleBuildTask = ":app:installDist";
  # The update records what the build itself fetches, the included builds' compilers and plugins among it.
  gradleUpdateTask = ":app:installDist";
  # The tests run in `./gradlew build`; they need Docker for Postgres, and Chromium, which the sandbox has neither of.
  doCheck = false;

  installPhase = ''
    runHook preInstall
    mkdir -p "$out/share/lark-bank"
    cp -r app/build/install/app/. "$out/share/lark-bank"
    makeWrapper "$out/share/lark-bank/bin/app" "$out/bin/app" --set JAVA_HOME "${jdk}"
    runHook postInstall
  '';

  meta = {
    description = "A bank on lark's actors and pelican's HTTP";
    mainProgram = "app";
    platforms = lib.platforms.linux ++ lib.platforms.darwin;
  };
})
