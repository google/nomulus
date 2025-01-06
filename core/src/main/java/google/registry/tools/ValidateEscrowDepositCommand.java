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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Sets.difference;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import google.registry.keyring.api.Keyring;
import google.registry.rde.Ghostryde;
import google.registry.tools.params.PathParameter;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.domain.XjcDomainContactType;
import google.registry.xjc.domain.XjcDomainHostAttrType;
import google.registry.xjc.rde.XjcRdeDeposit;
import google.registry.xjc.rdecontact.XjcRdeContact;
import google.registry.xjc.rdedomain.XjcRdeDomain;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rderegistrar.XjcRdeRegistrar;
import google.registry.xml.XmlException;
import jakarta.xml.bind.JAXBElement;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.annotation.Nullable;
import javax.inject.Inject;

/** Command to view and schema validate an XML RDE escrow deposit. */
@Parameters(separators = " =", commandDescription = "View/validate an XML escrow deposit.")
final class ValidateEscrowDepositCommand implements Command {

  @Parameter(
      names = {"-i", "--input"},
      description = "XML escrow deposit file. May be plain XML or an XML GhostRyDE file.",
      validateWith = PathParameter.InputFile.class)
  private Path input = Paths.get("/dev/stdin");

  @Inject
  Keyring keyring;

  @Override
  public void run() throws Exception {
    if (input.toString().endsWith(".ghostryde")) {
      try (InputStream in = Files.newInputStream(input);
          InputStream ghostrydeDecoder =
              Ghostryde.decoder(in, keyring.getRdeStagingDecryptionKey())) {
        validateXmlStream(ghostrydeDecoder);
      }
    } else {
      try (InputStream inputStream = Files.newInputStream(input)) {
        validateXmlStream(inputStream);
      }
    }
  }

  private static void validateXmlStream(InputStream inputStream) throws XmlException {
    XjcRdeDeposit deposit = XjcXmlTransformer.unmarshal(XjcRdeDeposit.class, inputStream);
    System.out.printf("ID: %s\n", deposit.getId());
    System.out.printf("Previous ID: %s\n", deposit.getPrevId());
    System.out.printf("Type: %s\n", deposit.getType());
    System.out.printf("Watermark: %s\n", deposit.getWatermark());
    System.out.printf("RDE Version: %s\n", deposit.getRdeMenu().getVersion());
    System.out.println();
    System.out.printf("RDE Object URIs:\n  - %s\n",
        Joiner.on("\n  - ").join(Ordering.natural().sortedCopy(deposit.getRdeMenu().getObjURIs())));
    Set<String> hostnames = new HashSet<>();
    Set<String> hostnameRefs = new HashSet<>();
    Set<String> contacts = new HashSet<>();
    Set<String> contactRefs = new HashSet<>();
    Set<String> registrars = new HashSet<>();
    Set<String> registrarRefs = new HashSet<>();
    SortedMap<String, Long> counts = new TreeMap<>();
    for (JAXBElement<?> item : deposit.getContents().getContents()) {
      String name = item.getDeclaredType().getSimpleName();
      counts.put(name, counts.getOrDefault(name, 0L) + 1L);
      if (XjcRdeHost.class.isAssignableFrom(item.getDeclaredType())) {
        XjcRdeHost host = (XjcRdeHost) item.getValue();
        hostnames.add(checkNotNull(host.getName()));
        addIfNotNull(registrarRefs, host.getClID());
        if (host.getUpRr() != null) {
          addIfNotNull(registrarRefs, host.getUpRr().getValue());
        }
      } else if (XjcRdeContact.class.isAssignableFrom(item.getDeclaredType())) {
        XjcRdeContact contact = (XjcRdeContact) item.getValue();
        contacts.add(checkNotNull(contact.getId()));
        addIfNotNull(registrarRefs, contact.getClID());
        if (contact.getUpRr() != null) {
          addIfNotNull(registrarRefs, contact.getUpRr().getValue());
        }
      } else if (XjcRdeDomain.class.isAssignableFrom(item.getDeclaredType())) {
        XjcRdeDomain domain = (XjcRdeDomain) item.getValue();
        addIfNotNull(registrarRefs, domain.getClID());
        if (domain.getUpRr() != null) {
          addIfNotNull(registrarRefs, domain.getUpRr().getValue());
        }
        if (domain.getNs() != null) {
          hostnameRefs.addAll(domain.getNs().getHostObjs());
          for (XjcDomainHostAttrType hostAttr : domain.getNs().getHostAttrs()) {
            addIfNotNull(hostnameRefs, hostAttr.getHostName());
          }
        }
        for (XjcDomainContactType contact : domain.getContacts()) {
          contactRefs.add(contact.getValue());
        }
      } else if (XjcRdeRegistrar.class.isAssignableFrom(item.getDeclaredType())) {
        XjcRdeRegistrar registrar = (XjcRdeRegistrar) item.getValue();
        registrars.add(checkNotNull(registrar.getId()));
      }
    }
    System.out.println();
    System.out.println("Contents:");
    for (Map.Entry<String, Long> count : counts.entrySet()) {
      System.out.printf("  - %s: %,d %s\n",
          count.getKey(),
          count.getValue(),
          count.getValue() == 1L ? "entry" : "entries");
    }
    System.out.println();
    boolean good = true;
    ImmutableList<String> badHostnameRefs =
        ImmutableList.copyOf(difference(hostnameRefs, hostnames));
    if (!badHostnameRefs.isEmpty()) {
      System.out.printf("Bad host refs: %s\n", Joiner.on(", ").join(badHostnameRefs));
      good = false;
    }
    ImmutableList<String> badContactRefs = ImmutableList.copyOf(difference(contactRefs, contacts));
    if (!badContactRefs.isEmpty()) {
      System.out.printf("Bad contact refs: %s\n", Joiner.on(", ").join(badContactRefs));
      good = false;
    }
    ImmutableList<String> badRegistrarRefs =
        ImmutableList.copyOf(difference(registrarRefs, registrars));
    if (!badRegistrarRefs.isEmpty()) {
      System.out.printf("Bad registrar refs: %s\n", Joiner.on(", ").join(badRegistrarRefs));
      good = false;
    }
    if (good) {
      System.out.println("RDE deposit is XML schema valid");
    } else {
      System.out.println("RDE deposit is XML schema valid but has bad references");
    }
  }

  private static <T> void addIfNotNull(Collection<T> collection, @Nullable T item) {
    if (item != null) {
      collection.add(item);
    }
  }
}
