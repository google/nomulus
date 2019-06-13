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

package google.registry.testing;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.services.appengine.v1.Appengine;
import com.google.api.services.appengine.v1.model.ListServicesResponse;
import com.google.api.services.appengine.v1.model.ListVersionsResponse;
import com.google.api.services.appengine.v1.model.ManualScaling;
import com.google.api.services.appengine.v1.model.Service;
import com.google.api.services.appengine.v1.model.TrafficSplit;
import com.google.api.services.appengine.v1.model.Version;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.stream.Collectors;

/** Helper class to provide a builder to construct {@link Appengine} object for testing. */
public class AppEngineAdminApiHelper extends ImmutableObject implements Buildable {

  private Appengine appengine;
  private String appId = "domain-registry-test";
  private Multimap<String, String> liveVersionsMap = ImmutableMultimap.of();
  private Multimap<String, String> manualScalingVersionsMap = ImmutableMultimap.of();

  /** Returns the {@link Appengine} object. */
  public Appengine getAppengine() {
    return appengine;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link Appengine} object, since it is immutable. */
  public static class Builder extends Buildable.Builder<AppEngineAdminApiHelper> {
    public Builder() {}

    private Builder(AppEngineAdminApiHelper instance) {
      super(instance);
    }

    public Builder setAppId(String appId) {
      getInstance().appId = appId;
      return this;
    }

    public Builder setLiveVersionsMap(Multimap<String, String> liveVersionsMap) {
      getInstance().liveVersionsMap = liveVersionsMap;
      return this;
    }

    public Builder setManualScalingVersionsMap(Multimap<String, String> manualScalingVersionsMap) {
      getInstance().manualScalingVersionsMap = manualScalingVersionsMap;
      return this;
    }

    @Override
    public AppEngineAdminApiHelper build() {
      getInstance().appengine = mock(Appengine.class, RETURNS_DEEP_STUBS);

      // Mockito cannot mock ListServicesResponse as it is a final class
      ListServicesResponse listServicesResponse = new ListServicesResponse();
      try {
        when((Object) getInstance().appengine.apps().services().list(getInstance().appId).execute())
            .thenReturn(listServicesResponse);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      // Add all given live versions to mocked Appengine object
      java.util.List<Service> serviceList = new ArrayList<>();
      getInstance()
          .liveVersionsMap
          .asMap()
          .forEach(
              (serviceId, versionList) -> {
                Service service = new Service();
                TrafficSplit trafficSplit = new TrafficSplit();
                trafficSplit.setAllocations(
                    versionList.stream()
                        .collect(Collectors.toMap(version -> version, version -> 1.0)));

                service.setId(serviceId);
                service.setSplit(trafficSplit);
                serviceList.add(service);
              });
      listServicesResponse.setServices(serviceList);

      // Add all given manual scaling versions to mocked Appengine object
      getInstance()
          .manualScalingVersionsMap
          .asMap()
          .forEach(
              (service, versionList) -> {
                // Mockito cannot mock ListVersionsResponse as it is a final class
                ListVersionsResponse listVersionsResponse = new ListVersionsResponse();
                try {
                  when((Object)
                          getInstance()
                              .appengine
                              .apps()
                              .services()
                              .versions()
                              .list(getInstance().appId, service)
                              .execute())
                      .thenReturn(listVersionsResponse);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
                listVersionsResponse.setVersions(
                    versionList.stream()
                        .map(
                            versionId -> {
                              Version version = new Version();
                              ManualScaling manualScaling = new ManualScaling();
                              version.setManualScaling(manualScaling);
                              version.setId(versionId);
                              return version;
                            })
                        .collect(Collectors.toList()));
              });

      return getInstance();
    }
  }
}
