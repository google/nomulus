// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.host;

import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import java.util.Optional;
import java.util.Set;
import org.joda.time.DateTime;

/** This interface defines the methods to manipulate {@link HostResource} in the database. */
public interface HostDao {

  /** Inserts or updates the {@link HostResource} in the database. */
  void save(HostResource hostResource);

  /** Finds and returns the {@link HostResource} by the fully qualified host name. */
  HostResource findByFqhn(String fqhn, DateTime time) throws ResourceDoesNotExistException;

  /**
   * Updates corresponding indexes when host is modified. This is for the backward compatibility of
   * datastore implementation. Implementation of Cloud Sql can just have a no-op function.
   */
  void updateIndex(HostResource newHost, DateTime now, Optional<HostResource> maybeExistingHost);

  /** Returns {@code true} if the given host exists in the database. */
  boolean checkExistsByFqhn(String fqhn, DateTime now);

  /**
   * Checks if the given hosts exist in the database and returns the set of existing host names. If
   * there is no host exists, the set will be empty.
   */
  Set<String> checkExistsByFqhn(Iterable<String> fqhns, DateTime now);
}
