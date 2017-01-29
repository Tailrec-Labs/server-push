package controllers

import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.ActorSystem
import akka.stream.actor.ActorPublisher
import javax.inject.Inject
import javax.inject.Singleton
import play.api.mvc.Action
import akka.actor.Props
import play.api.mvc.WebSocket
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Flow
import play.api.libs.EventSource
import play.api.libs.Comet
import play.api.mvc.RequestHeader
import play.api.libs.json.Json
import scala.concurrent.Future
import publisher.{ChatMessage, EventBusActorPublisher, EventBusImpl, JSONMessage}
import com.amazonaws.services.sns.AmazonSNS
import play.api.Logger
import play.api.mvc.BodyParsers

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class AmazonSNSChatController @Inject() (system: ActorSystem, sns: AmazonSNS) extends ServerPushChatController {
  
  val eventBus = new EventBusImpl()

  override def getIndexView(channel: String, protocol: String, endpoint: String)(implicit request: RequestHeader) = {
    views.html.sns_chat(channel, protocol, endpoint)
  }

  override def getPostEndpoint(): String = routes.AmazonSNSChatController.publish().url

  override def getPushEndpoint(channel: String, protocol: String)(implicit request: RequestHeader) = {
    protocol match {
      case "ws" => routes.AmazonSNSChatController.ws(channel).webSocketURL()
      case "sse" => routes.AmazonSNSChatController.sse(channel).url
      case "comet" => "" //not need because iframe is responsible for regenerating the unique url
      case _ => ???
    }
  }

  override def sendMessage(message: ChatMessage) = {
    val topicRes = sns.createTopic(message.channel)
    val publishRes = sns.publish(topicRes.getTopicArn, message.toJSONString(), message.channel)
  }

  def hook() = Action.async(BodyParsers.parse.text) { request =>
    Future{
      val json = Json.parse(request.body)
      val messgeType = (json \ "Type").as[String]
      Logger.debug(s"SNS Hook received: $messgeType")
      messgeType match {
        case "Notification" =>
          val channel = (json \ "Subject").as[String]
          val message = (json \ "Message").as[String]
          eventBus.publish(JSONMessage(channel, message))
        case "SubscriptionConfirmation" =>
          val topicArn = (json \ "TopicArn").as[String]
          val token = (json \ "Token").as[String]
          Logger.debug(s"Confirming subscription with topicArn: $topicArn and token: $token")
          val confirmResult = sns.confirmSubscription(topicArn, token)
          Logger.debug(s"Confirmed subscriptionArn: ${confirmResult.getSubscriptionArn}")
        case _ =>
          val err = s"Unsupported type: $json"
          Logger.error(err)
          BadRequest(err)
      }
      Ok
    }.recover{ case e =>
      Logger.error(e.getMessage())
      BadRequest(e.getMessage)
    }
  }

  def ws(channel: String) = WebSocket.accept[String,String] { implicit request  =>
    val source = createSource(channel)
    Flow.fromSinkAndSource(Sink.foreach(println), source)
  }

  def sse(channel: String) = Action { implicit request =>
    val source = createSource(channel)
    Ok.chunked(source via EventSource.flow).as(EVENT_STREAM)
  }

  def comet(channel: String) = Action { implicit request =>
    val source = createSource(channel)
    Ok.chunked(source via Comet.string("parent.msgChanged")).as(HTML)
  }

  private def createSource(channel: String)(implicit request: RequestHeader): Source[String, _] = {
    val topicArn = sns.createTopic(channel).getTopicArn
    val endpoint = routes.AmazonSNSChatController.hook.absoluteURL
    sns.subscribe(topicArn, "HTTP", endpoint)
    Logger.debug(s"Subscribe SNS topic: $topicArn with endpoint: $endpoint")
    val workerActor = system.actorOf(Props(new EventBusActorPublisher(channel, eventBus)))
    val publisher = ActorPublisher[String](workerActor)
    Source.fromPublisher(publisher)
  }
  
}

