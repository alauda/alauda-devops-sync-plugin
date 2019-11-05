package io.alauda.jenkins.devops.sync.event;

import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.kubernetes.client.models.V1Event;
import io.kubernetes.client.models.V1EventSource;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1ObjectReference;
import javax.annotation.Nonnull;
import org.joda.time.DateTime;

public class Events {

  private Events() {}

  public static Event newEvent(
      @Nonnull V1ObjectReference reference,
      @Nonnull EventType eventType,
      @Nonnull String reason,
      @Nonnull String message) {
    V1Event event = new V1Event();
    event
        .metadata(new V1ObjectMeta().generateName(reference.getName() + "-"))
        .involvedObject(reference)
        .reason(reason)
        .firstTimestamp(DateTime.now())
        .lastTimestamp(DateTime.now())
        .message(message)
        .source(
            new V1EventSource().component(AlaudaSyncGlobalConfiguration.get().getJenkinsService()))
        .type(eventType.toString());

    return new Event(event);
  }

  static V1ObjectReference convertToObjectReference(
      @Nonnull String apiVersion, @Nonnull String kind, @Nonnull V1ObjectMeta objectMeta) {
    return new V1ObjectReference()
        .apiVersion(apiVersion)
        .kind(kind)
        .namespace(objectMeta.getNamespace())
        .name(objectMeta.getName())
        .uid(objectMeta.getUid())
        .resourceVersion(objectMeta.getResourceVersion());
  }

  public enum EventType {
    EventTypeNormal("Normal"),
    EventTypeWarning("Warning");

    private String eventType;

    EventType(@Nonnull String eventType) {
      this.eventType = eventType;
    }

    @Override
    public String toString() {
      return eventType;
    }
  }
}
