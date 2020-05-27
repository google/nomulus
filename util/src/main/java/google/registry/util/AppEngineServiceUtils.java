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

/**
 * A wrapper for {@link com.google.appengine.api.modules.ModulesService} that provides a saner API.
 */
public interface AppEngineServiceUtils {

  /**
   * Returns a host name to use for the given service.
   *
   * <p>Note that this host name will not include a version, so it will always be whatever the live
   * version is at the time that you hit the URL.
   */
  String getServiceHostname(String service);

  /**
   * Returns a host name to use for the given service and current version.
   *
   * <p>Note that this host name will include the current version now at time of URL generation,
   * which will not be the live version in the future.
   */
  String getCurrentVersionHostname(String service);

  /** Returns a host name to use for the given service and version. */
  String getVersionHostname(String service, String version);

  /**
   * Converts a multi-level App Engine host name (not URL) to the -dot- single subdomain format.
   *
   * <p>This is needed because appspot.com only has a single wildcard SSL certificate, so the native
   * App Engine URLs of the form service.projectid.appspot.com or
   * version.service.projectid.appspot.com won't work over HTTPS when being fetched from outside of
   * GCP. The work-around is to change all of the "." subdomain markers to "-dot-". E.g.:
   *
   * <ul>
   *   <li>tools.projectid.appspot.com --&gt; tools-dot-projectid.appspot.com
   *   <li>version.backend.projectid.appspot.com --&gt;
   *       version-dot-backend-dot-projectid.appspot.com
   * </ul>
   *
   * @see <a
   *     href="https://cloud.google.com/appengine/docs/standard/java/how-requests-are-routed">How
   *     App Engine requests are routed</a>
   */
  String convertToSingleSubdomain(String hostname);

  /** Set the number of instances at runtime for a given service and version. */
  void setNumInstances(String service, String version, long numInstances);
}
