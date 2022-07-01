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

package google.registry.beam.spec11;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import java.io.Serializable;

/**
 * A POJO representing a domain name and associated info, parsed from a {@code SchemaAndRecord}.
 *
 * <p>This is a trivially serializable class that allows Beam to transform the results of a SQL
 * query into a standard Java representation.
 */
@AutoValue
public abstract class DomainNameInfo implements Serializable {

  /** Returns the fully qualified domain name. */
  abstract String domainName();

  /** Returns the domain repo ID (the primary key of the domain table). */
  abstract String domainRepoId();

  /** Returns the registrar ID of the associated registrar for this domain. */
  abstract String registrarId();

  /** Returns the email address of the registrar associated with this domain. */
  abstract String registrarEmailAddress();

  /**
   * Creates a concrete {@link DomainNameInfo}.
   */
  @VisibleForTesting
  static DomainNameInfo create(
      String domainName, String domainRepoId, String registrarId, String registrarEmailAddress) {
    return new AutoValue_DomainNameInfo(
        domainName, domainRepoId, registrarId, registrarEmailAddress);
  }
}
