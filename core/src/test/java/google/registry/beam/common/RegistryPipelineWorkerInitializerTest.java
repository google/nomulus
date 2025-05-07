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

package google.registry.beam.common;

import static com.google.common.truth.Truth.assertThat;

import google.registry.util.RegistryEnvironment;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.junit.jupiter.api.Test;

public class RegistryPipelineWorkerInitializerTest {

  @Test
  void test() {
    RegistryPipelineOptions options =
        PipelineOptionsFactory.fromArgs(
                "--registryEnvironment=ALPHA", "--isolationOverride=TRANSACTION_SERIALIZABLE")
            .withValidation()
            .as(RegistryPipelineOptions.class);
    new RegistryPipelineWorkerInitializer().beforeProcessing(options);
    assertThat(RegistryEnvironment.isOnJetty()).isTrue();
    System.clearProperty("google.registry.jetty");
  }
}
