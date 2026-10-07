# The bank as an OCI image (bank spec 0010): the package from package.nix, the few tools its start script calls,
# and the same JVM settings the Dockerfile gives it, so the Kubernetes manifests run either image unchanged.
{ dockerTools, writeShellScriptBin, jdk25_headless, bashInteractive, coreutils, gnused, gnugrep, findutils, cacert, bank }:

let
  # Lark's slice mover (spec 0105), on the bank's own classpath with its JDBC driver: run it from a bank pod.
  journalMove = writeShellScriptBin "lark-journal-move" ''
    exec ${jdk25_headless}/bin/java -cp "${bank}/share/lark-bank/lib/*" \
      io.github.matthewjones372.lark.actor.journal.jdbc.JournalMoveKt "$@"
  '';
in
dockerTools.streamLayeredImage {
  name = "lark-bank";
  tag = "nix";
  contents = [ bank journalMove bashInteractive coreutils gnused gnugrep findutils cacert ];
  # The JVM writes its perf data and temporary files here.
  extraCommands = "mkdir -m 1777 tmp";
  config = {
    Entrypoint = [ "${bank}/bin/app" ];
    # Heap from the container's limit, not the machine's; a JVM out of memory ends, and Kubernetes starts it again.
    Env = [
      "JAVA_OPTS=-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"
      "SSL_CERT_FILE=${cacert}/etc/ssl/certs/ca-bundle.crt"
    ];
    User = "10001:10001";
    ExposedPorts = { "8080/tcp" = { }; "25520/tcp" = { }; };
  };
}
