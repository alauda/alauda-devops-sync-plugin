package io.alauda.jenkins.devops.sync.event;

import io.kubernetes.client.models.V1Event;
import java.util.Objects;

/** Wrapper of {@link V1Event}, we can fold multiple events into one event easier. */
public class Event {

  private V1Event event;

  public Event(V1Event event) {
    this.event = event;
  }

  public V1Event getEvent() {
    return event;
  }

  public void submit() {
    EventExecutor eventExecutor = EventExecutor.getInstance();
    eventExecutor.submit(this);
  }

  /**
   * x If two event referred to same object, and has same type, reason, message. Those two event are
   * same.
   *
   * @param anotherEvent another event
   * @return true if events are same
   */
  @Override
  public boolean equals(Object anotherEvent) {
    if (this == anotherEvent) {
      return true;
    }
    if (anotherEvent == null || getClass() != anotherEvent.getClass()) {
      return false;
    }
    Event event = (Event) anotherEvent;
    V1Event actualEvent = event.getEvent();

    return this.event
            .getInvolvedObject()
            .getNamespace()
            .equals(actualEvent.getInvolvedObject().getNamespace())
        && this.event
            .getInvolvedObject()
            .getName()
            .equals(actualEvent.getInvolvedObject().getName())
        && this.event.getReason().equals(actualEvent.getReason())
        && this.event.getMessage().equals(actualEvent.getMessage())
        && this.event.getType().equals(actualEvent.getType());
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        event.getInvolvedObject().getNamespace(),
        event.getInvolvedObject().getName(),
        event.getReason(),
        event.getMessage(),
        event.getType());
  }
}
