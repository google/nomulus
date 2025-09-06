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

package google.registry.dns.writer.powerdns;

import java.util.List;

/** The POJO that PowerDNS YAML config files are deserialized into. */
public class PowerDnsConfigSettings {

  public PowerDns powerDns;

  public static class PowerDns {
    public String baseUrl;
    public String apiKey;
    public Boolean dnssecEnabled;
    public Boolean tsigEnabled;
    public List<String> rootNameServers;
    public String soaName;
  }
}
