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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.console.User;
import google.registry.persistence.VKey;
import java.util.List;

/** Command to display one or more users. */
@Parameters(separators = " =", commandDescription = "Show user(s)")
public class GetUserCommand implements Command {

  @Parameter(description = "Email address(es) of the user(s) to display", required = true)
  private List<String> mainParameters;

  @Override
  public void run() throws Exception {
    tm().transact(
            () -> {
              for (String emailAddress : mainParameters) {
                System.out.println(
                    tm().loadByKeyIfPresent(VKey.create(User.class, emailAddress))
                        .map(User::toString)
                        .orElse(String.format("No user with email address %s", emailAddress)));
              }
            });
  }
}
