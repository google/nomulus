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
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.util.CollectionUtils.nullToEmpty;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

/** A read-only {@link SessionMetadata} that doesn't support login/logout. */
public class StatelessRequestSessionMetadata implements SessionMetadata {

  private final String registrarId;
  private final ImmutableSet<String> serviceExtensionUris;

  public StatelessRequestSessionMetadata(
      String registrarId, ImmutableSet<String> serviceExtensionUris) {
    this.registrarId = checkNotNull(registrarId);
    this.serviceExtensionUris = checkNotNull(serviceExtensionUris);
  }

  @Override
  public void invalidate() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getRegistrarId() {
    return registrarId;
  }

  @Override
  public Set<String> getServiceExtensionUris() {
    return serviceExtensionUris;
  }

  @Override
  public int getFailedLoginAttempts() {
    return 0;
  }

  @Override
  public void setRegistrarId(String registrarId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setServiceExtensionUris(Set<String> serviceExtensionUris) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void incrementFailedLoginAttempts() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void resetFailedLoginAttempts() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String toString() {
    return toStringHelper(getClass())
        .add("clientId", getRegistrarId())
        .add("failedLoginAttempts", getFailedLoginAttempts())
        .add("serviceExtensionUris", Joiner.on('.').join(nullToEmpty(getServiceExtensionUris())))
        .toString();
  }
}

