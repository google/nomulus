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

package google.registry.rdap;

import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;

import google.registry.rdap.RdapDataStructures.Link;
import google.registry.rdap.RdapDataStructures.Notice;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapObjectClasses.HelpResponse;
import google.registry.request.Action;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.util.Optional;

/** RDAP action for help requests. */
@Action(
    service = Action.Service.PUBAPI,
    path = RdapHelpAction.PATH,
    method = {GET, HEAD},
    isPrefix = true,
    auth = Auth.AUTH_PUBLIC)
public class RdapHelpAction extends RdapActionBase {

  public static final String PATH = "/rdap/help";

  /** The help path for the RDAP terms of service. */
  public static final String TOS_PATH = "/tos";

  private static final String RDAP_HELP_LINK =
      "https://github.com/google/nomulus/blob/master/docs/rdap.md";

  @Inject public RdapHelpAction() {
    super("help", EndpointType.HELP);
  }

  private Notice createHelpNotice() {
    return Notice.builder()
        .setTitle("RDAP Help")
        .setDescription(
            "domain/XXXX",
            "nameserver/XXXX",
            "entity/XXXX",
            "domains?name=XXXX",
            "domains?nsLdhName=XXXX",
            "domains?nsIp=XXXX",
            "nameservers?name=XXXX",
            "nameservers?ip=XXXX",
            "entities?fn=XXXX",
            "entities?handle=XXXX",
            "help/XXXX")
        .addLink(
            Link.builder().setHref(RDAP_HELP_LINK).setRel("alternate").setType("text/html").build())
        .addLink(
            Link.builder()
                .setHref(rdapJsonFormatter.makeRdapServletRelativeUrl("help"))
                .setRel("self")
                .setType("application/rdap+json")
                .build())
        .build();
  }

  @Override
  public HelpResponse getJsonObjectForResource(String pathSearchString, boolean isHeadRequest) {
    if (pathSearchString.isEmpty() || pathSearchString.equals("/")) {
      return HelpResponse.create(Optional.of(createHelpNotice()));
    }
    if (pathSearchString.equals(TOS_PATH)) {
      // A TOS notice is added to every reply automatically, so we don't want to add another one
      // here
      return HelpResponse.create(Optional.empty());
    }
    throw new NotFoundException("no help found for " + pathSearchString);
  }
}
