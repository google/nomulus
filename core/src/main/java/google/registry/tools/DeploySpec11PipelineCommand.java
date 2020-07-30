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

package google.registry.tools;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.beam.initsql.BeamJpaModule.JpaTransactionManagerComponent;
import google.registry.beam.initsql.JpaSupplierFactory;
import google.registry.beam.spec11.Spec11Pipeline;
import google.registry.config.CredentialModule.LocalCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.Retrier;
import javax.annotation.Nullable;
import javax.inject.Inject;

/** Nomulus command that deploys the {@link Spec11Pipeline} template. */
@Parameters(commandDescription = "Deploy the Spec11 pipeline to GCS.")
public class DeploySpec11PipelineCommand implements Command {

  @Inject
  @Config("projectId")
  String projectId;

  @Parameter(
      names = {"-p", "--project"},
      description = "Cloud KMS project ID",
      required = true)
  String cloudKmsProjectId;

  @Inject
  @Config("beamStagingUrl")
  String beamStagingUrl;

  @Inject
  @Config("spec11TemplateUrl")
  String spec11TemplateUrl;

  @Inject
  @Config("reportingBucketUrl")
  String reportingBucketUrl;

  @Inject @LocalCredential GoogleCredentialsBundle googleCredentialsBundle;
  @Inject Retrier retrier;

  @Inject
  @Nullable
  @Config("sqlAccessInfoFile")
  String sqlAccessInfoFile;

  @Override
  public void run() {
    JpaSupplierFactory jpaSupplierFactory =
        new JpaSupplierFactory(
            sqlAccessInfoFile,
            cloudKmsProjectId,
            JpaTransactionManagerComponent::cloudSqlJpaTransactionManager);

    Spec11Pipeline pipeline =
        new Spec11Pipeline(
            projectId,
            beamStagingUrl,
            spec11TemplateUrl,
            reportingBucketUrl,
            jpaSupplierFactory,
            googleCredentialsBundle,
            retrier);
    pipeline.deploy();
  }
}
