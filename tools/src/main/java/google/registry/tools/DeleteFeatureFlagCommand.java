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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.common.FeatureFlag;
import java.util.List;

/**
 * Command to remove a {@link FeatureFlag} from the database entirely.
 *
 * <p>This should be used when a flag has been deprecated entirely, and we want to remove it, to
 * avoid having old invalid data in the database.
 *
 * <p>This command uses the native query format so that it is able to delete values that are no
 * longer part of the {@link FeatureFlag} enum.
 *
 * <p>This uses {@link ConfirmingCommand} instead of {@link MutatingCommand} because of the
 * nonstandard deletion flow required by the fact that the enum constant may already have been
 * removed.
 */
@Parameters(separators = " =", commandDescription = "Delete a FeatureFlag from the database")
public class DeleteFeatureFlagCommand extends ConfirmingCommand {

  @Parameter(description = "Feature flag to delete", required = true)
  private List<String> mainParameters;

  @Override
  protected boolean checkExecutionState() {
    checkArgument(
        mainParameters != null && !mainParameters.isEmpty() && !mainParameters.getFirst().isBlank(),
        "Must provide a non-blank feature flag as the main parameter");
    boolean exists =
        tm().transact(
                () ->
                    (long)
                            tm().getEntityManager()
                                .createNativeQuery(
                                    "SELECT COUNT(*) FROM \"FeatureFlag\" WHERE feature_name ="
                                        + " :featureName",
                                    long.class)
                                .setParameter("featureName", mainParameters.getFirst())
                                .getSingleResult()
                        > 0);
    if (!exists) {
      System.out.printf("No flag found with name '%s'", mainParameters.getFirst());
    }
    return exists;
  }

  @Override
  protected String prompt() throws Exception {
    return String.format("Delete feature flag named '%s'?", mainParameters.getFirst());
  }

  @Override
  protected String execute() throws Exception {
    String featureName = mainParameters.getFirst();
    tm().transact(
            () ->
                tm().getEntityManager()
                    .createNativeQuery(
                        "DELETE FROM \"FeatureFlag\" WHERE feature_name = :featureName")
                    .setParameter("featureName", featureName)
                    .executeUpdate());
    return String.format("Deleted feature flag with name '%s'", featureName);
  }
}
