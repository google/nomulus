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

package google.registry.tools.javascrap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Sets.difference;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import google.registry.keyring.api.Keyring;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.rde.Ghostryde;
import google.registry.tools.Command;
import google.registry.tools.params.PathParameter;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.rde.XjcRdeDeposit;
import google.registry.xjc.rdedomain.XjcRdeDomain;
import google.registry.xjc.rderegistrar.XjcRdeRegistrar;
import google.registry.xml.XmlException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.xml.bind.JAXBElement;

/**
 * Command to view and schema validate an XML RDE escrow deposit.
 *
 * <p>Note that this command only makes sure that both deposits contain the same registrars and
 * domains, regardless of the order. To verify that they are indeed equivalent one still needs to
 * verify internal consistency within each deposit (i.e. to check that all hosts and contacts
 * referenced by domains are included in the deposit) by calling {@code
 * google.registry.tools.ValidateEscrowDepositCommand}.
 */
@DeleteAfterMigration
@Parameters(separators = " =", commandDescription = "Compare two XML escrow deposits.")
public final class CompareEscrowDepositsCommand implements Command {

  @Parameter(
      description =
          "Two XML escrow deposit files. Each may be a plain XML or an XML GhostRyDE file.",
      validateWith = PathParameter.InputFile.class)
  private List<Path> inputs;

  @Inject Provider<Keyring> keyring;

  private XjcRdeDeposit getDeposit(Path input) throws IOException, XmlException {
    InputStream fileStream = Files.newInputStream(input);
    InputStream inputStream = fileStream;
    if (input.toString().endsWith(".ghostryde")) {
      inputStream = Ghostryde.decoder(fileStream, keyring.get().getRdeStagingDecryptionKey());
    }
    return XjcXmlTransformer.unmarshal(XjcRdeDeposit.class, inputStream);
  }

  @Override
  public void run() throws Exception {
    checkArgument(
        inputs.size() == 2,
        "Must supply 2 files to compare, but %s was/were supplied.",
        inputs.size());
    XjcRdeDeposit deposit1 = getDeposit(inputs.get(0));
    XjcRdeDeposit deposit2 = getDeposit(inputs.get(1));
    compareXmlDeposits(deposit1, deposit2);
  }

  private static void process(XjcRdeDeposit deposit, Set<String> domains, Set<String> registrars) {
    for (JAXBElement<?> item : deposit.getContents().getContents()) {
      if (XjcRdeDomain.class.isAssignableFrom(item.getDeclaredType())) {
        XjcRdeDomain domain = (XjcRdeDomain) item.getValue();
        domains.add(checkNotNull(domain.getName()));
      } else if (XjcRdeRegistrar.class.isAssignableFrom(item.getDeclaredType())) {
        XjcRdeRegistrar registrar = (XjcRdeRegistrar) item.getValue();
        registrars.add(checkNotNull(registrar.getId()));
      }
    }
  }

  private static boolean printUniqueElements(
      Set<String> set1, Set<String> set2, String element, String deposit) {
    ImmutableList<String> uniqueElements = ImmutableList.copyOf(difference(set1, set2));
    if (!uniqueElements.isEmpty()) {
      System.out.printf(
          "%s only in %s:\n%s\n", element, deposit, Joiner.on("\n").join(uniqueElements));
      return false;
    }
    return true;
  }

  private static void compareXmlDeposits(XjcRdeDeposit deposit1, XjcRdeDeposit deposit2) {
    Set<String> domains1 = new HashSet<>();
    Set<String> domains2 = new HashSet<>();
    Set<String> registrars1 = new HashSet<>();
    Set<String> registrars2 = new HashSet<>();
    process(deposit1, domains1, registrars1);
    process(deposit2, domains2, registrars2);
    boolean good = true;
    good &= printUniqueElements(domains1, domains2, "domains", "deposit1");
    good &= printUniqueElements(domains2, domains1, "domains", "deposit2");
    good &= printUniqueElements(registrars1, registrars2, "registrars", "deposit1");
    good &= printUniqueElements(registrars2, registrars1, "registrars", "deposit2");
    if (good) {
      System.out.println(
          "The two deposits contain the same domains and registrars. "
              + "You still need to run validate_escrow_deposit to check reference consistency.");
    } else {
      System.out.println("The two deposits differ.");
    }
  }
}
