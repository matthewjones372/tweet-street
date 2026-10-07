package checks.platform

import java.nio.ByteBuffer

enum FramingError(msg: String) extends RuntimeException(msg):
  case TooShort(length: Int)    extends FramingError(s"a record of $length bytes is shorter than its framing")
  case NotFramed(magic: Byte)   extends FramingError(s"a record starting with $magic, not the framing's zero byte")
  case OtherMessage(index: Int) extends FramingError(s"a record of message $index, where the topic has only its first")

// As lark-bank-events' README frames a record: a zero byte, the schema's 4-byte content id, the message index (one
// zero byte), then the message.
final case class Framed(contentId: Int, message: Array[Byte])

object Framing:
  def unframe(record: Array[Byte]): Either[FramingError, Framed] =
    if record.length < 6 then Left(FramingError.TooShort(record.length))
    else if record(0) != 0 then Left(FramingError.NotFramed(record(0)))
    else if record(5) != 0 then Left(FramingError.OtherMessage(record(5).toInt))
    else Right(Framed(ByteBuffer.wrap(record, 1, 4).getInt, record.drop(6)))

  def frame(contentId: Int, message: Array[Byte]): Array[Byte] =
    ByteBuffer.allocate(6 + message.length).put(0.toByte).putInt(contentId).put(0.toByte).put(message).array()
