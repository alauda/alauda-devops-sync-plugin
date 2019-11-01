package io.alauda.jenkins.devops.sync.util;

import antlr.ANTLRException;
import hudson.scheduler.CronTab;
import hudson.scheduler.CronTabList;
import hudson.scheduler.Hash;
import hudson.scheduler.Messages;
import java.util.Calendar;
import java.util.Collection;
import java.util.Vector;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

public class CronUtils {
  private final Vector<CronTab> tabs;

  public CronUtils(Collection<CronTab> tabs) {
    this.tabs = new Vector<>(tabs);
  }

  public static CronUtils create(@Nonnull String format, Hash hash) throws ANTLRException {
    Vector<CronTab> r = new Vector<>();
    int lineNumber = 0;
    String timezone = null;

    for (String line : format.split("\\r?\\n")) {
      lineNumber++;
      line = line.trim();

      if (lineNumber == 1 && line.startsWith("TZ=")) {
        timezone = CronTabList.getValidTimezone(line.replace("TZ=", ""));
        if (timezone == null) {
          throw new ANTLRException("Invalid or unsupported timezone '" + timezone + "'");
        }
        continue;
      }

      if (line.length() == 0 || line.startsWith("#")) continue; // ignorable line
      try {
        r.add(new CronTab(line, lineNumber, hash, timezone));
      } catch (ANTLRException e) {
        throw new ANTLRException(Messages.CronTabList_InvalidInput(line, e.toString()), e);
      }
    }

    return new CronUtils(r);
  }

  public @CheckForNull Calendar previous() {
    Calendar nearest = null;
    for (CronTab tab : tabs) {
      Calendar scheduled =
          tab.floor(
              tab.getTimeZone() == null
                  ? Calendar.getInstance()
                  : Calendar.getInstance(tab.getTimeZone()));
      if (nearest == null || nearest.before(scheduled)) {
        nearest = scheduled;
      }
    }
    return nearest;
  }

  public @CheckForNull Calendar next() {
    Calendar nearest = null;
    for (CronTab tab : tabs) {
      Calendar scheduled =
          tab.ceil(
              tab.getTimeZone() == null
                  ? Calendar.getInstance()
                  : Calendar.getInstance(tab.getTimeZone()));
      if (nearest == null || nearest.after(scheduled)) {
        nearest = scheduled;
      }
    }
    return nearest;
  }

  /**
   * Checks if this crontab entry looks reasonable, and if not, return an warning message.
   *
   * <p>The point of this method is to catch syntactically correct but semantically suspicious
   * combinations, like "* 0 * * *"
   */
  public String checkSanity() {
    for (CronTab tab : tabs) {
      String s = tab.checkSanity();
      if (s != null) return s;
    }
    return null;
  }
}
