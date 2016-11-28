// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.model.permission;

public enum Permission {
  NONE("No Permissions Required"),
  READ_PERMISSION_GROUPS("Read Access to Permission Groups"),
  WRITE_PERMISSON_GROUPS("Write Access to Permission Groups"),
  ASSIGN_PERMISSION_GROUPS("Assign Permission Groups to Registrar Contacts"),
  READ_REGISTRAR_CONTACTS("Read Access to Registrar Contacts");

  public final String description;

  private Permission(String description) {
    this.description = description;
  }
}
