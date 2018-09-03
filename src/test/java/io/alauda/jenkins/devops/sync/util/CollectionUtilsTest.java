package io.alauda.jenkins.devops.sync.util;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class CollectionUtilsTest {
    @Test
    public void isEmpty() {
        assertTrue(CollectionUtils.isEmpty(Collections.emptyList()));
        assertTrue(CollectionUtils.isEmpty(null));

        List<String> notEmpty = Collections.singletonList("");
        assertTrue(CollectionUtils.isNotEmpty(notEmpty));
    }
}
