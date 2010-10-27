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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSError;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.metrics.MetricsContext;
import org.apache.hadoop.metrics.MetricsUtil;
import org.apache.hadoop.metrics.jvm.JvmMetrics;
import org.apache.hadoop.util.StringUtils;
import org.apache.log4j.LogManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.InetSocketAddress;

/** 
 * The main() for child processes. 
 */

class DrmaaChild {
  public static final Log LOG = LogFactory.getLog(DrmaaChild.class);

  static volatile TaskAttemptID taskid = null;
  static volatile boolean isCleanup;

  public static void main(String[] args) throws Throwable {
    LOG.debug("DrmaaChild starting");

    JobConf defaultConf = new JobConf();
    String host = args[0];
    int port = Integer.parseInt(args[1]);

    // TODO: make sure the id is not a local one (127.0.0.1 / localhost)
    InetSocketAddress address = new InetSocketAddress(host, port);

    taskid = TaskAttemptID.forName(args[2]);
    final int SLEEP_LONGER_COUNT = 5;
    
    //int jvmIdInt = Integer.parseInt(args[3]);
    //JVMId jvmId = new JVMId(firstTaskid.getJobID(),firstTaskid.isMap(),jvmIdInt);

    TaskUmbilicalProtocol umbilical =
      (TaskUmbilicalProtocol)RPC.waitForProxy(TaskUmbilicalProtocol.class,
          TaskUmbilicalProtocol.versionID,
          address,
          defaultConf);

    int numTasksToExecute = -1; //-1 signifies "no limit"
    int numTasksExecuted = 0;

    //for the memory management, a PID file is written and the PID file
    //is written once per JVM. We simply symlink the file on a per task
    //basis later (see below). Long term, we should change the Memory
    //manager to use JVMId instead of TaskAttemptId

    Path srcPidPath = null;
    Path dstPidPath = null;
    int idleLoopCount = 0;
    Task task = null;
    try {
      while (true) {
        JvmTask myTask = umbilical.getTask(taskid);
        if (myTask.shouldDie()) {
          break;
        } else {
          if (myTask.getTask() == null) {
            taskid = null;
            if (++idleLoopCount >= SLEEP_LONGER_COUNT) {
              //we sleep for a bigger interval when we don't receive
              //tasks for a while
              Thread.sleep(1500);
            } else {
              Thread.sleep(500);
            }
            continue;
          }
        }
        idleLoopCount = 0;
        task = myTask.getTask();
        taskid = task.getTaskID();
        isCleanup = task.isTaskCleanupTask();

        // reset the statistics for the task
        FileSystem.clearStatistics();

        JobConf job = new JobConf(task.getJobFile());

        //setupWorkDir actually sets up the symlinks for the distributed
        //cache. After a task exits we wipe the workdir clean, and hence
        //the symlinks have to be rebuilt.
        TaskRunner.setupWorkDir(job, new File(".").getAbsoluteFile());

        numTasksToExecute = job.getNumTasksToExecutePerJvm();
        assert(numTasksToExecute != 0);
       
        task.setConf(job);

        defaultConf.addResource(new Path(task.getJobFile()));

        // Initiate Java VM metrics
        JvmMetrics.init(task.getPhase().toString(), job.getSessionId());
        // use job-specified working directory
        FileSystem.get(job).setWorkingDirectory(job.getWorkingDirectory());
        try {
          task.run(job, umbilical);             // run the task
        } finally {
          // do nothing
        }
        if (numTasksToExecute > 0 && ++numTasksExecuted == numTasksToExecute) {
          break;
        }
      }
    } catch (FSError e) {
      LOG.fatal("FSError from child", e);
      umbilical.fsError(taskid, e.getMessage());
    } catch (Exception exception) {
      LOG.warn("Error running child", exception);
      try {
        if (task != null) {
          // do cleanup for the task
          task.taskCleanup(umbilical);
        }
      } catch (Exception e) {
        LOG.info("Error cleaning up" + e);
      }
      // Report back any failures, for diagnostic purposes
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      exception.printStackTrace(new PrintStream(baos));
      if (taskid != null) {
        umbilical.reportDiagnosticInfo(taskid, baos.toString());
      }
    } catch (Throwable throwable) {
      LOG.fatal("Error running child : "
                + StringUtils.stringifyException(throwable));
      if (taskid != null) {
        Throwable tCause = throwable.getCause();
        String cause = tCause == null 
                       ? throwable.getMessage() 
                       : StringUtils.stringifyException(tCause);
        umbilical.fatalError(taskid, cause);
      }
    } finally {
      RPC.stopProxy(umbilical);
      MetricsContext metricsContext = MetricsUtil.getContext("mapred");
      metricsContext.close();
      // Shutting down log4j of the child-vm... 
      // This assumes that on return from Task.run() 
      // there is no more logging done.
      LogManager.shutdown();
    }
  }
}
