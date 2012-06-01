package com.meltmedia.cadmium.core.git;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepository;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.meltmedia.cadmium.core.FileSystemManager;

public class GitService {
  private static final Logger log = LoggerFactory.getLogger(GitService.class);
  
  protected Repository repository;
  protected Git git;
  
  public GitService(Repository repository) {
    this.repository = repository;
    git = new Git(this.repository);
  }
  
  protected GitService(Git gitRepo) {
    this.git = gitRepo;
    this.repository = gitRepo.getRepository();
  }
  
  public static void setupSsh(String sshDir) {
    SshSessionFactory.setInstance(new GithubConfigSessionFactory(sshDir));
  }
  
  public static GitService createGitService(String repositoryDirectory) throws Exception {
    if(repositoryDirectory != null) {
      if(!repositoryDirectory.endsWith(".git")) {
        String gitDir = FileSystemManager.getChildDirectoryIfExists(repositoryDirectory, ".git");
        if(gitDir != null) {
          repositoryDirectory = gitDir;
        } else {
          repositoryDirectory = null;
        }
      }
      if(repositoryDirectory != null 
          && FileSystemManager.isDirector(repositoryDirectory) 
          && FileSystemManager.canRead(repositoryDirectory)
          && FileSystemManager.canWrite(repositoryDirectory)){
        return new GitService(new FileRepository(repositoryDirectory));        
      }
    }
    throw new Exception("Invalid git repo");
  }
  
  public static GitService cloneRepo(String uri, String dir) throws Exception {
    if(dir == null || !FileSystemManager.exists(dir)) {
      log.debug("Cloning \""+uri+"\" to \""+dir+"\"");
      CloneCommand clone = Git.cloneRepository();
      clone.setCloneAllBranches(false);
      clone.setCloneSubmodules(false);
      if(dir != null) {
        clone.setDirectory(new File(dir));
      }
      clone.setURI(uri);
      
      return new GitService(clone.call());
    } 
    return null;
  }
  
  public static GitService init(String site, String dir) throws Exception {
  	String repoPath = dir + "/" + site;
  	log.debug("Repository Path :" + repoPath);
  	Repository repo = new FileRepository(repoPath + "/.git");
  	try {
  		repo.create();
  		Git git = new Git(repo);
  		
  		File localGitRepo = new File(repoPath);
  		localGitRepo.mkdirs();
  		new File(localGitRepo, "delete.me").createNewFile();
  		
  		git.add().addFilepattern("delete.me").call();
  		git.commit().setMessage("initial commit").call();
  		return new GitService(git);  		
  	} catch (IllegalStateException e) {
  		System.out.println("Repo Already exists locally");
  	}
		return null;
  }
  
  public String getRepositoryDirectory() throws Exception {
    return this.repository.getDirectory().getAbsolutePath();
  }
  
  public String getBaseDirectory() throws Exception {
    return FileSystemManager.getParent(this.repository.getDirectory().getParent());
  }
  
  public boolean pull() throws Exception {
    log.debug("Pulling latest updates from remote git repo");
    return git.pull().call().isSuccessful();
  }
  
  public void switchBranch(String branchName) throws Exception {
    if(branchName != null && !repository.getBranch().equals(branchName)) {
      log.info("Switching branch from {} to {}", repository.getBranch(), branchName);
      CheckoutCommand checkout = git.checkout();
      checkout.setName(branchName);
      if(this.repository.getRef(branchName) == null) {
        CreateBranchCommand create = git.branchCreate();
        create.setName(branchName);
        create.setUpstreamMode(SetupUpstreamMode.SET_UPSTREAM);
        create.setStartPoint("origin/"+branchName);
        create.call();
      }
      checkout.call();
    }
  }
  
  public void resetToRev(String revision) throws Exception {
    if(revision != null) {
      log.info("Resetting to sha {}", revision);
      git.reset().setMode(ResetType.HARD).setRef(revision).call();
    }
  }
  
  public boolean newRemoteBranch(String branchName) throws Exception {
    try{
      log.info("Purging branches that no longer have remotes.");
      git.fetch().setRemoveDeletedRefs(true).call();
    } catch(Exception e) {
      log.warn("Tried to fetch from remote when there is no remote.");
      return false;
    }
    log.info("Getting list of existing branches.");
    List<Ref> refs = git.branchList().setListMode(ListMode.ALL).call();
    boolean branchExists = false; 
    if(refs != null) {
      for(Ref ref : refs) {
        if(ref.getName().endsWith("/" + branchName)) {
          branchExists = true;
          break;
        }
      }
    }
    if(!branchExists) {
      Ref ref = git.branchCreate().setName(branchName).call();
      git.push().add(ref).call();
      return true;
    } else {
      log.info(branchName + " already exists.");
    }
    return false;
  }
  
  public String getBranchName() throws Exception {
    return repository.getBranch();
  }
  
  public String getCurrentRevision() throws Exception {
    return repository.getRef(getBranchName()).getObjectId().getName();
  }
  
  public void close() throws Exception {
    this.repository.close();
  }
}
