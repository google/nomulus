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

package google.registry.util;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.appengine.api.modules.ModulesService;
import com.google.common.flogger.FluentLogger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;

/** A wrapper for {@link ModulesService} that provides a saner API. */
public class AppEngineServiceUtilsImpl implements AppEngineServiceUtils {

  private static final Pattern APPSPOT_HOSTNAME_PATTERN =
      Pattern.compile("^(.*)\\.appspot\\.com$");
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final ModulesService modulesService;

  @Inject
  public AppEngineServiceUtilsImpl(ModulesService modulesService) {
    this.modulesService = modulesService;
  }

  @Override
  public String getServiceHostname(String service) {
    // This will be in the format "version.service.projectid.appspot.com"
    String hostnameWithVersion = modulesService.getVersionHostname(service, null);
    // Strip off the version and return just "service.projectid.appspot.com"
    return hostnameWithVersion.replaceFirst("^[^.]+\\.", "");
  }

  @Override
  public String getCurrentVersionHostname(String service) {
    return modulesService.getVersionHostname(service, null);
  }

  @Override
  public String getVersionHostname(String service, String version) {
    checkArgumentNotNull(version, "Must specify the version");
    return modulesService.getVersionHostname(service, version);
  }

  @Override
  public void setNumInstances(String service, String version, long numInstances) {
    checkArgumentNotNull(service, "Must specify the service");
    checkArgumentNotNull(version, "Must specify the version");
    checkArgument(numInstances > 0, "Number of instances must be greater than 0");
    modulesService.setNumInstances(service, version, numInstances);
  }

  @Override
  public String convertToSingleSubdomain(String hostname) {
    Matcher matcher = APPSPOT_HOSTNAME_PATTERN.matcher(hostname);
    if (!matcher.matches()) {
      logger.atWarning().log(
          "Skipping conversion because hostname '%s' can't be parsed.", hostname);
      return hostname;
    }
    return matcher.group(1).replace(".", "-dot-") + ".appspot.com";
  }
}
