// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

/** Command to update a PackagePromotion */
@Parameters(separators = " =", commandDescription = "Update package promotion object(s)")
public final class UpdatePackagePromotionCommand extends CreateOrUpdatePackagePromotionCommand {

  @Parameter(
      names = "--clear_last_notification_sent",
      description =
          "Clear the date last max-domain non-compliance notification was sent? (This should be"
              + " cleared whenever a tier is upgraded)")
  boolean clearLastNotificationSent;

  @Override
  boolean clearLastNotificationSent() {
    return clearLastNotificationSent;
  }
}
