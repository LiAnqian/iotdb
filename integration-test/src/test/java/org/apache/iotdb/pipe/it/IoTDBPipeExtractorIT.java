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

package org.apache.iotdb.pipe.it;

import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.client.sync.SyncConfigNodeIServiceClient;
import org.apache.iotdb.confignode.rpc.thrift.TCreatePipeReq;
import org.apache.iotdb.confignode.rpc.thrift.TShowPipeInfo;
import org.apache.iotdb.confignode.rpc.thrift.TShowPipeReq;
import org.apache.iotdb.db.it.utils.TestUtils;
import org.apache.iotdb.it.env.cluster.node.DataNodeWrapper;
import org.apache.iotdb.it.framework.IoTDBTestRunner;
import org.apache.iotdb.itbase.category.MultiClusterIT2;
import org.apache.iotdb.itbase.env.BaseEnv;
import org.apache.iotdb.rpc.TSStatusCode;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.fail;

@RunWith(IoTDBTestRunner.class)
@Category({MultiClusterIT2.class})
public class IoTDBPipeExtractorIT extends AbstractPipeDualIT {
  @Test
  public void testExtractorValidParameter() throws Exception {
    DataNodeWrapper receiverDataNode = receiverEnv.getDataNodeWrapper(0);

    String receiverIp = receiverDataNode.getIp();
    int receiverPort = receiverDataNode.getPort();

    try (SyncConfigNodeIServiceClient client =
        (SyncConfigNodeIServiceClient) senderEnv.getLeaderConfigNodeConnection()) {
      Map<String, String> extractorAttributes = new HashMap<>();
      Map<String, String> processorAttributes = new HashMap<>();
      Map<String, String> connectorAttributes = new HashMap<>();

      extractorAttributes.put("extractor", "iotdb-extractor");
      extractorAttributes.put("extractor.history.enable", "true");

      connectorAttributes.put("connector", "iotdb-thrift-connector");
      connectorAttributes.put("connector.batch.enable", "false");
      connectorAttributes.put("connector.ip", receiverIp);
      connectorAttributes.put("connector.port", Integer.toString(receiverPort));

      // test when start-time and end-time are not set
      TSStatus status =
          client.createPipe(
              new TCreatePipeReq("p1", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());

      // test when only extractor.history.start-time is set
      extractorAttributes.put("extractor.history.start-time", "2000.01.01T08:00:00");
      status =
          client.createPipe(
              new TCreatePipeReq("p2", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());

      // test when only extractor.history.end-time is set
      extractorAttributes.remove("extractor.history.start-time");
      extractorAttributes.put("extractor.history.end-time", "2000.01.01T08:00:00");
      status =
          client.createPipe(
              new TCreatePipeReq("p3", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());

      // test when extractor.history.start-time equals extractor.history.end-time
      extractorAttributes.put("extractor.history.start-time", "2000.01.01T08:00:00");
      status =
          client.createPipe(
              new TCreatePipeReq("p4", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());

      // test when extractor.history.start-time is greater than extractor.history.end-time
      extractorAttributes.replace("extractor.history.start-time", "2001.01.01T08:00:00");
      status =
          client.createPipe(
              new TCreatePipeReq("p5", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());

      // test when extractor.history.end-time is future time
      extractorAttributes.replace("extractor.history.end-time", "2100.01.01T08:00:00");
      status =
          client.createPipe(
              new TCreatePipeReq("p6", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
    }
  }

  @Test
  public void testExtractorInvalidParameter() throws Exception {
    DataNodeWrapper receiverDataNode = receiverEnv.getDataNodeWrapper(0);

    String receiverIp = receiverDataNode.getIp();
    int receiverPort = receiverDataNode.getPort();

    try (SyncConfigNodeIServiceClient client =
        (SyncConfigNodeIServiceClient) senderEnv.getLeaderConfigNodeConnection()) {
      // insert data to create data region
      if (!TestUtils.tryExecuteNonQueryWithRetry(
          senderEnv, "insert into root.sg1.d1(time, at1) values (1, 1)")) {
        return;
      }

      // invalid 'extractor.history.start-time'
      String formatString =
          String.format(
              "create pipe p1"
                  + " with extractor ("
                  + "'extractor.history.enable'='true',"
                  + "'extractor.history.start-time'=%s)"
                  + " with connector ("
                  + "'connector'='iotdb-thrift-connector',"
                  + "'connector.ip'='%s',"
                  + "'connector.port'='%s',"
                  + "'connector.batch.enable'='false')",
              "%s", receiverIp, receiverPort);

      List<String> invalidStartTimes =
          Arrays.asList(
              "''", "null", "'null'", "'1'", "'-1000-01-01T00:00:00'", "'2000-01-01T00:00:0'");
      for (String invalidStartTime : invalidStartTimes) {
        try (Connection connection = senderEnv.getConnection();
            Statement statement = connection.createStatement()) {
          statement.execute(String.format(formatString, invalidStartTime));
          fail();
        } catch (SQLException ignored) {
        }
      }

      List<TShowPipeInfo> showPipeResult = client.showPipe(new TShowPipeReq()).pipeInfoList;
      Assert.assertEquals(0, showPipeResult.size());

      // can not set 'extractor.history.enable' and 'extractor.realtime.enable' both to false
      String sql =
          String.format(
              "create pipe p1"
                  + " with extractor ("
                  + "'extractor.history.enable'='false',"
                  + "'extractor.realtime.enable'='false')"
                  + " with connector ("
                  + "'connector'='iotdb-thrift-connector',"
                  + "'connector.ip'='%s',"
                  + "'connector.port'='%s',"
                  + "'connector.batch.enable'='false')",
              receiverIp, receiverPort);
      try (Connection connection = senderEnv.getConnection();
          Statement statement = connection.createStatement()) {
        statement.execute(sql);
        fail();
      } catch (SQLException ignored) {
      }

      showPipeResult = client.showPipe(new TShowPipeReq()).pipeInfoList;
      Assert.assertEquals(0, showPipeResult.size());
    }
  }

  @Test
  public void testExtractorPatternMatch() throws Exception {
    DataNodeWrapper receiverDataNode = receiverEnv.getDataNodeWrapper(0);

    String receiverIp = receiverDataNode.getIp();
    int receiverPort = receiverDataNode.getPort();

    try (SyncConfigNodeIServiceClient client =
        (SyncConfigNodeIServiceClient) senderEnv.getLeaderConfigNodeConnection()) {
      if (!TestUtils.tryExecuteNonQueriesWithRetry(
          senderEnv,
          Arrays.asList(
              "insert into root.nonAligned.1TS (time, s_float) values (now(), 0.5)",
              "insert into root.nonAligned.100TS (time, s_float) values (now(), 0.5)",
              "insert into root.nonAligned.1000TS (time, s_float) values (now(), 0.5)",
              "insert into root.nonAligned.`1(TS)` (time, s_float) values (now(), 0.5)",
              "insert into root.nonAligned.6TS.`6` ("
                  + "time, `s_float(1)`, `s_int(1)`, `s_double(1)`, `s_long(1)`, `s_text(1)`, `s_bool(1)`) "
                  + "values (now(), 0.5, 1, 1.5, 2, \"text1\", true)",
              "insert into root.aligned.1TS (time, s_float) aligned values (now(), 0.5)",
              "insert into root.aligned.100TS (time, s_float) aligned values (now(), 0.5)",
              "insert into root.aligned.1000TS (time, s_float) aligned values (now(), 0.5)",
              "insert into root.aligned.`1(TS)` (time, s_float) aligned values (now(), 0.5)",
              "insert into root.aligned.6TS.`6` ("
                  + "time, `s_float(1)`, `s_int(1)`, `s_double(1)`, `s_long(1)`, `s_text(1)`, `s_bool(1)`) "
                  + "aligned values (now(), 0.5, 1, 1.5, 2, \"text1\", true)"))) {
        return;
      }

      Map<String, String> extractorAttributes = new HashMap<>();
      Map<String, String> processorAttributes = new HashMap<>();
      Map<String, String> connectorAttributes = new HashMap<>();

      extractorAttributes.put("extractor.pattern", null);

      connectorAttributes.put("connector", "iotdb-thrift-connector");
      connectorAttributes.put("connector.batch.enable", "false");
      connectorAttributes.put("connector.ip", receiverIp);
      connectorAttributes.put("connector.port", Integer.toString(receiverPort));

      List<String> patterns =
          Arrays.asList(
              "root.db.nonExistPath", // match nothing
              "root.nonAligned.1TS.s_float",
              "root.nonAligned.6TS.`6`.`s_text(1)`",
              "root.aligned.1TS",
              "root.aligned.6TS.`6`.`s_int(1)`",
              "root.aligned.`1(TS)`",
              "root.nonAligned.`1(TS)`",
              "root.aligned.6TS.`6`", // match device root.aligned.6TS.`6`
              "root.nonAligned.6TS.`6`", // match device root.nonAligned.6TS.`6`
              "root.nonAligned.`1`", // match nothing
              "root.nonAligned.1", // match device root.nonAligned.1TS, 100TS and 100TS
              "root.aligned.1" // match device root.aligned.1TS, 100TS and 100TS
              );

      List<Integer> expectedTimeseriesCount =
          Arrays.asList(0, 1, 2, 3, 4, 5, 6, 11, 16, 16, 18, 20);

      for (int i = 0; i < patterns.size(); ++i) {
        extractorAttributes.replace("extractor.pattern", patterns.get(i));
        TSStatus status =
            client.createPipe(
                new TCreatePipeReq("p" + i, connectorAttributes)
                    .setExtractorAttributes(extractorAttributes)
                    .setProcessorAttributes(processorAttributes));
        Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
        Assert.assertEquals(
            TSStatusCode.SUCCESS_STATUS.getStatusCode(), client.startPipe("p" + i).getCode());
        assertTimeseriesCountOnReceiver(receiverEnv, expectedTimeseriesCount.get(i));
      }

      TestUtils.assertDataOnEnv(
          receiverEnv,
          "show devices",
          "Device,IsAligned,Template,",
          new HashSet<>(
              Arrays.asList(
                  "root.nonAligned.1TS,false,null,",
                  "root.nonAligned.100TS,false,null,",
                  "root.nonAligned.1000TS,false,null,",
                  "root.nonAligned.`1(TS)`,false,null,",
                  "root.nonAligned.6TS.`6`,false,null,",
                  "root.aligned.1TS,true,null,",
                  "root.aligned.100TS,true,null,",
                  "root.aligned.1000TS,true,null,",
                  "root.aligned.`1(TS)`,true,null,",
                  "root.aligned.6TS.`6`,true,null,")));
    }
  }

  @Test
  public void testMatchingMultipleDatabases() throws Exception {
    DataNodeWrapper receiverDataNode = receiverEnv.getDataNodeWrapper(0);

    String receiverIp = receiverDataNode.getIp();
    int receiverPort = receiverDataNode.getPort();

    try (SyncConfigNodeIServiceClient client =
        (SyncConfigNodeIServiceClient) senderEnv.getLeaderConfigNodeConnection()) {
      Map<String, String> extractorAttributes = new HashMap<>();
      Map<String, String> processorAttributes = new HashMap<>();
      Map<String, String> connectorAttributes = new HashMap<>();

      extractorAttributes.put("extractor.pattern", "root.db1");

      connectorAttributes.put("connector", "iotdb-thrift-connector");
      connectorAttributes.put("connector.batch.enable", "false");
      connectorAttributes.put("connector.ip", receiverIp);
      connectorAttributes.put("connector.port", Integer.toString(receiverPort));

      TSStatus status =
          client.createPipe(
              new TCreatePipeReq("p1", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
      Assert.assertEquals(
          TSStatusCode.SUCCESS_STATUS.getStatusCode(), client.startPipe("p1").getCode());
      assertTimeseriesCountOnReceiver(receiverEnv, 0);

      if (!TestUtils.tryExecuteNonQueriesWithRetry(
          senderEnv,
          Arrays.asList(
              "insert into root.db1.d1 (time, at1) values (1, 10)",
              "insert into root.db2.d1 (time, at1) values (1, 20)",
              "flush"))) {
        return;
      }

      extractorAttributes.replace("extractor.pattern", "root.db2");
      status =
          client.createPipe(
              new TCreatePipeReq("p2", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
      Assert.assertEquals(
          TSStatusCode.SUCCESS_STATUS.getStatusCode(), client.startPipe("p2").getCode());
      assertTimeseriesCountOnReceiver(receiverEnv, 2);

      Assert.assertEquals(
          TSStatusCode.SUCCESS_STATUS.getStatusCode(), client.dropPipe("p1").getCode());
      Assert.assertEquals(
          TSStatusCode.SUCCESS_STATUS.getStatusCode(), client.dropPipe("p2").getCode());

      if (!TestUtils.tryExecuteNonQueriesWithRetry(
          senderEnv,
          Arrays.asList(
              "insert into root.db1.d1 (time, at1) values (2, 11)",
              "insert into root.db2.d1 (time, at1) values (2, 21)",
              "flush"))) {
        return;
      }

      extractorAttributes.remove("extractor.pattern"); // no pattern, will match all databases
      status =
          client.createPipe(
              new TCreatePipeReq("p3", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
      Assert.assertEquals(
          TSStatusCode.SUCCESS_STATUS.getStatusCode(), client.startPipe("p3").getCode());

      TestUtils.assertDataOnEnv(
          receiverEnv,
          "select count(*) from root.**",
          "count(root.db1.d1.at1),count(root.db2.d1.at1),",
          Collections.singleton("2,2,"));
    }
  }

  @Test
  public void testHistoryAndRealtime() throws Exception {
    DataNodeWrapper receiverDataNode = receiverEnv.getDataNodeWrapper(0);

    String receiverIp = receiverDataNode.getIp();
    int receiverPort = receiverDataNode.getPort();

    try (SyncConfigNodeIServiceClient client =
        (SyncConfigNodeIServiceClient) senderEnv.getLeaderConfigNodeConnection()) {
      if (!TestUtils.tryExecuteNonQueriesWithRetry(
          senderEnv,
          Arrays.asList(
              "insert into root.db.d1 (time, at1) values (1, 10)",
              "insert into root.db.d2 (time, at1) values (1, 20)",
              "insert into root.db.d3 (time, at1) values (1, 30)",
              "insert into root.db.d4 (time, at1) values (1, 40)",
              "flush"))) {
        return;
      }

      Map<String, String> extractorAttributes = new HashMap<>();
      Map<String, String> processorAttributes = new HashMap<>();
      Map<String, String> connectorAttributes = new HashMap<>();

      connectorAttributes.put("connector", "iotdb-thrift-connector");
      connectorAttributes.put("connector.batch.enable", "false");
      connectorAttributes.put("connector.ip", receiverIp);
      connectorAttributes.put("connector.port", Integer.toString(receiverPort));

      extractorAttributes.put("extractor.pattern", "root.db.d2");
      extractorAttributes.put("extractor.history.enable", "false");
      extractorAttributes.put("extractor.realtime.enable", "true");
      TSStatus status =
          client.createPipe(
              new TCreatePipeReq("p2", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
      Assert.assertEquals(
          TSStatusCode.SUCCESS_STATUS.getStatusCode(), client.startPipe("p2").getCode());

      extractorAttributes.replace("extractor.pattern", "root.db.d3");
      extractorAttributes.replace("extractor.history.enable", "true");
      extractorAttributes.replace("extractor.realtime.enable", "false");
      status =
          client.createPipe(
              new TCreatePipeReq("p3", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
      Assert.assertEquals(
          TSStatusCode.SUCCESS_STATUS.getStatusCode(), client.startPipe("p3").getCode());

      extractorAttributes.replace("extractor.pattern", "root.db.d4");
      extractorAttributes.replace("extractor.history.enable", "true");
      extractorAttributes.replace("extractor.realtime.enable", "true");
      status =
          client.createPipe(
              new TCreatePipeReq("p4", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
      Assert.assertEquals(
          TSStatusCode.SUCCESS_STATUS.getStatusCode(), client.startPipe("p4").getCode());

      if (!TestUtils.tryExecuteNonQueriesWithRetry(
          senderEnv,
          Arrays.asList(
              "insert into root.db.d1 (time, at1) values (2, 11)",
              "insert into root.db.d2 (time, at1) values (2, 21)",
              "insert into root.db.d3 (time, at1) values (2, 31)",
              "insert into root.db.d4 (time, at1) values (2, 41)"))) {
        return;
      }

      TestUtils.assertDataOnEnv(
          receiverEnv,
          "select count(*) from root.** where time <= 1",
          "count(root.db.d4.at1),count(root.db.d2.at1),count(root.db.d3.at1),",
          Collections.singleton("1,0,1,"));
      TestUtils.assertDataOnEnv(
          receiverEnv,
          "select count(*) from root.** where time >= 2",
          "count(root.db.d4.at1),count(root.db.d2.at1),count(root.db.d3.at1),",
          Collections.singleton("1,1,0,"));
    }
  }

  @Test
  public void testStartTimeAndEndTimeWorkingWithOrWithoutPattern() throws Exception {
    DataNodeWrapper receiverDataNode = receiverEnv.getDataNodeWrapper(0);

    String receiverIp = receiverDataNode.getIp();
    int receiverPort = receiverDataNode.getPort();

    try (SyncConfigNodeIServiceClient client =
        (SyncConfigNodeIServiceClient) senderEnv.getLeaderConfigNodeConnection()) {
      if (!TestUtils.tryExecuteNonQueriesWithRetry(
          senderEnv,
          Arrays.asList(
              "insert into root.db.d1 (time, at1)"
                  + " values (1000, 1), (2000, 2), (3000, 3), (4000, 4), (5000, 5)",
              "insert into root.db.d2 (time, at1)"
                  + " values (1000, 1), (2000, 2), (3000, 3), (4000, 4), (5000, 5)",
              "flush"))) {
        return;
      }

      Map<String, String> extractorAttributes = new HashMap<>();
      Map<String, String> processorAttributes = new HashMap<>();
      Map<String, String> connectorAttributes = new HashMap<>();

      extractorAttributes.put("extractor.pattern", "root.db.d1");
      extractorAttributes.put("extractor.history.enable", "true");
      extractorAttributes.put("extractor.history.start-time", "1970-01-01T08:00:02+08:00");
      extractorAttributes.put("extractor.history.end-time", "1970-01-01T08:00:04+08:00");

      connectorAttributes.put("connector", "iotdb-thrift-connector");
      connectorAttributes.put("connector.batch.enable", "false");
      connectorAttributes.put("connector.ip", receiverIp);
      connectorAttributes.put("connector.port", Integer.toString(receiverPort));

      TSStatus status =
          client.createPipe(
              new TCreatePipeReq("p1", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
      Assert.assertEquals(
          TSStatusCode.SUCCESS_STATUS.getStatusCode(), client.startPipe("p1").getCode());

      TestUtils.assertDataOnEnv(
          receiverEnv,
          "select count(*) from root.**",
          "count(root.db.d1.at1),",
          Collections.singleton("3,"));

      extractorAttributes.remove("extractor.pattern");
      status =
          client.createPipe(
              new TCreatePipeReq("p2", connectorAttributes)
                  .setExtractorAttributes(extractorAttributes)
                  .setProcessorAttributes(processorAttributes));
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
      Assert.assertEquals(
          TSStatusCode.SUCCESS_STATUS.getStatusCode(), client.startPipe("p2").getCode());

      TestUtils.assertDataOnEnv(
          receiverEnv,
          "select count(*) from root.**",
          "count(root.db.d1.at1),count(root.db.d2.at1),",
          Collections.singleton("3,3,"));
    }
  }

  private void assertTimeseriesCountOnReceiver(BaseEnv receiverEnv, int count) {
    TestUtils.assertDataOnEnv(
        receiverEnv, "count timeseries", "count(timeseries),", Collections.singleton(count + ","));
  }
}
