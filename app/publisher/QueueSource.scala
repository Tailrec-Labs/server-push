package publisher

import java.util.UUID

import scala.util.Try
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage._

import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.PoisonPill
import play.api.Logger

@Deprecated
object QueueSource {
  
  case object Poll
  
  class QueueSourceActorPublisher(channel: String, channelManager: ChannelManager) extends ActorPublisher[String] {
  
    var cancellable = context.system.scheduler.scheduleOnce(2 seconds, self, Poll)
    
    val session: ChannelSession = channelManager.startSession(channel)
    
    def receive = {
      case Poll =>
        Logger.debug("Polling data")
        if(isActive && totalDemand > 0){
          channelManager.readMessages(session).foreach{ msg =>
            val updatedMsg = ChatMessage.updateProcessingTime(msg)
            Logger.debug(s"Received: $updatedMsg from session: $session")
            onNext(updatedMsg)
          }
          if(!cancellable.isCancelled) self ! Poll
        }else{
          Logger.info(s"Killing actor publisher of session: $session because there is no active subscriber!")
          self ! PoisonPill
        }
      case Request(count) =>
        Logger.debug(s"Received Request ($count) from subscriber: ${session}")
      case Cancel =>
        Logger.debug(s"Stopping worker actor for session: $session")
        cancellable.cancel()
        context.stop(self)
    }
    
    override def postRestart(reason: Throwable) = {
      Logger.debug(s"[Actor] postRestart last exception: $reason")
      super.postRestart(reason)
    }
    
    override def postStop() = {
      Logger.debug("[Actor] postStop")
      channelManager.closeSession(session)
    }
    
  }
  
  trait QueueManager {
    
    def createQueue(queueName: String): Try[_]
    def readMessages(queueName: String): Seq[String]
    def sendMessage(queueName: String, message: String): Try[_]
    def deleteQueue(queueName: String): Try[_]
  }
  
  trait SessionRepository {
    val TableName = "channel-tracker"
  
    case object TableAttrs {
      val channel = "channel"
      val sessions = "sessions"
    }
    
    def createSession(session: ChannelSession): Try[_]
    /* This function may throw an exception */
    def deleteSession(session: ChannelSession): Unit
    def getSessions(channel: String): Seq[ChannelSession]
  }
  
  class ChannelManager (sessionRepo: SessionRepository, queueManager: QueueManager) {
    
    def startSession(channel: String): ChannelSession = {
      val session = ChannelSession(channel)
      sessionRepo.createSession(session).map { _ =>
        if(queueManager.createQueue(session.name).isFailure) {
          sessionRepo.deleteSession(session)
        }
      }
      session
    }
    
    def readMessages(session: ChannelSession): Seq[String] = {
      Logger.debug(s"Read message from session: $session")
      queueManager.readMessages(session.name)
    }
    
    def writeMessage(channel: String, message: ChatMessage) = {
      sessionRepo.getSessions(channel).foreach{ session =>
        if(queueManager.sendMessage(session.name, message.toJSONString()).isFailure){
          sessionRepo.deleteSession(session)
        }
      }
    }
    
    def closeSession(session: ChannelSession) = {
      queueManager.deleteQueue(session.name)
      sessionRepo.deleteSession(session)
    }
  }
  
  case class ChannelSession(channel: String, id: String){
    val name = channel + "_" + id
    override def toString() = name
  }
  
  case object ChannelSession {
    def apply(channel: String): ChannelSession = 
      ChannelSession(channel, UUID.randomUUID().toString())
  }
  
}