package net.benmur.riemann.client

import java.io.{ DataInputStream, DataOutputStream }
import java.net.{ Socket, SocketAddress, SocketException }
import com.aphyr.riemann.Proto
import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, OneForOneStrategy, Props }
import akka.actor.SupervisorStrategy._
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import akka.actor.ActorInitializationException
import scala.concurrent.{ExecutionContext, Future}

trait ReliableIO {
  private type ImplementedTransport = Reliable.type
  type Reliable = ImplementedTransport

  private[this] class ReliableConnectionActor(where: SocketAddress, factory: ImplementedTransport#SocketFactory, dispatcherId: Option[String])(implicit system: ActorSystem) extends Actor {
    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 1 second) { // Maybe this needs to be configurable
      case _ => Restart
    }

    val props = {
      val p = Props(new TcpConnectionActor(where, factory))
      if (dispatcherId.isEmpty) p else p.withDispatcher(dispatcherId.get)
    }

    val ioActor = context.actorOf(props, "io")

    def receive = {
      case message => ioActor forward message
    }
  }

  implicit object ReliableEventPartSendAndExpectFeedback extends SendAndExpectFeedback[EventPart, Boolean, ImplementedTransport] with Serializers {
    def send(connection: ImplementedTransport#Connection, command: Write[EventPart])(implicit timeout: Timeout, context: ExecutionContext): Future[Boolean] = {
      val data = serializeEventPartToProtoMsg(command.m).toByteArray
      (connection.ioActor ask WriteBinary(data)).mapTo[Proto.Msg] map (_.getOk)
    }
  }

  implicit object ReliableQuerySendAndExpectFeedback extends SendAndExpectFeedback[Query, Iterable[EventPart], ImplementedTransport] with Serializers {
    def send(connection: ImplementedTransport#Connection, command: Write[Query])(implicit timeout: Timeout, context: ExecutionContext): Future[Iterable[EventPart]] = {
      val data = serializeQueryToProtoMsg(command.m).toByteArray
      (connection.ioActor ask WriteBinary(data)).mapTo[Proto.Msg] map unserializeProtoMsg
    }
  }

  implicit object ReliableSendOff extends SendOff[EventPart, ImplementedTransport] with Serializers {
    def sendOff(connection: ImplementedTransport#Connection, command: Write[EventPart]): Unit = connection match {
      case rc: ReliableConnection =>
        rc.ioActor ! WriteBinary(serializeEventPartToProtoMsg(command.m).toByteArray)
      case c =>
        System.err.println(
          "don't know how to send data to " + c.getClass.getName)
    }
  }

  class TcpConnectionActor(where: SocketAddress, factory: ImplementedTransport#SocketFactory) extends Actor with ActorLogging {
    lazy val connection = factory(where)
    lazy val outputStream = new DataOutputStream(connection.outputStream)
    lazy val inputStream = new DataInputStream(connection.inputStream)

    def receive = {
      case WriteBinary(ab) =>
        try {
          outputStream writeInt ab.length
          outputStream write ab
          outputStream.flush
          val buf = Array.ofDim[Byte](inputStream.readInt())
          inputStream.readFully(buf)
          sender ! Proto.Msg.parseFrom(buf)
        } catch {
          case e: SocketException =>
            throw e
          case exception =>
            log.error(exception, "could not send or receive data")
            val message = Option(exception.getMessage) getOrElse "(no message)"
            sender ! Proto.Msg.newBuilder.setError(message).setOk(false).build
        }
    }
  }

  val makeTcpConnection: ImplementedTransport#SocketFactory = (addr) => {
    val socket = new Socket()
    socket.connect(addr)
    new ImplementedTransport#SocketWrapper {
      override def outputStream = socket.getOutputStream()
      override def inputStream = socket.getInputStream()
    }
  }

  class ReliableConnection(val ioActor: ActorRef) extends ImplementedTransport#Connection

  implicit object TwoWayConnectionBuilder extends ConnectionBuilder[ImplementedTransport] {
    def buildConnection(where: SocketAddress, factory: Option[ImplementedTransport#SocketFactory] = None, dispatcherId: Option[String])(implicit system: ActorSystem, timeout: Timeout): ImplementedTransport#Connection = {
      val props = {
        val p = Props(new ReliableConnectionActor(where, factory getOrElse makeTcpConnection, dispatcherId))
        if (dispatcherId.isEmpty) p else p.withDispatcher(dispatcherId.get)
      }
      new ReliableConnection(system.actorOf(props, io.IO.clientName("riemann-tcp-client-")))
    }
  }
}

object ReliableIO extends ReliableIO
