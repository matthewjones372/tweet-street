{
  description = "bank-access: who may do what across the Lark Bank estate, in OpenFGA (lark-bank spec 0022)";

  inputs.nixpkgs.url = "https://channels.nixos.org/nixos-26.05/nixexprs.tar.xz";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      forEach = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in {
      # The OpenFGA CLI, which tests the model as the server would answer it.
      devShells = forEach (pkgs: {
        # The CLI, and JDK 25 for access-sync's Gradle build.
        default = pkgs.mkShell { packages = [ pkgs.openfga-cli pkgs.jdk25_headless ]; };
      });

      # The model and what applies it, as an image the access-model Job runs, loaded into kind by
      # lark-bank's scripts/up.sh (lark-bank spec 0022's access-server).
      packages = forEach (pkgs: rec {
        apply-model = pkgs.writeShellApplication {
          name = "apply-model";
          runtimeInputs = [ pkgs.openfga-cli pkgs.jq pkgs.curl ];
          text = builtins.readFile ./scripts/apply-model.sh;
        };
        model-image = pkgs.dockerTools.buildLayeredImage {
          name = "bank-access-model";
          tag = "dev";
          contents = [ pkgs.cacert ];
          config = {
            Entrypoint = [ "${apply-model}/bin/apply-model" ];
            Env = [ "MODEL=${./model/model.fga}" "SSL_CERT_FILE=${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt" ];
          };
        };
        default = model-image;
      });

      # `nix flake check`: the model's tests, which fail on any relation that no longer answers as they say.
      checks = forEach (pkgs: {
        model = pkgs.runCommand "bank-access-model-tests" { nativeBuildInputs = [ pkgs.openfga-cli ]; } ''
          cd ${./model}
          fga model test --tests model.fga.yaml | tee $out
        '';
        # The JSON the services and tests load is the model, transformed: never edited by hand, never behind.
        model-json = pkgs.runCommand "bank-access-model-json" { nativeBuildInputs = [ pkgs.openfga-cli pkgs.jq ]; } ''
          fga model transform --file ${./model/model.fga} | jq -S . > fresh.json
          jq -S . ${./model/model.json} > kept.json
          diff -u kept.json fresh.json || { echo "model/model.json is not model.fga: fga model transform --file model/model.fga > model/model.json"; exit 1; }
          touch $out
        '';
      });
    };
}
