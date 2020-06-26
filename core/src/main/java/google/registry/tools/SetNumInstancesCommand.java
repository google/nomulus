// Copyright 2018 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSetMultimap.flatteningToImmutableSetMultimap;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.api.services.appengine.v1.Appengine;
import com.google.common.base.Enums;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Maps;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action.Service;
import google.registry.util.AppEngineServiceUtils;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.inject.Inject;

/** A command to set the number of instances for an App Engine service. */
@Parameters(
    separators = " =",
    commandDescription =
        "Set the number of instances for a given service and version. "
            + "Note that this command only works for manual scaling service.")
final class SetNumInstancesCommand implements CommandWithRemoteApi {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final ImmutableSet<Service> ALL_DEPLOYED_SERVICES =
      ImmutableSet.copyOf(Service.values());

  private static final ImmutableMap<String, Service> SERVICE_ID_TO_SERVICE =
      Maps.uniqueIndex(ALL_DEPLOYED_SERVICES, Service::getServiceId);

  // TODO(b/119629679): Use List<Service> after upgrading jcommander to latest version.
  @Parameter(
      names = {"-s", "--services"},
      description =
          "Comma-delimited list of App Engine services to set. "
              + "Allowed values: [DEFAULT, TOOLS, BACKEND, PUBAPI]")
  private List<String> serviceNames = ImmutableList.of();

  @Parameter(
      names = {"-v", "--versions"},
      description =
          "Comma-delimited list of App Engine versions to set, e.g., canary. "
              + "Cannot be set if --non_live_versions is set.")
  private List<String> versions = ImmutableList.of();

  @Parameter(
      names = {"-n", "--num_instances"},
      description =
          "The new number of instances for the given versions "
              + "or for all non-live versions if --non_live_versions is set.",
      required = true)
  private Long numInstances;

  @Parameter(
      names = "--non_live_versions",
      description = "Whether to set number of instances for all non-live versions.",
      arity = 1)
  private Boolean nonLiveVersions = false;

  @Inject AppEngineServiceUtils appEngineServiceUtils;
  @Inject Appengine appengine;

  @Inject
  @Config("projectId")
  String projectId;

  @Override
  public void run() {

    ImmutableSet<Service> services =
        serviceNames.stream()
            .map(s -> s.toUpperCase(Locale.US))
            .map(
                name ->
                    checkArgumentPresent(
                        Enums.getIfPresent(Service.class, name).toJavaUtil(),
                        "Invalid service '%s'. Allowed values are %s",
                        name,
                        ALL_DEPLOYED_SERVICES))
            .collect(toImmutableSet());

    if (nonLiveVersions) {
      checkArgument(versions.isEmpty(), "--versions cannot be set if --non_live_versions is set");

      services = services.isEmpty() ? ALL_DEPLOYED_SERVICES : services;
      ImmutableSetMultimap<Service, String> allLiveVersionsMap = getAllLiveVersionsMap(services);
      ImmutableSetMultimap<Service, String> manualScalingVersionsMap =
          getManualScalingVersionsMap(services);

      // Set number of instances for versions which are manual scaling and non-live
      manualScalingVersionsMap.forEach(
          (service, versionId) -> {
            if (!allLiveVersionsMap.containsEntry(service, versionId)) {
              setNumInstances(service, versionId, numInstances);
            }
          });
    } else {
      checkArgument(!services.isEmpty(), "Service must be specified");
      checkArgument(!versions.isEmpty(), "Version must be specified");
      checkArgument(numInstances > 0, "Number of instances must be greater than zero");

      ImmutableSetMultimap<Service, String> manualScalingVersionsMap =
          getManualScalingVersionsMap(services);

      for (Service service : services) {
        for (String versionId : versions) {
          checkArgument(
              manualScalingVersionsMap.containsEntry(service, versionId),
              "Version %s of service %s is not managed through manual scaling",
              versionId,
              service);
          setNumInstances(service, versionId, numInstances);
        }
      }
    }
  }

  private void setNumInstances(Service service, String version, long numInstances) {
    appEngineServiceUtils.setNumInstances(service.getServiceId(), version, numInstances);
    logger.atInfo().log(
        "Successfully set version %s of service %s to %d instances.",
        version, service, numInstances);
  }

  private ImmutableSetMultimap<Service, String> getAllLiveVersionsMap(Set<Service> services) {
    try {
      return nullToEmpty(appengine.apps().services().list(projectId).execute().getServices())
          .stream()
          .filter(
              service ->
                  services.contains(SERVICE_ID_TO_SERVICE.getOrDefault(service.getId(), null)))
          .collect(
              flatteningToImmutableSetMultimap(
                  service -> SERVICE_ID_TO_SERVICE.get(service.getId()),
                  service -> nullToEmpty(service.getSplit().getAllocations()).keySet().stream()));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private ImmutableSetMultimap<Service, String> getManualScalingVersionsMap(Set<Service> services) {
    return services.stream()
        .collect(
            flatteningToImmutableSetMultimap(
                service -> service,
                service -> {
                  try {
                    return nullToEmpty(
                            appengine
                                .apps()
                                .services()
                                .versions()
                                .list(projectId, service.getServiceId())
                                .execute()
                                .getVersions())
                        .stream()
                        .filter(version -> version.getManualScaling() != null)
                        .map(version -> version.getId());
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                }));
  }
}
