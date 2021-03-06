/*
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
package org.apache.ambari.server.checks;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.PrereqCheckRequest;
import org.apache.ambari.server.state.*;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;
import org.apache.ambari.server.state.stack.PrereqCheckType;
import org.apache.ambari.server.state.stack.UpgradePack;
import org.apache.ambari.server.state.stack.UpgradePack.ProcessingComponent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Checks that all hosts in maintenance state do not have master components.
 */
public class HostsMasterMaintenanceCheck extends AbstractCheckDescriptor {

  /**
   * Constructor.
   */
  public HostsMasterMaintenanceCheck() {
    super("HOSTS_MASTER_MAINTENANCE", PrereqCheckType.HOST, "Hosts in Maintenance Mode must not have any master components");
  }

  @Override
  public boolean isApplicable(PrereqCheckRequest request) throws AmbariException {
    return request.getRepositoryVersion() != null;
  }

  @Override
  public void perform(PrerequisiteCheck prerequisiteCheck, PrereqCheckRequest request) throws AmbariException {
    final String clusterName = request.getClusterName();
    final Cluster cluster = clustersProvider.get().getCluster(clusterName);
    final StackId stackId = cluster.getDesiredStackVersion();
    final Set<String> hostsWithMasterComponent = new HashSet<String>();
    final String upgradePackName = repositoryVersionHelper.get().getUpgradePackageName(stackId.getStackName(), stackId.getStackVersion(), request.getRepositoryVersion());
    if (upgradePackName == null) {
      prerequisiteCheck.setStatus(PrereqCheckStatus.FAIL);
      prerequisiteCheck.setFailReason("Could not find suitable upgrade pack for " + stackId.getStackName() + " " + stackId.getStackVersion() + " to version " + request.getRepositoryVersion());
      return;
    }
    final UpgradePack upgradePack = ambariMetaInfo.get().getUpgradePacks(stackId.getStackName(), stackId.getStackVersion()).get(upgradePackName);
    if (upgradePack == null) {
      prerequisiteCheck.setStatus(PrereqCheckStatus.FAIL);
      prerequisiteCheck.setFailReason("Could not find upgrade pack named " + upgradePackName);
      return;
    }
    final Set<String> componentsFromUpgradePack = new HashSet<String>();
    for (Map<String, ProcessingComponent> task: upgradePack.getTasks().values()) {
      componentsFromUpgradePack.addAll(task.keySet());
    }
    for (Service service: cluster.getServices().values()) {
      for (ServiceComponent serviceComponent: service.getServiceComponents().values()) {
        if (serviceComponent.isMasterComponent() && componentsFromUpgradePack.contains(serviceComponent.getName())) {
          hostsWithMasterComponent.addAll(serviceComponent.getServiceComponentHosts().keySet());
        }
      }
    }
    final Map<String, Host> clusterHosts = clustersProvider.get().getHostsForCluster(clusterName);
    for (Map.Entry<String, Host> hostEntry : clusterHosts.entrySet()) {
      final Host host = hostEntry.getValue();
      if (host.getMaintenanceState(cluster.getClusterId()) == MaintenanceState.ON && hostsWithMasterComponent.contains(host.getHostName())) {
        prerequisiteCheck.getFailedOn().add(host.getHostName());
      }
    }
    if (!prerequisiteCheck.getFailedOn().isEmpty()) {
      prerequisiteCheck.setStatus(PrereqCheckStatus.FAIL);
      prerequisiteCheck.setFailReason(formatEntityList(prerequisiteCheck.getFailedOn()) + " must not be in in Maintenance Mode as they have master components installed");
    }
  }
}
