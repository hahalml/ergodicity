package com.ergodicity.engine.core.model

import com.ergodicity.engine.plaza2.Repository.Snapshot
import akka.actor.{FSM, Props, ActorRef, Actor}
import akka.actor.FSM.{SubscribeTransitionCallBack, CurrentState, Transition}

case class TrackSession(session: ActorRef)

sealed trait SessionContentsState

object SessionContentsState {
  case object Idle extends SessionContentsState
  case object TrackingSessionState extends SessionContentsState
}

trait SessionContents[S <: Security, R <: SessContents] extends Actor with FSM[SessionContentsState, SessionState] {

  import SessionContentsState._
  
  def initialState: SessionState

  def converter: R => S

  protected[core] var instruments: Map[String, ActorRef] = Map()

  startWith(Idle, initialState)

  when(Idle) {
    case Event(TrackSession(session), _) => session ! SubscribeTransitionCallBack(self); goto(TrackingSessionState)
  }

  when(TrackingSessionState) {
    case Event(CurrentState(_, state: SessionState), _) => stay() using state
    case Event(Transition(_, _, to: SessionState), _) => handleSessionState(to); stay() using to
    case Event(snapshot: Snapshot[R], _) => handleSessionContentsRecord(snapshot.data); stay()
  }

  onTransition {
    case from -> to => log.info("SessionContents updated from " + from + " -> " + to)
  }

  protected def handleSessionState(state: SessionState)

  protected def handleSessionContentsRecord(records: Iterable[R]);

  def conformIsinToActorName(isin: String) = isin.replaceAll(" ","@")
}

case class StatefulSessionContents[S <: Security, R <: StatefulSessContents](initialState: SessionState)(implicit val converter: R => S) extends SessionContents[S, R] {

  private var originalInstrumentState: Map[String, InstrumentState] = Map()

  protected def handleSessionState(state: SessionState) {
    instruments.foreach {
      case (isin, ref) => ref ! mergeStates(state, originalInstrumentState(isin))
    }
  }

  def handleSessionContentsRecord(records: Iterable[R]) {
    records.foreach {
      record =>
        val isin = record.isin
        val state = mergeStates(stateData, InstrumentState(record.state))
        instruments.get(isin).map(_ ! state) getOrElse {
          instruments = instruments + (isin -> context.actorOf(Props(new Instrument(converter(record), state)), isin))
        }
        originalInstrumentState = originalInstrumentState + (isin -> InstrumentState(record.state))
    }
  }

  def mergeStates(sessionState: SessionState, instrumentState: InstrumentState) = {
    sessionState match {

      case SessionState.Assigned => instrumentState match {
        case InstrumentState.Online => InstrumentState.Assigned
        case other => other
      }
      case SessionState.Online => instrumentState

      case SessionState.Suspended => instrumentState match {
        case s@(InstrumentState.Canceled | InstrumentState.Completed) => s
        case s@(InstrumentState.Assigned | InstrumentState.Online) => InstrumentState.Suspended
        case other => other
      }

      case SessionState.Canceled => InstrumentState.Canceled

      case SessionState.Completed => InstrumentState.Completed
    }
  }
}

case class StatelessSessionContents[S <: Security, R <: StatelessSessContents](initialState: SessionState)(implicit val converter: R => S) extends SessionContents[S, R] {
  protected def handleSessionState(state: SessionState) {
    instruments.foreach {
      case (isin, ref) => ref ! InstrumentState(state)
    }
  }

  protected def handleSessionContentsRecord(records: Iterable[R]) {
    records.foreach {
      record =>
        val isin = record.isin
        val state = InstrumentState(stateData)
        instruments.get(isin).map(_ ! state) getOrElse {
          instruments = instruments + (record.isin -> context.actorOf(Props(new Instrument(converter(record), state)), conformIsinToActorName(isin)))
        }
    }
  }
}