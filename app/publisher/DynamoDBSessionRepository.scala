package publisher

import scala.util.Try
import com.amazonaws.services.dynamodbv2.document.DynamoDB
import com.amazonaws.services.dynamodbv2.document.Expected
import com.amazonaws.services.dynamodbv2.document.Item
import com.amazonaws.services.dynamodbv2.document.AttributeUpdate
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec
import scala.collection.JavaConversions._
import publisher.QueueSource._

@Deprecated
class DynamoDBSessionRepository(dynamoDB: DynamoDB) extends SessionRepository {
  
  val table = dynamoDB.getTable(TableName)
  
  override def createSession(session: ChannelSession): Try[_] = {
    val createNew = Try(table.putItem(new Item()
        .withPrimaryKey(TableAttrs.channel, session.channel)
        .withStringSet(TableAttrs.sessions, session.id), 
          new Expected(TableAttrs.channel).ne(session.channel)))
    if(createNew.isFailure){
       Try(table.updateItem(new UpdateItemSpec()
            .withPrimaryKey(TableAttrs.channel, session.channel)
            .withAttributeUpdate(new AttributeUpdate(TableAttrs.sessions)
            .addElements(session.id))))
    }else createNew
  }
  
  override def deleteSession(session: ChannelSession): Unit = {
    table.updateItem(new UpdateItemSpec()
        .withPrimaryKey(TableAttrs.channel, session.channel)
        .withAttributeUpdate(new AttributeUpdate(TableAttrs.sessions)
        .removeElements(session.id)))
  }
  
  override def getSessions(channel: String): Seq[ChannelSession] = {
    Option(table.getItem(TableAttrs.channel, channel)).flatMap { item =>
      Option(item.getStringSet(TableAttrs.sessions)).map { sessions =>
        sessions.map{ sessionId => ChannelSession(channel, sessionId) }.toSeq
      }
    }.getOrElse(Nil)
  }
  
}