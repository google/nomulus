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

package google.registry.util;

import com.google.apphosting.api.ApiProxy.Environment;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/** A placeholder GAE environment class that is used when masquerading a thread as a GAE thread. */
public final class PlaceholderEnvironment implements Environment {

  private static final PlaceholderEnvironment INSTANCE = new PlaceholderEnvironment();

  public static PlaceholderEnvironment get() {
    return INSTANCE;
  }

  private PlaceholderEnvironment() {}

  @Override
  public String getAppId() {
    return "PlaceholderAppId";
  }

  @Override
  public Map<String, Object> getAttributes() {
    return ImmutableMap.of();
  }

  @Override
  public String getModuleId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getVersionId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getEmail() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isLoggedIn() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isAdmin() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getAuthDomain() {
    throw new UnsupportedOperationException();
  }

  @SuppressWarnings("deprecation")
  @Override
  public String getRequestNamespace() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getRemainingMillis() {
    throw new UnsupportedOperationException();
  }
}
