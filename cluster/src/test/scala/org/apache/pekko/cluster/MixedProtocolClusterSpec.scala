/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.cluster

import com.typesafe.config.{ Config, ConfigFactory }

import org.apache.pekko
import pekko.testkit.{ LongRunningTest, PekkoSpec }

object MixedProtocolClusterSpec {

  import PekkoSpec._

  val baseConfig: Config =
    ConfigFactory.parseString("""
      pekko.actor.provider = "cluster"
      pekko.coordinated-shutdown.terminate-actor-system = on

      pekko.remote.artery.canonical.port = 0
      pekko.remote.classic.netty.tcp.port = 0
      pekko.remote.artery.advanced.aeron.idle-cpu-level = 3
      pekko.remote.accept-protocol-names = ["pekko", "akka"]
      pekko.remote.enforce-strict-config-prefix-check-on-join = on

      pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
      pekko.cluster.split-brain-resolver.active-strategy = keep-majority
      pekko.cluster.jmx.multi-mbeans-in-same-jvm = on""")

  val configWithUdp: Config =
    ConfigFactory.parseString("""
      pekko.remote.artery.transport = "aeron-udp"
    """).withFallback(baseConfig)

  val configWithPekkoUdp: Config =
    ConfigFactory.parseString("""
      pekko.remote.protocol-name = "pekko"
    """).withFallback(configWithUdp)

  val configWithAkkaUdp: Config =
    ConfigFactory.parseString("""
      pekko.remote.protocol-name = "akka"
    """).withFallback(configWithUdp)

  val configWithPekkoTcp: Config =
    ConfigFactory.parseString("""
      pekko.remote.protocol-name = "pekko"
    """).withFallback(baseConfig)

  val configWithAkkaTcp: Config =
    ConfigFactory.parseString("""
      pekko.remote.protocol-name = "akka"
    """).withFallback(baseConfig)

  val configWithNetty: Config =
    ConfigFactory.parseString("""
      pekko.remote.artery.enabled = false
      pekko.remote.classic {
        enabled-transports = ["pekko.remote.classic.netty.tcp"]
      }
    """).withFallback(baseConfig)

  val configWithPekkoNetty: Config =
    ConfigFactory.parseString("""
      pekko.remote.protocol-name = "pekko"
    """).withFallback(configWithNetty)

  val configWithAkkaNetty: Config =
    ConfigFactory.parseString("""
      pekko.remote.protocol-name = "akka"
    """).withFallback(configWithNetty)

  val configWithNettySsl: Config =
    ConfigFactory.parseString(s"""
      pekko.remote.classic {
        enabled-transports = ["pekko.remote.classic.netty.ssl"]
        netty.ssl.hostname = "localhost"
        netty.ssl.port = 0
        netty.ssl.security = {
          key-store = "${resourcePath("keystore")}"
          trust-store = "${resourcePath("truststore")}"
          key-store-password = "changeme"
          key-password = "changeme"
          trust-store-password = "changeme"
          protocol = "TLSv1.2"
          enabled-algorithms = [TLS_RSA_WITH_AES_128_CBC_SHA, TLS_DHE_RSA_WITH_AES_256_GCM_SHA384]
        }
      }
    """).withFallback(configWithNetty)

  val configWithPekkoNettySsl: Config =
    ConfigFactory.parseString("""
      pekko.remote.protocol-name = "pekko"
    """).withFallback(configWithNettySsl)

  val configWithAkkaNettySsl: Config =
    ConfigFactory.parseString("""
      pekko.remote.protocol-name = "akka"
    """).withFallback(configWithNettySsl)
}

class MixedProtocolClusterSpec extends PekkoSpec with ClusterTestKit {

  import MixedProtocolClusterSpec._

  "A node using the akka protocol" must {

    "be allowed to join a cluster with a node using the pekko protocol (udp)" taggedAs LongRunningTest in {

      val clusterTestUtil = new ClusterTestUtil(system.name)
      try {
        // start the first node with the "pekko" protocol
        clusterTestUtil.newActorSystem(configWithPekkoUdp)

        // have a node using the "akka" protocol join
        val joiningNode = clusterTestUtil.newActorSystem(configWithAkkaUdp)
        clusterTestUtil.formCluster()

        awaitCond(clusterTestUtil.isMemberUp(joiningNode), message = "awaiting joining node to be 'Up'")
      } finally {
        clusterTestUtil.shutdownAll()
      }
    }

    "be allowed to join a cluster with a node using the pekko protocol (tcp)" taggedAs LongRunningTest in {

      val clusterTestUtil = new ClusterTestUtil(system.name)
      try {
        // start the first node with the "pekko" protocol
        clusterTestUtil.newActorSystem(configWithPekkoTcp)

        // have a node using the "akka" protocol join
        val joiningNode = clusterTestUtil.newActorSystem(configWithAkkaTcp)
        clusterTestUtil.formCluster()

        awaitCond(clusterTestUtil.isMemberUp(joiningNode), message = "awaiting joining node to be 'Up'")
      } finally {
        clusterTestUtil.shutdownAll()
      }
    }

    "be allowed to join a cluster with a node using the pekko protocol (netty)" taggedAs LongRunningTest in {

      val clusterTestUtil = new ClusterTestUtil(system.name)
      try {
        // start the first node with the "pekko" protocol
        clusterTestUtil.newActorSystem(configWithPekkoNetty)

        // have a node using the "akka" protocol join
        val joiningNode = clusterTestUtil.newActorSystem(configWithAkkaNetty)
        clusterTestUtil.formCluster()

        awaitCond(clusterTestUtil.isMemberUp(joiningNode), message = "awaiting joining node to be 'Up'")
      } finally {
        clusterTestUtil.shutdownAll()
      }
    }

    "be allowed to join a cluster with a node using the pekko protocol (netty ssl)" taggedAs LongRunningTest in {

      val clusterTestUtil = new ClusterTestUtil(system.name)
      try {
        // start the first node with the "pekko" protocol
        clusterTestUtil.newActorSystem(configWithPekkoNettySsl)

        // have a node using the "akka" protocol join
        val joiningNode = clusterTestUtil.newActorSystem(configWithAkkaNettySsl)
        clusterTestUtil.formCluster()

        awaitCond(clusterTestUtil.isMemberUp(joiningNode), message = "awaiting joining node to be 'Up'")
      } finally {
        clusterTestUtil.shutdownAll()
      }
    }

    "allow a node using the pekko protocol to join the cluster (udp)" taggedAs LongRunningTest in {

      val clusterTestUtil = new ClusterTestUtil(system.name)
      try {
        // create the first node with the "akka" protocol
        clusterTestUtil.newActorSystem(configWithAkkaUdp)

        // have a node using the "pekko" protocol join
        val joiningNode = clusterTestUtil.newActorSystem(configWithPekkoUdp)
        clusterTestUtil.formCluster()

        awaitCond(clusterTestUtil.isMemberUp(joiningNode), message = "awaiting joining node to be 'Up'")
      } finally {
        clusterTestUtil.shutdownAll()
      }
    }

    "allow a node using the pekko protocol to join the cluster (tcp)" taggedAs LongRunningTest in {

      val clusterTestUtil = new ClusterTestUtil(system.name)
      try {
        // create the first node with the "akka" protocol
        clusterTestUtil.newActorSystem(configWithAkkaTcp)

        // have a node using the "pekko" protocol join
        val joiningNode = clusterTestUtil.newActorSystem(configWithPekkoTcp)
        clusterTestUtil.formCluster()

        awaitCond(clusterTestUtil.isMemberUp(joiningNode), message = "awaiting joining node to be 'Up'")
      } finally {
        clusterTestUtil.shutdownAll()
      }
    }

    "allow a node using the pekko protocol to join the cluster (netty)" taggedAs LongRunningTest in {

      val clusterTestUtil = new ClusterTestUtil(system.name)
      try {
        // create the first node with the "akka" protocol
        clusterTestUtil.newActorSystem(configWithAkkaNetty)

        // have a node using the "pekko" protocol join
        val joiningNode = clusterTestUtil.newActorSystem(configWithPekkoNetty)
        clusterTestUtil.formCluster()

        awaitCond(clusterTestUtil.isMemberUp(joiningNode), message = "awaiting joining node to be 'Up'")
      } finally {
        clusterTestUtil.shutdownAll()
      }
    }

    "allow a node using the pekko protocol to join the cluster (netty ssl)" taggedAs LongRunningTest in {

      val clusterTestUtil = new ClusterTestUtil(system.name)
      try {
        // create the first node with the "akka" protocol
        clusterTestUtil.newActorSystem(configWithAkkaNettySsl)

        // have a node using the "pekko" protocol join
        val joiningNode = clusterTestUtil.newActorSystem(configWithPekkoNettySsl)
        clusterTestUtil.formCluster()

        awaitCond(clusterTestUtil.isMemberUp(joiningNode), message = "awaiting joining node to be 'Up'")
      } finally {
        clusterTestUtil.shutdownAll()
      }
    }

  }

  "A node using the pekko protocol" must {
    "allow a plain pekko node to join (netty ssl)" taggedAs LongRunningTest in {
      // this is not a mixed protocol test, but the netty ssl transport seems not to have many tests

      val cfg = ConfigFactory.parseString("""
        pekko.remote.accept-protocol-names = ["pekko"]""")
        .withFallback(configWithPekkoNettySsl)
      val clusterTestUtil = new ClusterTestUtil(system.name)
      try {
        // create the first node with the "pekko" protocol
        clusterTestUtil.newActorSystem(cfg)

        // have a node using the "pekko" protocol join
        val joiningNode = clusterTestUtil.newActorSystem(cfg)
        clusterTestUtil.formCluster()

        awaitCond(clusterTestUtil.isMemberUp(joiningNode), message = "awaiting joining node to be 'Up'")
      } finally {
        clusterTestUtil.shutdownAll()
      }
    }
  }
}
