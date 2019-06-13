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

package google.registry.dns;

/** Static class for DNS-related constants. */
public class DnsConstants {
  private DnsConstants() {}

  /** The name of the DNS pull queue. */
  public static final String DNS_PULL_QUEUE_NAME = "dns-pull";  // See queue.xml.

  /** The name of the DNS publish push queue. */
  public static final String DNS_PUBLISH_PUSH_QUEUE_NAME = "dns-publish";  // See queue.xml.

  /** The parameter to use for storing the target type ("domain" or "host" or "zone"). */
  public static final String DNS_TARGET_TYPE_PARAM = "Target-Type";

  /** The parameter to use for storing the target name (domain or host name) with the task. */
  public static final String DNS_TARGET_NAME_PARAM = "Target-Name";

  /** The parameter to use for storing the creation time with the task. */
  public static final String DNS_TARGET_CREATE_TIME_PARAM = "Create-Time";

  /** The possible values of the {@code DNS_TARGET_TYPE_PARAM} parameter. */
  public enum TargetType { DOMAIN, HOST, ZONE }
}
