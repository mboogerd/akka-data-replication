/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication.protobuf

import scala.concurrent.duration._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.contrib.datareplication.Flag
import akka.contrib.datareplication.GCounter
import akka.contrib.datareplication.GSet
import akka.contrib.datareplication.LWWMap
import akka.contrib.datareplication.LWWRegister
import akka.contrib.datareplication.ORMap
import akka.contrib.datareplication.ORMultiMap
import akka.contrib.datareplication.ORSet
import akka.contrib.datareplication.PNCounter
import akka.contrib.datareplication.PNCounterMap
import akka.contrib.datareplication.Replicator._
import akka.contrib.datareplication.Replicator.Internal._
import akka.contrib.datareplication.VectorClock
import akka.testkit.TestKit
import akka.cluster.UniqueAddress
import com.typesafe.config.ConfigFactory

class ReplicatedDataSerializerSpec extends TestKit(ActorSystem("ReplicatedDataSerializerSpec",
  ConfigFactory.parseString("akka.actor.provider=akka.cluster.ClusterActorRefProvider")))
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  val serializer = new ReplicatedDataSerializer(system.asInstanceOf[ExtendedActorSystem])

  val address1 = UniqueAddress(Address("akka.tcp", system.name, "some.host.org", 4711), 1)
  val address2 = UniqueAddress(Address("akka.tcp", system.name, "other.host.org", 4711), 2)
  val address3 = UniqueAddress(Address("akka.tcp", system.name, "some.host.org", 4712), 3)

  override def afterAll {
    shutdown()
  }

  def checkSerialization(obj: AnyRef): Unit = {
    val blob = serializer.toBinary(obj)
    val ref = serializer.fromBinary(blob, obj.getClass)
    ref should be(obj)
  }

  def checkSameContent(a: AnyRef, b: AnyRef): Unit = {
    a should be(b)
    val blobA = serializer.toBinary(a)
    val blobB = serializer.toBinary(b)
    blobA.toSeq should be(blobB.toSeq)
  }

  "ReplicatedDataSerializer" must {

    "serialize GSet" in {
      checkSerialization(GSet())
      checkSerialization(GSet() + "a")
      checkSerialization(GSet() + "a" + "b")

      checkSerialization(GSet() + 1 + 2 + 3)
      checkSerialization(GSet() + address1 + address2)

      checkSerialization(GSet() + 1L + "2" + 3 + address1)

      checkSameContent(GSet() + "a" + "b", GSet() + "a" + "b")
      checkSameContent(GSet() + "a" + "b", GSet() + "b" + "a")
      checkSameContent(GSet() + address1 + address2 + address3, GSet() + address2 + address1 + address3)
      checkSameContent(GSet() + address1 + address2 + address3, GSet() + address3 + address2 + address1)
    }

    "serialize ORSet" in {
      checkSerialization(ORSet())
      checkSerialization(ORSet().add(address1, "a"))
      checkSerialization(ORSet().add(address1, "a").add(address2, "a"))
      checkSerialization(ORSet().add(address1, "a").remove(address2, "a"))
      checkSerialization(ORSet().add(address1, "a").add(address2, "b").remove(address1, "a"))
      checkSerialization(ORSet().add(address1, 1).add(address2, 2))
      checkSerialization(ORSet().add(address1, 1L).add(address2, 2L))
      checkSerialization(ORSet().add(address1, "a").add(address2, 2).add(address3, 3L).add(address3, address3))

      val s1 = ORSet().add(address1, "a").add(address2, "b")
      val s2 = ORSet().add(address2, "b").add(address1, "a")
      checkSameContent(s1.merge(s2), s2.merge(s1))

      val s3 = ORSet().add(address1, "a").add(address2, 17).remove(address3, 17)
      val s4 = ORSet().add(address2, 17).remove(address3, 17).add(address1, "a")
      checkSameContent(s3.merge(s4), s4.merge(s3))
    }

    "serialize Flag" in {
      checkSerialization(Flag())
      checkSerialization(Flag().switchOn)
    }

    "serialize LWWRegister" in {
      checkSerialization(LWWRegister(address1, "value1", LWWRegister.defaultClock))
      checkSerialization(LWWRegister(address1, "value2", LWWRegister.defaultClock)
        .withValue(address2, "value3", LWWRegister.defaultClock))
    }

    "serialize GCounter" in {
      checkSerialization(GCounter())
      checkSerialization(GCounter().increment(address1, 3))
      checkSerialization(GCounter().increment(address1, 2).increment(address2, 5))

      checkSameContent(
        GCounter().increment(address1, 2).increment(address2, 5),
        GCounter().increment(address2, 5).increment(address1, 1).increment(address1, 1))
      checkSameContent(
        GCounter().increment(address1, 2).increment(address3, 5),
        GCounter().increment(address3, 5).increment(address1, 2))
    }

    "serialize PNCounter" in {
      checkSerialization(PNCounter())
      checkSerialization(PNCounter().increment(address1, 3))
      checkSerialization(PNCounter().increment(address1, 3).decrement(address1, 1))
      checkSerialization(PNCounter().increment(address1, 2).increment(address2, 5))
      checkSerialization(PNCounter().increment(address1, 2).increment(address2, 5).decrement(address1, 1))

      checkSameContent(
        PNCounter().increment(address1, 2).increment(address2, 5),
        PNCounter().increment(address2, 5).increment(address1, 1).increment(address1, 1))
      checkSameContent(
        PNCounter().increment(address1, 2).increment(address3, 5),
        PNCounter().increment(address3, 5).increment(address1, 2))
      checkSameContent(
        PNCounter().increment(address1, 2).decrement(address1, 1).increment(address3, 5),
        PNCounter().increment(address3, 5).increment(address1, 2).decrement(address1, 1))
    }

    "serialize ORMap" in {
      checkSerialization(ORMap())
      checkSerialization(ORMap().put(address1, "a", GSet() + "A"))
      checkSerialization(ORMap().put(address1, "a", GSet() + "A").put(address2, "b", GSet() + "B"))
    }

    "serialize LWWMap" in {
      checkSerialization(LWWMap())
      checkSerialization(LWWMap().put(address1, "a", "value1", LWWRegister.defaultClock))
      checkSerialization(LWWMap().put(address1, "a", "value1", LWWRegister.defaultClock)
        .put(address2, "b", 17, LWWRegister.defaultClock))
    }

    "serialize PNCounterMap" in {
      checkSerialization(PNCounterMap())
      checkSerialization(PNCounterMap().increment(address1, "a", 3))
      checkSerialization(PNCounterMap().increment(address1, "a", 3).decrement(address2, "a", 2).
        increment(address2, "b", 5))
    }

    "serialize ORMultiMap" in {
      checkSerialization(ORMultiMap())
      checkSerialization(ORMultiMap().addBinding(address1, "a", "A"))
      checkSerialization(ORMultiMap.empty[String]
        .addBinding(address1, "a", "A1")
        .put(address2, "b", Set("B1", "B2", "B3"))
        .addBinding(address2, "a", "A2"))

      val m1 = ORMultiMap.empty[String].addBinding(address1, "a", "A1").addBinding(address2, "a", "A2")
      val m2 = ORMultiMap.empty[String].put(address2, "b", Set("B1", "B2", "B3"))
      checkSameContent(m1.merge(m2), m2.merge(m1))
    }

    "serialize DeletedData" in {
      checkSerialization(DeletedData)
    }

    "serialize VectorClock" in {
      checkSerialization(VectorClock())
      checkSerialization(VectorClock().increment(address1))
      checkSerialization(VectorClock().increment(address1).increment(address2))

      val v1 = VectorClock().increment(address1).increment(address1)
      val v2 = VectorClock().increment(address2)
      checkSameContent(v1.merge(v2), v2.merge(v1))
    }

  }
}

