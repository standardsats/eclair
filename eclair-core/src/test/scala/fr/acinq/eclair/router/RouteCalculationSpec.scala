/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Satoshi}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{RichWeight, WeightRatios}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiryDelta, Globals, MilliSatoshi, ShortChannelId, randomKey}
import org.scalatest.FunSuite
import scodec.bits._

import scala.collection.immutable.SortedMap
import scala.util.{Failure, Success}

/**
 * Created by PM on 31/05/2016.
 */

class RouteCalculationSpec extends FunSuite {

  import RouteCalculationSpec._

  val (a, b, c, d, e, f) = (randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)

  test("calculate simple route") {

    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(1), 10, cltvDelta = CltvExpiryDelta(1)),
      makeUpdate(2L, b, c, MilliSatoshi(1), 10, cltvDelta = CltvExpiryDelta(1)),
      makeUpdate(3L, c, d, MilliSatoshi(1), 10, cltvDelta = CltvExpiryDelta(1)),
      makeUpdate(4L, d, e, MilliSatoshi(1), 10, cltvDelta = CltvExpiryDelta(1))
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)

    assert(route.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("check fee against max pct properly") {

    // fee is acceptable is it is either
    // - below our maximum fee base
    // - below our maximum fraction of the paid amount

    // here we have a maximum fee base of 1 msat, and all our updates have a base fee of 10 msat
    // so our fee will always be above the base fee, and we will always check that it is below our maximum percentage
    // of the amount being paid

    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(10), 10, cltvDelta = CltvExpiryDelta(1)),
      makeUpdate(2L, b, c, MilliSatoshi(10), 10, cltvDelta = CltvExpiryDelta(1)),
      makeUpdate(3L, c, d, MilliSatoshi(10), 10, cltvDelta = CltvExpiryDelta(1)),
      makeUpdate(4L, d, e, MilliSatoshi(10), 10, cltvDelta = CltvExpiryDelta(1))
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(maxFeeBase = MilliSatoshi(1)))

    assert(route.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("calculate the shortest path (correct fees)") {

    val (a, b, c, d, e, f) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // a: source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), // d: target
      PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      PublicKey(hex"020c65be6f9252e85ae2fe9a46eed892cb89565e2157730e78311b1621a0db4b22")
    )

    // note: we don't actually use floating point numbers
    // cost(CD) = 10005 = amountMsat + 1 + (amountMsat * 400 / 1000000)
    // cost(BC) = 10009,0015 = (cost(CD) + 1 + (cost(CD) * 300 / 1000000)
    // cost(FD) = 10002 = amountMsat + 1 + (amountMsat * 100 / 1000000)
    // cost(EF) = 10007,0008 = cost(FD) + 1 + (cost(FD) * 400 / 1000000)
    // cost(AE) = 10007 -> A is source, shortest path found
    // cost(AB) = 10009

    val amount = MilliSatoshi(10000)
    val expectedCost = MilliSatoshi(10007)

    val updates = List(
      makeUpdate(1L, a, b, feeBase = MilliSatoshi(1), feeProportionalMillionth = 200, minHtlc = MilliSatoshi(0)),
      makeUpdate(4L, a, e, feeBase = MilliSatoshi(1), feeProportionalMillionth = 200, minHtlc = MilliSatoshi(0)),
      makeUpdate(2L, b, c, feeBase = MilliSatoshi(1), feeProportionalMillionth = 300, minHtlc = MilliSatoshi(0)),
      makeUpdate(3L, c, d, feeBase = MilliSatoshi(1), feeProportionalMillionth = 400, minHtlc = MilliSatoshi(0)),
      makeUpdate(5L, e, f, feeBase = MilliSatoshi(1), feeProportionalMillionth = 400, minHtlc = MilliSatoshi(0)),
      makeUpdate(6L, f, d, feeBase = MilliSatoshi(1), feeProportionalMillionth = 100, minHtlc = MilliSatoshi(0))
    ).toMap

    val graph = makeGraph(updates)

    val Success(route) = Router.findRoute(graph, a, d, amount, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)

    val totalCost = Graph.pathWeight(hops2Edges(route), amount, false, 0, None).cost

    assert(hops2Ids(route) === 4 :: 5 :: 6 :: Nil)
    assert(totalCost === expectedCost)

    // now channel 5 could route the amount (10000) but not the amount + fees (10007)
    val (desc, update) = makeUpdate(5L, e, f, feeBase = MilliSatoshi(1), feeProportionalMillionth = 400, minHtlc = MilliSatoshi(0), maxHtlc = Some(MilliSatoshi(10005L)))
    val graph1 = graph.addEdge(desc, update)

    val Success(route1) = Router.findRoute(graph1, a, d, amount, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)

    assert(hops2Ids(route1) === 1 :: 2 :: 3 :: Nil)
  }

  test("calculate route considering the direct channel pays no fees") {
    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(5), 0), // a -> b
      makeUpdate(2L, a, d, MilliSatoshi(15), 0), // a -> d  this goes a bit closer to the target and asks for higher fees but is a direct channel
      makeUpdate(3L, b, c, MilliSatoshi(5), 0), // b -> c
      makeUpdate(4L, c, d, MilliSatoshi(5), 0), // c -> d
      makeUpdate(5L, d, e, MilliSatoshi(5), 0) // d -> e
    ).toMap

    val g = makeGraph(updates)
    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)

    assert(route.map(hops2Ids) === Success(2 :: 5 :: Nil))
  }

  test("calculate simple route (add and remove edges") {

    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(0), 0),
      makeUpdate(2L, b, c, MilliSatoshi(0), 0),
      makeUpdate(3L, c, d, MilliSatoshi(0), 0),
      makeUpdate(4L, d, e, MilliSatoshi(0), 0)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))

    val graphWithRemovedEdge = g.removeEdge(ChannelDesc(ShortChannelId(3L), c, d))
    val route2 = Router.findRoute(graphWithRemovedEdge, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route2.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("calculate the shortest path (hardcoded nodes)") {

    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // target
    )

    val updates = List(
      makeUpdate(1L, f, g, MilliSatoshi(0), 0),
      makeUpdate(2L, g, h, MilliSatoshi(0), 0),
      makeUpdate(3L, h, i, MilliSatoshi(0), 0),
      makeUpdate(4L, f, h, MilliSatoshi(50), 0) // more expensive
    ).toMap

    val graph = makeGraph(updates)

    val route = Router.findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Success(4 :: 3 :: Nil))

  }

  test("calculate the shortest path (select direct channel)") {

    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // target
    )

    val updates = List(
      makeUpdate(1L, f, g, MilliSatoshi(0), 0),
      makeUpdate(4L, f, i, MilliSatoshi(50), 0), // our starting node F has a direct channel with I
      makeUpdate(2L, g, h, MilliSatoshi(0), 0),
      makeUpdate(3L, h, i, MilliSatoshi(0), 0)
    ).toMap

    val graph = makeGraph(updates)

    val route = Router.findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, numRoutes = 2, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Success(4 :: Nil))
  }

  test("find a route using channels with htlMaximumMsat close to the payment amount") {
    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
    )

    val updates = List(
      makeUpdate(1L, f, g, MilliSatoshi(1), 0),
      // the maximum htlc allowed by this channel is only 50msat greater than what we're sending
      makeUpdate(2L, g, h, MilliSatoshi(1), 0, maxHtlc = Some(DEFAULT_AMOUNT_MSAT + MilliSatoshi(50))),
      makeUpdate(3L, h, i, MilliSatoshi(1), 0)
    ).toMap

    val graph = makeGraph(updates)

    val route = Router.findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) == Success(1 :: 2 :: 3 :: Nil))
  }

  test("find a route using channels with htlMinimumMsat close to the payment amount") {
    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
    )

    val updates = List(
      makeUpdate(1L, f, g, MilliSatoshi(1), 0),
      // this channel requires a minimum amount that is larger than what we are sending
      makeUpdate(2L, g, h, MilliSatoshi(1), 0, minHtlc = DEFAULT_AMOUNT_MSAT + MilliSatoshi(50)),
      makeUpdate(3L, h, i, MilliSatoshi(1), 0)
    ).toMap

    val graph = makeGraph(updates)

    val route = Router.findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("if there are multiple channels between the same node, select the cheapest") {

    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
    )

    val updates = List(
      makeUpdate(1L, f, g, MilliSatoshi(0), 0),
      makeUpdate(2L, g, h, MilliSatoshi(5), 5), // expensive  g -> h channel
      makeUpdate(6L, g, h, MilliSatoshi(0), 0), // cheap      g -> h channel
      makeUpdate(3L, h, i, MilliSatoshi(0), 0)
    ).toMap

    val graph = makeGraph(updates)

    val route = Router.findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Success(1 :: 6 :: 3 :: Nil))
  }

  test("calculate longer but cheaper route") {

    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(0), 0),
      makeUpdate(2L, b, c, MilliSatoshi(0), 0),
      makeUpdate(3L, c, d, MilliSatoshi(0), 0),
      makeUpdate(4L, d, e, MilliSatoshi(0), 0),
      makeUpdate(5L, b, e, MilliSatoshi(10), 10)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("no local channels") {

    val updates = List(
      makeUpdate(2L, b, c, MilliSatoshi(0), 0),
      makeUpdate(4L, d, e, MilliSatoshi(0), 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("route not found") {

    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(0), 0),
      makeUpdate(2L, b, c, MilliSatoshi(0), 0),
      makeUpdate(4L, d, e, MilliSatoshi(0), 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("route not found (source OR target node not connected)") {

    val updates = List(
      makeUpdate(2L, b, c, MilliSatoshi(0), 0),
      makeUpdate(4L, c, d, MilliSatoshi(0), 0)
    ).toMap

    val g = makeGraph(updates).addVertex(a).addVertex(e)

    assert(Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS) === Failure(RouteNotFound))
    assert(Router.findRoute(g, b, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS) === Failure(RouteNotFound))
  }

  test("route not found (amount too high OR too low)") {

    val highAmount = DEFAULT_AMOUNT_MSAT * 10
    val lowAmount = DEFAULT_AMOUNT_MSAT / 10

    val updatesHi = List(
      makeUpdate(1L, a, b, MilliSatoshi(0), 0),
      makeUpdate(2L, b, c, MilliSatoshi(0), 0, maxHtlc = Some(DEFAULT_AMOUNT_MSAT)),
      makeUpdate(3L, c, d, MilliSatoshi(0), 0)
    ).toMap

    val updatesLo = List(
      makeUpdate(1L, a, b, MilliSatoshi(0), 0),
      makeUpdate(2L, b, c, MilliSatoshi(0), 0, minHtlc = DEFAULT_AMOUNT_MSAT),
      makeUpdate(3L, c, d, MilliSatoshi(0), 0)
    ).toMap

    val g = makeGraph(updatesHi)
    val g1 = makeGraph(updatesLo)

    assert(Router.findRoute(g, a, d, highAmount, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS) === Failure(RouteNotFound))
    assert(Router.findRoute(g1, a, d, lowAmount, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS) === Failure(RouteNotFound))
  }

  test("route to self") {

    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(0), 0),
      makeUpdate(2L, b, c, MilliSatoshi(0), 0),
      makeUpdate(3L, c, d, MilliSatoshi(0), 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, a, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Failure(CannotRouteToSelf))
  }

  test("route to immediate neighbor") {

    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(0), 0),
      makeUpdate(2L, b, c, MilliSatoshi(0), 0),
      makeUpdate(3L, c, d, MilliSatoshi(0), 0),
      makeUpdate(4L, d, e, MilliSatoshi(0), 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, b, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Success(1 :: Nil))
  }

  test("directed graph") {
    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(0), 0),
      makeUpdate(2L, b, c, MilliSatoshi(0), 0),
      makeUpdate(3L, c, d, MilliSatoshi(0), 0),
      makeUpdate(4L, d, e, MilliSatoshi(0), 0)
    ).toMap

    // a->e works, e->a fails

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))

    val route2 = Router.findRoute(g, e, a, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route2.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("calculate route and return metadata") {

    val DUMMY_SIG = Transactions.PlaceHolderSig

    val uab = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(1L), 0L, 0, 0, CltvExpiryDelta(1), MilliSatoshi(42), MilliSatoshi(2500), 140, None)
    val uba = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(1L), 1L, 0, 1, CltvExpiryDelta(1), MilliSatoshi(43), MilliSatoshi(2501), 141, None)
    val ubc = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(2L), 1L, 0, 0, CltvExpiryDelta(1), MilliSatoshi(44), MilliSatoshi(2502), 142, None)
    val ucb = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(2L), 1L, 0, 1, CltvExpiryDelta(1), MilliSatoshi(45), MilliSatoshi(2503), 143, None)
    val ucd = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(3L), 1L, 1, 0, CltvExpiryDelta(1), MilliSatoshi(46), MilliSatoshi(2504), 144, Some(MilliSatoshi(500000000L)))
    val udc = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(3L), 1L, 0, 1, CltvExpiryDelta(1), MilliSatoshi(47), MilliSatoshi(2505), 145, None)
    val ude = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(4L), 1L, 0, 0, CltvExpiryDelta(1), MilliSatoshi(48), MilliSatoshi(2506), 146, None)
    val ued = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(4L), 1L, 0, 1, CltvExpiryDelta(1), MilliSatoshi(49), MilliSatoshi(2507), 147, None)

    val updates = Map(
      ChannelDesc(ShortChannelId(1L), a, b) -> uab,
      ChannelDesc(ShortChannelId(1L), b, a) -> uba,
      ChannelDesc(ShortChannelId(2L), b, c) -> ubc,
      ChannelDesc(ShortChannelId(2L), c, b) -> ucb,
      ChannelDesc(ShortChannelId(3L), c, d) -> ucd,
      ChannelDesc(ShortChannelId(3L), d, c) -> udc,
      ChannelDesc(ShortChannelId(4L), d, e) -> ude,
      ChannelDesc(ShortChannelId(4L), e, d) -> ued
    )

    val g = makeGraph(updates)

    val hops = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS).get

    assert(hops === Hop(a, b, uab) :: Hop(b, c, ubc) :: Hop(c, d, ucd) :: Hop(d, e, ude) :: Nil)
  }

  test("convert extra hops to channel_update") {
    val a = randomKey.publicKey
    val b = randomKey.publicKey
    val c = randomKey.publicKey
    val d = randomKey.publicKey
    val e = randomKey.publicKey

    val extraHop1 = ExtraHop(a, ShortChannelId(1), 10, 11, CltvExpiryDelta(12))
    val extraHop2 = ExtraHop(b, ShortChannelId(2), 20, 21, CltvExpiryDelta(22))
    val extraHop3 = ExtraHop(c, ShortChannelId(3), 30, 31, CltvExpiryDelta(32))
    val extraHop4 = ExtraHop(d, ShortChannelId(4), 40, 41, CltvExpiryDelta(42))

    val extraHops = extraHop1 :: extraHop2 :: extraHop3 :: extraHop4 :: Nil

    val fakeUpdates: Map[ShortChannelId, ExtraHop] = Router.toAssistedChannels(extraHops, e).map { case (shortChannelId, assistedChannel) =>
      (shortChannelId, assistedChannel.extraHop)
    }

    assert(fakeUpdates == Map(
      extraHop1.shortChannelId -> extraHop1,
      extraHop2.shortChannelId -> extraHop2,
      extraHop3.shortChannelId -> extraHop3,
      extraHop4.shortChannelId -> extraHop4
    ))

  }

  test("blacklist routes") {
    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(0), 0),
      makeUpdate(2L, b, c, MilliSatoshi(0), 0),
      makeUpdate(3L, c, d, MilliSatoshi(0), 0),
      makeUpdate(4L, d, e, MilliSatoshi(0), 0)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, ignoredEdges = Set(ChannelDesc(ShortChannelId(3L), c, d)), routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route1.map(hops2Ids) === Failure(RouteNotFound))

    // verify that we left the graph untouched
    assert(g.containsEdge(makeUpdate(3L, c, d, MilliSatoshi(0), 0)._1)) // c -> d
    assert(g.containsVertex(c))
    assert(g.containsVertex(d))

    // make sure we can find a route if without the blacklist
    val route2 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route2.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("route to a destination that is not in the graph (with assisted routes)") {
    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(10), 10),
      makeUpdate(2L, b, c, MilliSatoshi(10), 10),
      makeUpdate(3L, c, d, MilliSatoshi(10), 10)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))

    // now we add the missing edge to reach the destination
    val (extraDesc, extraUpdate) = makeUpdate(4L, d, e, MilliSatoshi(5), 5)
    val extraGraphEdges = Set(GraphEdge(extraDesc, extraUpdate))

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, extraEdges = extraGraphEdges, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }


  test("verify that extra hops takes precedence over known channels") {
    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(10), 10),
      makeUpdate(2L, b, c, MilliSatoshi(10), 10),
      makeUpdate(3L, c, d, MilliSatoshi(10), 10),
      makeUpdate(4L, d, e, MilliSatoshi(10), 10)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
    assert(route1.get(1).lastUpdate.feeBaseMsat == MilliSatoshi(10))

    val (extraDesc, extraUpdate) = makeUpdate(2L, b, c, MilliSatoshi(5), 5)

    val extraGraphEdges = Set(GraphEdge(extraDesc, extraUpdate))

    val route2 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, extraEdges = extraGraphEdges, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route2.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
    assert(route2.get(1).lastUpdate.feeBaseMsat == MilliSatoshi(5))
  }

  test("compute ignored channels") {

    val f = randomKey.publicKey
    val g = randomKey.publicKey
    val h = randomKey.publicKey
    val i = randomKey.publicKey
    val j = randomKey.publicKey

    val channels = Map(
      ShortChannelId(1L) -> makeChannel(1L, a, b),
      ShortChannelId(2L) -> makeChannel(2L, b, c),
      ShortChannelId(3L) -> makeChannel(3L, c, d),
      ShortChannelId(4L) -> makeChannel(4L, d, e),
      ShortChannelId(5L) -> makeChannel(5L, f, g),
      ShortChannelId(6L) -> makeChannel(6L, f, h),
      ShortChannelId(7L) -> makeChannel(7L, h, i),
      ShortChannelId(8L) -> makeChannel(8L, i, j)
    )

    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(10), 10),
      makeUpdate(2L, b, c, MilliSatoshi(10), 10),
      makeUpdate(2L, c, b, MilliSatoshi(10), 10),
      makeUpdate(3L, c, d, MilliSatoshi(10), 10),
      makeUpdate(4L, d, e, MilliSatoshi(10), 10),
      makeUpdate(5L, f, g, MilliSatoshi(10), 10),
      makeUpdate(6L, f, h, MilliSatoshi(10), 10),
      makeUpdate(7L, h, i, MilliSatoshi(10), 10),
      makeUpdate(8L, i, j, MilliSatoshi(10), 10)
    ).toMap

    val publicChannels = channels.map { case (shortChannelId, announcement) =>
      val (_, update) = updates.find{ case (d, u) => d.shortChannelId == shortChannelId}.get
      val (update_1_opt, update_2_opt) = if (Announcements.isNode1(update.channelFlags)) (Some(update), None) else (None, Some(update))
      val pc = PublicChannel(announcement, ByteVector32.Zeroes, Satoshi(1000), update_1_opt, update_2_opt)
      (shortChannelId, pc)
    }


    val ignored = Router.getIgnoredChannelDesc(publicChannels, ignoreNodes = Set(c, j, randomKey.publicKey))

    assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(2L), b, c)))
    assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(2L), c, b)))
    assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(3L), c, d)))
    assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(8L), i, j)))
  }

  test("limit routes to 20 hops") {

    val nodes = (for (_ <- 0 until 22) yield randomKey.publicKey).toList

    val updates = nodes
      .zip(nodes.drop(1)) // (0, 1) :: (1, 2) :: ...
      .zipWithIndex // ((0, 1), 0) :: ((1, 2), 1) :: ...
      .map { case ((na, nb), index) => makeUpdate(index, na, nb, MilliSatoshi(5), 0) }
      .toMap

    val g = makeGraph(updates)

    assert(Router.findRoute(g, nodes(0), nodes(18), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS).map(hops2Ids) === Success(0 until 18))
    assert(Router.findRoute(g, nodes(0), nodes(19), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS).map(hops2Ids) === Success(0 until 19))
    assert(Router.findRoute(g, nodes(0), nodes(20), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS).map(hops2Ids) === Success(0 until 20))
    assert(Router.findRoute(g, nodes(0), nodes(21), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS).map(hops2Ids) === Failure(RouteNotFound))
  }

  test("ignore cheaper route when it has more than 20 hops") {

    val nodes = (for (_ <- 0 until 50) yield randomKey.publicKey).toList

    val updates = nodes
      .zip(nodes.drop(1)) // (0, 1) :: (1, 2) :: ...
      .zipWithIndex // ((0, 1), 0) :: ((1, 2), 1) :: ...
      .map { case ((na, nb), index) => makeUpdate(index, na, nb, MilliSatoshi(1), 0) }
      .toMap

    val updates2 = updates + makeUpdate(99, nodes(2), nodes(48), MilliSatoshi(1000), 0) // expensive shorter route

    val g = makeGraph(updates2)

    val route = Router.findRoute(g, nodes(0), nodes(49), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Success(0 :: 1 :: 99 :: 48 :: Nil))
  }

  test("ignore cheaper route when it has more than the requested CLTV") {

    val f = randomKey.publicKey

    val g = makeGraph(List(
      makeUpdate(1, a, b, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(50)),
      makeUpdate(2, b, c, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(50)),
      makeUpdate(3, c, d, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(50)),
      makeUpdate(4, a, e, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(5, e, f, feeBase = MilliSatoshi(5), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(6, f, d, feeBase = MilliSatoshi(5), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(9))
    ).toMap)

    val route = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(routeMaxCltv = CltvExpiryDelta(28)))
    assert(route.map(hops2Ids) === Success(4 :: 5 :: 6 :: Nil))
  }

  test("ignore cheaper route when it grows longer than the requested size") {

    val f = randomKey.publicKey

    val g = makeGraph(List(
      makeUpdate(1, a, b, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(2, b, c, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(3, c, d, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(4, d, e, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(5, e, f, feeBase = MilliSatoshi(5), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(6, b, f, feeBase = MilliSatoshi(5), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(9))
    ).toMap)

    val route = Router.findRoute(g, a, f, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(routeMaxLength = 3))
    assert(route.map(hops2Ids) === Success(1 :: 6 :: Nil))
  }

  test("ignore loops") {

    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(10), 10),
      makeUpdate(2L, b, c, MilliSatoshi(10), 10),
      makeUpdate(3L, c, a, MilliSatoshi(10), 10),
      makeUpdate(4L, c, d, MilliSatoshi(10), 10),
      makeUpdate(5L, d, e, MilliSatoshi(10), 10)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 4 :: 5 :: Nil))
  }

  test("ensure the route calculation terminates correctly when selecting 0-fees edges") {

    // the graph contains a possible 0-cost path that goes back on its steps ( e -> f, f -> e )
    val updates = List(
      makeUpdate(1L, a, b, MilliSatoshi(10), 10), // a -> b
      makeUpdate(2L, b, c, MilliSatoshi(10), 10),
      makeUpdate(4L, c, d, MilliSatoshi(10), 10),
      makeUpdate(3L, b, e, MilliSatoshi(0), 0), // b -> e
      makeUpdate(6L, e, f, MilliSatoshi(0), 0), // e -> f
      makeUpdate(6L, f, e, MilliSatoshi(0), 0), // e <- f
      makeUpdate(5L, e, d, MilliSatoshi(0), 0) // e -> d
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route1.map(hops2Ids) === Success(1 :: 3 :: 5 :: Nil))
  }

  /**
   *
   * +---+            +---+            +---+
   * | A +-----+      | B +----------> | C |
   * +-+-+     |      +-+-+            +-+-+
   * ^       |        ^                |
   * |       |        |                |
   * |       v----> + |                |
   * +-+-+            <-+-+            +-+-+
   * | D +----------> | E +----------> | F |
   * +---+            +---+            +---+
   *
   */
  test("find the k-shortest paths in a graph, k=4") {

    val (a, b, c, d, e, f) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), //a
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), //b
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), //c
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), //d
      PublicKey(hex"02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a"), //e
      PublicKey(hex"03fc5b91ce2d857f146fd9b986363374ffe04dc143d8bcd6d7664c8873c463cdfc") //f
    )


    val edges = Seq(
      makeUpdate(1L, d, a, MilliSatoshi(1), 0),
      makeUpdate(2L, d, e, MilliSatoshi(1), 0),
      makeUpdate(3L, a, e, MilliSatoshi(1), 0),
      makeUpdate(4L, e, b, MilliSatoshi(1), 0),
      makeUpdate(5L, e, f, MilliSatoshi(1), 0),
      makeUpdate(6L, b, c, MilliSatoshi(1), 0),
      makeUpdate(7L, c, f, MilliSatoshi(1), 0)
    )

    val graph = DirectedGraph().addEdges(edges)

    val fourShortestPaths = Graph.yenKshortestPaths(graph, d, f, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 4, None, 0, noopBoundaries)

    assert(fourShortestPaths.size === 4)
    assert(hops2Ids(fourShortestPaths(0).path.map(graphEdgeToHop)) === 2 :: 5 :: Nil) // D -> E -> F
    assert(hops2Ids(fourShortestPaths(1).path.map(graphEdgeToHop)) === 1 :: 3 :: 5 :: Nil) // D -> A -> E -> F
    assert(hops2Ids(fourShortestPaths(2).path.map(graphEdgeToHop)) === 2 :: 4 :: 6 :: 7 :: Nil) // D -> E -> B -> C -> F
    assert(hops2Ids(fourShortestPaths(3).path.map(graphEdgeToHop)) === 1 :: 3 :: 4 :: 6 :: 7 :: Nil) // D -> A -> E -> B -> C -> F
  }

  test("find the k shortest path (wikipedia example)") {
    val (c, d, e, f, g, h) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), //c
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), //d
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), //e
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), //f
      PublicKey(hex"02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a"), //g
      PublicKey(hex"03fc5b91ce2d857f146fd9b986363374ffe04dc143d8bcd6d7664c8873c463cdfc") //h
    )


    val edges = Seq(
      makeUpdate(10L, c, e, MilliSatoshi(2), 0),
      makeUpdate(20L, c, d, MilliSatoshi(3), 0),
      makeUpdate(30L, d, f, MilliSatoshi(4), 5), // D- > F has a higher cost to distinguish it from the 2nd cheapest route
      makeUpdate(40L, e, d, MilliSatoshi(1), 0),
      makeUpdate(50L, e, f, MilliSatoshi(2), 0),
      makeUpdate(60L, e, g, MilliSatoshi(3), 0),
      makeUpdate(70L, f, g, MilliSatoshi(2), 0),
      makeUpdate(80L, f, h, MilliSatoshi(1), 0),
      makeUpdate(90L, g, h, MilliSatoshi(2), 0)
    )

    val graph = DirectedGraph().addEdges(edges)

    val twoShortestPaths = Graph.yenKshortestPaths(graph, c, h, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 2, None, 0, noopBoundaries)

    assert(twoShortestPaths.size === 2)
    val shortest = twoShortestPaths(0)
    assert(hops2Ids(shortest.path.map(graphEdgeToHop)) === 10 :: 50 :: 80 :: Nil) // C -> E -> F -> H

    val secondShortest = twoShortestPaths(1)
    assert(hops2Ids(secondShortest.path.map(graphEdgeToHop)) === 10 :: 60 :: 90 :: Nil) // C -> E -> G -> H
  }

  test("terminate looking for k-shortest path if there are no more alternative paths than k, must not consider routes going back on their steps") {

    val f = randomKey.publicKey

    // simple graph with only 2 possible paths from A to F
    val edges = Seq(
      makeUpdate(1L, a, b, MilliSatoshi(1), 0),
      makeUpdate(1L, b, a, MilliSatoshi(1), 0),
      makeUpdate(2L, b, c, MilliSatoshi(1), 0),
      makeUpdate(2L, c, b, MilliSatoshi(1), 0),
      makeUpdate(3L, c, f, MilliSatoshi(1), 0),
      makeUpdate(3L, f, c, MilliSatoshi(1), 0),
      makeUpdate(4L, c, d, MilliSatoshi(1), 0),
      makeUpdate(4L, d, c, MilliSatoshi(1), 0),
      makeUpdate(41L, d, c, MilliSatoshi(1), 0), // there is more than one D -> C channel
      makeUpdate(5L, d, e, MilliSatoshi(1), 0),
      makeUpdate(5L, e, d, MilliSatoshi(1), 0),
      makeUpdate(6L, e, f, MilliSatoshi(1), 0),
      makeUpdate(6L, f, e, MilliSatoshi(1), 0)
    )

    val graph = DirectedGraph().addEdges(edges)

    //we ask for 3 shortest paths but only 2 can be found
    val foundPaths = Graph.yenKshortestPaths(graph, a, f, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 3, None, 0, noopBoundaries)

    assert(foundPaths.size === 2)
    assert(hops2Ids(foundPaths(0).path.map(graphEdgeToHop)) === 1 :: 2 :: 3 :: Nil) // A -> B -> C -> F
    assert(hops2Ids(foundPaths(1).path.map(graphEdgeToHop)) === 1 :: 2 :: 4 :: 5 :: 6 :: Nil) // A -> B -> C -> D -> E -> F
  }

  test("select a random route below the requested fee") {

    val strictFeeParams = DEFAULT_ROUTE_PARAMS.copy(maxFeeBase = MilliSatoshi(7), maxFeePct = 0)

    // A -> B -> C -> D has total cost of 10000005
    // A -> E -> C -> D has total cost of 11080003 !!
    // A -> E -> F -> D has total cost of 10000006
    val g = makeGraph(List(
      makeUpdate(1L, a, b, feeBase = MilliSatoshi(1), 0),
      makeUpdate(4L, a, e, feeBase = MilliSatoshi(1), 0),
      makeUpdate(2L, b, c, feeBase = MilliSatoshi(2), 0),
      makeUpdate(3L, c, d, feeBase = MilliSatoshi(3), 0),
      makeUpdate(5L, e, f, feeBase = MilliSatoshi(3), 0),
      makeUpdate(6L, f, d, feeBase = MilliSatoshi(3), 0),
      makeUpdate(7L, e, c, feeBase = MilliSatoshi(9), 0)
    ).toMap)

    (for {_ <- 0 to 10} yield Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 3, routeParams = strictFeeParams)).map {
      case Failure(thr) => assert(false, thr)
      case Success(someRoute) =>

        val routeCost = Graph.pathWeight(hops2Edges(someRoute), DEFAULT_AMOUNT_MSAT, isPartial = false, 0, None).cost - DEFAULT_AMOUNT_MSAT

        // over the three routes we could only get the 2 cheapest because the third is too expensive (over 7msat of fees)
        assert(routeCost == MilliSatoshi(5) || routeCost == MilliSatoshi(6))
    }
  }

  test("Use weight ratios to when computing the edge weight") {

    val largeCapacity = MilliSatoshi(8000000000L)

    // A -> B -> C -> D is 'fee optimized', lower fees route (totFees = 2, totCltv = 4000)
    // A -> E -> F -> D is 'timeout optimized', lower CLTV route (totFees = 3, totCltv = 18)
    // A -> E -> C -> D is 'capacity optimized', more recent channel/larger capacity route
    val updates = List(
      makeUpdate(1L, a, b, feeBase = MilliSatoshi(0), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(13)),
      makeUpdate(4L, a, e, feeBase = MilliSatoshi(0), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(12)),
      makeUpdate(2L, b, c, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(500)),
      makeUpdate(3L, c, d, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(500)),
      makeUpdate(5L, e, f, feeBase = MilliSatoshi(2), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(6L, f, d, feeBase = MilliSatoshi(2), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(7L, e, c, feeBase = MilliSatoshi(2), 0, minHtlc = MilliSatoshi(0), maxHtlc = Some(largeCapacity), CltvExpiryDelta(12))
    ).toMap

    val g = makeGraph(updates)

    val Success(routeFeeOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 0, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(hops2Nodes(routeFeeOptimized) === (a, b) :: (b, c) :: (c, d) :: Nil)

    val Success(routeCltvOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 0, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      cltvDeltaFactor = 1,
      ageFactor = 0,
      capacityFactor = 0
    ))))

    assert(hops2Nodes(routeCltvOptimized) === (a, e) :: (e, f) :: (f, d) :: Nil)

    val Success(routeCapacityOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 0, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      cltvDeltaFactor = 0,
      ageFactor = 0,
      capacityFactor = 1
    ))))

    assert(hops2Nodes(routeCapacityOptimized) === (a, e) :: (e, c) :: (c, d) :: Nil)
  }

  test("prefer going through an older channel if fees and CLTV are the same") {

    val currentBlockHeight = 554000

    val g = makeGraph(List(
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight}x0x1"), a, b, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight}x0x4"), a, e, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight - 3000}x0x2"), b, c, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(144)), // younger channel
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight - 3000}x0x3"), c, d, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight}x0x5"), e, f, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight}x0x6"), f, d, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(144))
    ).toMap)

    Globals.blockCount.set(currentBlockHeight)

    val Success(routeScoreOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT / 2, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      ageFactor = 0.33,
      cltvDeltaFactor = 0.33,
      capacityFactor = 0.33
    ))))

    assert(hops2Nodes(routeScoreOptimized) === (a, b) :: (b, c) :: (c, d) :: Nil)
  }

  test("prefer a route with a smaller total CLTV if fees and score are the same") {

    val g = makeGraph(List(
      makeUpdateShort(ShortChannelId(s"0x0x1"), a, b, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
      makeUpdateShort(ShortChannelId(s"0x0x4"), a, e, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
      makeUpdateShort(ShortChannelId(s"0x0x2"), b, c, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(10)), // smaller CLTV
      makeUpdateShort(ShortChannelId(s"0x0x3"), c, d, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
      makeUpdateShort(ShortChannelId(s"0x0x5"), e, f, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
      makeUpdateShort(ShortChannelId(s"0x0x6"), f, d, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(12))
    ).toMap)


    val Success(routeScoreOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      ageFactor = 0.33,
      cltvDeltaFactor = 0.33,
      capacityFactor = 0.33
    ))))

    assert(hops2Nodes(routeScoreOptimized) === (a, b) :: (b, c) :: (c, d) :: Nil)
  }


  test("avoid a route that breaks off the max CLTV") {

    // A -> B -> C -> D is cheaper but has a total CLTV > 2016!
    // A -> E -> F -> D is more expensive but has a total CLTV < 2016
    val g = makeGraph(List(
      makeUpdateShort(ShortChannelId(s"0x0x1"), a, b, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"0x0x4"), a, e, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"0x0x2"), b, c, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(1000)),
      makeUpdateShort(ShortChannelId(s"0x0x3"), c, d, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(900)),
      makeUpdateShort(ShortChannelId(s"0x0x5"), e, f, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"0x0x6"), f, d, feeBase = MilliSatoshi(1), 0, minHtlc = MilliSatoshi(0), maxHtlc = None, cltvDelta = CltvExpiryDelta(144))
    ).toMap)

    val Success(routeScoreOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT / 2, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      ageFactor = 0.33,
      cltvDeltaFactor = 0.33,
      capacityFactor = 0.33
    ))))

    assert(hops2Nodes(routeScoreOptimized) === (a, e) :: (e, f) :: (f, d) :: Nil)
  }

  test("cost function is monotonic") {

    // This test have a channel (542280x2156x0) that according to heuristics is very convenient but actually useless to reach the target,
    // then if the cost function is not monotonic the path-finding breaks because the result path contains a loop.
    val updates = SortedMap(
      ShortChannelId("565643x1216x0") -> PublicChannel(
        ann = makeChannel(ShortChannelId("565643x1216x0").toLong, PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca")),
        fundingTxid = ByteVector32.Zeroes,
        capacity = Satoshi(0),
        update_1_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("565643x1216x0"), 0, 1.toByte, 0.toByte, CltvExpiryDelta(14), htlcMinimumMsat = MilliSatoshi(1), feeBaseMsat = MilliSatoshi(1000), 10, Some(MilliSatoshi(4294967295L)))),
        update_2_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("565643x1216x0"), 0, 1.toByte, 1.toByte, CltvExpiryDelta(144), htlcMinimumMsat = MilliSatoshi(0), feeBaseMsat = MilliSatoshi(1000), 100, Some(MilliSatoshi(15000000000L))))
      ),
      ShortChannelId("542280x2156x0") -> PublicChannel(
        ann = makeChannel(ShortChannelId("542280x2156x0").toLong, PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), PublicKey(hex"03cb7983dc247f9f81a0fa2dfa3ce1c255365f7279c8dd143e086ca333df10e278")),
        fundingTxid = ByteVector32.Zeroes,
        capacity = Satoshi(0),
        update_1_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("542280x2156x0"), 0, 1.toByte, 0.toByte, CltvExpiryDelta(144), htlcMinimumMsat = MilliSatoshi(1000), feeBaseMsat = MilliSatoshi(1000), 100, Some(MilliSatoshi(16777000000L)))),
        update_2_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("542280x2156x0"), 0, 1.toByte, 1.toByte, CltvExpiryDelta(144), htlcMinimumMsat = MilliSatoshi(1), feeBaseMsat = MilliSatoshi(667), 1, Some(MilliSatoshi(16777000000L))))
      ),
      ShortChannelId("565779x2711x0") -> PublicChannel(
        ann = makeChannel(ShortChannelId("565779x2711x0").toLong, PublicKey(hex"036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96"), PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f")),
        fundingTxid = ByteVector32.Zeroes,
        capacity = Satoshi(0),
        update_1_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("565779x2711x0"), 0, 1.toByte, 0.toByte, CltvExpiryDelta(144), htlcMinimumMsat = MilliSatoshi(1), feeBaseMsat = MilliSatoshi(1000), 100, Some(MilliSatoshi(230000000L)))),
        update_2_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("565779x2711x0"), 0, 1.toByte, 3.toByte, CltvExpiryDelta(144), htlcMinimumMsat = MilliSatoshi(1), feeBaseMsat = MilliSatoshi(1000), 100, Some(MilliSatoshi(230000000L))))
      )
    )

    val g = DirectedGraph.makeGraph(updates)

    val params = RouteParams(randomize = false, maxFeeBase = MilliSatoshi(21000), maxFeePct = 0.03, routeMaxCltv = CltvExpiryDelta(1008), routeMaxLength = 6, ratios = Some(
      WeightRatios(cltvDeltaFactor = 0.15, ageFactor = 0.35, capacityFactor = 0.5)
    ))
    val thisNode = PublicKey(hex"036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96")
    val targetNode = PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca")
    val amount = MilliSatoshi(351000)

    Globals.blockCount.set(567634) // simulate mainnet block for heuristic
    val Success(route) = Router.findRoute(g, thisNode, targetNode, amount, 1, Set.empty, Set.empty, Set.empty, params)

    assert(route.size == 2)
    assert(route.last.nextNodeId == targetNode)
  }
}

object RouteCalculationSpec {

  val noopBoundaries = { _: RichWeight => true }

  val DEFAULT_AMOUNT_MSAT = MilliSatoshi(10000000)

  val DEFAULT_ROUTE_PARAMS = RouteParams(randomize = false, maxFeeBase = MilliSatoshi(21000), maxFeePct = 0.03, routeMaxCltv = CltvExpiryDelta(2016), routeMaxLength = 6, ratios = None)

  val DUMMY_SIG = Transactions.PlaceHolderSig

  def makeChannel(shortChannelId: Long, nodeIdA: PublicKey, nodeIdB: PublicKey) = {
    val (nodeId1, nodeId2) = if (Announcements.isNode1(nodeIdA, nodeIdB)) (nodeIdA, nodeIdB) else (nodeIdB, nodeIdA)
    ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, ByteVector.empty, Block.RegtestGenesisBlock.hash, ShortChannelId(shortChannelId), nodeId1, nodeId2, randomKey.publicKey, randomKey.publicKey)
  }

  def makeUpdate(shortChannelId: Long, nodeId1: PublicKey, nodeId2: PublicKey, feeBase: MilliSatoshi, feeProportionalMillionth: Int, minHtlc: MilliSatoshi = DEFAULT_AMOUNT_MSAT, maxHtlc: Option[MilliSatoshi] = None, cltvDelta: CltvExpiryDelta = CltvExpiryDelta(0)): (ChannelDesc, ChannelUpdate) = {
    makeUpdateShort(ShortChannelId(shortChannelId), nodeId1, nodeId2, feeBase, feeProportionalMillionth, minHtlc, maxHtlc, cltvDelta)
  }

  def makeUpdateShort(shortChannelId: ShortChannelId, nodeId1: PublicKey, nodeId2: PublicKey, feeBase: MilliSatoshi, feeProportionalMillionth: Int, minHtlc: MilliSatoshi = DEFAULT_AMOUNT_MSAT, maxHtlc: Option[MilliSatoshi] = None, cltvDelta: CltvExpiryDelta = CltvExpiryDelta(0), timestamp: Long = 0): (ChannelDesc, ChannelUpdate) =
    ChannelDesc(shortChannelId, nodeId1, nodeId2) -> ChannelUpdate(
      signature = DUMMY_SIG,
      chainHash = Block.RegtestGenesisBlock.hash,
      shortChannelId = shortChannelId,
      timestamp = timestamp,
      messageFlags = maxHtlc match {
        case Some(_) => 1
        case None => 0
      },
      channelFlags = if (Announcements.isNode1(nodeId1, nodeId2)) 0 else 1,
      cltvExpiryDelta = cltvDelta,
      htlcMinimumMsat = minHtlc,
      feeBaseMsat = feeBase,
      feeProportionalMillionths = feeProportionalMillionth,
      htlcMaximumMsat = maxHtlc
    )

  def makeGraph(updates: Map[ChannelDesc, ChannelUpdate]) = DirectedGraph().addEdges(updates.toSeq)

  def hops2Ids(route: Seq[Hop]) = route.map(hop => hop.lastUpdate.shortChannelId.toLong)

  def hops2Edges(route: Seq[Hop]) = route.map(hop => GraphEdge(ChannelDesc(hop.lastUpdate.shortChannelId, hop.nodeId, hop.nextNodeId), hop.lastUpdate))

  def hops2ShortChannelIds(route: Seq[Hop]) = route.map(hop => hop.lastUpdate.shortChannelId.toString).toList

  def hops2Nodes(route: Seq[Hop]) = route.map(hop => (hop.nodeId, hop.nextNodeId))

}
