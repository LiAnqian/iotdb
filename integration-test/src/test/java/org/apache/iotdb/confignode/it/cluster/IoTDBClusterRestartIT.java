/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.confignode.it.cluster;

import org.apache.iotdb.commons.client.exception.ClientManagerException;
import org.apache.iotdb.commons.client.sync.SyncConfigNodeIServiceClient;
import org.apache.iotdb.commons.cluster.NodeStatus;
import org.apache.iotdb.confignode.it.utils.ConfigNodeTestUtils;
import org.apache.iotdb.confignode.rpc.thrift.TShowClusterResp;
import org.apache.iotdb.it.env.EnvFactory;
import org.apache.iotdb.it.env.cluster.EnvUtils;
import org.apache.iotdb.it.env.cluster.config.MppBaseConfig;
import org.apache.iotdb.it.env.cluster.config.MppCommonConfig;
import org.apache.iotdb.it.env.cluster.config.MppJVMConfig;
import org.apache.iotdb.it.env.cluster.env.AbstractEnv;
import org.apache.iotdb.it.env.cluster.node.DataNodeWrapper;
import org.apache.iotdb.it.framework.IoTDBTestRunner;
import org.apache.iotdb.itbase.category.ClusterIT;

import org.apache.thrift.TException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.apache.iotdb.consensus.ConsensusFactory.RATIS_CONSENSUS;

@RunWith(IoTDBTestRunner.class)
@Category({ClusterIT.class})
public class IoTDBClusterRestartIT {
  private static final Logger logger = LoggerFactory.getLogger(IoTDBClusterRestartIT.class);

  private static final int testReplicationFactor = 2;

  private static final int testConfigNodeNum = 3, testDataNodeNum = 2;

  @Before
  public void setUp() {
    EnvFactory.getEnv()
        .getConfig()
        .getCommonConfig()
        .setConfigNodeConsensusProtocolClass(RATIS_CONSENSUS)
        .setSchemaRegionConsensusProtocolClass(RATIS_CONSENSUS)
        .setDataRegionConsensusProtocolClass(RATIS_CONSENSUS)
        .setSchemaReplicationFactor(testReplicationFactor)
        .setDataReplicationFactor(testReplicationFactor);

    EnvFactory.getEnv().initClusterEnvironment(testConfigNodeNum, testDataNodeNum);
  }

  @After
  public void tearDown() {
    EnvFactory.getEnv().cleanClusterEnvironment();
  }

  @Test
  public void clusterRestartTest() throws InterruptedException {
    // Shutdown all cluster nodes
    for (int i = 0; i < testConfigNodeNum; i++) {
      EnvFactory.getEnv().shutdownConfigNode(i);
    }
    for (int i = 0; i < testDataNodeNum; i++) {
      EnvFactory.getEnv().shutdownDataNode(i);
    }

    // Sleep 1s before restart
    TimeUnit.SECONDS.sleep(1);

    // Restart all cluster nodes
    for (int i = 0; i < testConfigNodeNum; i++) {
      EnvFactory.getEnv().startConfigNode(i);
    }
    for (int i = 0; i < testDataNodeNum; i++) {
      EnvFactory.getEnv().startDataNode(i);
    }

    ((AbstractEnv) EnvFactory.getEnv()).testWorkingNoUnknown();
  }

  @Test
  public void clusterRestartAfterUpdateDataNodeTest()
      throws InterruptedException, ClientManagerException, IOException, TException {
    // Shutdown all DataNodes
    for (int i = 0; i < testDataNodeNum; i++) {
      EnvFactory.getEnv().shutdownDataNode(i);
    }
    TimeUnit.SECONDS.sleep(1);

    List<DataNodeWrapper> dataNodeWrapperList = EnvFactory.getEnv().getDataNodeWrapperList();
    for (int i = 0; i < testDataNodeNum; i++) {
      // Modify DataNode clientRpcEndPoint
      int[] portList = EnvUtils.searchAvailablePorts();
      dataNodeWrapperList.get(i).setPort(portList[0]);
      // Update DataNode files' names
      dataNodeWrapperList.get(i).renameFile();
    }

    // Restart DataNodes
    for (int i = 0; i < testDataNodeNum; i++) {
      dataNodeWrapperList
          .get(i)
          .changeConfig(
              (MppBaseConfig) EnvFactory.getEnv().getConfig().getDataNodeConfig(),
              (MppCommonConfig) EnvFactory.getEnv().getConfig().getDataNodeCommonConfig(),
              (MppJVMConfig) EnvFactory.getEnv().getConfig().getDataNodeJVMConfig());
      EnvFactory.getEnv().startDataNode(i);
    }

    // Check DataNode status
    EnvFactory.getEnv()
        .ensureNodeStatus(
            Arrays.asList(
                EnvFactory.getEnv().getDataNodeWrapper(0),
                EnvFactory.getEnv().getDataNodeWrapper(1)),
            Arrays.asList(NodeStatus.Running, NodeStatus.Running));

    // Check DataNode EndPoint
    try (SyncConfigNodeIServiceClient client =
        (SyncConfigNodeIServiceClient) EnvFactory.getEnv().getLeaderConfigNodeConnection()) {
      TShowClusterResp showClusterResp = client.showCluster();
      ConfigNodeTestUtils.checkNodeConfig(
          showClusterResp.getConfigNodeList(),
          showClusterResp.getDataNodeList(),
          EnvFactory.getEnv().getConfigNodeWrapperList(),
          dataNodeWrapperList);
    }
  }

  // TODO: Add persistence tests in the future

  @Test
  public void clusterRestartWithoutSeedConfigNode() {
    // shutdown all ConfigNodes and DataNodes
    for (int i = testConfigNodeNum - 1; i >= 0; i--) {
      EnvFactory.getEnv().shutdownConfigNode(i);
    }
    for (int i = testDataNodeNum - 1; i >= 0; i--) {
      EnvFactory.getEnv().shutdownDataNode(i);
    }
    logger.info("Shutdown all ConfigNodes and DataNodes");
    // restart without seed ConfigNode, the cluster should still work
    for (int i = 1; i < testConfigNodeNum; i++) {
      EnvFactory.getEnv().startConfigNode(i);
    }
    for (int i = 0; i < testDataNodeNum; i++) {
      EnvFactory.getEnv().startDataNode(i);
    }
    logger.info("Restarted");
    ((AbstractEnv) EnvFactory.getEnv()).testWorkingOneUnknownOtherRunning();
    logger.info("Working without Seed-ConfigNode");
  }
}
