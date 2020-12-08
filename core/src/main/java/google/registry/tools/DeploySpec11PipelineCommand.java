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

import com.beust.jcommander.Parameters;
import google.registry.beam.spec11.Spec11Pipeline;
import javax.inject.Inject;

/** Nomulus command that deploys the {@link Spec11Pipeline} template. */
@Parameters(commandDescription = "Deploy the Spec11 pipeline to GCS.")
public class DeploySpec11PipelineCommand implements Command {

  @Inject Spec11Pipeline spec11Pipeline;

  @Override
  public void run() {
    spec11Pipeline.deploy();
  }
}
