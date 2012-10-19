package com.meltmedia.cadmium.core.scheduler;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.BindingAnnotation;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.meltmedia.cadmium.core.CoordinatorOnly;
import com.meltmedia.cadmium.core.Scheduled;
import com.meltmedia.cadmium.core.messaging.MembershipTracker;

/**
 * Wraps a method call with a Runnable to execute in a Executor.
 * 
 * @author John McEntire
 *
 */
public class SchedulerTask {
  
  private final Logger log = LoggerFactory.getLogger(getClass());
  
  @Inject
  protected Injector injector;
  
  @Inject
  protected MembershipTracker membershipTracker;
  
  private Runnable task = new RunnableTask();
  private Method method;
  private Class<?> type;
  private boolean coordinatorOnly = false;
  private Scheduled annotation;
  
  /**
   * Creates an instance with a given @Schedule Annotated method to run.
   * 
   * @param type
   * @param methodToRun
   */
  public SchedulerTask(Class<?> type, Method methodToRun) {
    this.type = type;
    if(methodToRun != null) {
      this.method = methodToRun;
    } else {
      checkRunnable(type);
    }
    init(type);
  }

  /**
   * Sets all values needed for the scheduling of this Runnable.
   * 
   * @param type
   */
  private void init(Class<?> type) {
    if(method.isAnnotationPresent(CoordinatorOnly.class) 
        || type.isAnnotationPresent(CoordinatorOnly.class)) {
      coordinatorOnly = true;
    }
    if(method.isAnnotationPresent(Scheduled.class)) {
      annotation = method.getAnnotation(Scheduled.class);
    } else if(type.isAnnotationPresent(Scheduled.class)) {
      annotation = type.getAnnotation(Scheduled.class);
    }
  }
  
  /**
   * Creates an instance with either the run method of an existing @Schedule Annotated Runnable.
   * 
   * @param type
   */
  public SchedulerTask(Class<?> type) {
    this.type = type;
    checkRunnable(type);

    init(type);
  }

  /**
   * Checks if the type is a Runnable and gets the run method.
   * 
   * @param type
   */
  private void checkRunnable(Class<?> type) {
    if(Runnable.class.isAssignableFrom(type)) {
      try{
        this.method = type.getMethod("run");
      } catch(Exception e) {
        throw new RuntimeException("Cannot get run method of runnable class.", e);
      }
    }
  }
  
  /**
   * @return The Runnable task for execution.
   */
  public Runnable getTask() {
    return task;
  }
  
  /**
   * @return True if and only if the method to run is Annotated with @CoordinatorOnly and should only be run on the Coordinator.
   */
  public boolean isCoordinatorOnly() {
    return coordinatorOnly;
  }
  
  /**
   * @return True if and only if the method should be run immediately.
   */
  public boolean isImmediate() {
    return annotation.delay() <= 0;
  }
  
  /**
   * @return The delay before the method should be run.
   */
  public long getDelay() {
    return annotation.delay();
  }
  
  /**
   * @return The interval between runs of the method.
   */
  public long getInterval() {
    return annotation.interval();
  }
  
  /**
   * @return The {@link TimeUnit} for the delay and interval.
   */
  public TimeUnit getTimeUnit() {
    return annotation.unit();
  }
  
  /**
   * The {@link Runnable} class that will actually be run.
   * 
   * @author John McEntire
   *
   */
  private final class RunnableTask implements Runnable {

    @Override
    public void run() {
      if(!isCoordinatorOnly() || membershipTracker.getCoordinator().isMine()) {
        try {
          method.invoke(injector.getInstance(type), resolveParameters());
        } catch (Throwable e) {
          log.error("Failed to invoke @Scheduled service.", e);
        }
      }
    }

    /**
     * Resolves all parameters threw Guice's Injector.
     * 
     * @return An array of the Objects pulled from Guice to satisfy each parameter.
     */
    private Object[] resolveParameters() {
      List<Object> params = new ArrayList<Object>();
      Class<?> paramTypes[] = method.getParameterTypes();
      int i = 0;
      for(Class<?> paramType : paramTypes) {
        try {
          Object value = null;
          if(method.getParameterAnnotations()[i] != null && method.getParameterAnnotations()[i].length > 0) {
            for(Annotation annot : method.getParameterAnnotations()[i]) {
              if(annot.getClass().isAnnotationPresent(BindingAnnotation.class)) {
                value = injector.getInstance(Key.get(paramType, annot));
                if(value != null) {
                  break;
                }
              }
            }
          } else {
            value = injector.getInstance(paramType);
          }
          if(value != null) {
            params.add(value);
          } else {
            throw new RuntimeException("Unable to resolve a value to pass to " 
                + type.getName()
                + "#"+method.getName() 
                + " for parameter index " + i);
          }
        } finally {
          i++;
        }
      }
      return params.toArray();
    }
    
  }

}
