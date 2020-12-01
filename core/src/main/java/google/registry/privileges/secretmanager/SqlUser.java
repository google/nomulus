// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.privileges.secretmanager;

import com.google.common.base.Ascii;

/**
 * SQL user information for privilege management purposes.
 *
 * <p>A {@link RobotUser} represents a software system accessing the database using its own
 * credential. Robots are well known and enumerated in {@link RobotId}.
 */
public abstract class SqlUser {

  private final UserType type;
  private final String userName;

  protected SqlUser(UserType type, String userName) {
    this.type = type;
    this.userName = userName;
  }

  public UserType getType() {
    return type;
  }

  public String geUserName() {
    return userName;
  }

  /** Cloud SQL user types. Please see class javadoc of {@link SqlUser} for more information. */
  enum UserType {
    ROBOT
  }

  /** Enumerates the {@link RobotUser RobotUsers} in the system. */
  public enum RobotId {
    NOMULUS;
  }

  /** Information of a RobotUser for privilege management purposes. */
  public static class RobotUser extends SqlUser {

    public RobotUser(RobotId robot) {
      super(UserType.ROBOT, Ascii.toLowerCase(robot.name()));
    }
  }
}
