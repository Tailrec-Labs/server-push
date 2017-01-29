package controllers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import org.apache.activemq.ActiveMQConnectionFactory
import akka.actor.ActorSystem
import akka.actor.Props
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import javax.inject.Inject
import javax.inject.Singleton
import play.api.{Configuration, Logger}
import play.api.inject.ApplicationLifecycle
import play.api.libs.Comet
import play.api.libs.EventSource
import play.api.mvc.Action
import play.api.mvc.RequestHeader
import play.api.mvc.WebSocket
import publisher.ChatMessage
import redis.RedisClient
import publisher.EventBusImpl
import publisher.JSONMessage
import akka.stream.actor.ActorPublisherMessage.Cancel
import akka.pattern.AskableActorSelection
import akka.util.Timeout
import akka.actor.Identify
import scala.concurrent.duration.DurationInt
import akka.actor.ActorIdentity
import javax.jms.Session
import scala.util.Try
import javax.jms.TextMessage
import akka.actor.Actor
import org.apache.activemq.ActiveMQConnection
import org.apache.activemq.command.ActiveMQQueue
import javax.jms.DeliveryMode

@Deprecated
@Singleton
class ActiveMQQueueChatController @Inject() (config: Configuration, appLifecycle: ApplicationLifecycle)
  (implicit system: ActorSystem) extends ServerPushChatController {
  
  val messageBus = new EventBusImpl()
  
  lazy val connection = {
    val connectionFactory = new ActiveMQConnectionFactory(
      config.getString("jms.broker.host").getOrElse("tcp://localhost:61616"))
    val c = connectionFactory.createConnection().asInstanceOf[ActiveMQConnection]
    c.start()
    appLifecycle.addStopHook { () => Future(c.close()) }
    c
  }
  
  lazy val redis = RedisClient(
    host = config.getString("redis.host").getOrElse("localhost"),
    port = config.getInt("redis.port").getOrElse(6379)
  )
  
  override def getIndexView(channel: String, protocol: String, endpoint: String)(implicit request: RequestHeader) = {
    views.html.jms_queue_chat(channel, protocol, endpoint)
  }
  
  override def getPostEndpoint(): String = routes.ActiveMQQueueChatController.publish().url 
  
  override def getPushEndpoint(channel: String, protocol: String)(implicit request: RequestHeader) = {
    protocol match {
      case "ws" => routes.ActiveMQQueueChatController.ws(channel).webSocketURL()
      case "sse" => routes.ActiveMQQueueChatController.sse(channel).url
      case "comet" => "" //not need because iframe is responsible for regenerating the unique url
      case _ => ???
    }
  }
  
  override def sendMessage(message: ChatMessage) = {
    redis.smembers(message.channel).map{ addrs =>
      addrs.foreach { bstr =>
        val queueSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
        val queueName = message.channel + "_" + bstr.utf8String
        val queue = queueSession.createQueue(queueName)
        val producer = queueSession.createProducer(queue)
        producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT)
        producer.setTimeToLive(60*1000) //TTL = 60 seconds
        val txtMsg = queueSession.createTextMessage(message.toJSONString())
        producer.send(txtMsg)
        queueSession.close()
      }
    }
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
    val actorName = s"activemq-$channel"
    val actorSel = new AskableActorSelection(system.actorSelection(s"/user/$actorName"))
    implicit val timeout = new Timeout(2 seconds)
    actorSel.ask(Identify(1)).map{ sel =>
      Option(sel.asInstanceOf[ActorIdentity].getRef).getOrElse{
        system.actorOf(Props(new ActiveMQQueuePollerActor(channel, messageBus, connection, redis)))
      }
    }
    val publisher = ActorPublisher[String](system.actorOf(Props(
        new ActiveMQQueueActorPublisher(channel, messageBus))))
    Source.fromPublisher(publisher)
  }
  
}

@Deprecated
class ActiveMQQueuePollerActor(channel: String, messageBus: EventBusImpl,
                               connection: ActiveMQConnection, redis: RedisClient) extends Actor {
  val Poll = "Poll"
  val queueSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
  val hostAddr = ChatMessage.getHostAddress().getOrElse("unknown")
  val queueName = channel + "_" + hostAddr
  val queue = queueSession.createQueue(queueName)
  val consumer = queueSession.createConsumer(queue)
  redis.sadd(channel, hostAddr)
  
  self ! Poll
  
  def receive = {
    case Poll => 
      Try(consumer.receive(5000)).map{ m => 
        val message = m.asInstanceOf[TextMessage].getText
        messageBus.publish(JSONMessage(channel, message))
      }
      self ! Poll
  }
  
  override def postStop = {
    consumer.close()
    queueSession.close()
    connection.destroyDestination(new ActiveMQQueue(queueName))
    redis.srem(channel, hostAddr)
  }
}

@Deprecated
class ActiveMQQueueActorPublisher(channel: String, messageBus: EventBusImpl) extends ActorPublisher[String] {
  
  messageBus.subscribe(self, channel)
  
  def receive = {
    case notification: JSONMessage =>
      Logger.debug(s"Received: ${notification.jsonStr} from EventBus of class: $channel")
      val msg = ChatMessage.updateProcessingTime(notification.jsonStr)
      onNext(msg)
    case Cancel =>
      Logger.debug(s"Unsubscribing actor: $self from EventBus of class: $channel")
      messageBus.unsubscribe(self)
  }
}
