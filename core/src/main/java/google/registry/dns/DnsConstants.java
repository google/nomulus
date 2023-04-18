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
public final class DnsConstants {

  private DnsConstants() {}

  /** The name of the DNS publish push queue. */
  public static final String DNS_PUBLISH_PUSH_QUEUE_NAME = "dns-publish"; // See queue.xml.

  /** The possible values of the {@code DNS_TARGET_TYPE_PARAM} parameter. */
  public enum TargetType {
    DOMAIN,
    HOST
  }
}
