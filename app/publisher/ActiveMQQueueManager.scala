package publisher

import scala.util.Try
import javax.jms.Connection
import javax.jms.Session
import javax.jms.TextMessage
import javax.jms.DeliveryMode
import publisher.QueueSource._
import org.apache.activemq.ActiveMQConnection
import org.apache.activemq.command.ActiveMQQueue

@Deprecated
class ActiveMQQueueManager(connection: Connection) extends QueueManager {
  
  override def createQueue(queueName: String): Try[Unit] = Try{}
  
  override def readMessages(queueName: String): Seq[String] = {
    val queueSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    val queue = queueSession.createQueue(queueName)
    val consumer = queueSession.createConsumer(queue)
    val message = Try(consumer.receive(15000))
    queueSession.close()
    message.map(_.asInstanceOf[TextMessage].getText::Nil).getOrElse(Nil)
  }
  
  override def sendMessage(queueName: String, message: String): Try[Unit] = Try{
    val queueSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    val queue = queueSession.createQueue(queueName)
    val producer = queueSession.createProducer(queue)
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT)
    val txtMsg = queueSession.createTextMessage(message)
    producer.send(txtMsg)
    queueSession.close()
  }
  
  override def deleteQueue(queueName: String): Try[Unit] = Try{
    connection.asInstanceOf[ActiveMQConnection]
      .destroyDestination(new ActiveMQQueue(queueName))
  }
  
}
