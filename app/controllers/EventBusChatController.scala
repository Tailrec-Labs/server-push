package controllers

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
import publisher._

@Singleton
class EventBusChatController @Inject() (system: ActorSystem) extends ServerPushChatController {
  
  val eventBus = new EventBusImpl()

  override def getIndexView(channel: String, protocol: String, endpoint: String)(implicit request: RequestHeader) = {
    views.html.eventbus_chat(channel, protocol, endpoint)
  }

  override def getPostEndpoint(): String = routes.EventBusChatController.publish().url

  override def getPushEndpoint(channel: String, protocol: String)(implicit request: RequestHeader) = {
    protocol match {
      case "ws" => routes.EventBusChatController.ws(channel).webSocketURL()
      case "sse" => routes.EventBusChatController.sse(channel).url
      case "comet" => "" //not need because iframe is responsible for regenerating the unique url
      case _ => ???
    }
  }

  override def sendMessage(message: ChatMessage) = {
    eventBus.publish(JSONMessage(message.channel, message.toJSONString()))
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
    val workerActor = system.actorOf(Props(new EventBusActorPublisher(channel, eventBus)))
    val publisher = ActorPublisher[String](workerActor)
    Source.fromPublisher(publisher)
  }
  
}
