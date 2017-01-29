package publisher

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.GetQueueUrlRequest
import scala.util.Try
import com.amazonaws.services.sqs.model.CreateQueueRequest
import scala.collection.JavaConversions._
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import publisher.QueueSource._

@Deprecated
class AmazonSQSQueueManager(sqs: AmazonSQS) extends QueueManager {
  
  def getURL(queueName: String): String = {
    sqs.getQueueUrl(new GetQueueUrlRequest(queueName)).getQueueUrl
  }
  
  override def createQueue(queueName: String): Try[_] = {
    val queueReq = new CreateQueueRequest(queueName)
        .withAttributes(Map("VisibilityTimeout" -> "15"))
    Try(sqs.createQueue(queueReq)).map(_.getQueueUrl)
  }
  
  override def readMessages(queueName: String): Seq[String] = {
    val queueURL = getURL(queueName)
    val msgRequest = new ReceiveMessageRequest(queueURL)
    msgRequest.setWaitTimeSeconds(15) //long polling
    sqs.receiveMessage(msgRequest).getMessages.map{ msg =>
      Future{
        sqs.deleteMessage(queueURL, msg.getReceiptHandle)
      }
      msg.getBody
    }
  }
  
  override def sendMessage(queueName: String, message: String): Try[Unit] = {
    Try(getURL(queueName)).map(sqs.sendMessage(_, message))
  }
  
  def deleteQueue(queueName: String): Try[Unit] = {
    Try(sqs.deleteQueue(getURL(queueName)))
  }
  
}