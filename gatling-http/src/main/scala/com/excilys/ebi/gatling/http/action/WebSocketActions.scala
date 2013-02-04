/**
 * Copyright 2011-2012 eBusiness Information, Groupe Excilys (www.excilys.com)
 * Copyright 2012 Gilt Groupe, Inc. (www.gilt.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.excilys.ebi.gatling.http.action

import akka.actor.{ActorRef, Props}
import com.excilys.ebi.gatling.core.action.{Action, BaseActor, Bypass}
import com.excilys.ebi.gatling.core.config.ProtocolConfigurationRegistry
import com.excilys.ebi.gatling.core.result.message.RequestStatus._
import com.excilys.ebi.gatling.core.session._
import com.excilys.ebi.gatling.core.util.StringHelper._
import com.excilys.ebi.gatling.core.util.TimeHelper._
import com.excilys.ebi.gatling.http.util.{RequestLogger, WebSocketClient}
import com.ning.http.client.websocket.{WebSocket, WebSocketTextListener}
import java.io.IOException
import java.net.URI

private[http] abstract class WebSocketAction(actionName: EvaluatableString) extends Action() with Bypass {
  def resolvedActionName(session: Session): String = {
    try {
      actionName(session)
    }
    catch {
      case e => error("Action name resolution crashed", e); "no-name"
    }
  }
}

private[http] class OpenWebSocketAction(actionName: EvaluatableString, attributeName: String, fUrl: EvaluatableString, webSocketClient: WebSocketClient, requestLogger: RequestLogger, val next: ActorRef, registry: ProtocolConfigurationRegistry) extends WebSocketAction(actionName) {
  def execute(session: Session) {
    val rActionName = resolvedActionName(session)

    info("Opening websocket '" + attributeName + "': Scenario '" + session.scenarioName + "', UserId #" + session.userId)

    val actor = context.actorOf(Props(new WebSocketActor(attributeName, requestLogger)))

    val started = nowMillis
    try {
      webSocketClient.open(URI.create(fUrl(session)), new WebSocketTextListener {
        var opened = false

        def onOpen(webSocket: WebSocket) {
          opened = true
          actor ! OnOpen(rActionName, webSocket, started, nowMillis, next, session)
        }

        def onMessage(message: String) {
          actor ! OnMessage(message)
        }

        def onFragment(fragment: String, last: Boolean) {
        }

        def onClose(webSocket: WebSocket) {
          if (opened) {
            actor ! OnClose
          }
          else {
            actor ! OnFailedOpen(rActionName, "closed", started, nowMillis, next, session)
          }
        }

        def onError(t: Throwable) {
          if (opened) {
            actor ! OnError(t)
          }
          else {
            actor ! OnFailedOpen(rActionName, t.getMessage, started, nowMillis, next, session)
          }
        }
      })
    }
    catch {
      case e: IOException =>
        actor ! OnFailedOpen(rActionName, e.getMessage, started, nowMillis, next, session)
    }
  }
}

private[http] class SendWebSocketMessageAction(actionName: EvaluatableString, attributeName: String, fMessage: EvaluatableString, val next: ActorRef, registry: ProtocolConfigurationRegistry) extends WebSocketAction(actionName) {
  def execute(session: Session) {
    session.getAttributeAsOption[(ActorRef, _)](attributeName).foreach(_._1 ! SendMessage(resolvedActionName(session), fMessage(session), next, session))
  }
}

private[http] class CloseWebSocketAction(actionName: EvaluatableString, attributeName: String, val next: ActorRef, registry: ProtocolConfigurationRegistry) extends WebSocketAction(actionName) {
  def execute(session: Session) {
    val rActionName = resolvedActionName(session)
    info("Closing websocket '" + attributeName + "': Scenario '" + session.scenarioName + "', UserId #" + session.userId)
    session.getAttributeAsOption[(ActorRef, _)](attributeName).foreach(_._1 ! Close(rActionName, next, session))
  }
}

private[http] class WebSocketActor(val attributeName: String, requestLogger: RequestLogger) extends BaseActor {
  var webSocket: Option[WebSocket] = None
  var errorMessage: Option[String] = None

  def receive = {
    case OnOpen(actionName, webSocket, started, ended, next, session) =>
      requestLogger.logRequest(session, actionName, OK, started, ended)
      this.webSocket = Some(webSocket)
      next ! session.setAttribute(attributeName, (self, webSocket))

    case OnFailedOpen(actionName, message, started, ended, next, session) =>
      warn("Websocket '" + attributeName + "' failed to open: " + message)
      requestLogger.logRequest(session, actionName, KO, started, ended, Some(message))
      next ! session.setFailed
      context.stop(self)

    case OnMessage(message) =>
      debug("Received message on websocket '" + attributeName + "':" + END_OF_LINE + message)

    case OnClose =>
      errorMessage = Some("Websocket '" + attributeName + "' was unexpectedly closed")
      warn(errorMessage.get)

    case OnError(t) =>
      errorMessage = Some("Websocket '" + attributeName + "' gave an error: '" + t.getMessage + "'")
      warn(errorMessage.get)

    case SendMessage(actionName, message, next, session) =>
      if (!handleEarlierError(actionName, next, session)) {
        val started = nowMillis
        webSocket.foreach(_.sendTextMessage(message))
        requestLogger.logRequest(session, actionName, OK, started, nowMillis)
        next ! session
      }

    case Close(actionName, next, session) =>
      if (!handleEarlierError(actionName, next, session)) {
        val started = nowMillis
        webSocket.foreach(_.close)
        requestLogger.logRequest(session, actionName, OK, started, nowMillis)
        next ! session
        context.stop(self)
      }
  }

  def handleEarlierError(actionName: String, next: ActorRef, session: Session) = {
    if (errorMessage.isDefined) {
      val now = nowMillis
      requestLogger.logRequest(session, actionName, KO, now, now, errorMessage)
      errorMessage = None
      next ! session.setFailed
      context.stop(self)
      true
    }
    else {
      false
    }
  }
}

private[http] case class OnOpen(actionName: String, webSocket: WebSocket, started: Long, ended: Long, next: ActorRef, session: Session)
private[http] case class OnFailedOpen(actionName: String, message: String, started: Long, ended: Long, next: ActorRef, session: Session)
private[http] case class OnMessage(message: String)
private[http] case object OnClose
private[http] case class OnError(t: Throwable)

private[http] case class SendMessage(actionName: String, message: String, next: ActorRef, session: Session)
private[http] case class Close(actionName: String, next: ActorRef, session: Session)
