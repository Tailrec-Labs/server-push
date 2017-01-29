package controllers

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import play.api.data.Form
import play.api.data.Forms.default
import play.api.data.Forms.longNumber
import play.api.data.Forms.number
import play.api.data.Forms.mapping
import play.api.data.Forms.optional
import play.api.data.Forms.text
import play.api.mvc.Action
import play.api.mvc.Controller
import play.api.mvc.RequestHeader
import play.twirl.api.HtmlFormat
import publisher.ChatMessage

trait ServerPushChatController extends Controller {
  
  val messageForm = Form(
    mapping(
      "channel" -> text,
      "username" -> text,
      "body" -> text,
      "label" -> optional(text),
      "n" -> default(number, 0),
      "timestamp" -> default(longNumber, Instant.now().toEpochMilli())
    )(ChatMessage.applyForm)(ChatMessage.unapplyForm)
  )
  
  def sendMessage(message: ChatMessage): Any
  
  def publish = Action.async { implicit request =>
    messageForm.bindFromRequest.fold(
      formWithErrors => {
        Future.successful(BadRequest)
      },
      message => {
        Future{
          sendMessage(message)
          Ok
        }
      }
    )
  }
  
  case class ConnectionSettings(channel: Option[String], protocol: Option[String])
  
  val connForm = Form(
    mapping(
      "channel" -> optional(text),
      "protocol" -> optional(text)
    )(ConnectionSettings.apply)(ConnectionSettings.unapply)
  )
  
  def getIndexView(channel: String, protocol: String, endpoint: String)(implicit request: RequestHeader): HtmlFormat.Appendable
  
  def index = Action{ implicit request =>
    connForm.bindFromRequest.fold(
      formWithErrors => {
        BadRequest("Unknown error")
      },
      connection => {
        Ok(getIndexView(
            connection.channel.getOrElse("default"), 
            connection.protocol.getOrElse("ws"),
            getPostEndpoint()))
      }
    )
  }
  
  def getPostEndpoint(): String
  
  def getPushEndpoint(channel: String, protocol: String)(implicit request: RequestHeader): String
  
  def pushJs(channel: String, protocol: String) = Action { implicit request =>
    Try(getPushEndpoint(channel, protocol)) match {
      case Success(endpoint) => Ok(views.js.push(channel, protocol, endpoint))
      case Failure(e) =>
        val msg = HtmlFormat.escape(e.getMessage)
        Ok(s"alert('Error: $msg');")
    }
  }
  
}