// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.flows;

import static com.google.common.base.MoreObjects.toStringHelper;
import static google.registry.util.CollectionUtils.nullToEmpty;

import com.google.common.base.Joiner;
import google.registry.request.Response;
import java.util.Set;

/** Object to allow setting and retrieving session information in flows. */
public abstract class SessionMetadata {

  /**
   * Invalidates the session. A new instance must be created after this for future sessions.
   * Attempts to invoke methods of this class after this method has been called will throw {@code
   * IllegalStateException}.
   */
  public abstract void invalidate();

  public abstract String getRegistrarId();

  public abstract Set<String> getServiceExtensionUris();

  public abstract int getFailedLoginAttempts();

  public abstract void setRegistrarId(String registrarId);

  public abstract void setServiceExtensionUris(Set<String> serviceExtensionUris);

  public abstract void incrementFailedLoginAttempts();

  public abstract void resetFailedLoginAttempts();

  @Override
  public String toString() {
    return toStringHelper(getClass())
        .add("clientId", getRegistrarId())
        .add("failedLoginAttempts", getFailedLoginAttempts())
        .add("serviceExtensionUris", Joiner.on('.').join(nullToEmpty(getServiceExtensionUris())))
        .toString();
  }

  public void save(Response response) {}
}
