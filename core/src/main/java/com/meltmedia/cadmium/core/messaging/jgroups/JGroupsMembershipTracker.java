/**
 *    Copyright 2012 meltmedia
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.meltmedia.cadmium.core.messaging.jgroups;

import com.meltmedia.cadmium.core.ClusterMembers;
import com.meltmedia.cadmium.core.ConfigurationGitService;
import com.meltmedia.cadmium.core.ContentGitService;
import com.meltmedia.cadmium.core.commands.ExternalIpMessage;
import com.meltmedia.cadmium.core.commands.GitLocation;
import com.meltmedia.cadmium.core.commands.SyncRequest;
import com.meltmedia.cadmium.core.commands.WarInfoRequest;
import com.meltmedia.cadmium.core.config.ConfigManager;
import com.meltmedia.cadmium.core.git.DelayedGitServiceInitializer;
import com.meltmedia.cadmium.core.messaging.ChannelMember;
import com.meltmedia.cadmium.core.messaging.MembershipTracker;
import com.meltmedia.cadmium.core.messaging.Message;
import com.meltmedia.cadmium.core.messaging.MessageSender;
import com.meltmedia.cadmium.core.messaging.ProtocolMessage;
import com.meltmedia.cadmium.core.util.WarUtils;
import org.apache.commons.lang3.StringUtils;
import org.jgroups.Address;
import org.jgroups.JChannel;
import org.jgroups.MembershipListener;
import org.jgroups.View;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;

@Singleton
public class JGroupsMembershipTracker implements MembershipTracker, MembershipListener, Closeable {
  private final Logger log = LoggerFactory.getLogger(getClass());  
  
  protected MessageSender sender;
  
  protected JChannel channel;
  
  protected List<ChannelMember> members;
  
  protected ConfigManager configManager;
  
  protected DelayedGitServiceInitializer gitService;
  protected DelayedGitServiceInitializer configGitService;
  private Timer timer = new Timer();
  
  @Inject
  public JGroupsMembershipTracker(MessageSender sender, JChannel channel, @ClusterMembers List<ChannelMember> members, ConfigManager configManager, @ContentGitService DelayedGitServiceInitializer gitService, @ConfigurationGitService DelayedGitServiceInitializer configGitService) {
    this.sender = sender;
    this.channel = channel;
    this.members = members;
    this.configManager = configManager;
    this.gitService = gitService;
    this.configGitService = configGitService;
  }
  
//  @PostConstruct
//  @Scheduled(delay=2l, interval=3600l, unit=TimeUnit.MINUTES)
//  public void attainExternalIp() {
//    try {
//      log.debug("Updating my external ip address.");
//      String ip = PublicIpUtils.lookup();
//      sendIp(ip, null);
//    } catch(Throwable t) {
//      log.debug("Failed to lookup external ip address.", t);
//    }
//  }

  private void sendIp(String ip, ChannelMember mem) throws Exception {
    if(StringUtils.isNotBlank(ip)){
      ExternalIpMessage updateReq = new ExternalIpMessage();
      updateReq.setIp(ip);
      Message<ExternalIpMessage> msg = new Message<ExternalIpMessage>(ProtocolMessage.EXTERNAL_IP_MESSAGE, updateReq);
      sender.sendMessage(msg, mem);
    }
  }
  
  @PostConstruct
  public void init() {
    log.info("Starting membership tracker.");
    if(this.channel != null) {
      viewAccepted(this.channel.getView());
    }
  }

  @Override
  public void viewAccepted(View new_view) {
    if(this.members != null) {
      log.info("Received a new view ["+new_view.size()+"]");
      List<Address> memberAddresses = new_view.getMembers();
      
      List<ChannelMember> newMembers = addNewMembers(memberAddresses, new_view);
      
      pergeDroppedMembers(memberAddresses);
      
      fixCoordinator(new_view);
      
      sendStateMessages(newMembers);
      
      sendWarInfoMessages(newMembers);
      
      handleSyncRequest(new_view);
      log.info("Processed new view now there are ["+new_view.size()+"] members");
    } else {
      log.warn("Received a new view members list is null");
    }
  }

  private void fixCoordinator(View newView) {
    if(members != null) {
      for(ChannelMember member : members) {
        if(members.size() == 1 || isCoordinator(member.getAddress(), newView)) {
          member.setCoordinator(true);
          log.info("Coordinator is ["+member.getAddress()+"]");
        } else {
          member.setCoordinator(false);
          log.info("Member ["+member.getAddress()+"] is not coordinator");
        }
      }
    }
  }

  private void handleSyncRequest(View new_view) {
    log.info("Here is the new view {}", new_view);
    final ChannelMember coordinator = getCoordinator();    
    if(coordinator != null && !coordinator.isMine()) {
      timer.schedule(new TimerTask() {
        public void run() {
          log.debug("I'm not the coordinator!!!");
          if(gitService != null) {
            try {
              log.debug("Waiting for content git service to initialize.");
              gitService.getGitService();
              gitService.releaseGitService();
            } catch(Throwable t){}
          }
          if(configGitService != null) {
            try {
              log.debug("Waiting for config git service to initialize.");
              configGitService.getGitService();
              configGitService.releaseGitService();
            } catch(Throwable t){}
          }
          Properties configProperties = configManager.getDefaultProperties();
          SyncRequest request = new SyncRequest();
          if(configProperties.containsKey("repo") && configProperties.containsKey("branch") && configProperties.containsKey("git.ref.sha")) {
            request.setContentLocation(new GitLocation(
              configProperties.getProperty("repo"),
              configProperties.getProperty("branch"),
              configProperties.getProperty("git.ref.sha")));
            log.info("I have repo:{}, branch:{}, and sha:{} for content", new Object[] { configProperties.getProperty("repo"), configProperties.getProperty("branch"), configProperties.getProperty("git.ref.sha")});
          }
          if(configProperties.containsKey("config.repo") && configProperties.containsKey("config.branch") && configProperties.containsKey("config.git.ref.sha")) {
            request.setConfigLocation(new GitLocation(
              configProperties.getProperty("config.repo"),
              configProperties.getProperty("config.branch"),
              configProperties.getProperty("config.git.ref.sha")));
            log.info("I have repo:{}, branch:{}, and sha:{} for configuration", new Object[] { configProperties.getProperty("config.repo"), configProperties.getProperty("config.branch"), configProperties.getProperty("config.git.ref.sha")});
          }
          Message<SyncRequest> syncMessage = new Message<SyncRequest>(ProtocolMessage.SYNC, request);
          try{
            sender.sendMessage(syncMessage, coordinator);
          } catch(Exception e) {
            log.warn("Failed to send sync message: {}", e.getMessage());
          }
        }
      }, 50l);
    }
  }
  
  private void sendStateMessages(List<ChannelMember> newMembers) {
    if(newMembers != null) {
      for(ChannelMember cMember : newMembers) {
        Message<Void> stateMsg = new Message<Void>(ProtocolMessage.CURRENT_STATE, null);
        try {
          sender.sendMessage(stateMsg, cMember);
          sendMyIp(cMember);
        } catch (Exception e) {
          log.error("Failed to send message to check for peir's current state", e);
        }
      }
    }
  }
  
  private void sendWarInfoMessages(List<ChannelMember> newMembers) {
    try {
      Message<WarInfoRequest> stateMsg = new Message<WarInfoRequest>(ProtocolMessage.WAR_INFO, new WarInfoRequest());
      stateMsg.getBody().setWarInfo(WarUtils.getWarInfo(new File(System.getProperty("jboss.server.home.dir")+"/deploy", configManager.getWarFileName())));
      try {
        sender.sendMessage(stateMsg, null);
      } catch (Exception e) {
        log.error("Failed to send message to check for peir's WAR_INFO", e);
      }
    } catch(Exception e) {
      log.error("Failed to retrieve war information.", e);
    }
    if(newMembers != null) {
      for(ChannelMember cMember : newMembers) {
        Message<WarInfoRequest> stateMsg = new Message<WarInfoRequest>(ProtocolMessage.WAR_INFO, new WarInfoRequest());
        try {
          sender.sendMessage(stateMsg, cMember);
          sendMyIp(cMember);
        } catch (Exception e) {
          log.error("Failed to send message to check for peir's current state", e);
        }
      }
    }
  }

  private void pergeDroppedMembers(List<Address> memberAddresses) {
    List<ChannelMember> oldMembers = new ArrayList<ChannelMember>();
    oldMembers.addAll(members);
    for(ChannelMember member : oldMembers) {
      boolean found = false;
      for(Address newMember : memberAddresses) {
        if(member.getAddress().toString().equals(newMember.toString())) {
          found = true;
        }
      }
      if(!found) {
        members.remove(member);
        log.info("Purging old member {}, was coordinator {}, me {}", new Object[] {member.getAddress().toString(), member.isCoordinator(), member.isMine()});
      }
    }
  }

  private List<ChannelMember> addNewMembers(List<Address> memberAddresses, View newView) {
    List<ChannelMember> newMembers = new ArrayList<ChannelMember>();
    for(Address member : memberAddresses) {
      ChannelMember cMember = new ChannelMember(member, isCoordinator(member, newView), isMine(member));
      if(!members.contains(cMember)) {
        log.info("Discovered new member {}, coordinator {}, me {}", new Object[] {member.toString(), cMember.isCoordinator(), cMember.isMine()});
        members.add(cMember);
        newMembers.add(cMember);
      }
    }
    return newMembers;
  }
  
  private boolean isCoordinator(Address newAddress, View newView) {
    boolean coord = false;
    if(newAddress != null && newAddress.toString().equals(newView.getCreator().toString())) {
      coord = true;
    }
    return coord;
  }
  
  private boolean isMine(Address newAddress) {
    boolean mine = false;
    if(newAddress != null && newAddress.toString().equals(channel.getAddress().toString())) {
      mine = true;
    }
    return mine;
  }
  
  @Override
  public ChannelMember getCoordinator() {
    if(members != null) {
      for(ChannelMember member : members) {
        if(member.isCoordinator()) {
          return member;
        }
      }
    }
    return null;
  }

  @Override
  public void updateMembersIp(Address aMember, String ip) {
    if(members != null) {
      ChannelMember memberToCheck = new ChannelMember(aMember);
      for(ChannelMember member : members) {
        if(member.equals(memberToCheck)) {
          log.debug("Updating members {} ip address to {}", member, ip);
          member.setExternalIp(ip);
          break;
        }
      }
    }
  }

  @Override
  public String getMembersIp(Address aMember) {
    if(members != null) {
      ChannelMember memberToCheck = new ChannelMember(aMember);
      for(ChannelMember member : members) {
        if(member.equals(memberToCheck)) {
          log.debug("getting members {} ip", member);
          return member.getExternalIp();
        }
      }
    }
    return null;
  }

  @Override
  public void sendMyIp(ChannelMember member) throws Exception {
    if(members != null) {
      for(ChannelMember mem : members) {
        if(mem.isMine() && StringUtils.isNotBlank(mem.getExternalIp())) {
          sendIp(mem.getExternalIp(), member);
          break;
        }
      }
    }
  }

  @Override
  public void block() {
    
  }

  @Override
  public void suspect(Address arg0) {
    
  }

  @Override
  public void unblock() {
    
  }

  @Override
  public void close() throws IOException {
    this.channel = null;
    try {
      timer.purge();
    } catch(Exception e){}
    try {
      timer.cancel();
    } catch(Exception e){}
    timer = null;
  }
}
