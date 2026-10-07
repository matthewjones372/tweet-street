{
  description = "bank-checks: screening, monitoring and the rules wizard for lark-bank (bank spec 0018)";

  inputs.nixpkgs.url = "https://channels.nixos.org/nixos-26.05/nixexprs.tar.xz";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      forEach = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in {
      # sbt on JDK 25, and protoc and buf for the flags' schema.
      devShells = forEach (pkgs: {
        default = pkgs.mkShell {
          packages = [ (pkgs.sbt.override { jre = pkgs.jdk25_headless; }) pkgs.jdk25_headless pkgs.protobuf pkgs.buf pkgs.skopeo ];
          # The wizard's test drives Nix's Chromium: none is downloaded, and it needs a font to lay pages out.
          PLAYWRIGHT_BROWSERS_PATH = "${pkgs.playwright-driver.browsers}";
          PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = "1";
          FONTCONFIG_FILE = pkgs.makeFontsConf { fontDirectories = [ pkgs.dejavu_fonts ]; };
        };
      });
    };
}
