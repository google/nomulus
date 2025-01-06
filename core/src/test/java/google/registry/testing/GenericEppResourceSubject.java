// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import google.registry.model.EppResource;

/** Truth subject for asserting things about {@link EppResource} instances. */
public final class GenericEppResourceSubject
    extends AbstractEppResourceSubject<EppResource, GenericEppResourceSubject> {

  public GenericEppResourceSubject(FailureMetadata failureMetadata, EppResource subject) {
    super(failureMetadata, checkNotNull(subject));
  }

  public static SimpleSubjectBuilder<GenericEppResourceSubject, EppResource>
      assertAboutEppResources() {
    return assertAbout(GenericEppResourceSubject::new);
  }
}
