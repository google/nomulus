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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertAbout;

import com.google.common.truth.FailureMetadata;
import com.google.common.truth.SimpleSubjectBuilder;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import google.registry.testing.TruthChainer.And;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** Truth subject for asserting things about {@link HostResource} instances. */
public final class HostResourceSubject
    extends AbstractEppResourceSubject<HostResource, HostResourceSubject> {

  private final HostResource actual;

  public HostResourceSubject(FailureMetadata failureMetadata, HostResource subject) {
    super(failureMetadata, checkNotNull(subject));
    this.actual = subject;
  }

  public static SimpleSubjectBuilder<HostResourceSubject, HostResource> assertAboutHosts() {
    return assertAbout(HostResourceSubject::new);
  }

  public And<HostResourceSubject> hasLastTransferTime(DateTime lastTransferTime) {
    return hasValue(lastTransferTime, actual.getLastTransferTime(), "has lastTransferTime");
  }

  public And<HostResourceSubject> hasLastTransferTimeNotEqualTo(DateTime lastTransferTime) {
    return doesNotHaveValue(lastTransferTime, actual.getLastTransferTime(), "lastTransferTime");
  }

  public And<HostResourceSubject> hasLastSuperordinateChange(DateTime lastSuperordinateChange) {
    return hasValue(
        lastSuperordinateChange,
        actual.getLastSuperordinateChange(),
        "has lastSuperordinateChange");
  }

  public And<HostResourceSubject> hasSuperordinateDomain(
      @Nullable VKey<DomainBase> superordinateDomain) {
    return hasValue(
        superordinateDomain, actual.getSuperordinateDomain(), "has superordinateDomain");
  }
}
