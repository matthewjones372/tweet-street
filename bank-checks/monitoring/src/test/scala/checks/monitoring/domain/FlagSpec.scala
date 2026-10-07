package checks.monitoring.domain

import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.DynamicMessage
import zio.*
import zio.schema.codec.ProtobufCodec
import zio.test.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

object FlagSpec extends ZIOSpecDefault:
  private val root = Path.of("..").toAbsolutePath.normalize

  // flag.proto compiled by protoc itself, as any other language's consumer would compile it.
  private val descriptor = ZIO.attemptBlocking {
    val out = Files.createTempFile("flag", ".binpb")
    val _   = Seq(
      "protoc",
      s"--proto_path=${root.resolve("monitoring/src/main/resources")}",
      s"--descriptor_set_out=$out",
      "checks/flags/v1/flag.proto"
    ).!!
    val file = FileDescriptorSet.parseFrom(Files.readAllBytes(out)).getFile(0)
    FileDescriptor.buildFrom(file, Array.empty).findMessageTypeByName("Flag")
  }

  private val flags =
    for
      account  <- Gen.alphaNumericStringBounded(1, 20)
      sequence <- Gen.long(0, Long.MaxValue)
      rule     <- Gen.alphaNumericStringBounded(1, 30)
      version  <- Gen.int(1, Int.MaxValue)
      severity <- Gen.elements("low", "medium", "high")
      at       <- Gen.long(0, 4_000_000_000_000L)
      currency <- Gen.elements("GBP", "BTC", "JPY")
      amount   <- Gen.bigDecimal(BigDecimal(0), BigDecimal(1_000_000)).map(_.setScale(2, BigDecimal.RoundingMode.DOWN))
      evidence <- Gen.string
    yield Flag(account, sequence, rule, version, severity, at, currency, amount.bigDecimal.toPlainString, evidence)

  def spec = suite("Flag")(
    test("protoc's classes read every Flag zio-schema writes, field for field") {
      for
        message <- descriptor
        result  <- check(flags) { flag =>
                    val read                  = DynamicMessage.parseFrom(message, ProtobufCodec.protobufCodec[Flag].encode(flag).toArray)
                    val fields                = read.getAllFields.asScala.map((field, value) => field.getName -> value).toMap
                    def of(name: String): Any = fields.getOrElse(name, message.findFieldByName(name).getDefaultValue)
                    assertTrue(
                      of("account") == flag.account,
                      of("sequence") == flag.sequence,
                      of("rule") == flag.rule,
                      of("version") == flag.version,
                      of("severity") == flag.severity,
                      of("at_millis") == flag.atMillis,
                      of("currency") == flag.currency,
                      of("amount") == flag.amount,
                      of("evidence") == flag.evidence
                    )
                  }
      yield result
    },
    test("the flags' schema breaks nothing published before it") {
      for code <- ZIO.attemptBlocking(
                    Process(Seq("buf", "breaking", "--against", "monitoring/flags.binpb"), root.toFile).!
                  )
      yield assertTrue(code == 0)
    }
  )
