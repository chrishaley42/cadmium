package com.meltmedia.cadmium.core.worker;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.meltmedia.cadmium.core.FileSystemManager;
import com.meltmedia.cadmium.core.git.GitService;

public class CreateNewRenderedDirectoryTask implements Callable<Boolean> {
  private final Logger log = LoggerFactory.getLogger(getClass());
  
  private GitService service;
  private String lastDirectory;
  private Map<String, String> properties;
  private Future<Boolean> previousTask;
  
  public CreateNewRenderedDirectoryTask(GitService service, String lastDirectory, Map<String, String> properties, Future<Boolean> previousTask) {
    this.service = service;
    this.lastDirectory = lastDirectory;
    this.properties = properties;
    this.previousTask = previousTask;
  }

  @Override
  public Boolean call() throws Exception {
    if(previousTask != null) {
      Boolean lastResponse = previousTask.get();
      if(lastResponse != null && !lastResponse.booleanValue() ) {
        throw new Exception("Previous task failed");
      }
    }
    log.info("Creating a new directory for the rendered content");
    try{
      String nextDirectory = FileSystemManager.getNextDirInSequence(lastDirectory);
      if(nextDirectory != null) {
        GitService git = GitService.cloneRepo(service.getRepositoryDirectory(), nextDirectory);
        if(git != null) {
          git.close();
          FileSystemManager.deleteDeep(FileSystemManager.getChildDirectoryIfExists(nextDirectory, ".git"));
          properties.put("nextDirectory", nextDirectory);
        } else {
          log.warn("Failed to clone repo to "+nextDirectory);
          return false;
        }
      } else {
        log.warn("Failed to get next directory");
        return false;
      }
    } catch(Exception e) {
      log.error("Failed to create new rendered directory", e);
      return false;
    }
    return true;
  }

}