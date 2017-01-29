package publisher

import akka.stream.actor.ActorPublisher
import play.api.Logger
import scala.annotation.tailrec
import akka.stream.actor.ActorPublisherMessage._

/**
  * @author Hussachai Puripunpinyo
  */
class EventBusActorPublisher (channel: String, eventBus: EventBusImpl) extends ActorPublisher[String] {

  Logger.debug(s"Started ActorPublisher for channel: $channel")

  eventBus.subscribe(self, channel)

  var buffer = Vector.empty[String]

  def receive = {
    case notification: JSONMessage =>
      //        log.debug(s"Received: ${notification.jsonStr} from EventBus of class: $channel")
      val msg = ChatMessage.updateProcessingTime(notification.jsonStr)
      if(buffer.isEmpty && totalDemand > 0) {
        onNext(msg)
      } else {
        buffer :+= msg
        deliverBuffer()
      }
    case Request(_) =>
      deliverBuffer()
    case Cancel =>
      Logger.debug(s"Unsubscribing actor: $self from EventBus of class: $channel")
      eventBus.unsubscribe(self)
      context.stop(self)
    // ** For poll base system **
    // we may have to track the number of people in channel
    // if no one is in a channel, we can stop the actor polling data from the topic of that channel.
  }

  @tailrec
  final def deliverBuffer(): Unit = {
    if(totalDemand > 0) {
      if(totalDemand <= Int.MaxValue) {
        val (use, keep) = buffer.splitAt(totalDemand.toInt)
        buffer = keep
        use foreach onNext
      } else {
        val (use, keep) = buffer.splitAt(Int.MaxValue)
        buffer = keep
        use foreach onNext
        deliverBuffer()
      }
    }
  }
}
