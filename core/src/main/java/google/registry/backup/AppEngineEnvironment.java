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

package google.registry.backup;

import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.Environment;
import com.google.common.collect.ImmutableMap;
import java.io.Closeable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Sets up a placeholder {@link Environment} on a non-AppEngine platform so that Datastore Entities
 * can be deserialized. See {@code DatastoreEntityExtension} in test source for more information.
 */
public class AppEngineEnvironment implements Closeable {

  private static final Environment PLACEHOLDER_ENV = createAppEngineEnvironment();

  private boolean isPlaceHolderNeeded;

  AppEngineEnvironment() {
    isPlaceHolderNeeded = ApiProxy.getCurrentEnvironment() == null;
    // isPlaceHolderNeeded may be true when we are invoked in a test with AppEngineRule.
    if (isPlaceHolderNeeded) {
      ApiProxy.setEnvironmentForCurrentThread(PLACEHOLDER_ENV);
    }
  }

  @Override
  public void close() {
    if (isPlaceHolderNeeded) {
      ApiProxy.setEnvironmentForCurrentThread(null);
    }
  }

  /** Returns a placeholder {@link Environment} that can return hardcoded AppId and Attributes. */
  private static Environment createAppEngineEnvironment() {
    return (Environment)
        Proxy.newProxyInstance(
            Environment.class.getClassLoader(),
            new Class[] {Environment.class},
            (Object proxy, Method method, Object[] args) -> {
              switch (method.getName()) {
                case "getAppId":
                  return "PlaceholderAppId";
                case "getAttributes":
                  return ImmutableMap.<String, Object>of();
                default:
                  throw new UnsupportedOperationException(method.getName());
              }
            });
  }
}
