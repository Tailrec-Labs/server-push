package controllers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Try
import org.apache.activemq.ActiveMQConnectionFactory
import akka.actor.{Actor, ActorSystem, Props, actorRef2Scala}
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import javax.inject.Inject
import javax.inject.Singleton
import javax.jms.Connection
import javax.jms.DeliveryMode
import javax.jms.Session
import javax.jms.TextMessage
import akka.stream.actor.ActorPublisherMessage.Cancel
import akka.util.Timeout
import play.api.Configuration
import play.api.{Logger => log}
import play.api.inject.ApplicationLifecycle
import play.api.libs.Comet
import play.api.libs.EventSource
import play.api.mvc.Action
import play.api.mvc.RequestHeader
import play.api.mvc.WebSocket
import publisher.{ChatMessage, EventBusActorPublisher, JSONMessage, EventBusImpl}


@Singleton
class ActiveMQTopicChatController @Inject() (config: Configuration,
    system: ActorSystem, appLifecycle: ApplicationLifecycle) extends ServerPushChatController {

  val eventBus = new EventBusImpl()

  lazy val connection = {
    val connectionFactory = new ActiveMQConnectionFactory(
      config.getString("jms.broker.host").getOrElse("tcp://localhost:61616"))
    val c = connectionFactory.createConnection()
    c.start()
    appLifecycle.addStopHook { () => Future(c.close()) }
    c
  }

  override def getIndexView(channel: String, protocol: String, endpoint: String)(implicit request: RequestHeader) = {
    views.html.jms_topic_chat(channel, protocol, endpoint)
  }

  override def getPostEndpoint(): String = routes.ActiveMQTopicChatController.publish().url

  override def getPushEndpoint(channel: String, protocol: String)(implicit request: RequestHeader) = {
    protocol match {
      case "ws" => routes.ActiveMQTopicChatController.ws(channel).webSocketURL()
      case "sse" => routes.ActiveMQTopicChatController.sse(channel).url
      case "comet" => "" //not need because iframe is responsible for regenerating the unique url
      case _ => ???
    }
  }

  override def sendMessage(message: ChatMessage) = {
    val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    val producer = session.createProducer(session.createTopic(message.channel))
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT)
    producer.send(session.createTextMessage(message.toJSONString()))
    session.close()
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
    implicit val timeout = Timeout(2.seconds)
    val pollerActorName = s"actor-topic-${channel}"
    system.actorSelection(s"akka://${system.name}/user/${pollerActorName}").resolveOne().onFailure {
      case e: Throwable =>
        log.debug(s"Actor poller for channel: ${channel} does not exist! Creating a new one...")
        system.actorOf(Props(new JMSTopicActorPoller(channel, connection)), name = pollerActorName)
    }

    val workerActor = system.actorOf(Props(new EventBusActorPublisher(channel, eventBus)))
    val publisher = ActorPublisher[String](workerActor)
    Source.fromPublisher(publisher)
  }

  class JMSTopicActorPoller(channel: String, connection: Connection) extends Actor {

    case object Poll

    val cancellable = context.system.scheduler.scheduleOnce(2.seconds, self, Poll)

    val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    val topic = session.createTopic(channel)
    val consumer = session.createConsumer(topic)

    def receive = {
      case Poll =>
        Try(consumer.receive(1500).asInstanceOf[TextMessage].getText).map{ msg =>
          eventBus.publish(JSONMessage(channel, msg))
        }
        self ! Poll
      case Cancel =>
        log.debug(s"Stopping worker actor for channel: $channel")
        cancellable.cancel()
        context.stop(self)
    }

    override def postStop() = {
      log.debug("[Actor] postStop")
      session.close()
    }
  }

}