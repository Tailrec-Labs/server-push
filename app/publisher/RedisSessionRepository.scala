package publisher

import redis.RedisClient
import publisher.QueueSource._

import scala.util.Try
import scala.concurrent.duration.DurationInt
import scala.concurrent.Await

@Deprecated
class RedisSessionRepository(redis: RedisClient) extends SessionRepository {
  
  override def createSession(session: ChannelSession): Try[_] = {
    Try(redis.sadd(session.channel, session.id))
  }
  
  override def deleteSession(session: ChannelSession): Unit = {
    redis.srem(session.channel, session.id)
  }
  
  override def getSessions(channel: String): Seq[ChannelSession] = {
    Await.result(redis.smembers[String](channel), 2 seconds).map { session =>
      ChannelSession(channel, session)
    }
  }
  
}