package controllers

import java.net.InetSocketAddress
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.stream.Materializer
import javax.inject.Inject
import javax.inject.Singleton
import play.api.{Configuration, Logger}
import play.api.libs.streams.ActorFlow
import play.api.mvc.RequestHeader
import play.api.mvc.WebSocket
import publisher.ChatMessage
import redis.RedisClient
import redis.actors.RedisSubscriberActor
import redis.api.pubsub.Message
import redis.api.pubsub.PMessage

@Singleton
class RedisChatController @Inject() (config: Configuration)(implicit system: ActorSystem, materializer: Materializer) 
  extends ServerPushChatController {
  
  lazy val redis = RedisClient(
    host = config.getString("redis.host").getOrElse("localhost"),
    port = config.getInt("redis.port").getOrElse(6379)
  )
  
  override def getIndexView(channel: String, protocol: String, endpoint: String)(implicit request: RequestHeader) = {
    views.html.redis_chat(channel, protocol, endpoint)
  }
  
  override def getPostEndpoint(): String = routes.RedisChatController.publish().url
  
  override def getPushEndpoint(channel: String, protocol: String)(implicit request: RequestHeader) = {
    protocol match {
      case "ws" => routes.RedisChatController.ws(channel).webSocketURL()
      case _ => ???
    }
  }
  
  override def sendMessage(message: ChatMessage) = {
    redis.publish(message.channel, message.toJSONString()).map{ n =>
      if(n > 0) {
        Logger.debug(s"Total subscriber: $n")
        Ok(n.toString)
      }
      else {
        Logger.debug("No recipient")
        Gone
      }
    }
  }
  
  def ws(channel: String) = WebSocket.acceptOrResult[String, String] { request =>
    def props(channel: String)(out: ActorRef) = Props(classOf[SubscribeActor], redis, out, Seq(channel), Nil)
      .withDispatcher("rediscala.rediscala-client-worker-dispatcher")
    Future.successful(Right(ActorFlow.actorRef(props(channel))))
  }
  
}

class SubscribeActor(redis: RedisClient, out: ActorRef, 
    channels: Seq[String] = Nil, patterns: Seq[String] = Nil) extends RedisSubscriberActor (
    new InetSocketAddress(redis.host, redis.port), channels, patterns,
    onConnectStatus = connected => { println(s"connected: $connected") }) {
    
  Logger.debug(s"Started RedisSubscriberActor for channels: $channels")
  
  def onMessage(message: Message) {
    val msg = message.data.decodeString("UTF-8")
    val updatedMsg = ChatMessage.updateProcessingTime(msg)
    out ! updatedMsg
  }
  
  def onPMessage(pmessage: PMessage) {}
  
  override def onClosingConnectionClosed(): Unit = {
    Logger.debug(s"RedisSubscriberActor for channels: $channels is closing")
  }
  
  override def postStop() {
    Logger.debug(s"RedisSubscriberActor for channels: $channels is stopping")
  }
}
