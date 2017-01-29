package publisher

import java.net.NetworkInterface
import java.net.Inet6Address
import play.api.{Logger => log}
import play.api.libs.json.Json
import play.api.libs.json.JsValue

case class ChatMessage(
      channel: String, 
      username: String, 
      body: String, 
      label: Option[String], 
      n: Int, /* current number of requests left */
      timestamp: Long, 
      viaServer: String,
      processingTime: Long){
  
  def toJSONString() = {
    ChatMessage.toJSONString(this)
  }
  
}
  
case object ChatMessage {
  
  implicit val messageFormat = Json.format[ChatMessage]
  
  def applyForm(channel: String, username: String, body: String, label: Option[String], n: Int, timestamp: Long): ChatMessage = {
    ChatMessage(channel, username, body, label, n, timestamp,
        getHostAddress().getOrElse("Unknown"),
        System.currentTimeMillis()) 
        /* nanoTime is not portable (CPU pick arbitrary point of time and 
         * different machine will have different reference point)
         */
  }
  
  def unapplyForm(chatMessage: ChatMessage) = {
    Option(chatMessage).map { m =>
      (m.channel, m.username, m.body, m.label, m.n, m.timestamp)
    }
  }
  
  def updateProcessingTime(json: String): String = {
    import play.api.libs.json._
    import play.api.libs.functional.syntax._
    Json.parse(json).transform(
      (__ \ 'processingTime).json.update(__.read[JsNumber].map { o =>
        JsNumber(System.currentTimeMillis() - o.value.longValue()) 
    })).map(_.toString).getOrElse{
      log.error("Processing time field not found")
      json
    }
  }
  
  def toJSONString(message: ChatMessage) = {
    Json.toJson(message).toString()
  }
  
  def fromJSONString(message: String): Option[ChatMessage] = {
    fromJSON(Json.parse(message))
  }
  
  def fromJSON(message: JsValue): Option[ChatMessage] = {
    message.validate[ChatMessage].asOpt
  }
  
  def getHostAddress(): Option[String] = {
    import scala.collection.JavaConversions._
    val interfaces = NetworkInterface.getNetworkInterfaces()
    interfaces.map{ n: NetworkInterface =>
      n.getInetAddresses.find { x =>
        ! (x.isLoopbackAddress() || (x match { case x: Inet6Address => true; case _ => false}) )
      }
    }.filter { x => x.isDefined }.next().map(_.getHostAddress)
  }
}