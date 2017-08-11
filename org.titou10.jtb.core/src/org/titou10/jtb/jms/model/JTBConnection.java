/*
 * Copyright (C) 2015-2017 Denis Forveille titou10.titou10@gmail.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.titou10.jtb.jms.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.jms.Connection;
import javax.jms.ConnectionMetaData;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;

import org.eclipse.jface.preference.PreferenceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.titou10.jtb.config.gen.DestinationFilter;
import org.titou10.jtb.config.gen.SessionDef;
import org.titou10.jtb.jms.qm.ConnectionData;
import org.titou10.jtb.jms.qm.QManager;
import org.titou10.jtb.jms.qm.QueueData;
import org.titou10.jtb.jms.qm.TopicData;
import org.titou10.jtb.util.Constants;

/**
 * 
 * A JTBConnection represents a JMS Connection to a Q Manager
 * 
 * @author Denis Forveille
 * 
 */
public class JTBConnection {

   private static final Logger  log                        = LoggerFactory.getLogger(JTBConnection.class);

   private static final Long    RECEIVE_MAX_WAIT_REMOVE    = 1 * 100L;                                    // 1 secs
   private static final Long    RECEIVE_MAX_WAIT_REMOVE_ID = 30 * 1000L;                                  // 30 seconds

   private static final String  UNKNOWN                    = "Unknown";

   // Global unique ID for the session
   private static long          CONN_CLIENT_ID             = System.currentTimeMillis();

   private JTBSessionClientType jtbSessionClientType;
   private SessionDef           sessionDef;
   private QManager             qm;
   private PreferenceStore      ps;

   // JMS Provider Information
   private boolean              connected;
   private Connection           jmsConnection;
   private Session              jmsSession;
   private Map<String, Session> jmsAsynchronousSessions    = new HashMap<>();

   // Connection Metadata
   private String               metaJMSVersion             = UNKNOWN;
   private String               metaJMSProviderName        = UNKNOWN;
   private List<String>         metaJMSPropertyNames       = new ArrayList<>(16);
   private String               metaProviderVersion        = UNKNOWN;

   // Children
   private SortedSet<JTBQueue>  jtbQueues;
   private SortedSet<JTBTopic>  jtbTopics;

   private SortedSet<JTBQueue>  jtbQueuesFiltered;
   private SortedSet<JTBTopic>  jtbTopicsFiltered;

   // Destination filter
   private String               filterPattern;
   private boolean              apply;
   private String               filterRegexPattern;

   // ------------------------
   // Constructor
   // ------------------------

   public JTBConnection(PreferenceStore ps,
                        JTBSessionClientType jtbSessionClientType,
                        SessionDef sessionDef,
                        QManager qm,
                        DestinationFilter df) {
      this.ps = ps;
      this.jtbSessionClientType = jtbSessionClientType;
      this.sessionDef = sessionDef;
      this.qm = qm;

      this.jtbQueues = new TreeSet<>();
      this.jtbTopics = new TreeSet<>();
      this.jtbQueuesFiltered = new TreeSet<>();
      this.jtbTopicsFiltered = new TreeSet<>();

      this.connected = false;

      if (df != null) {
         this.apply = df.isApply();
         this.filterPattern = df.getPattern();
      } else {
         this.apply = false;
      }

      updateFilterData(filterPattern, apply);
   }

   // ------------------------
   // Helpers
   // ------------------------
   public String getSessionName() {
      return sessionDef.getName();
   }

   public Boolean isConnected() {
      return connected;
   }

   @Override
   public String toString() {
      StringBuilder builder = new StringBuilder(128);
      builder.append("JTBConnection [jtbSessionClientType=");
      builder.append(jtbSessionClientType);
      builder.append(", isConnected()=");
      builder.append(isConnected());
      builder.append("]");
      return builder.toString();
   }

   // ------------------------
   // Filter Management
   // ------------------------
   public void updateFilterData(boolean apply) {
      updateFilterData(this.filterPattern, apply);
   }

   public void updateFilterData(String filterPattern, boolean apply) {
      this.filterPattern = filterPattern;

      if (filterPattern == null) {
         this.apply = false;
         this.filterRegexPattern = null;
      } else {
         this.apply = apply;
         filterRegexPattern = filterPattern.replaceAll(";", "|");
         filterRegexPattern = filterRegexPattern.replaceAll("\\.", "\\\\.").replaceAll("\\?", ".").replaceAll("\\*", ".*");
      }

      buildFilteredSortedSet();
   }

   private void buildFilteredSortedSet() {

      jtbQueuesFiltered.clear();
      jtbTopicsFiltered.clear();

      if (filterRegexPattern == null) {
         jtbQueuesFiltered.addAll(jtbQueues);
         jtbTopicsFiltered.addAll(jtbTopics);
         return;
      }
      for (JTBQueue jtbQueue : jtbQueues) {
         if (jtbQueue.getName().matches(filterRegexPattern)) {
            jtbQueuesFiltered.add(jtbQueue);
         }
      }

      for (JTBTopic jtbTopic : jtbTopics) {
         if (jtbTopic.getName().matches(filterRegexPattern)) {
            jtbTopicsFiltered.add(jtbTopic);
         }
      }
   }

   public SortedSet<JTBQueue> getJtbQueuesToDisplay() {
      if (jtbSessionClientType.isUseFiltering()) {
         if (apply) {
            return jtbQueuesFiltered;
         }
      }
      return jtbQueues;
   }

   public SortedSet<JTBTopic> getJtbTopicsToDisplay() {
      if (jtbSessionClientType.isUseFiltering()) {
         if (apply) {
            return jtbTopicsFiltered;
         }
      }
      return jtbTopics;
   }

   public String getFilterPattern() {
      return filterPattern;
   }

   public void setFilterPattern(String filterPattern) {
      this.filterPattern = filterPattern;
   }

   public boolean isFilterApplied() {
      if (jtbSessionClientType.isUseFiltering()) {
         return apply;
      }
      return false;
   }

   // ------------------------
   // Session Interaction
   // ------------------------
   public void connectOrDisconnect() throws Exception {
      if (this.isConnected()) {
         disConnect();
      } else {
         connect();
      }
   }

   @SuppressWarnings("unchecked")
   private void connect() throws Exception {
      log.debug("connect '{}'", this);

      boolean showSystemObject = ps.getBoolean(Constants.PREF_SHOW_SYSTEM_OBJECTS);
      String clientIdPrefix = ps.getString(Constants.PREF_CONN_CLIENT_ID_PREFIX);

      // Must be a unique Name as JMS API restricts duplicate usage
      String clientId = clientIdPrefix + "-" + CONN_CLIENT_ID++;

      ConnectionData cd = qm.connect(sessionDef, showSystemObject, clientId);
      jmsConnection = cd.getJmsConnection();
      jmsSession = jmsConnection.createSession(true, Session.SESSION_TRANSACTED);

      SortedSet<QueueData> qDatas = cd.getListQueueData();
      if (qDatas != null) {
         for (QueueData qData : qDatas) {
            log.debug("jmsSession.createQueue '{}'", qData.getName());
            Queue jmsQ = jmsSession.createQueue(qData.getName());
            jtbQueues.add(new JTBQueue(this, qData.getName(), jmsQ, qData.isBrowsable()));
         }
      }

      SortedSet<TopicData> tDatas = cd.getListTopicData();
      if (tDatas != null) {
         for (TopicData tData : tDatas) {
            log.debug("jmsSession.createTopic '{}'", tData.getName());
            Topic jmsTopic = jmsSession.createTopic(tData.getName());
            jtbTopics.add(new JTBTopic(this, tData.getName(), jmsTopic));
         }
      }

      buildFilteredSortedSet();

      // Connection MetadaData
      ConnectionMetaData meta = jmsConnection.getMetaData();
      metaJMSProviderName = meta.getJMSProviderName();
      metaProviderVersion = meta.getProviderVersion();
      metaJMSVersion = meta.getJMSVersion();
      metaJMSPropertyNames = Collections.list(meta.getJMSXPropertyNames());
      Collections.sort(metaJMSPropertyNames);

      connected = true;
   }

   private void disConnect() throws JMSException {
      log.debug("disconnect : '{}'", this);

      // No need to close sessions, producers etc . They will be closed when closing connection
      try {
         jmsConnection.stop();
         qm.close(jmsConnection);
      } catch (Exception e) {
         log.warn("Exception occured when disconnecting. Ignoring: {}", e.getMessage());
      }

      connected = false;
      // jmsSessionAsynchronous = null;
      jmsSession = null;
      jmsAsynchronousSessions.clear();

      jtbQueues.clear();
      jtbQueuesFiltered.clear();
      jtbTopics.clear();
      jtbTopicsFiltered.clear();

      metaJMSVersion = UNKNOWN;
      metaJMSProviderName = UNKNOWN;
      metaProviderVersion = UNKNOWN;
      metaJMSPropertyNames.clear();
   }

   // ----------------------
   // Create/Remove Messages
   // ----------------------

   public Message createJMSMessage(JTBMessageType jtbMessageType) throws JMSException {
      log.debug("createJMSMessage {}", jtbMessageType);
      switch (jtbMessageType) {
         case TEXT:
            return jmsSession.createTextMessage();

         case BYTES:
            return jmsSession.createBytesMessage();

         case MESSAGE:
            return jmsSession.createMessage();

         case MAP:
            return jmsSession.createMapMessage();

         case OBJECT:
            return jmsSession.createObjectMessage();

         case STREAM:
            return jmsSession.createStreamMessage();
      }
      return null; // Impossible
   }

   public void removeMessage(JTBMessage jtbMessage) throws JMSException {
      log.debug("Remove Message {}", jtbMessage);

      Message message = jtbMessage.getJmsMessage();
      JTBDestination jtbDestination = jtbMessage.getJtbDestination();

      StringBuilder sb = new StringBuilder(128);
      sb.append("JMSMessageID='");
      sb.append(message.getJMSMessageID());
      sb.append("'");

      try (MessageConsumer consumer = jmsSession.createConsumer(jtbDestination.getJmsDestination(), sb.toString());) {
         message = consumer.receive(RECEIVE_MAX_WAIT_REMOVE_ID);
         if (message == null) {
            throw new JMSException("JMSToolBox was not able to receive the message within " + RECEIVE_MAX_WAIT_REMOVE_ID + "ms");
         }
      }

      jmsSession.commit();
   }

   public List<JTBMessage> removeFirstMessages(JTBDestination jtbDestination, int limit) throws JMSException {
      log.debug("Remove First {} Message from {}", limit, jtbDestination);

      List<JTBMessage> jtbMessages = new ArrayList<>(limit);

      Message message;
      int n = 0;
      try (MessageConsumer consumer = jmsSession.createConsumer(jtbDestination.getJmsDestination());) {
         while (n++ < limit) {
            message = consumer.receive(RECEIVE_MAX_WAIT_REMOVE); // Seems necessary for ActiveMQ instead of receiveNoWait()
            if (message != null) {
               message.acknowledge();
               jtbMessages.add(new JTBMessage(jtbDestination, message));
            } else {
               break;
            }
         }
      }

      jmsSession.commit();

      return jtbMessages;

   }

   public int emptyQueue(JTBQueue jtbQueue) throws JMSException {
      Message message = null;
      Integer nb = 0;
      try (MessageConsumer consumer = jmsSession.createConsumer(jtbQueue.getJmsDestination());) {
         do {
            message = consumer.receive(RECEIVE_MAX_WAIT_REMOVE); // Seems necessary for ActiveMQ instead of receiveNoWait()
            if (message != null) {
               message.acknowledge();
               nb++;
            }
         } while (message != null);
      }
      jmsSession.commit();

      return nb;
   }

   public void sendMessage(JTBMessage jtbMessage, JTBDestination jtbDestination) throws JMSException {
      log.debug("sendMessage {} to {}", jtbMessage, jtbDestination);
      Message m = jtbMessage.getJmsMessage();
      Destination d = jtbDestination.getJmsDestination();

      try (MessageProducer p = jmsSession.createProducer(d);) {
         if (jtbMessage.getDeliveryMode() != null) {
            p.setDeliveryMode(jtbMessage.getDeliveryMode().intValue());
         }
         if (jtbMessage.getPriority() != null) {
            p.setPriority(jtbMessage.getPriority());
         }
         if (jtbMessage.getTimeToLive() != null) {
            p.setTimeToLive(jtbMessage.getTimeToLive());
         }
         if (jtbMessage.getReplyToDestinationName() != null) {
            // Destination replyToDest = jmsSession.createTemporaryQueue();
            Destination replyToDest = jmsSession.createQueue(jtbMessage.getReplyToDestinationName());
            m.setJMSReplyTo(replyToDest);
         }
         if (jtbMessage.getDeliveryDelay() != null) {
            try {
               p.setDeliveryDelay(jtbMessage.getDeliveryDelay());
            } catch (Throwable t) {
               log.warn("JMS 2.0 feature 'setDeliveryDelay' failed. ignoring. Msg: {}", t.getMessage());
            }
         }

         p.send(m);
      }

      jmsSession.commit();
      log.debug("Message sent");
   }

   public void sendMessage(JTBMessage jtbMessage) throws JMSException {
      sendMessage(jtbMessage, jtbMessage.getJtbDestination());
   }

   // ----------------------
   // Topic Subscribver
   // ----------------------
   public MessageConsumer createTopicSubscriber(JTBTopic jtbTopic,
                                                MessageListener messageListener,
                                                String selector) throws JMSException {
      // JMS does not allow to perform synchronous and asynchronous calls simultaneously
      // We must use a separate session for this per topic
      Session jmsAsynchronousSession = jmsAsynchronousSessions.get(jtbTopic.getName());
      if (jmsAsynchronousSession == null) {
         jmsAsynchronousSession = jmsConnection.createSession(true, Session.SESSION_TRANSACTED);
         jmsAsynchronousSessions.put(jtbTopic.getName(), jmsAsynchronousSession);
      }
      MessageConsumer messageConsumer = jmsAsynchronousSession.createConsumer(jtbTopic.getJmsDestination(), selector);
      messageConsumer.setMessageListener(messageListener);
      return messageConsumer;
   }

   // ------------------------
   // Browse/Search Messages
   // ------------------------
   public Date getFirstMessageTimestamp(JTBQueue jtbQueue) throws JMSException {
      try (QueueBrowser browser = jmsSession.createBrowser(jtbQueue.getJmsQueue());) {
         Enumeration<?> msgs = browser.getEnumeration();
         while (msgs.hasMoreElements()) {
            Message firstMessage = (Message) msgs.nextElement();
            return new Date(firstMessage.getJMSTimestamp());
         }
      }

      jmsSession.commit();

      return null;
   }

   public List<JTBMessage> browseQueue(JTBQueue jtbQueue, int maxMessages) throws JMSException {

      int limit = Integer.MAX_VALUE;
      if (maxMessages != 0) {
         limit = maxMessages - 2;
      }

      List<JTBMessage> jtbMessages = new ArrayList<>(256);
      try (QueueBrowser browser = jmsSession.createBrowser(jtbQueue.getJmsQueue());) {
         int n = 0;
         Enumeration<?> msgs = browser.getEnumeration();
         while (msgs.hasMoreElements()) {
            Message message = (Message) msgs.nextElement();
            jtbMessages.add(new JTBMessage(jtbQueue, message));
            if (n++ > limit) {
               break;
            }
         }
      }

      jmsSession.commit();

      return jtbMessages;
   }

   public List<JTBMessage> searchQueue(JTBQueue jtbQueue, String searchString, int maxMessages) throws JMSException {

      int limit = Integer.MAX_VALUE;
      if (maxMessages != 0) {
         limit = maxMessages - 2;
      }

      List<JTBMessage> jtbMessages = new ArrayList<>(256);
      try (QueueBrowser browser = jmsSession.createBrowser(jtbQueue.getJmsQueue());) {
         int n = 0;
         Enumeration<?> msgs = browser.getEnumeration();
         while (msgs.hasMoreElements()) {
            Message message = (Message) msgs.nextElement();

            // Search on text payload of Text Messages
            if (message instanceof TextMessage) {
               String text = ((TextMessage) message).getText();
               if (text.contains(searchString)) {
                  jtbMessages.add(new JTBMessage(jtbQueue, message));
                  if (n++ > limit) {
                     break;
                  }
               }
            }

            // Search on "values" of Map Message content
            if (message instanceof MapMessage) {
               MapMessage mm = (MapMessage) message;
               Enumeration<?> mapNames = mm.getMapNames();
               while (mapNames.hasMoreElements()) {
                  String key = (String) mapNames.nextElement();
                  Object value = mm.getObject(key);
                  if (value != null) {
                     if (value.toString().contains(searchString)) {
                        jtbMessages.add(new JTBMessage(jtbQueue, message));
                        if (n++ > limit) {
                           break;
                        }
                     }
                  }
               }
            }
         }
      }

      jmsSession.commit();

      return jtbMessages;
   }

   public List<JTBMessage> browseQueueWithSelector(JTBQueue jtbQueue, String searchString, int maxMessages) throws JMSException {

      int limit = Integer.MAX_VALUE;
      if (maxMessages != 0) {
         limit = maxMessages - 2;
      }

      List<JTBMessage> jtbMessages = new ArrayList<>(64);
      try (QueueBrowser browser = jmsSession.createBrowser(jtbQueue.getJmsQueue(), searchString);) {
         int n = 0;
         Enumeration<?> msgs = browser.getEnumeration();
         while (msgs.hasMoreElements()) {
            Message message = (Message) msgs.nextElement();
            jtbMessages.add(new JTBMessage(jtbQueue, message));
            if (n++ > limit) {
               break;
            }
         }
      }

      jmsSession.commit();

      return jtbMessages;
   }

   // ------------------------
   // Helpers
   // ------------------------

   public JTBDestination getJTBDestinationByName(String destinationName) {
      for (JTBQueue jtbQueue : jtbQueues) {
         if (jtbQueue.getName().equals(destinationName)) {
            return jtbQueue;
         }
      }

      for (JTBTopic jtbTopic : jtbTopics) {
         if (jtbTopic.getName().equals(destinationName)) {
            return jtbTopic;
         }
      }

      return null;
   }

   // ------------------------
   // Standard Getters/Setters
   // ------------------------

   public SortedSet<JTBQueue> getJtbQueues() {
      return jtbQueues;
   }

   public SortedSet<JTBTopic> getJtbTopics() {
      return jtbTopics;
   }

   public String getMetaJMSVersion() {
      return metaJMSVersion;
   }

   public String getMetaJMSProviderName() {
      return metaJMSProviderName;
   }

   public String getMetaProviderVersion() {
      return metaProviderVersion;
   }

   public List<String> getMetaJMSPropertyNames() {
      return metaJMSPropertyNames;
   }

   public SortedSet<JTBQueue> getJtbQueuesFiltered() {
      return jtbQueuesFiltered;
   }

   public SortedSet<JTBTopic> getJtbTopicsFiltered() {
      return jtbTopicsFiltered;
   }

   public QManager getQm() {
      return qm;
   }

   public void setQm(QManager qm) {
      this.qm = qm;
   }

   public Connection getJmsConnection() {
      return jmsConnection;
   }
}
