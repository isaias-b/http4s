package org.http4s

import org.http4s.headers.`Content-Type`
import org.http4s.parser.QueryParser
import org.http4s.util.{UrlFormCodec, UrlCodingUtils}

import scala.collection.{GenTraversableOnce, MapLike}
import scala.io.Codec
import scalaz.{ \/, Equal }

class UrlForm private (val values: Map[String, Seq[String]]) extends AnyVal {
  override def toString: String = values.toString()

  def get(key: String): Seq[String] =
    this.getOrElse(key, Seq.empty[String])

  def getOrElse(key: String, default: => Seq[String]): Seq[String] =
    values.get(key).getOrElse(default)

  def getFirst(key: String): Option[String] =
    values.get(key).flatMap(_.headOption)

  def getFirstOrElse(key: String, default: => String): String =
    this.getFirst(key).getOrElse(default)

  def +(kv: (String, String)): UrlForm = {
    val newValues = values.get(kv._1).fold(Seq(kv._2))(_ :+ kv._2)
    UrlForm(values.updated(kv._1, newValues))
  }

  def withFormField[T](key: String, value: T)(implicit ev: QueryParamEncoder[T]): UrlForm =
    this + (key -> ev.encode(value).value)

  def withFormField[T](key: String, value: Option[T])(implicit ev: QueryParamEncoder[T]): UrlForm = {
    import scalaz.syntax.std.option._
    value.cata[UrlForm](withFormField(key, _)(ev), this)
  }

  def withFormFields[T](key: String, values: Seq[T])(implicit ev: QueryParamEncoder[T]): UrlForm =
    values.foldLeft(this)(_.withFormField(key, _)(ev))

  def +?[T : QueryParamEncoder](key: String, value: T): UrlForm =
    withFormField(key, value)

  def +?[T : QueryParamEncoder](key: String, value: Option[T]): UrlForm =
    withFormField(key, value)

  def ++?[T : QueryParamEncoder](key: String, values: Seq[T]): UrlForm =
    withFormFields(key, values)
}

object UrlFormApp extends App {
  val form = UrlForm.empty.withFormField("foo", 1).withFormField[Boolean]("bar", None).withFormFields("dummy", List("a", "b", "c"))
  Console.println(implicitly[EntityEncoder[UrlForm]].toEntity(form).run.body.pipe(scalaz.stream.text.utf8Decode).runLog.run.reduceLeft(_ + _))
}

object UrlForm {

  val empty: UrlForm = new UrlForm(Map.empty)

  def apply(values: Map[String, Seq[String]]): UrlForm =
    // value "" -> Seq() is just noise and it is not maintain during encoding round trip
    if(values.get("").fold(false)(_.isEmpty)) new UrlForm(values - "")
    else new UrlForm(values)

  def apply(values: (String, String)*): UrlForm =
    values.foldLeft(empty)(_ + _)

  implicit def entityEncoder(implicit charset: Charset = DefaultCharset): EntityEncoder[UrlForm] =
    EntityEncoder.stringEncoder(charset)
      .contramap[UrlForm](encodeString(charset))
      .withContentType(`Content-Type`(MediaType.`application/x-www-form-urlencoded`, charset))

  implicit def entityDecoder(implicit defaultCharset: Charset = DefaultCharset): EntityDecoder[UrlForm] =
    EntityDecoder.decodeBy(MediaType.`application/x-www-form-urlencoded`){ m =>
      DecodeResult(
        EntityDecoder.decodeString(m)
          .map(decodeString(m.charset.getOrElse(defaultCharset)))
      )
    }

  implicit val eqInstance: Equal[UrlForm] = new Equal[UrlForm] {
    import scalaz.syntax.equal._
    import scalaz.std.list._
    import scalaz.std.string._
    import scalaz.std.map._

    def equal(x: UrlForm, y: UrlForm): Boolean =
      x.values.mapValues(_.toList).view.force === y.values.mapValues(_.toList).view.force
  }

  /** Attempt to decode the `String` to a [[UrlForm]] */
  def decodeString(charset: Charset)(urlForm: String): MalformedMessageBodyFailure \/ UrlForm =
    QueryParser.parseQueryString(urlForm.replace("+", "%20"), new Codec(charset.nioCharset))
      .map(q => UrlForm(q.multiParams))
      .leftMap { parseFailure => MalformedMessageBodyFailure(parseFailure.message, None) }

  /** Encode the [[UrlForm]] into a `String` using the provided `Charset` */
  def encodeString(charset: Charset)(urlForm: UrlForm): String = {
    def encode(s: String): String =
      UrlCodingUtils.urlEncode(s, charset.nioCharset, spaceIsPlus = true, toSkip = UrlFormCodec.urlUnreserved)

    val sb = new StringBuilder(urlForm.values.size * 20)
    urlForm.values.foreach { case (k, vs) =>
      if (sb.nonEmpty) sb.append('&')
      val encodedKey = encode(k)
      if (vs.isEmpty) sb.append(encodedKey)
      else {
        var first = true
        vs.foreach { v =>
          if(!first) sb.append('&')
          else first = false
          sb.append(encodedKey)
            .append('=')
            .append(encode(v))
        }
      }
    }
    sb.result()
  }
}
