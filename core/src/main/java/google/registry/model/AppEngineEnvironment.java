// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model;

import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.Environment;
import com.google.common.collect.ImmutableMap;
import google.registry.model.ofy.ObjectifyService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Sets up a fake {@link Environment} so that the following operations can be performed without the
 * Datastore service:
 *
 * <ul>
 *   <li>Create Objectify {@code Keys}.
 *   <li>Instantiate Objectify objects.
 *   <li>Convert Datastore {@code Entities} to their corresponding Objectify objects.
 * </ul>
 *
 * <p>User has the option to specify their desired {@code appId} string, which forms part of an
 * Objectify {@code Key} and is included in the equality check. This feature makes it easy to
 * compare a migrated object in SQL with the original in Objectify.
 *
 * <p>Note that conversion from Objectify objects to Datastore {@code Entities} still requires the
 * Datastore service.
 */
public class AppEngineEnvironment {

  private Environment environment;

  public AppEngineEnvironment() {
    this("PlaceholderAppId");
  }

  public AppEngineEnvironment(String appId) {
    environment = createAppEngineEnvironment(appId);
  }

  public void setEnvironmentForCurrentThread() {
    ApiProxy.setEnvironmentForCurrentThread(environment);
    ObjectifyService.initOfy();
  }

  public void setEnvironmentForAllThreads() {
    setEnvironmentForCurrentThread();
    ApiProxy.setEnvironmentFactory(() -> environment);
  }

  public void unsetEnvironmentForCurrentThread() {
    ApiProxy.clearEnvironmentForCurrentThread();
  }

  public void unsetEnvironmentForAllThreads() {
    try {
      Method method = ApiProxy.class.getDeclaredMethod("clearEnvironmentFactory");
      method.setAccessible(true);
      method.invoke(null);
    } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns a placeholder {@link Environment} that can return hardcoded AppId and Attributes. */
  private static Environment createAppEngineEnvironment(String appId) {
    return (Environment)
        Proxy.newProxyInstance(
            Environment.class.getClassLoader(),
            new Class[] {Environment.class},
            (Object proxy, Method method, Object[] args) -> {
              switch (method.getName()) {
                case "getAppId":
                  return appId;
                case "getAttributes":
                  return ImmutableMap.<String, Object>of();
                default:
                  throw new UnsupportedOperationException(method.getName());
              }
            });
  }
}
