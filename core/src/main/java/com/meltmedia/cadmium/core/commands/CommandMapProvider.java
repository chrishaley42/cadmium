package com.meltmedia.cadmium.core.commands;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import com.meltmedia.cadmium.core.CommandAction;
import com.meltmedia.cadmium.core.messaging.ProtocolMessage;

@Singleton
public class CommandMapProvider implements Provider<Map<ProtocolMessage, CommandAction>> {
  
  @Inject
  @Named("CURRENT_STATE")
  protected CommandAction currentState;
  
  @Inject
  @Named("STATE_UPDATE")
  protected CommandAction stateUpdate;
  
  @Inject
  @Named("SYNC")
  protected CommandAction sync;
  
  @Inject
  @Named("UPDATE")
  protected CommandAction update;
  
  @Inject
  @Named("UPDATE_DONE")
  protected CommandAction updateDone;
  
  @Inject
  @Named("UPDATE_FAILED")
  protected CommandAction updateFailed;
  
  @Inject
  @Named("MAINTENANCE")
  protected CommandAction maintenance;
  
  private Map<ProtocolMessage, CommandAction> actionMap = null;
  
  public CommandMapProvider() {}

  @Override
  public Map<ProtocolMessage, CommandAction> get() {
    if(actionMap == null) {
      actionMap = new HashMap<ProtocolMessage, CommandAction>();
      actionMap.put(ProtocolMessage.CURRENT_STATE, currentState);
      actionMap.put(ProtocolMessage.STATE_UPDATE, stateUpdate);
      actionMap.put(ProtocolMessage.SYNC, sync);
      actionMap.put(ProtocolMessage.UPDATE, update);
      actionMap.put(ProtocolMessage.UPDATE_DONE, updateDone);
      actionMap.put(ProtocolMessage.UPDATE_FAILED, updateFailed);
      actionMap.put(ProtocolMessage.MAINTENANCE, maintenance);
      
      actionMap = Collections.unmodifiableMap(actionMap);
    }
    return actionMap;
  }

}
