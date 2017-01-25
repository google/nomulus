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

package google.registry.whois;

import google.registry.config.RegistryConfig.Config;
import google.registry.util.Clock;
import org.joda.time.DateTime;

import javax.inject.Inject;
import java.io.IOException;
import java.io.StringReader;

/** High-level WHOIS API for other packages. */
public final class Whois {

  private final Clock clock;
  private final String disclaimer;
  private final WhoisCommandFactory commandFactory;

  @Inject
  public Whois(
      Clock clock,
      @Config("whoisDisclaimer") String disclaimer,
      @Config("whoisCommandFactory") WhoisCommandFactory commandFactory) {
    this.clock = clock;
    this.disclaimer = disclaimer;
    this.commandFactory = commandFactory;
  }

  /** Performs a WHOIS lookup on a plaintext query string. */
  public String lookup(String query, boolean preferUnicode) {
    DateTime now = clock.nowUtc();
    try {
      return new WhoisReader(new StringReader(query), commandFactory, now)
          .readCommand()
          .executeQuery(now)
          .getPlainTextOutput(preferUnicode, disclaimer);
    } catch (WhoisException e) {
      return e.getPlainTextOutput(preferUnicode, disclaimer);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
