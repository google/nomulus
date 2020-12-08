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

package google.registry.beam.initsql;

import google.registry.beam.initsql.Transforms.SerializableSupplier;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.transaction.JpaTransactionManager;
import javax.annotation.Nullable;
import org.apache.beam.sdk.transforms.SerializableFunction;

public class JpaSupplierFactory implements SerializableSupplier<JpaTransactionManager> {

  private static final long serialVersionUID = 1L;

  private final String credentialFileUrl;
  @Nullable private final String cloudKmsProjectId;
  @Nullable private final TransactionIsolationLevel isolationLevelOverride;
  private final SerializableFunction<BeamJpaModule, JpaTransactionManager> jpaGetter;

  public JpaSupplierFactory(
      String credentialFileUrl,
      @Nullable String cloudKmsProjectId,
      SerializableFunction<BeamJpaModule, JpaTransactionManager> jpaGetter) {
    this(credentialFileUrl, cloudKmsProjectId, jpaGetter, null);
  }

  public JpaSupplierFactory(
      String credentialFileUrl,
      @Nullable String cloudKmsProjectId,
      SerializableFunction<BeamJpaModule, JpaTransactionManager> jpaGetter,
      @Nullable TransactionIsolationLevel isolationLevelOverride) {
    this.credentialFileUrl = credentialFileUrl;
    this.cloudKmsProjectId = cloudKmsProjectId;
    this.isolationLevelOverride = isolationLevelOverride;
    this.jpaGetter = jpaGetter;
  }

  @Override
  public JpaTransactionManager get() {
    return jpaGetter.apply(
        new BeamJpaModule(credentialFileUrl, cloudKmsProjectId, isolationLevelOverride));
  }
}
