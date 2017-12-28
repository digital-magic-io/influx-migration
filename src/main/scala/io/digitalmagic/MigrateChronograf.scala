package io.digitalmagic

import java.io.{File, PrintWriter}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import spray.json._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.Source

object HttpClient {

  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  private val akkaSSLConfig = AkkaSSLConfig().mapSettings(s => s.withLoose(
    s.loose
      .withDisableSNI(true)
      .withDisableHostnameVerification(true)
    )
  )
  private val httpContext = Http().createClientHttpsContext(akkaSSLConfig)

  def execute(request: HttpRequest): Future[HttpResponse] = {
    if (request.uri.toString().startsWith("https")) Http().singleRequest(request, httpContext)
    else Http().singleRequest(request)
  }

  def getAs[T](url: String)(implicit reader: RootJsonReader[T]): Future[T] = {
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
    execute(HttpRequest(uri = url)).flatMap(r => {
      if (r.status.isSuccess()) Unmarshal(r.entity).to[T]
      else throw new Exception(s"Unable to retrieve content. Http status: ${r.status}")
    })
  }

  def post[T](url: String, entity: T)(implicit writer: RootJsonWriter[T]): Future[HttpResponse] = {
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
    for {
      requestEntity <- Marshal(entity).to[RequestEntity]
      response <- execute(HttpRequest(method = HttpMethods.POST, uri = url, entity = requestEntity))
    } yield {
      println(s"Response status: ${response.status}")
      response
    }
  }

  def terminate(): Future[Unit] = {
    println("Terminating ActorSystem")
    HttpClient.system.terminate().map(_ => println("Terminated ActorSystem"))
  }
}

object MigrateChronograf {

  case class Dashboard(id: Int, cells: JsValue, templates: JsValue, name: String, links: JsValue)
  case class Dashboards(dashboards: List[Dashboard])

  object ChronografJsonProtocol extends DefaultJsonProtocol {
    implicit val dashboardFormat: RootJsonFormat[Dashboard] = jsonFormat5(Dashboard)
    implicit val dashboardsFormat: RootJsonFormat[Dashboards] = jsonFormat1(Dashboards)
  }

  import HttpClient.ec
  import ChronografJsonProtocol._

  private def buildDashboardsUrl(basePath: String): String = s"$basePath/chronograf/v1/dashboards"

  def exportDashboards(basePath: String): Future[Dashboards] = {
    HttpClient.getAs[Dashboards](buildDashboardsUrl(basePath))
  }

  def importDashboards(basePath: String, ds: Dashboards): Future[Unit] = {
    println("Starting import...")
    val results = ds.dashboards.map { d =>
      println(s"Importing dashboard: ID=${d.id} Name=${d.name}")
      val res = HttpClient.post(buildDashboardsUrl(basePath), d).map { resp =>
        resp.discardEntityBytes(HttpClient.materializer)
        if (resp.status == StatusCodes.Created) {
          println(s"Successfully imported dashbboard: ID=${d.id} Name=${d.name}")
        } else {
          println(s"Error: Unable to import dashboard  ID=${d.id} Name=${d.name} with HTTP STATUS: ${resp.status}")
        }
      }.recover {
        case e => sys.error(s"Failed: $e")
      }
      Thread.sleep(100)
      res
    }
    Future.sequence(results).map{ _ => println("Import successfully completed...")}
  }

  def copyDashboards(sourceBasePath: String, targetBasePath: String): Future[Unit] = {
    exportDashboards(sourceBasePath).flatMap(res => {
      println(s"Returned records: ${res.dashboards.size}")
      importDashboards(targetBasePath, res)
    })
  }

  val sourceBasePath = "http://prod-lb-monitoring-462569446.us-east-1.elb.amazonaws.com"
  val targetBasePath = "http://dev-lb-monitoring-1619326503.us-west-2.elb.amazonaws.com"

  def doCopy(): Future[Unit] = MigrateChronograf.copyDashboards(sourceBasePath = sourceBasePath, targetBasePath = targetBasePath)

  def doExportToFiles(): Future[Unit] = {
    MigrateChronograf.exportDashboards(sourceBasePath).map { ds =>
      ds.dashboards.foreach { d =>
        val writer = new PrintWriter(new File(s"dashboard_${d.name}.json"))
        writer.write(d.toJson.prettyPrint)
        writer.close()
      }
    }
  }

  def doImportFromFile(): Future[Unit] = {
    val jsonStr = Source.fromFile("dashboard_REST-resources.json").mkString.parseJson
    val ds = Dashboards(List(jsonStr.convertTo[Dashboard]))
    MigrateChronograf.importDashboards(targetBasePath, ds)
  }

  def main(array: Array[String]): Unit = {
    val f = doCopy().recover {
      case e =>
        sys.error(s"Failed: $e")
    }
    f onComplete (_ => HttpClient.terminate())
    Await.result(f, Duration.Inf)
  }

}
