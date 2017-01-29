package controllers

import com.amazonaws.services.sqs.AmazonSQS
import akka.actor.ActorSystem
import akka.stream.actor.ActorPublisher
import javax.inject.Inject
import javax.inject.Singleton
import play.api.mvc.Action
import com.amazonaws.services.dynamodbv2.document.DynamoDB
import akka.actor.Props
import play.api.mvc.WebSocket
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Flow
import play.api.libs.EventSource
import play.api.libs.Comet
import play.api.mvc.RequestHeader
import publisher.AmazonSQSQueueManager
import publisher.ChatMessage
import publisher.DynamoDBSessionRepository
import publisher.QueueSource.ChannelManager
import publisher.QueueSource.QueueSourceActorPublisher

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Deprecated
@Singleton
class AmazonSQSChatController @Inject() (system: ActorSystem, sqs: AmazonSQS, dyn: DynamoDB) 
  extends ServerPushChatController {
  
  val channelManager = new ChannelManager(
      new DynamoDBSessionRepository(dyn), new AmazonSQSQueueManager(sqs))
  
  override def getIndexView(channel: String, protocol: String, endpoint: String)(implicit request: RequestHeader) = {
    views.html.sqs_chat(channel, protocol, endpoint)
  }
  
  override def getPostEndpoint(): String = routes.AmazonSQSChatController.publish().url 
  
  override def getPushEndpoint(channel: String, protocol: String)(implicit request: RequestHeader) = {
    protocol match {
      case "ws" => routes.AmazonSQSChatController.ws(channel).webSocketURL()
      case "sse" => routes.AmazonSQSChatController.sse(channel).url
      case "comet" => "" //not need because iframe is responsible for regenerating the unique url
      case _ => ???
    }
  }
  
  override def sendMessage(message: ChatMessage) = {
    channelManager.writeMessage(message.channel, message)
  }
  
  def ws(channel: String) = WebSocket.accept[String,String] { request  =>
    val source = createSource(channel)
    Flow.fromSinkAndSource(Sink.foreach(println), source)
  }
  
  def sse(channel: String) = Action { request =>
    val source = createSource(channel)
    Ok.chunked(source via EventSource.flow).as(EVENT_STREAM)
  }
  
  def comet(channel: String) = Action { request =>
    val source = createSource(channel)
    Ok.chunked(source via Comet.string("parent.msgChanged")).as(HTML)
  }
  
  private def createSource(channel: String): Source[String, _] = {
    val workerActor = system.actorOf(Props(
        new QueueSourceActorPublisher(channel, channelManager)))
    val publisher = ActorPublisher[String](workerActor)
    Source.fromPublisher(publisher)
  }
  
}
