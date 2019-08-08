// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.host;

/** Factory class to create {@link HostDao} instance. */
public class HostDaoFactory {
  private static HostDao DAO = createHostDao();

  private HostDaoFactory() {}

  private static HostDao createHostDao() {
    // TODO: Conditionally returns the corresponding implementation once we have
    //  HostCloudSqlDao
    return new HostDatastoreDao();
  }

  /** Returns the instance of {@link HostDao}. */
  public static HostDao hostDao() {
    return DAO;
  }
}
