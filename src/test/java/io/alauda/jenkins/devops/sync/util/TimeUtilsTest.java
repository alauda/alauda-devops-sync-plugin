package io.alauda.jenkins.devops.sync.util;

import org.junit.Test;

import java.text.ParseException;

import static org.junit.Assert.assertNotNull;

public class TimeUtilsTest {
    @Test
    public void basic() throws ParseException {
        assertNotNull(TimeUtils.getUTCTime());
    }
}
