package com.samuel.bank.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}



object PersistentBankAccount {

  sealed trait Command

  object Command {
    case class CreateBankAccount(user: String, currency: String, initialBalance: BigDecimal, replyTo: ActorRef[Response]) extends Command
    case class UpdateBalance(id: String, currency: String, amount: BigDecimal, replyTo: ActorRef[Response]) extends Command
    case class GetBankAccount(id: String, replyTo: ActorRef[Response]) extends Command
  }

  // events = to persist to Cassandra

  trait Event
  case class BankAccountCreated(bankAccount: BankAccount) extends Event
  case class BalanceUpdated(amount: BigDecimal) extends Event
  // state
  case class BankAccount(id: String, user: String, currency: String, balance: BigDecimal)

  // responses
  sealed trait Response
  object Response {
    case class BankAccountCreatedResponse(id: String) extends Response
    case class BankAccountBalanceUpdatedResponse(maybeBankAccount: Option[BankAccount]) extends Response
    case class GetBankAccountResponse(maybeBankAccount: Option[BankAccount]) extends Response
  }

  import Command._
  import Response._

  // commands = messages


  // command handler = message handler => persist an event
  // event handler => update state
  // state

  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] = (state, command) =>
    command match {
      case CreateBankAccount(user, currency, initialBalance, replyTo) =>
        val id = state.id
        /*
        * - bank creates me
        * - bank sends me CreateBankAccount
        * - I persist BankAccountCreated
        * - I update my state
        * - reply back to bank with the BankAccountCreatedResponse
        * - (the bank surfaces the response to the http server
        */
        Effect
          .persist(BankAccountCreated(BankAccount(id, user, currency, initialBalance))) // persisted into cassandra
          .thenReply(replyTo)(_ => BankAccountCreatedResponse(id))
      case UpdateBalance(_, _, amount, replyTo) =>
        val newBalance = state.balance + amount

        // check here for withdrawal
        if (newBalance < 0) // illegal
          Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(None))
        else {
          Effect
          .persist(BalanceUpdated(amount))
          .thenReply(replyTo)(newState => BankAccountBalanceUpdatedResponse(Some(newState)))
        }
      case GetBankAccount(_, replyTo) =>
        Effect.reply(replyTo)(GetBankAccountResponse(Some(state)))

    }
  val eventHandler: (BankAccount, Event) => BankAccount = (state, event) =>
    event match {
      case BankAccountCreated(bankAccount) => bankAccount
      case BalanceUpdated(amount) => state.copy(balance = state.balance + amount)
    }

  def apply(id: String):Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "","", 0.0),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}
