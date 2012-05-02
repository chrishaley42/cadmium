package com.meltmedia.cadmium.jgroups.jersey;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import org.jgroups.JChannel;
import org.jgroups.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.meltmedia.cadmium.jgroups.receivers.UpdateChannelReceiver;

@Path("/update")
public class UpdateService {
  private final Logger log = LoggerFactory.getLogger(getClass());
  public static final String REPOSITORY_LOCATION = "GitLocation";

  @Inject
  protected JChannel channel;
  
  @Inject 
  @Named(REPOSITORY_LOCATION)
  protected String repo;
  
  @GET
  @Produces("text/plain")
  public String update() throws Exception {
    if(channel != null) {
      log.debug("Sending update message");
      channel.send(new Message(null, null, UpdateChannelReceiver.ProtocolMessage.UPDATE.name() + " repo="+repo));
    } else {
      log.error("Channel is not wired");
    }
    return "ok";
  }
}