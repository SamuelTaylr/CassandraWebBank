package com.samuel.bank.http

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives._
import com.samuel.bank.actors.PersistentBankAccount.Command.{CreateBankAccount, GetBankAccount, UpdateBalance}
import com.samuel.bank.actors.PersistentBankAccount.Response._
import com.samuel.bank.actors.PersistentBankAccount.{Command, Response}
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

case class BankAccountCreationPayload(user: String, currency: String, balance: BigDecimal) {
  def toCommand(replyTo: ActorRef[Response]): Command = CreateBankAccount(user, currency, balance, replyTo)
}

case class FailureResponse(reason: String)

case class BankAccountUpdateRequest(currency: String, amount: BigDecimal) {
  def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateBalance(id, currency, amount, replyTo)
}

class BankRouter(bank: ActorRef[Command])(implicit system: ActorSystem[_]) {
  implicit val timeout: Timeout = Timeout(5.seconds)

  def createBankAccount(request: BankAccountCreationPayload): Future[Response] =
    bank.ask(replyTo => request.toCommand(replyTo))

  def getBankAccount(id: String): Future[Response] =
    bank.ask(replyTo => GetBankAccount(id, replyTo))

  def updateBankAccount(id: String, request: BankAccountUpdateRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(id, replyTo))
  /*
  * POST /bank/
  * Payload: bank account creation request as JSON
  * Response:
  *   201 Created
  *   Location: /bank/uuid
  *
  * GET /bank/uuid
  *   Response:
  *     200 OK
  *     JSON representation of bank account details
  *
  * PUT /bank/uuid
  *   Payload: (currency, amount) as JSON
  *   Response
  *     1) 200 OK
  *        Payload: new bank details as JSON
  *     2) 404 Not Found
  *     3) TODO 400 Bad request if something wrong
  *
   */

  val routes =
    pathPrefix("bank") {
      pathEndOrSingleSlash {
        post {
          // parse the payload
          entity(as[BankAccountCreationPayload]) { request =>
            /*
            * convert request into a Command for the bank actor
            * send the command to the bank
            * expect a reply
            * parse the reply, use data to send back a response
            * */
            onSuccess(createBankAccount(request)) {
              case BankAccountCreatedResponse(id) =>
                respondWithHeader(Location(s"/bank/$id")) {
                  complete(StatusCodes.Created)
                }
            }
          }
        }
      } ~
        path(Segment) { id =>
          get {
            /*
          * send command to the bank
          * expect a reply
           */
            onSuccess(getBankAccount(id)) {
              case GetBankAccountResponse(Some(account)) =>
                complete(account)
              case GetBankAccountResponse(None) =>
                complete(StatusCodes.NotFound, FailureResponse(s"Bank account $id cannot be found"))
            }
          } ~
            put {
              entity(as[BankAccountUpdateRequest]) { request =>
                /*
              * transform the request to a command
              * send the command to the bank
              * expect a reply
              * send back an HTTP response
              * */
                // TODO validate the request
                onSuccess(updateBankAccount(id, request)) {
                  case BankAccountBalanceUpdatedResponse(Some(account)) =>
                    complete(account)
                  case BankAccountBalanceUpdatedResponse(None) =>
                    complete(StatusCodes.NotFound, FailureResponse(s"Bank account $id cannot be found"))
                }
              }
            }
        }
    }

}
