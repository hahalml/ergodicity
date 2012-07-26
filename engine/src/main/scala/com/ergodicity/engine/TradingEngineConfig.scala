package com.ergodicity.engine

import com.twitter.util.Config
import akka.actor.ActorSystem
import com.ergodicity.cgate.config.{ConnectionConfig, Replication}
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import component.{PosListenerComponent, OptInfoListenerComponent, FutInfoListenerComponent, ConnectionComponent}
import ru.micexrts.cgate.{Connection => CGConnection, Listener => CGListener}

case class ReplicationScheme(futInfo: Replication, optInfo: Replication, pos: Replication)

trait TradingEngineConfig extends Config[TradingEngine] {
  var connectionConfig = required[ConnectionConfig](Tcp("localhost", 4001, "TradingEngine"))

  var processMessagesTimeout = required[Int](100)

  var system = required[ActorSystem](ActorSystem("TradingEngine", ConfigWithDetailedLogging))
}

trait CGateTradingEngineConfig extends TradingEngineConfig {

  var replicationScheme = required[ReplicationScheme]

  def apply() = new TradingEngine(processMessagesTimeout) with ConnectionComponent with FutInfoListenerComponent with OptInfoListenerComponent with PosListenerComponent {
    val underlyingConnection = new CGConnection(connectionConfig())

    def underlyingFutInfoListener = listener => new CGListener(underlyingConnection, replicationScheme.futInfo(), listener)

    def underlyingOptInfoListener = listener => new CGListener(underlyingConnection, replicationScheme.optInfo(), listener)

    def underlyingPosListener = listener => new CGListener(underlyingConnection, replicationScheme.pos(), listener)
  }
}

