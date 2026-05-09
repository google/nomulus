// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Maps.filterValues;
import static google.registry.model.tld.Tlds.findTldForNameOrThrow;
import static google.registry.tools.CommandUtilities.addHeader;
import static google.registry.util.DomainNameUtils.canonicalizeHostname;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;
import static google.registry.xml.XmlTransformer.prettyPrint;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.net.InternetDomainName;
import com.google.common.net.MediaType;
import google.registry.model.eppcommon.EppXmlTransformer;
import google.registry.model.eppinput.EppInput;
import google.registry.model.registrar.Registrar;
import google.registry.util.Clock;
import google.registry.xml.ValidationMode;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A command to execute an epp command. */
abstract class EppToolCommand extends ConfirmingCommand implements CommandWithConnection {

  @Inject Clock clock;

  @Parameter(
      names = {"-u", "--superuser"},
      description = "Run in superuser mode")
  boolean superuser = false;

  private List<XmlEppParameters> commands = new ArrayList<>();

  private ServiceConnection connection;

  record XmlEppParameters(String clientId, String xml) {

    @Override
    public String toString() {
      return prettyPrint(xml);
    }
  }

  /**
   * Helper function for grouping sets of domain names into respective TLDs. Useful for batched EPP
   * calls when invoking commands (i.e. domain check) with sets of domains across multiple TLDs.
   */
  protected static Multimap<String, String> validateAndGroupDomainNamesByTld(
      ImmutableList<String> names) {
    ImmutableMultimap.Builder<String, String> builder = new ImmutableMultimap.Builder<>();
    for (String name : names) {
      String canonicalDomain = canonicalizeHostname(name);
      InternetDomainName tld = findTldForNameOrThrow(InternetDomainName.from(canonicalDomain));
      builder.put(tld.toString(), canonicalDomain);
    }
    return builder.build();
  }

  @Override
  public void setConnection(ServiceConnection connection) {
    this.connection = connection;
  }

  protected void addXmlCommand(String clientId, String xml) {
    checkArgumentPresent(
        Registrar.loadByRegistrarId(clientId), "Registrar with client ID %s not found", clientId);
    commands.add(new XmlEppParameters(clientId, xml));
  }

  /**
   * Adds an EPP command to the list of commands to be executed.
   *
   * @param clientId the registrar client ID to execute the command as
   * @param eppInput the EPP input object to marshal and send
   */
  protected void addEppInput(String clientId, EppInput eppInput) {
    try {
      String xml =
          new String(EppXmlTransformer.marshalInput(eppInput, ValidationMode.STRICT), UTF_8);
      addXmlCommand(clientId, xml);
    } catch (Exception e) {
      throw new RuntimeException("Failed to marshal EppInput", e);
    }
  }

  /** Subclasses can override to implement a dry run flag. False by default. */
  protected boolean isDryRun() {
    return false;
  }

  @Override
  protected boolean dontRunCommand() {
    return isDryRun();
  }

  @Override
  public String prompt() throws IOException {
    String prompt = addHeader("Command(s)", Joiner.on("\n").join(commands)
        + (force ? "" : addHeader("Dry Run", Joiner.on("\n").join(processCommands(true)))));
    force = force || isDryRun();
    return prompt;
  }

  private ImmutableList<String> processCommands(boolean dryRun) throws IOException {
    ImmutableList.Builder<String> responses = new ImmutableList.Builder<>();
    for (XmlEppParameters command : commands) {
      ImmutableMap<String, Object> params =
          ImmutableMap.<String, Object>builder()
              .put("dryRun", dryRun)
              .put("clientId", command.clientId)
              .put("superuser", superuser)
              .put("xml", URLEncoder.encode(command.xml, UTF_8))
              .build();
      String requestBody =
          Joiner.on('&').withKeyValueSeparator("=").join(filterValues(params, Objects::nonNull));
      responses.add(
          nullToEmpty(
              connection.sendPostRequest(
                  "/_dr/epptool",
                  ImmutableMap.of(),
                  MediaType.FORM_DATA,
                  requestBody.getBytes(UTF_8))));
    }
    return responses.build();
  }

  @Override
  public String execute() throws Exception {
    return isDryRun() ? "" : addHeader("Response", Joiner.on("\n").join(processCommands(false)));
  }

  @Override
  protected final void init() throws Exception {
    initEppToolCommand();
  }

  abstract void initEppToolCommand() throws Exception;
}
