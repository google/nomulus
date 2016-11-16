// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.truth.AbstractVerb.DelegatedVerb;
import com.google.common.truth.FailureStrategy;
import google.registry.model.host.HostResource;
import google.registry.testing.TruthChainer.And;
import org.joda.time.DateTime;

/** Truth subject for asserting things about {@link HostResource} instances. */
public final class HostResourceSubject
    extends AbstractEppResourceSubject<HostResource, HostResourceSubject> {

  /** A factory for instances of this subject. */
  private static class SubjectFactory
      extends ReflectiveSubjectFactory<HostResource, HostResourceSubject>{}

  public HostResourceSubject(FailureStrategy strategy, HostResource subject) {
    super(strategy, checkNotNull(subject));
  }

  public static DelegatedVerb<HostResourceSubject, HostResource> assertAboutHosts() {
    return assertAbout(new SubjectFactory());
  }

  public And<HostResourceSubject> hasLastTransferTime(DateTime lastTransferTime) {
    return hasValue(
        lastTransferTime,
        actual().getLastTransferTime(),
        "has lastTransferTime");
  }

  public And<HostResourceSubject> hasLastTransferTimeNotEqualTo(DateTime lastTransferTime) {
    return doesNotHaveValue(
        lastTransferTime,
        actual().getLastTransferTime(),
        "lastTransferTime");
  }
}
