package google.registry.flows;

import com.google.auto.value.AutoValue;
import google.registry.model.ImmutableObject;

/** Object to allow setting and retrieving metadata information in flows. */
@AutoValue
public abstract class FlowMetadata extends ImmutableObject {

  public abstract boolean superuser();

  public static Builder newBuilder() {
    return new AutoValue_FlowMetadata.Builder();
  }

  /** Builder for {@link FlowMetadata} */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder setSuperuser(boolean superuser);

    public abstract FlowMetadata build();
  }
}
