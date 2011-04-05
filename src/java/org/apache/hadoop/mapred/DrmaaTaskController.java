/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.mapred.CleanupQueue.PathDeletionContext;
import org.apache.hadoop.mapred.JvmManager.JvmEnv;
import org.apache.hadoop.util.Shell;
import org.apache.hadoop.util.Shell.ShellCommandExecutor;
import org.ggf.drmaa.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;

/**
 * The default implementation for controlling tasks.
 * 
 * This class provides an implementation for launching and killing 
 * tasks that need to be run as the tasktracker itself. Hence,
 * many of the initializing or cleanup methods are not required here.
 * 
 * <br/>
 * 
 */
public class DrmaaTaskController extends TaskController {

  private static final Log LOG =
      LogFactory.getLog(DrmaaTaskController.class);

  private static Session sessionInstance;

  /**
   * Singleton builder for Drmaa Session
   * @return
   */
  public static Session getDrmaaSessionInstance() {
    if (sessionInstance == null) {
      try {
        LOG.info("Initializing drmaa Session.");
        SessionFactory factory = SessionFactory.getFactory();
        sessionInstance = factory.getSession();
        sessionInstance.init("");
      } catch (DrmaaException e) {
        // ignore for now
      }
    }
    
    return sessionInstance;
  }


  /**
   * Launch a new JVM for the task.
   *
   * This method launches the new remote JVM for the task by calling using the DRMAA interface 
   */
  
  void launchTaskJVM(TaskControllerContext context) throws IOException {
    initializeTask(context);
    LOG.debug("Launching DRMAA task jvm for task " + context.task.getTaskID());
    try {
      Session session = DrmaaTaskController.getDrmaaSessionInstance();
      JvmEnv env = context.env;
      // craete new job
      JobTemplate jt = session.createJobTemplate();

      // generate a proper job name
      Task task = context.task;
      String typeFlag = task.isMapTask() ? "M" : "R";
      if (task.isJobSetupTask()) typeFlag = "S";
      if (task.isJobCleanupTask()) typeFlag = "C";
      if (task.isTaskCleanupTask()) typeFlag = "c";

      String attemptID = task.getTaskID().toString();
      String jobName = attemptID.substring(Math.max(attemptID.length() - 10, 0)); // typeFlag + ":" + task.conf.getJobName().replaceAll("\\W", "_");
      jt.setJobName(jobName);

      // set working directory to hadoop temp directory created by the task tracker
      jt.setWorkingDirectory(env.workDir.getAbsolutePath());

      // set std error and std out path files defined by hadoop
      jt.setErrorPath(env.stderr.getAbsolutePath());
      jt.setOutputPath(env.stdout.getAbsolutePath());

      // set environment variables
      jt.setJobEnvironment(env.env);

      // set command with params that will run the remote child JVM
      jt.setRemoteCommand(env.vargs.get(0));
      //LOG.info("PRE ARGS: " + StringUtils.join(env.vargs.toArray(), " :: "));
      env.vargs.set(env.vargs.size() - 6, DrmaaChild.class.getName());
      //LOG.info("POST ARGS: " + StringUtils.join(env.vargs.toArray(), " :: "));
      jt.setArgs(env.vargs.subList(1, env.vargs.size()));


      Date startTime = new Date();
      // submit the job
      String id = session.runJob(jt);
      Date subTime = new Date();
      LOG.info("DRMAA Job " + attemptID + " has been submitted with id " + id);
      session.deleteJobTemplate(jt);
      org.ggf.drmaa.JobInfo info = session.wait(id, Session.TIMEOUT_WAIT_FOREVER);
      session.control();
      Date finishTime = new Date();

      String status = "finished with unclear conditions";
      if (info.wasAborted()) {
        status = " never ran";
      } else if (info.hasExited()) {
        status = "finished regularly with exit status " + info.getExitStatus();
      } else if (info.hasSignaled()) {
        status = "finished due to signal " + info.getTerminatingSignal();
      } 
      double submitSeconds = (subTime.getTime() - startTime.getTime()) / 1000.0;
      double finishSeconds = (finishTime.getTime() - startTime.getTime()) / 1000.0;
        
      LOG.info("DRMAA Job " + attemptID + " took " + finishSeconds + "s (" + submitSeconds + "s) with status: " + status);

      Map rmap = info.getResourceUsage();
      Iterator i = rmap.keySet().iterator();
      StringBuilder usage = new StringBuilder();
      while (i.hasNext()) {
        String name = (String) i.next();
        String value = (String) rmap.get(name);
        usage.append(name + "=" + value + "; ");
      }

      LOG.info("DRMAA Job " + attemptID + " usage: " + usage.toString());


      // set a fake shell command executer so hadoop can access the error code
      // TODO: remove this hack

      try {
        // set a dummy shell exec object that holds the exit code of the drmaa job
        Shell.ShellCommandExecutor shexec2 = new ShellCommandExecutor(new String[0], env.workDir, env.env);
        Field exitCode = Shell.class.getDeclaredField("exitCode");
        exitCode.setAccessible(true);
        exitCode.setInt(shexec2, info.getExitStatus());
        context.shExec = shexec2;

        LOG.info("DRMAA Job (" + attemptID + ") is faking exit status: " + info.getExitStatus());

        // missuse the pid as the job id
        context.pid = id;
      } catch (NoSuchFieldException e) {
        LOG.error("Could not find private field: extiCode: " + e.getMessage());
      }
      catch (IllegalAccessException e) {
        LOG.error("Could not set private field: extiCode: " + e.getMessage());
      }
    } catch (DrmaaException e) {
      LOG.error("Error: " + e.getMessage());
    }
  }

  /**
   * Initialize the task environment.
   *
   * Since tasks are launched as the tasktracker user itself, this
   * method has no action to perform.
   */
  void initializeTask(TaskControllerContext context) {
    // The default task controller does not need to set up
    // any permissions for proper execution.
    // So this is a dummy method.
    LOG.debug("Initializing DRMAA task " + context.task.getTaskID());

    String drmaaImpl =  context.task.conf.get("org.ggf.drmaa.SessionFactory");
    if (drmaaImpl != null) {
      System.setProperty("org.ggf.drmaa.SessionFactory", drmaaImpl);
    }
    return;
  }

  /*
   * No need to do anything as we don't need to do as we dont need anything
   * extra from what TaskTracker has done.
   */
  @Override
  void initializeJob(JobInitializationContext context) {

  }

  @Override
  void terminateTask(TaskControllerContext context) {
    LOG.debug("Terminating DRMAA task " + context.task.getTaskID());

    return; // not implemented yet

//    ShellCommandExecutor shexec = context.shExec;
//    if (shexec != null) {
//      Process process = shexec.getProcess();
//      if (Shell.WINDOWS) {
//        // Currently we don't use setsid on WINDOWS.
//        //So kill the process alone.
//        if (process != null) {
//          process.destroy();
//        }
//      }
//      else { // In addition to the task JVM, kill its subprocesses also.
//        String pid = context.pid;
//        if (pid != null) {
//          if(ProcessTree.isSetsidAvailable) {
//            ProcessTree.terminateProcessGroup(pid);
//          }else {
//            ProcessTree.terminateProcess(pid);
//          }
//        }
//      }
//    }
  }
  
  @Override
  void killTask(TaskControllerContext context) {
    LOG.debug("Killing DRMAA task " + context.task.getTaskID());    
    return; // not implemented yet

//    ShellCommandExecutor shexec = context.shExec;
//    if (shexec != null) {
//      if (Shell.WINDOWS) {
//        //We don't do send kill process signal in case of windows as
//        //already we have done a process.destroy() in terminateTaskJVM()
//        return;
//      }
//      String pid = context.pid;
//      if (pid != null) {
//        if(ProcessTree.isSetsidAvailable) {
//          ProcessTree.killProcessGroup(pid);
//        } else {
//          ProcessTree.killProcess(pid);
//        }
//      }
//    }
  }

  @Override
  void dumpTaskStack(TaskControllerContext context) {
    return; // not implemented yet
//    ShellCommandExecutor shexec = context.shExec;
//    if (shexec != null) {
//      if (Shell.WINDOWS) {
//        // We don't use signals in Windows.
//        return;
//      }
//      String pid = context.pid;
//      if (pid != null) {
//        // Send SIGQUIT to get a stack dump
//        if (ProcessTree.isSetsidAvailable) {
//          ProcessTree.sigQuitProcessGroup(pid);
//        } else {
//          ProcessTree.sigQuitProcess(pid);
//        }
//      }
//    }
  }

  @Override
  public void initializeDistributedCacheFile(DistributedCacheFileContext context)
      throws IOException {
    return; // not implemented yet

//    Path localizedUniqueDir = context.getLocalizedUniqueDir();
//    try {
//      // Setting recursive execute permission on localized dir
//      LOG.info("Doing chmod on localdir :" + localizedUniqueDir);
//      FileUtil.chmod(localizedUniqueDir.toString(), "+x", true);
//    } catch (InterruptedException ie) {
//      LOG.warn("Exception in doing chmod on" + localizedUniqueDir, ie);
//      throw new IOException(ie);
//    }
  }

  @Override
  public void initializeUser(InitializationContext context) {
    // Do nothing.
  }
  
  @Override
  void runDebugScript(DebugScriptContext context) throws IOException {
    return; // not implemented yet

//    List<String>  wrappedCommand = TaskLog.captureDebugOut(context.args,
//        context.stdout);
//    // run the script.
//    ShellCommandExecutor shexec =
//      new ShellCommandExecutor(wrappedCommand.toArray(new String[0]), context.workDir);
//    shexec.execute();
//    int exitCode = shexec.getExitCode();
//    if (exitCode != 0) {
//      throw new IOException("Task debug script exit with nonzero status of "
//          + exitCode + ".");
//    }
  }

  /**
   * Enables the task for cleanup by changing permissions of the specified path
   * in the local filesystem
   */
  @Override
  void enableTaskForCleanup(PathDeletionContext context)
         throws IOException {
    enablePathForCleanup(context);
  }
  
  /**
   * Enables the job for cleanup by changing permissions of the specified path
   * in the local filesystem
   */
  @Override
  void enableJobForCleanup(PathDeletionContext context)
         throws IOException {
    enablePathForCleanup(context);
  }
  
  /**
   * Enables the path for cleanup by changing permissions of the specified path
   * in the local filesystem
   */
  private void enablePathForCleanup(PathDeletionContext context)
         throws IOException {
    try {
      FileUtil.chmod(context.fullPath, "u+rwx", true);
    } catch(InterruptedException e) {
      LOG.warn("Interrupted while setting permissions for " + context.fullPath +
          " for deletion.");
    } catch(IOException ioe) {
      LOG.warn("Unable to change permissions of " + context.fullPath);
    }
  }
}
