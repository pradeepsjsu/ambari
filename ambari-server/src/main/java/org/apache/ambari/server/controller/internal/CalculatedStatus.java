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
package org.apache.ambari.server.controller.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;

import org.apache.ambari.server.actionmanager.HostRoleCommand;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.actionmanager.Stage;
import org.apache.ambari.server.orm.entities.HostRoleCommandEntity;
import org.apache.ambari.server.orm.entities.StageEntity;

/**
 * Status of a request resource, calculated from a set of tasks or stages.
 */
public class CalculatedStatus {

  /**
   * The calculated overall status.
   */
  private final HostRoleStatus status;

  /**
   * The calculated percent complete.
   */
  private final double percent;


  // ----- Constructors ------------------------------------------------------

  /**
   * Constructor.
   *
   * @param status   the calculated overall status
   * @param percent  the calculated percent complete
   */
  private CalculatedStatus(HostRoleStatus status, double percent) {
    this.status  = status;
    this.percent = percent;
  }


  // ----- CalculatedStatus --------------------------------------------------

  /**
   * Get the calculated status.
   *
   * @return the status
   */
  public HostRoleStatus getStatus() {
    return status;
  }

  /**
   * Get the calculated percent complete.
   *
   * @return the percent complete
   */
  public double getPercent() {
    return percent;
  }


  // ----- helper methods ----------------------------------------------------

  /**
   * Factory method to create a calculated status.  Calculate request status from the given
   * collection of task entities.
   *
   * @param tasks      the collection of task entities
   * @param skippable  true if a single failed status should NOT result in an overall failed status
   *
   * @return a calculated status
   */
  public static CalculatedStatus statusFromTaskEntities(Collection<HostRoleCommandEntity> tasks, boolean skippable) {

    int size = tasks.size();

    Map<HostRoleStatus, Integer> taskStatusCounts = CalculatedStatus.calculateTaskEntityStatusCounts(tasks);

    HostRoleStatus status = calculateSummaryStatus(taskStatusCounts, size, skippable);

    double progressPercent = calculateProgressPercent(taskStatusCounts, size);

    return new CalculatedStatus(status, progressPercent);
  }

  /**
   * Factory method to create a calculated status.  Calculate request status from the given
   * collection of stage entities.
   *
   * @param stages  the collection of stage entities
   *
   * @return a calculated status
   */
  public static CalculatedStatus statusFromStageEntities(Collection<StageEntity> stages) {

    Collection<HostRoleStatus> stageStatuses = new HashSet<HostRoleStatus>();
    Collection<HostRoleCommandEntity> tasks = new HashSet<HostRoleCommandEntity>();

    for (StageEntity stage : stages) {
      // get all the tasks for the stage
      Collection<HostRoleCommandEntity> stageTasks = stage.getHostRoleCommands();

      // calculate the stage status from the task status counts
      HostRoleStatus stageStatus =
          calculateSummaryStatus(calculateTaskEntityStatusCounts(stageTasks), stageTasks.size(), stage.isSkippable());

      stageStatuses.add(stageStatus);

      // keep track of all of the tasks for all stages
      tasks.addAll(stageTasks);
    }

    // calculate the overall status from the stage statuses
    HostRoleStatus status = calculateSummaryStatus(calculateStatusCounts(stageStatuses), stageStatuses.size(), false);

    // calculate the progress from the task status counts
    double progressPercent = calculateProgressPercent(calculateTaskEntityStatusCounts(tasks), tasks.size());

    return new CalculatedStatus(status, progressPercent);
  }

  /**
   * Factory method to create a calculated status.  Calculate request status from the given
   * collection of stages.
   *
   * @param stages  the collection of stages
   *
   * @return a calculated status
   */
  public static CalculatedStatus statusFromStages(Collection<Stage> stages) {

    Collection<HostRoleStatus> stageStatuses = new HashSet<HostRoleStatus>();
    Collection<HostRoleCommand> tasks = new HashSet<HostRoleCommand>();

    for (Stage stage : stages) {
      // get all the tasks for the stage
      Collection<HostRoleCommand> stageTasks = stage.getOrderedHostRoleCommands();

      // calculate the stage status from the task status counts
      HostRoleStatus stageStatus =
          calculateSummaryStatus(calculateTaskStatusCounts(stageTasks), stageTasks.size(), stage.isSkippable());

      stageStatuses.add(stageStatus);

      // keep track of all of the tasks for all stages
      tasks.addAll(stageTasks);
    }

    // calculate the overall status from the stage statuses
    HostRoleStatus status = calculateSummaryStatus(calculateStatusCounts(stageStatuses), stageStatuses.size(), false);

    // calculate the progress from the task status counts
    double progressPercent = calculateProgressPercent(calculateTaskStatusCounts(tasks), tasks.size());

    return new CalculatedStatus(status, progressPercent);
  }

  /**
   * Returns counts of tasks that are in various states.
   *
   * @param hostRoleStatuses  the collection of tasks
   *
   * @return a map of counts of tasks keyed by the task status
   */
  public static Map<HostRoleStatus, Integer> calculateStatusCounts(Collection<HostRoleStatus> hostRoleStatuses) {
    Map<HostRoleStatus, Integer> counters = new HashMap<HostRoleStatus, Integer>();
    // initialize
    for (HostRoleStatus hostRoleStatus : HostRoleStatus.values()) {
      counters.put(hostRoleStatus, 0);
    }
    // calculate counts
    for (HostRoleStatus status : hostRoleStatuses) {
      // count tasks where isCompletedState() == true as COMPLETED
      // but don't count tasks with COMPLETED status twice
      if (status.isCompletedState() && status != HostRoleStatus.COMPLETED) {
        // Increase total number of completed tasks;
        counters.put(HostRoleStatus.COMPLETED, counters.get(HostRoleStatus.COMPLETED) + 1);
      }
      // Increment counter for particular status
      counters.put(status, counters.get(status) + 1);
    }

    // We overwrite the value to have the sum converged
    counters.put(HostRoleStatus.IN_PROGRESS,
        hostRoleStatuses.size() -
            counters.get(HostRoleStatus.COMPLETED) -
            counters.get(HostRoleStatus.QUEUED) -
            counters.get(HostRoleStatus.PENDING));

    return counters;
  }

  /**
   * Returns counts of task entities that are in various states.
   *
   * @param tasks  the collection of task entities
   *
   * @return a map of counts of tasks keyed by the task status
   */
  public static Map<HostRoleStatus, Integer> calculateTaskEntityStatusCounts(Collection<HostRoleCommandEntity> tasks) {
    Collection<HostRoleStatus> hostRoleStatuses = new LinkedList<HostRoleStatus>();

    for (HostRoleCommandEntity hostRoleCommand : tasks) {
      hostRoleStatuses.add(hostRoleCommand.getStatus());
    }
    return calculateStatusCounts(hostRoleStatuses);
  }

  /**
   * Returns counts of tasks that are in various states.
   *
   * @param tasks  the collection of tasks
   *
   * @return a map of counts of tasks keyed by the task status
   */
  private static Map<HostRoleStatus, Integer> calculateTaskStatusCounts(Collection<HostRoleCommand> tasks) {
    Collection<HostRoleStatus> hostRoleStatuses = new LinkedList<HostRoleStatus>();

    for (HostRoleCommand hostRoleCommand : tasks) {
      hostRoleStatuses.add(hostRoleCommand.getStatus());
    }
    return calculateStatusCounts(hostRoleStatuses);
  }

  /**
   * Calculate the percent complete based on the given status counts.
   *
   * @param counters  counts of resources that are in various states
   * @param total     total number of resources in request
   *
   * @return the percent complete for the stage
   */
  private static double calculateProgressPercent(Map<HostRoleStatus, Integer> counters, double total) {
    return total == 0 ? 0 :
        ((counters.get(HostRoleStatus.QUEUED)              * 0.09 +
          counters.get(HostRoleStatus.IN_PROGRESS)         * 0.35 +
          counters.get(HostRoleStatus.HOLDING)             * 0.35 +
          counters.get(HostRoleStatus.HOLDING_FAILED)      * 0.35 +
          counters.get(HostRoleStatus.HOLDING_TIMEDOUT)    * 0.35 +
          counters.get(HostRoleStatus.COMPLETED)) / total) * 100.0;
  }

  /**
   * Calculate an overall status based on the given status counts.
   *
   * @param counters   counts of resources that are in various states
   * @param total      total number of resources in request
   * @param skippable  true if a single failed status should NOT result in an overall failed status return
   *
   * @return summary request status based on statuses of tasks in different states.
   */
  private static HostRoleStatus calculateSummaryStatus(Map<HostRoleStatus, Integer> counters,
                                                      int total,
                                                      boolean skippable) {

    return counters.get(HostRoleStatus.PENDING) == total ? HostRoleStatus.PENDING :
        counters.get(HostRoleStatus.HOLDING) > 0 ? HostRoleStatus.HOLDING :
        counters.get(HostRoleStatus.HOLDING_FAILED) > 0 ? HostRoleStatus.HOLDING_FAILED :
        counters.get(HostRoleStatus.HOLDING_TIMEDOUT) > 0 ? HostRoleStatus.HOLDING_TIMEDOUT :
        counters.get(HostRoleStatus.FAILED) > 0 && !skippable ? HostRoleStatus.FAILED :
        counters.get(HostRoleStatus.ABORTED) > 0 && !skippable ? HostRoleStatus.ABORTED :
        counters.get(HostRoleStatus.TIMEDOUT) > 0 && !skippable ? HostRoleStatus.TIMEDOUT :
        counters.get(HostRoleStatus.COMPLETED) == total ? HostRoleStatus.COMPLETED : HostRoleStatus.IN_PROGRESS;
  }
}
