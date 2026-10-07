# What CI builds bank-approvals in (lark-bank spec 0017).
# `nix develop .#ci -c ./gradlew build`.
{
  description = "bank-approvals";

  inputs.nixpkgs.url = "https://channels.nixos.org/nixos-26.05/nixexprs.tar.xz";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forEach = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forEach (pkgs: rec {
        ci = pkgs.mkShellNoCC {
          packages = [ pkgs.jdk25_headless pkgs.git pkgs.skopeo ];
          JAVA_HOME = "${pkgs.jdk25_headless}/lib/openjdk";
          # The pages' Playwright tests, when they come (approvals-pages): the same revision as lark-bank's.
          PLAYWRIGHT_BROWSERS_PATH = "${pkgs.playwright-driver.browsers}";
          FONTCONFIG_FILE = pkgs.makeFontsConf { fontDirectories = [ pkgs.dejavu_fonts ]; };
        };
        default = ci;
      });

      formatter = forEach (pkgs: pkgs.nixpkgs-fmt);
    };
}
