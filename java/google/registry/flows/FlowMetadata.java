package google.registry.flows;

import com.google.auto.value.AutoValue;
import google.registry.model.ImmutableObject;

/** Object to hold metadata information specific to a particular execution of a flow. */
@AutoValue
public abstract class FlowMetadata extends ImmutableObject {

  /** True if this flow is being run with superuser privileges */
  public abstract boolean isSuperuser();

  public static Builder newBuilder() {
    return new AutoValue_FlowMetadata.Builder();
  }

  /** Builder for {@link FlowMetadata} */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder setSuperuser(boolean isSuperuser);

    public abstract FlowMetadata build();
  }
}
