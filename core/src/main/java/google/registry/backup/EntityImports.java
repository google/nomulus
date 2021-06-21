// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.backup;

import com.google.apphosting.api.ApiProxy;
import com.google.storage.onestore.v3.OnestoreEntity;
import com.google.storage.onestore.v3.OnestoreEntity.EntityProto;
import com.google.storage.onestore.v3.OnestoreEntity.Path;
import com.google.storage.onestore.v3.OnestoreEntity.Property.Meaning;
import com.google.storage.onestore.v3.OnestoreEntity.PropertyValue.ReferenceValue;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Utilities for handling imported Datastore entities. */
public class EntityImports {

  /**
   * Transitively sets the {@code appId} of all keys in a foreign entity to that of the current
   * system.
   */
  public static EntityProto fixEntity(EntityProto entityProto) {
    String currentAappId = ApiProxy.getCurrentEnvironment().getAppId();
    if (Objects.equals(currentAappId, entityProto.getKey().getApp())) {
      return entityProto;
    }
    return fixEntity(entityProto, currentAappId);
  }

  private static EntityProto fixEntity(EntityProto entityProto, String appId) {
    if (entityProto.hasKey()) {
      fixKey(entityProto, appId);
    }

    for (OnestoreEntity.Property property : entityProto.mutablePropertys()) {
      fixProperty(property, appId);
    }

    for (OnestoreEntity.Property property : entityProto.mutableRawPropertys()) {
      fixProperty(property, appId);
    }

    // CommitLogMutation embeds an entity as bytes, which needs additional fixes.
    if (isCommitLogMutation(entityProto)) {
      fixMutationEntityProtoBytes(entityProto, appId);
    }
    return entityProto;
  }

  private static boolean isCommitLogMutation(EntityProto entityProto) {
    if (!entityProto.hasKey()) {
      return false;
    }
    Path path = entityProto.getKey().getPath();
    if (path.elementSize() == 0) {
      return false;
    }
    return Objects.equals(
        path.getElement(path.elementSize() - 1).getType(StandardCharsets.UTF_8),
        "CommitLogMutation");
  }

  private static void fixMutationEntityProtoBytes(EntityProto entityProto, String appId) {
    for (OnestoreEntity.Property property : entityProto.mutableRawPropertys()) {
      if (Objects.equals(property.getName(), "entityProtoBytes")) {
        OnestoreEntity.PropertyValue value = property.getValue();
        EntityProto fixedProto =
            fixEntity(bytesToEntityProto(value.getStringValueAsBytes()), appId);
        value.setStringValueAsBytes(fixedProto.toByteArray());
        return;
      }
    }
  }

  private static void fixKey(EntityProto entityProto, String appId) {
    entityProto.getMutableKey().setApp(appId);
  }

  private static void fixKey(ReferenceValue referenceValue, String appId) {
    referenceValue.setApp(appId);
  }

  private static void fixProperty(OnestoreEntity.Property property, String appId) {
    OnestoreEntity.PropertyValue value = property.getMutableValue();
    if (value.hasReferenceValue()) {
      fixKey(value.getMutableReferenceValue(), appId);
      return;
    }
    if (property.getMeaningEnum().equals(Meaning.ENTITY_PROTO)) {
      EntityProto embeddedProto = bytesToEntityProto(value.getStringValueAsBytes());
      fixEntity(embeddedProto, appId);
      value.setStringValueAsBytes(embeddedProto.toByteArray());
    }
  }

  private static EntityProto bytesToEntityProto(byte[] bytes) {
    EntityProto entityProto = new EntityProto();
    boolean isParsed = entityProto.parseFrom(bytes);
    if (!isParsed) {
      throw new IllegalStateException("Failed to parse raw bytes as EntityProto.");
    }
    return entityProto;
  }
}
