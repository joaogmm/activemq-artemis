/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.tests.integration.federation;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.util.Collections;

import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.FederationConfiguration;
import org.apache.activemq.artemis.core.config.TransformerConfiguration;
import org.apache.activemq.artemis.core.config.federation.FederationDownstreamConfiguration;
import org.apache.activemq.artemis.core.config.federation.FederationQueuePolicyConfiguration;
import org.apache.activemq.artemis.core.config.federation.FederationTransformerConfiguration;
import org.apache.activemq.artemis.core.config.federation.FederationUpstreamConfiguration;
import org.apache.activemq.artemis.core.postoffice.QueueBinding;
import org.apache.activemq.artemis.core.server.transformer.Transformer;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.tests.util.Wait;
import org.junit.Before;
import org.junit.Test;

/**
 * Federated Queue Test
 */
public class FederatedQueueTest extends FederatedTestBase {


   @Override
   @Before
   public void setUp() throws Exception {
      super.setUp();
   }


   protected ConnectionFactory getCF(int i) throws Exception {
      return new ActiveMQConnectionFactory("vm://" + i);
   }

   @Test
   public void testFederatedQueueRemoteConsumeUpstream() throws Exception {
      String queueName = getName();

      FederationConfiguration federationConfiguration = createUpstreamFederationConfiguration("server1", queueName);
      getServer(0).getConfiguration().getFederationConfigurations().add(federationConfiguration);
      getServer(0).getFederationManager().deploy();

      testFederatedQueueRemoteConsume(queueName);
   }

   @Test
   public void testFederatedQueueRemoteConsumeUpstreamPriorityAdjustment() throws Exception {
      String queueName = getName();

      FederationConfiguration federationConfiguration = createUpstreamFederationConfiguration("server1", queueName);
      FederationQueuePolicyConfiguration policy = (FederationQueuePolicyConfiguration) federationConfiguration.getFederationPolicyMap().get("QueuePolicy" + queueName);
      //Favor federated broker over local consumers
      policy.setPriorityAdjustment(1);

      getServer(0).getConfiguration().getFederationConfigurations().add(federationConfiguration);
      getServer(0).getFederationManager().deploy();

      testFederatedQueueRemoteConsumeUpstreamPriorityAdjustment(queueName);
   }

   @Test
   public void testFederatedQueueRemoteConsumeDownstreamPriorityAdjustment() throws Exception {
      String queueName = getName();

      FederationConfiguration federationConfiguration = createDownstreamFederationConfiguration("server0", queueName, "server1");
      FederationQueuePolicyConfiguration policy = (FederationQueuePolicyConfiguration) federationConfiguration.getFederationPolicyMap().get("QueuePolicy" + queueName);
      //Favor federated broker over local consumers
      policy.setPriorityAdjustment(1);

      getServer(1).getConfiguration().getFederationConfigurations().add(federationConfiguration);
      getServer(1).getFederationManager().deploy();

      testFederatedQueueRemoteConsumeUpstreamPriorityAdjustment(queueName);
   }

   private void testFederatedQueueRemoteConsumeUpstreamPriorityAdjustment(final String queueName) throws Exception {
      ConnectionFactory cf1 = getCF(1);
      ConnectionFactory cf0 = getCF(0);
      try (Connection connection1 = cf1.createConnection(); Connection connection0 = cf0.createConnection()) {
         connection0.start();
         connection1.start();
         Session session0 = connection0.createSession();
         Session session1 = connection1.createSession();
         Queue queue0 = session0.createQueue(queueName);
         Queue queue1 = session1.createQueue(queueName);

         MessageConsumer consumer0 = session0.createConsumer(queue0);
         MessageConsumer consumer1 = session1.createConsumer(queue1);

         //Wait for local and federated consumer to be established on Server 1
         assertTrue(Wait.waitFor(() -> getServer(1).locateQueue(SimpleString.toSimpleString(queueName)).getConsumerCount() == 2,
                                 5000, 100));

         MessageProducer producer1 = session1.createProducer(queue1);
         producer1.send(session1.createTextMessage("hello"));

         //Consumer 0 should receive the message over consumer because of adjusted priority
         //to favor the federated broker
         assertNull(consumer1.receive(500));
         assertNotNull(consumer0.receive(1000));

         consumer0.close();
         consumer1.close();
      }
   }

   private void verifyTransformer(String queueName) throws Exception {
      ConnectionFactory cf1 = getCF(1);
      ConnectionFactory cf0 = getCF(0);
      try (Connection connection1 = cf1.createConnection(); Connection connection0 = cf0.createConnection()) {
         connection1.start();
         Session session1 = connection1.createSession();
         Queue queue1 = session1.createQueue(queueName);
         MessageProducer producer1 = session1.createProducer(queue1);
         producer1.send(session1.createTextMessage("hello"));

         connection0.start();
         Session session0 = connection0.createSession();
         Queue queue0 = session0.createQueue(queueName);
         MessageConsumer consumer0 = session0.createConsumer(queue0);

         Message message = consumer0.receive(1000);
         assertNotNull(message);
         assertEquals(message.getBooleanProperty(TestTransformer.TEST_PROPERTY), true);
      }
   }

   @Test
   public void testFederatedQueueRemoteConsumeUpstreamTransformer() throws Exception {
      String queueName = getName();

      FederationConfiguration federationConfiguration = createUpstreamFederationConfiguration("server1", queueName);
      addTransformerConfiguration(federationConfiguration, queueName);
      getServer(0).getConfiguration().getFederationConfigurations().add(federationConfiguration);
      getServer(0).getFederationManager().deploy();

      verifyTransformer(queueName);
   }

   @Test
   public void testFederatedQueueRemoteConsumeDownstream() throws Exception {
      String queueName = getName();

      FederationConfiguration federationConfiguration = createDownstreamFederationConfiguration("server0", queueName, "server1");
      getServer(1).getConfiguration().getFederationConfigurations().add(federationConfiguration);
      getServer(1).getFederationManager().deploy();

      testFederatedQueueRemoteConsume(queueName);
   }

   @Test
   public void testFederatedQueueRemoteConsumeDownstreamTransformer() throws Exception {
      String queueName = getName();

      FederationConfiguration federationConfiguration = createDownstreamFederationConfiguration("server0", queueName, "server1");
      addTransformerConfiguration(federationConfiguration, queueName);
      getServer(1).getConfiguration().getFederationConfigurations().add(federationConfiguration);
      getServer(1).getFederationManager().deploy();

      verifyTransformer(queueName);
   }

   private void testFederatedQueueRemoteConsume(final String queueName) throws Exception {

      ConnectionFactory cf1 = getCF(1);
      ConnectionFactory cf0 = getCF(0);
      try (Connection connection1 = cf1.createConnection(); Connection connection0 = cf0.createConnection()) {
         connection1.start();
         Session session1 = connection1.createSession();
         Queue queue1 = session1.createQueue(queueName);
         MessageProducer producer = session1.createProducer(queue1);
         producer.send(session1.createTextMessage("hello"));

         connection0.start();
         Session session0 = connection0.createSession();
         Queue queue0 = session0.createQueue(queueName);
         MessageConsumer consumer0 = session0.createConsumer(queue0);

         assertNotNull(consumer0.receive(1000));


         producer.send(session1.createTextMessage("hello"));

         assertNotNull(consumer0.receive(1000));

         MessageConsumer consumer1 = session1.createConsumer(queue1);

         producer.send(session1.createTextMessage("hello"));

         assertNotNull(consumer1.receive(1000));
         assertNull(consumer0.receive(10));
         consumer1.close();

         //Groups
         producer.send(session1.createTextMessage("hello"));
         assertNotNull(consumer0.receive(1000));

         producer.send(createTextMessage(session1, "groupA"));

         assertNotNull(consumer0.receive(1000));
         consumer1 = session1.createConsumer(queue1);

         producer.send(createTextMessage(session1, "groupA"));
         assertNull(consumer1.receive(10));
         assertNotNull(consumer0.receive(1000));
      }

   }

   @Test
   public void testFederatedQueueRemoteConsumeDeployAfterConsumersExist() throws Exception {
      String queueName = getName();
      ConnectionFactory cf0 = getCF(0);

      ConnectionFactory cf1 = getCF(1);
      try (Connection connection0 = cf0.createConnection(); Connection connection1 = cf1.createConnection()) {

         connection1.start();
         Session session1 = connection1.createSession();
         Queue queue1 = session1.createQueue(queueName);
         MessageProducer producer = session1.createProducer(queue1);
         producer.send(session1.createTextMessage("hello"));

         connection0.start();
         Session session0 = connection0.createSession();
         Queue queue0 = session0.createQueue(queueName);
         MessageConsumer consumer0 = session0.createConsumer(queue0);

         assertNull(consumer0.receive(100));

         FederationConfiguration federationConfiguration = createUpstreamFederationConfiguration("server1", queueName);
         getServer(0).getConfiguration().getFederationConfigurations().add(federationConfiguration);
         getServer(0).getFederationManager().deploy();

         producer.send(session1.createTextMessage("hello"));

         assertNotNull(consumer0.receive(10000));
      }
   }

   @Test
   public void testFederatedQueueBiDirectionalUpstream() throws Exception {
      String queueName = getName();
      //Set queue up on both brokers
      for (int i = 0; i < 2; i++) {
         getServer(i).createQueue(SimpleString.toSimpleString(queueName), RoutingType.ANYCAST, SimpleString.toSimpleString(queueName), null, true, false);
      }
      FederationConfiguration federationConfiguration0 = createUpstreamFederationConfiguration("server1", queueName);
      getServer(0).getConfiguration().getFederationConfigurations().add(federationConfiguration0);
      getServer(0).getFederationManager().deploy();

      FederationConfiguration federationConfiguration1 = createUpstreamFederationConfiguration("server0", queueName);
      getServer(1).getConfiguration().getFederationConfigurations().add(federationConfiguration1);
      getServer(1).getFederationManager().deploy();

      testFederatedQueueBiDirectional(queueName, false);
   }

   @Test
   public void testFederatedQueueBiDirectionalDownstream() throws Exception {
      String queueName = getName();
      //Set queue up on both brokers
      for (int i = 0; i < 2; i++) {
         getServer(i).createQueue(SimpleString.toSimpleString(queueName), RoutingType.ANYCAST, SimpleString.toSimpleString(queueName), null, true, false);
      }
      FederationConfiguration federationConfiguration0 = createDownstreamFederationConfiguration("server1", queueName, "server0");
      getServer(0).getConfiguration().getFederationConfigurations().add(federationConfiguration0);
      getServer(0).getFederationManager().deploy();

      FederationConfiguration federationConfiguration1 = createDownstreamFederationConfiguration("server0", queueName, "server1");
      getServer(1).getConfiguration().getFederationConfigurations().add(federationConfiguration1);
      getServer(1).getFederationManager().deploy();

      testFederatedQueueBiDirectional(queueName, false);
   }

   @Test
   public void testFederatedQueueBiDirectionalDownstreamUpstream() throws Exception {
      String queueName = getName();
      //Set queue up on both brokers
      for (int i = 0; i < 2; i++) {
         getServer(i).createQueue(SimpleString.toSimpleString(queueName), RoutingType.ANYCAST, SimpleString.toSimpleString(queueName), null, true, false);
      }

      FederationConfiguration federationConfiguration0 = createDownstreamFederationConfiguration("server1-downstream",
          "server1", queueName, null, false, "server0");
      FederationUpstreamConfiguration upstreamConfig = createFederationUpstream("server1", queueName);
      federationConfiguration0.addUpstreamConfiguration(upstreamConfig);
      getServer(0).getConfiguration().getFederationConfigurations().add(federationConfiguration0);
      getServer(0).getFederationManager().deploy();

      testFederatedQueueBiDirectional(queueName, false);
   }

   @Test
   public void testFederatedQueueBiDirectionalDownstreamUpstreamSharedConnection() throws Exception {
      String queueName = getName();
      //Set queue up on both brokers
      for (int i = 0; i < 2; i++) {
         getServer(i).createQueue(SimpleString.toSimpleString(queueName), RoutingType.ANYCAST, SimpleString.toSimpleString(queueName), null, true, false);
      }

      FederationConfiguration federationConfiguration0 = createDownstreamFederationConfiguration("server1-downstream",
          "server1", queueName, null, true, "server0");
      FederationUpstreamConfiguration upstreamConfig = createFederationUpstream("server1", queueName);
      upstreamConfig.getConnectionConfiguration().setShareConnection(true);
      federationConfiguration0.addUpstreamConfiguration(upstreamConfig);
      getServer(0).getConfiguration().getFederationConfigurations().add(federationConfiguration0);
      getServer(0).getFederationManager().deploy();

      testFederatedQueueBiDirectional(queueName, true);
   }

   @Test
   public void testFederatedQueueShareUpstreamConnectionFalse() throws Exception {
      String queueName = getName();
      //Set queue up on both brokers
      for (int i = 0; i < 2; i++) {
         getServer(i).createQueue(SimpleString.toSimpleString(queueName), RoutingType.ANYCAST, SimpleString.toSimpleString(queueName), null, true, false);
      }

      FederationConfiguration federationConfiguration0 = createDownstreamFederationConfiguration("server1-downstream",
          "server1", queueName, null, false, "server0");
      federationConfiguration0.addUpstreamConfiguration(createFederationUpstream("server1", queueName));
      getServer(0).getConfiguration().getFederationConfigurations().add(federationConfiguration0);
      getServer(0).getFederationManager().deploy();

      testFederatedQueueShareUpstreamConnection(queueName, 2, 3);
   }

   @Test
   public void testFederatedQueueShareUpstreamConnectionTrue() throws Exception {
      String queueName = getName();
      //Set queue up on both brokers
      for (int i = 0; i < 2; i++) {
         getServer(i).createQueue(SimpleString.toSimpleString(queueName), RoutingType.ANYCAST, SimpleString.toSimpleString(queueName), null, true, false);
      }

      FederationConfiguration federationConfiguration0 = createDownstreamFederationConfiguration("server1-downstream",
          "server1", queueName, null, true, "server0");
      FederationUpstreamConfiguration upstreamConfiguration = createFederationUpstream("server1", queueName);
      upstreamConfiguration.getConnectionConfiguration().setShareConnection(true);
      federationConfiguration0.addUpstreamConfiguration(upstreamConfiguration);
      getServer(0).getConfiguration().getFederationConfigurations().add(federationConfiguration0);
      getServer(0).getFederationManager().deploy();

      testFederatedQueueShareUpstreamConnection(queueName, 2, 2);
   }

   private void testFederatedQueueShareUpstreamConnection(String queueName, int server0Connections, int server1Connections) throws Exception {
      ConnectionFactory cf1 = getCF(1);
      ConnectionFactory cf0 = getCF(0);
      try (Connection connection1 = cf1.createConnection(); Connection connection0 = cf0.createConnection()) {
         connection0.start();
         connection1.start();
         Session session0 = connection0.createSession();
         Session session1 = connection1.createSession();

         MessageConsumer consumer0 = session0.createConsumer(session0.createQueue(queueName));
         MessageConsumer consumer1 = session1.createConsumer(session1.createQueue(queueName));

         assertTrue(Wait.waitFor(() -> getServer(0).getConnectionCount() == server0Connections, 500, 100));
         assertTrue(Wait.waitFor(() -> getServer(1).getConnectionCount() == server1Connections, 500, 100));
         assertFalse(Wait.waitFor(() -> getServer(0).getConnectionCount() > server0Connections, 500, 100));
         assertFalse(Wait.waitFor(() -> getServer(1).getConnectionCount() > server1Connections, 500, 100));
      }
   }

   private void testFederatedQueueBiDirectional(String queueName, boolean shared) throws Exception {
      ConnectionFactory cf1 = getCF(1);
      ConnectionFactory cf0 = getCF(0);
      try (Connection connection1 = cf1.createConnection(); Connection connection0 = cf0.createConnection()) {
         connection0.start();
         Session session0 = connection0.createSession();
         Queue queue0 = session0.createQueue(queueName);
         MessageProducer producer0 = session0.createProducer(queue0);

         connection1.start();
         Session session1 = connection1.createSession();
         Queue queue1 = session1.createQueue(queueName);
         MessageProducer producer1 = session1.createProducer(queue1);
         MessageConsumer consumer0 = session0.createConsumer(queue0);

         //Test producers being on broker 0 and broker 1 and consumer on broker 0.
         producer0.send(session1.createTextMessage("hello"));
         assertNotNull(consumer0.receive(1000));

         producer1.send(session1.createTextMessage("hello"));
         assertNotNull(consumer0.receive(1000));

         //Wait to see if extra consumers are created - this tests to make sure there is no loop and tests the FederatedQueue metaDataFilterString
         //is working properly - should only be 1 consumer on each (1 for the local consumer on broker0 and 1 for the federated consumer on broker1)
         assertFalse(Wait.waitFor(() -> getServer(0).locateQueue(SimpleString.toSimpleString(queueName)).getConsumerCount() > 1, 500, 100));
         assertFalse(Wait.waitFor(() -> getServer(1).locateQueue(SimpleString.toSimpleString(queueName)).getConsumerCount() > 1, 500, 100));

         //Test consumer move from broker 0, to broker 1
         final int server1ConsumerCount = getServer(1).getConnectionCount();
         consumer0.close();
         Wait.waitFor(() -> ((QueueBinding) getServer(0).getPostOffice().getBinding(SimpleString.toSimpleString(queueName))).consumerCount() == 0, 1000);

         //Make sure we don't drop connection if shared
         if (shared) {
            assertFalse(Wait.waitFor(() -> getServer(1).getConnectionCount() == server1ConsumerCount - 1,
                    500, 100));
            assertTrue(server1ConsumerCount == getServer(1).getConnectionCount());
         }

         MessageConsumer consumer1 = session1.createConsumer(queue1);

         producer0.send(session1.createTextMessage("hello"));
         assertNotNull(consumer1.receive(1000));

         producer1.send(session1.createTextMessage("hello"));
         assertNotNull(consumer1.receive(1000));

         //Test consumers on both broker 0, and broker 1 that messages route to consumers on same broker
         consumer0 = session0.createConsumer(queue0);

         producer0.send(session1.createTextMessage("produce0"));
         producer1.send(session1.createTextMessage("produce1"));

         Message message0 = consumer0.receive(1000);
         assertNotNull(message0);
         assertEquals("produce0", ((TextMessage) message0).getText());

         Message message1 = consumer1.receive(1000);
         assertNotNull(message1);
         assertEquals("produce1", ((TextMessage) message1).getText());
      }
   }

   @Test
   public void testFederatedQueueChainOfBrokers() throws Exception {
      String queueName = getName();

      //Set queue up on all three brokers
      for (int i = 0; i < 3; i++) {
         getServer(i).createQueue(SimpleString.toSimpleString(queueName), RoutingType.ANYCAST, SimpleString.toSimpleString(queueName), null, true, false);
      }

      //Connect broker 0 (consumer will be here at end of chain) to broker 1
      FederationConfiguration federationConfiguration0 = createUpstreamFederationConfiguration("server1", queueName, true);
      getServer(0).getConfiguration().getFederationConfigurations().add(federationConfiguration0);
      getServer(0).getFederationManager().deploy();

      //Connect broker 1 (middle of chain) to broker 2
      FederationConfiguration federationConfiguration1 = createUpstreamFederationConfiguration("server2", queueName, true);
      getServer(1).getConfiguration().getFederationConfigurations().add(federationConfiguration1);
      getServer(1).getFederationManager().deploy();
      //Broker 2 we dont setup any federation as he is the upstream (head of the chain)

      //Now the test.


      ConnectionFactory cf2 = getCF(2);
      ConnectionFactory cf0 = getCF(0);
      try (Connection connection2 = cf2.createConnection(); Connection connection0 = cf0.createConnection()) {
         connection0.start();
         Session session0 = connection0.createSession();
         Queue queue0 = session0.createQueue(queueName);

         connection2.start();
         Session session2 = connection2.createSession();
         Queue queue2 = session2.createQueue(queueName);
         MessageProducer producer2 = session2.createProducer(queue2);
         MessageConsumer consumer0 = session0.createConsumer(queue0);


         //Test producers being on broker 2 and consumer on broker 0, with broker 2 being in the middle of the chain.
         producer2.send(session2.createTextMessage("hello"));
         assertNotNull(consumer0.receive(1000));
      }
   }

   @Test
   public void testFederatedQueueRemoteBrokerRestart() throws Exception {
      String queueName = getName();

      //Set queue up on both brokers
      for (int i = 0; i < 2; i++) {
         getServer(i).createQueue(SimpleString.toSimpleString(queueName), RoutingType.ANYCAST, SimpleString.toSimpleString(queueName), null, true, false);
      }

      FederationConfiguration federationConfiguration = createUpstreamFederationConfiguration("server1", queueName);
      getServer(0).getConfiguration().getFederationConfigurations().add(federationConfiguration);
      getServer(0).getFederationManager().deploy();

      ConnectionFactory cf1 = getCF(1);
      Connection connection1 = cf1.createConnection();
      connection1.start();
      Session session1 = connection1.createSession();
      Queue queue1 =  session1.createQueue(queueName);
      MessageProducer producer = session1.createProducer(queue1);
      producer.send(session1.createTextMessage("hello"));

      ConnectionFactory cf0 = getCF(0);
      Connection connection0 = cf0.createConnection();
      connection0.start();
      Session session0 = connection0.createSession();
      Queue queue0 =  session0.createQueue(queueName);
      MessageConsumer consumer0 = session0.createConsumer(queue0);

      assertNotNull(consumer0.receive(1000));

      producer.send(session1.createTextMessage("hello"));

      assertNotNull(consumer0.receive(1000));

      connection1.close();
      getServer(1).stop();

      assertNull(consumer0.receive(100));

      getServer(1).start();

      connection1 = cf1.createConnection();
      connection1.start();
      session1 = connection1.createSession();
      queue1 =  session1.createQueue(queueName);
      producer = session1.createProducer(queue1);
      producer.send(session1.createTextMessage("hello"));

      Wait.waitFor(() -> ((QueueBinding) getServer(1).getPostOffice().getBinding(SimpleString.toSimpleString(queueName))).consumerCount() == 1);

      assertNotNull(consumer0.receive(1000));
   }

   @Test
   public void testFederatedQueueLocalBrokerRestart() throws Exception {
      String queueName = getName();

      //Set queue up on both brokers
      for (int i = 0; i < 2; i++) {
         getServer(i).createQueue(SimpleString.toSimpleString(queueName), RoutingType.ANYCAST, SimpleString.toSimpleString(queueName), null, true, false);
      }

      FederationConfiguration federationConfiguration = createUpstreamFederationConfiguration("server1", queueName);
      getServer(0).getConfiguration().getFederationConfigurations().add(federationConfiguration);
      getServer(0).getFederationManager().deploy();

      ConnectionFactory cf1 = getCF(1);
      Connection connection1 = cf1.createConnection();
      connection1.start();
      Session session1 = connection1.createSession();
      Queue queue1 =  session1.createQueue(queueName);
      MessageProducer producer = session1.createProducer(queue1);
      producer.send(session1.createTextMessage("hello"));

      ConnectionFactory cf0 = getCF(0);
      Connection connection0 = cf0.createConnection();
      connection0.start();
      Session session0 = connection0.createSession();
      Queue queue0 =  session0.createQueue(queueName);
      MessageConsumer consumer0 = session0.createConsumer(queue0);

      assertNotNull(consumer0.receive(1000));

      producer.send(session1.createTextMessage("hello"));

      assertNotNull(consumer0.receive(1000));

      connection0.close();
      getServer(0).stop();

      producer.send(session1.createTextMessage("hello"));

      getServer(0).start();
      Wait.waitFor(() -> getServer(0).isActive());

      connection0 = getCF(0).createConnection();
      connection0.start();
      session0 = connection0.createSession();
      queue0 =  session0.createQueue(queueName);
      consumer0 = session0.createConsumer(queue0);
      producer.send(session1.createTextMessage("hello"));

      Wait.waitFor(() -> ((QueueBinding) getServer(1)
            .getPostOffice()
            .getBinding(SimpleString.toSimpleString(queueName)))
            .consumerCount() == 1);

      assertNotNull(consumer0.receive(1000));
   }

   private FederationConfiguration createDownstreamFederationConfiguration(String connector, String queueName, Boolean includeFederated,
       String transportConfigurationRef) {
      return createDownstreamFederationConfiguration(null, connector, queueName, includeFederated, false, transportConfigurationRef);
   }

   private FederationConfiguration createDownstreamFederationConfiguration(String name, String connector, String queueName, Boolean includeFederated,
       boolean shareConnection, String transportConfigurationRef) {
      FederationDownstreamConfiguration downstreamConfiguration = new FederationDownstreamConfiguration();
      downstreamConfiguration.setName(name != null ? name : connector);
      downstreamConfiguration.getConnectionConfiguration().setStaticConnectors(Collections.singletonList(connector));
      downstreamConfiguration.getConnectionConfiguration().setCircuitBreakerTimeout(-1);
      downstreamConfiguration.getConnectionConfiguration().setShareConnection(shareConnection);
      downstreamConfiguration.addPolicyRef("QueuePolicy" + queueName);
      downstreamConfiguration.setUpstreamConfigurationRef(transportConfigurationRef);

      FederationConfiguration federationConfiguration = createFederationConfiguration(connector, queueName, includeFederated);
      federationConfiguration.addDownstreamConfiguration(downstreamConfiguration);

      return federationConfiguration;
   }

   private FederationConfiguration createDownstreamFederationConfiguration(String connector, String queueName, String transportConfigurationRef) {
      return createDownstreamFederationConfiguration(null, connector, queueName, null, false, transportConfigurationRef);
   }

   private FederationConfiguration createUpstreamFederationConfiguration(String connector, String queueName, Boolean includeFederated) {
      FederationUpstreamConfiguration upstreamConfiguration = createFederationUpstream(connector, queueName);

      FederationConfiguration federationConfiguration = createFederationConfiguration(connector, queueName, includeFederated);
      federationConfiguration.addUpstreamConfiguration(upstreamConfiguration);

      return federationConfiguration;
   }

   private FederationUpstreamConfiguration createFederationUpstream(String connector, String queueName) {

      FederationUpstreamConfiguration upstreamConfiguration = new FederationUpstreamConfiguration();
      upstreamConfiguration.setName("server1-upstream");
      upstreamConfiguration.getConnectionConfiguration().setStaticConnectors(Collections.singletonList(connector));
      upstreamConfiguration.getConnectionConfiguration().setCircuitBreakerTimeout(-1);
      upstreamConfiguration.addPolicyRef("QueuePolicy" + queueName);

      return upstreamConfiguration;
   }

   private FederationConfiguration createUpstreamFederationConfiguration(String connector, String queueName) {
      return createUpstreamFederationConfiguration(connector, queueName, null);
   }

   private FederationConfiguration createFederationConfiguration(String connector, String queueName, Boolean includeFederated) {

      FederationQueuePolicyConfiguration queuePolicyConfiguration = new FederationQueuePolicyConfiguration();
      queuePolicyConfiguration.setName( "QueuePolicy" + queueName);
      queuePolicyConfiguration.addInclude(new FederationQueuePolicyConfiguration.Matcher()
            .setQueueMatch(queueName).setAddressMatch("#"));
      if (includeFederated != null) {
         queuePolicyConfiguration.setIncludeFederated(includeFederated);
      }

      FederationConfiguration federationConfiguration = new FederationConfiguration();
      federationConfiguration.setName("default");
      federationConfiguration.addFederationPolicy(queuePolicyConfiguration);

      return federationConfiguration;
   }

   private void addTransformerConfiguration(final FederationConfiguration federationConfiguration, final String queueName) {
      federationConfiguration.addTransformerConfiguration(
         new FederationTransformerConfiguration("transformer", new TransformerConfiguration(TestTransformer.class.getName())));
      FederationQueuePolicyConfiguration policy = (FederationQueuePolicyConfiguration) federationConfiguration.getFederationPolicyMap().get("QueuePolicy" + queueName);
      policy.setTransformerRef("transformer");
   }

   private Message createTextMessage(Session session1, String group) throws JMSException {
      Message message = session1.createTextMessage("hello");
      message.setStringProperty("JMSXGroupID", group);
      return message;
   }

   public static class TestTransformer implements Transformer {

      static String TEST_PROPERTY = "transformed";

      @Override
      public org.apache.activemq.artemis.api.core.Message transform(org.apache.activemq.artemis.api.core.Message message) {
         message.putBooleanProperty(TEST_PROPERTY, true);
         return message;
      }
   }

}
