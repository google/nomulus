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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Maps.filterValues;
import static com.google.common.io.Resources.getResource;
import static google.registry.model.tld.Registries.findTldForNameOrThrow;
import static google.registry.tools.CommandUtilities.addHeader;
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
import com.google.template.soy.SoyFileSet;
import com.google.template.soy.data.SoyRecord;
import com.google.template.soy.parseinfo.SoyFileInfo;
import com.google.template.soy.parseinfo.SoyTemplateInfo;
import google.registry.model.registrar.Registrar;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A command to execute an epp command. */
abstract class EppToolCommand extends ConfirmingCommand
    implements CommandWithConnection, CommandWithRemoteApi {

  @Parameter(
      names = {"-u", "--superuser"},
      description = "Run in superuser mode")
  boolean superuser = false;

  private SoyFileInfo soyFileInfo;
  private SoyTemplateInfo soyRenderer;

  private List<XmlEppParameters> commands = new ArrayList<>();

  private AppEngineConnection connection;

  static class XmlEppParameters {
    final String clientId;
    final String xml;

    XmlEppParameters(String clientId, String xml) {
      this.clientId = clientId;
      this.xml = xml;
    }

    @Override
    public String toString() {
      return prettyPrint(xml);
    }
  }

  /**
   * Helper function for grouping sets of domain names into respective TLDs. Useful for batched
   * EPP calls when invoking commands (i.e. domain check) with sets of domains across multiple TLDs.
   */
  protected static Multimap<String, String> validateAndGroupDomainNamesByTld(List<String> names) {
    ImmutableMultimap.Builder<String, String> builder = new ImmutableMultimap.Builder<>();
    for (String name : names) {
      InternetDomainName tld = findTldForNameOrThrow(InternetDomainName.from(name));
      builder.put(tld.toString(), name);
    }
    return builder.build();
  }

  protected void setSoyTemplate(SoyFileInfo soyFileInfo, SoyTemplateInfo soyRenderer) {
    this.soyFileInfo = soyFileInfo;
    this.soyRenderer = soyRenderer;
  }

  @Override
  public void setConnection(AppEngineConnection connection) {
    this.connection = connection;
  }

  protected void addXmlCommand(String clientId, String xml) {
    checkArgumentPresent(
        Registrar.loadByClientId(clientId), "Registrar with client ID %s not found", clientId);
    commands.add(new XmlEppParameters(clientId, xml));
  }

  protected void addSoyRecord(String clientId, SoyRecord record) {
    checkNotNull(soyFileInfo, "SoyFileInfo is missing, cannot add record.");
    checkNotNull(soyRenderer, "SoyRenderer is missing, cannot add record.");
    addXmlCommand(clientId, SoyFileSet.builder()
        .add(getResource(soyFileInfo.getClass(), soyFileInfo.getFileName()))
        .build()
        .compileToTofu()
        .newRenderer(soyRenderer)
        .setData(record)
        .render());
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

  private List<String> processCommands(boolean dryRun) throws IOException {
    ImmutableList.Builder<String> responses = new ImmutableList.Builder<>();
    for (XmlEppParameters command : commands) {
      Map<String, Object> params = new HashMap<>();
      params.put("dryRun", dryRun);
      params.put("clientId", command.clientId);
      params.put("superuser", superuser);
      params.put("xml", URLEncoder.encode(command.xml, UTF_8.toString()));
      String requestBody =
          Joiner.on('&').withKeyValueSeparator("=").join(filterValues(params, Objects::nonNull));
      responses.add(
          nullToEmpty(
              connection.sendPostRequest(
                  "/_dr/epptool",
                  ImmutableMap.<String, String>of(),
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
