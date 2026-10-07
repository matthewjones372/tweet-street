package checks.screening.adapters

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import zio.*
import zio.http.*

import java.lang.Iterable as JavaIterable
import scala.jdk.CollectionConverters.*

/**
 * A server span for each request the routes answer (lark-bank spec 0024),
 * continuing the caller's trace from its `traceparent`: the bank's screening
 * call, so a transfer's trace holds what the check did with it. The span's ids
 * are put on every line logged while it is answered, as `trace_id` and
 * `span_id`, as the bank's are.
 *
 * Named once for the routes it wraps, `POST /screen`, rather than from the
 * request's path, which for another route could carry an id.
 */
object Traced:
  private object Headers extends TextMapGetter[Request]:
    def keys(request: Request): JavaIterable[String] = request.headers.toList.map(_.headerName).asJava
    def get(request: Request, key: String): String   =
      if request == null then null else request.headers.get(key).orNull

  private val Status = AttributeKey.longKey("http.response.status_code")
  private val Method = AttributeKey.stringKey("http.request.method")

  def server(name: String, telemetry: OpenTelemetry): Middleware[Any] = new Middleware[Any]:
    def apply[Env1, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
      routes.transform { answering =>
        Handler.fromFunctionZIO[Request] { request =>
          ZIO.suspendSucceed {
            val parent = telemetry.getPropagators.getTextMapPropagator.extract(Context.root(), request, Headers)
            val span   = telemetry
              .getTracer("bank-checks")
              .spanBuilder(name)
              .setSpanKind(SpanKind.SERVER)
              .setParent(parent)
              .setAttribute(Method, request.method.name)
              .startSpan()
            val ids = span.getSpanContext
            ZIO
              .logAnnotate(LogAnnotation("trace_id", ids.getTraceId), LogAnnotation("span_id", ids.getSpanId))(
                // Its own scope, closed with the answer: screening's is a JSON body, nothing streams after it.
                ZIO.scoped[Env1](answering(request))
              )
              .tap(response =>
                ZIO.succeed {
                  span.setAttribute(Status, response.status.code.toLong)
                  if response.status.code >= 500 then span.setStatus(StatusCode.ERROR): Unit
                }
              )
              .ensuring(ZIO.succeed(span.end()))
          }
        }
      }
