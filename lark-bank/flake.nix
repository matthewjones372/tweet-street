# The bank built from source, as a package and an OCI image (bank spec 0010).
{
  description = "lark-bank";

  inputs = {
    nixpkgs.url = "https://channels.nixos.org/nixos-26.05/nixexprs.tar.xz";
    lark = { url = "git+https://github.com/matthewjones372/lark?shallow=1"; flake = false; };
    pelican = { url = "git+https://github.com/matthewjones372/pelican?shallow=1"; flake = false; };
    # bank-access's client (bank spec 0022), from the folder beside this one, built in the same Gradle run as lark and
    # pelican. A relative path input: Nix 2.26 or later.
    bank-access = { url = "path:../bank-access"; flake = false; };
  };

  outputs = { self, nixpkgs, lark, pelican, bank-access }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forEach = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
      # Only what Gradle reads, so a change to the manifests, the docs or the specs does not rebuild the bank.
      src = nixpkgs.lib.fileset.toSource {
        root = ./.;
        fileset = nixpkgs.lib.fileset.unions [
          ./settings.gradle.kts ./build.gradle.kts ./gradle.properties ./gradle
          ./domain ./events ./protocol ./api ./app ./issuer ./loadtest
        ];
      };
      bankFor = pkgs: pkgs.callPackage ./nix/package.nix { inherit src lark pelican; access = bank-access; };
    in
    {
      packages = forEach (pkgs: rec {
        bank = bankFor pkgs;
        # The bank as an OCI image, built without Docker (bank spec 0010). `./result | docker load`.
        bank-image = pkgs.callPackage ./nix/image.nix { inherit bank; };
        default = bank;
      });

      # What CI builds in (bank spec 0017): the JDK, the Chromium that
      # the bank's Playwright drives (the same revision: Playwright for Java 1.59.0 and nixpkgs' 1.59.1 both use
      # 1217), and skopeo to push the images. `nix develop .#ci -c ./gradlew build`.
      devShells = forEach (pkgs: rec {
        ci = pkgs.mkShellNoCC {
          packages = [ pkgs.jdk25_headless pkgs.skopeo pkgs.git pkgs.buf pkgs.protobuf ];
          JAVA_HOME = "${pkgs.jdk25_headless}/lib/openjdk";
          # The toolchains the composite build asks for, 25 for everything and 21 for the Gradle plugins, named for
          # Gradle on the command line: -Porg.gradle.java.installations.fromEnv=JDK25,JDK21 (a gradle.properties
          # does not reach the included builds' plugin builds).
          JDK25 = "${pkgs.jdk25_headless}/lib/openjdk";
          JDK21 = "${pkgs.jdk21_headless}/lib/openjdk";
          PLAYWRIGHT_BROWSERS_PATH = "${pkgs.playwright-driver.browsers}";
          # The events' schemas (spec 0015): buf checks them against the last published, and Nix's protoc
          # compiles them, since Maven's cannot run in Nix's sandbox.
          BUF = "${pkgs.buf}/bin/buf";
          PROTOC = "${pkgs.protobuf}/bin/protoc";
          # Chromium aborts without a font configuration, which a runner image or a bare machine may not have.
          FONTCONFIG_FILE = pkgs.makeFontsConf { fontDirectories = [ pkgs.dejavu_fonts ]; };
        };
        # Looking at and steering a cluster (bank spec 0025): `nix develop .#ops -c k9s`.
        ops = pkgs.mkShellNoCC { packages = [ pkgs.kubectl pkgs.k9s pkgs.fluxcd pkgs.kind ]; };
        default = ci;
      });

      formatter = forEach (pkgs: pkgs.nixpkgs-fmt);
    };
}
