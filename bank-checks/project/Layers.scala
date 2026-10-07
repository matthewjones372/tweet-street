import sbt.*
import sbt.Keys.*

// The context map's one line the module graph cannot draw: a context's domain is pure, so nothing under a
// `domain` package imports ZIO, save its schema and prelude. Checked before every compile.
object Layers {
  private val allowed = Set("zio.schema", "zio.prelude")

  val check = taskKey[Unit]("Fails when a domain package imports ZIO beyond zio.schema and zio.prelude")

  def violations(sources: Seq[File]): Seq[String] =
    for {
      source       <- sources
      if source.getPath.contains("/domain/")
      (line, index) <- IO.readLines(source).zipWithIndex
      imported      = line.trim.stripPrefix("import ").trim
      if line.trim.startsWith("import zio")
      if !allowed.exists(prefix => imported == prefix || imported.startsWith(prefix + "."))
    } yield s"${source.getPath}:${index + 1}: a domain imports $imported"

  lazy val settings = Seq(
    check := Def.uncached {
      val found = violations((Compile / unmanagedSources).value)
      if (found.nonEmpty) sys.error(("The domain is pure: no ZIO in it." +: found).mkString("\n"))
    },
    Compile / compile := Def.uncached((Compile / compile).dependsOn(check).value)
  )
}
