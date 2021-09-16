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

import java.util.Set;

/** Object to allow setting and retrieving session information in flows. */
public interface SessionMetadata {

  /**
   * Invalidates the session. A new instance must be created after this for future sessions.
   * Attempts to invoke methods of this class after this method has been called will throw
   * {@code IllegalStateException}.
   */
  void invalidate();

  String getRegistrarId();

  Set<String> getServiceExtensionUris();

  int getFailedLoginAttempts();

  void setRegistrarId(String registrarId);

  void setServiceExtensionUris(Set<String> serviceExtensionUris);

  void incrementFailedLoginAttempts();

  void resetFailedLoginAttempts();
}
