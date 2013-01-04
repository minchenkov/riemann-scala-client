package net.benmur.riemann.client
import org.scalamock.ProxyMockFactory
import org.scalamock.scalatest.MockFactory
import org.scalatest.FunSuite
import net.benmur.riemann.client.testingsupport.TestingTransportSupport

class EventSenderDSLTest extends FunSuite
    with testingsupport.ImplicitActorSystem
    with MockFactory
    with ProxyMockFactory {

  object DestinationOps extends DestinationOps
  import DestinationOps.RiemannDestination
  import TestingTransportSupport._
  import EventSenderDSL._

  def makeDestination = {
    val conn = new TestingTransportConnection
    val dest = new DestinationOps.RiemannDestination[TestingTransport](EventPart(), conn)
    (conn, dest)
  }

  test("DSL operator to send an event without expecting a result") {
    val (conn, dest) = makeDestination
    implicit val sender = mock[SendOff[EventPart, TestingTransport]]
    sender expects 'sendOff withArguments (conn, Write(event)) once

    event |>> dest
  }

  test("DSL operator to send operator to send an event expecting a status") {
    val (conn, dest) = makeDestination
    implicit val sender = mock[SendAndExpectFeedback[EventPart, Boolean, TestingTransport]]
    sender expects 'send withArguments (conn, Write(event), system, timeout) once

    event |>< dest
  }

  test("DSL operator to send operator to send a query expecting a status") {
    val (conn, dest) = makeDestination
    implicit val sender = mock[SendAndExpectFeedback[Query, Iterable[EventPart], TestingTransport]]

    val q = Query("true")
    sender expects 'send withArguments (conn, Write(q), system, timeout) once

    q |>< dest
  }
}