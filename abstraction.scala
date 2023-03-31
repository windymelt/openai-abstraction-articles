//> using scala "3.2.2"
//> using lib "io.circe::circe-core:0.14.5"
//> using lib "io.circe::circe-literal:0.14.5"
//> using lib "io.circe::circe-parser:0.14.5"
//> using lib "org.scala-lang.modules::scala-xml:2.1.0"
//> using lib "com.softwaremill.sttp.tapir::tapir-core:1.2.11",
//> using lib "com.softwaremill.sttp.tapir::tapir-http4s-client:1.2.11",
//> using lib "com.softwaremill.sttp.tapir::tapir-json-circe:1.2.11",
//> using lib "org.http4s::http4s-ember-client:0.23.18"
//> using lib "org.http4s::http4s-dsl:0.23.18"
//> using lib "com.monovore::decline:2.4.1"
//> using lib "com.monovore::decline-effect:2.4.1"

/* コマンドラインオプション */
import cats.effect._
import cats.implicits._

import com.monovore.decline._
import com.monovore.decline.effect._

val domainOpt = Opts
  .option[String]("domain", "分野（あなたは、$domainに詳しい…… という使われ方をします）", short = "d")
val roleOpt = Opts
  .option[String]("role", "役割（あなたは、……に詳しい$roleです という使われ方をします）", short = "r")
  .withDefault("専門家")
val subjectOpt = Opts
  .option[String](
    "subject",
    "要約対象（以下の$subjectを要約してください という使われ方をします）",
    short = "s"
  )
  .withDefault("記事")
val nOfPointOpt =
  Opts.option[Int]("count", "要約ごとに何行の要点にまとめるか", short = "n").withDefault(3)
val openAiApiKeyOpt =
  Opts.env[String]("OPENAI_API_KEY", help = "OpenAI API Key")
val openAiOrg = Opts
  .env[String]("OPENAI_ORG", help = "OpenAI Organization id")
  .withDefault("")

/* OpenAIのAPIまわりの定義 */

import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.model.Header
import sttp.model.MediaType
import io.circe.generic.auto._

object OpenAI {
  lazy val BasicEndpoint = endpoint
    .securityIn(auth.bearer[String]())
    .securityIn(header[String]("OpenAI-Organization"))

  lazy val completion =
    BasicEndpoint.post // Watch! you may forget to write `post` here
      .in("v1" / "chat" / "completions")
      .in(jsonBody[CompletionParams])
      .out(jsonBody[CompletionResult])
      .errorOut(stringBody)

  case class CompletionParams(
      model: String,
      messages: Seq[CompletionMessage],
      temperature: Double
  )

  case class CompletionMessage(role: String, content: String)

  case class CompletionResult(
      id: String,
      `object`: String,
      created: Long,
      model: String,
      usage: CompletionResultUsage,
      choices: Seq[CompletionResultChoice]
  )
  case class CompletionResultUsage(
      prompt_tokens: Int,
      completion_tokens: Int,
      total_tokens: Int
  )
  case class CompletionResultChoice(
      message: CompletionResultChoiceMessage,
      finish_reason: String,
      index: Int
  )
  case class CompletionResultChoiceMessage(role: String, content: String)
}

/* ここから本体 */

import cats.effect._
import cats.data.Kleisli
import cats.effect.IO
import cats.effect.IOApp.Simple
import cats.implicits._
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import sttp.tapir.DecodeResult

object Main
    extends CommandIOApp(
      name = "abstraction",
      header = "要約をOpenAIで生成する",
      version = "0.0.x"
    ) {
  override def main: Opts[IO[ExitCode]] =
    (
      domainOpt,
      roleOpt,
      subjectOpt,
      nOfPointOpt,
      openAiApiKeyOpt,
      openAiOrg
    ) mapN { (domain, role, subject, nOfPoint, apikey, org) =>

      val body = """ ここに本文をなんとかして読み込ませる(stdinとかでいい) """.stripMargin

      val prefix =
        s"あなたは${domain}に詳しい${role}。以下の${subject}を、タイトルと要約の2点をそれぞれ改行で分けて日本語で説明して。要点は${nOfPoint}行程度の箇条書きで。\n"

      // Interpret the tapir endpoint as a http4s request and a response parser.
      import sttp.tapir.client.http4s.Http4sClientInterpreter
      val (req, resParser) =
        Http4sClientInterpreter[IO]()
          .toSecureRequest(
            OpenAI.completion,
            baseUri =
              Some(Uri.fromString("https://api.openai.com/").toOption.get)
          )
          .apply(
            apikey,
            org
          )
          .apply(
            OpenAI.CompletionParams(
              "gpt-3.5-turbo",
              Seq(
                OpenAI.CompletionMessage(
                  "user",
                  prefix + body
                )
              ),
              0.7
            )
          )
      // https://stackoverflow.com/questions/49649453/how-to-log-all-requests-for-an-http4s-client
      // thank you guys
      // prepare client resource
      val httpClientResource = EmberClientBuilder.default[IO].build
      // import org.http4s.client.middleware.Logger
      // val liftClientToLog: Client[IO] => Client[IO] =
      //   Logger(logBody = true, logHeaders = true)(_)
      val loggingClientResource = sys.env.get("DEBUG").getOrElse("") match {
        // case "1" => httpClientResource.map(liftClientToLog)
        case _ => httpClientResource
      }

      val requesting: Client[IO] => IO[
        DecodeResult[Either[String, OpenAI.CompletionResult]]
      ] = _.run(req).use(resParser)

      val show: DecodeResult[Either[String, OpenAI.CompletionResult]] => IO[
        Unit
      ] = {
        case sttp.tapir.DecodeResult.Value(Right(v)) =>
          IO.println(v.choices.head.message.content)
        case sttp.tapir.DecodeResult.Value(Left(_)) =>
          IO.println("access failure")
        case sttp.tapir.DecodeResult.Missing      => IO.println(s"missing")
        case sttp.tapir.DecodeResult.Multiple(vs) => IO.println(s"mul $vs")
        case sttp.tapir.DecodeResult.Error(original, error) =>
          IO.println(s"orig: ${original}, err: ${error.getMessage()}")
        case sttp.tapir.DecodeResult.Mismatch(expected, actual) =>
          IO.println(s"missmatch: exp: $expected, act: $actual")
        case sttp.tapir.DecodeResult.InvalidValue(errors) =>
          IO.println(s"invalid: $errors")
      }
      // use client resource
      IO.println(s"PREFIX: $prefix") >> loggingClientResource.use(
        (Kleisli(requesting) >>> Kleisli(show)).run
      ) >> IO.pure(
        ExitCode.Success
      )
    }
}
