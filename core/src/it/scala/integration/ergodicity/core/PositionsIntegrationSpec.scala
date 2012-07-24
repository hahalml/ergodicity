package integration.ergodicity.core

import java.io.File
import java.util.concurrent.TimeUnit
import akka.actor.{Actor, Props, ActorSystem}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import integration.ergodicity.core.AkkaIntegrationConfigurations._
import com.ergodicity.core.position.Positions.BindPositions
import com.ergodicity.core.position.Positions
import com.ergodicity.cgate.config.ConnectionType.Tcp
import ru.micexrts.cgate.{CGate, Connection => CGConnection, Listener => CGListener}
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate.config.Replication._
import com.ergodicity.cgate._
import config.{Replication, CGateConfig}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import akka.event.Logging

class PositionsIntegrationSpec extends TestKit(ActorSystem("PositionsIntegrationSpec", ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  val Host = "localhost"
  val Port = 4001

  val RouterConnection = Tcp(Host, Port, system.name)

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
  }

  override def afterAll() {
    system.shutdown()
    CGate.close()
  }

  "Positions" must {
    "should work" in {
      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = TestFSMRef(new Connection(underlyingConnection), "Connection")
      connection ! Connection.Open

      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) => connection ! StartMessageProcessing(100);
        }
      })))

      val dataStream = TestFSMRef(new DataStream, "PositionsDataStream")

      // Listeners
      val listenerConfig = Replication("FORTS_POS_REPL", new File("cgate/scheme/pos.ini"), "CustReplScheme")
      val underlyingListener = new CGListener(underlyingConnection, listenerConfig(), new DataStreamSubscriber(dataStream))
      val listener = TestFSMRef(new Listener(underlyingListener), "PosListener")

      // Create Positions actor
      val positions = TestFSMRef(new Positions(dataStream), "Positions")
      positions ! BindPositions

      Thread.sleep(1000)

      // Open Listener in Combined mode
      listener ! Listener.Open(ReplicationParams(ReplicationMode.Combined))

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}