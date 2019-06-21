//package io.alauda.jenkins.devops.sync;
//
//import io.alauda.kubernetes.api.model.Pipeline;
//import io.alauda.kubernetes.api.model.PipelineBuilder;
//import org.junit.Test;
//
//import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER;
//import static org.junit.Assert.assertEquals;
//import static org.junit.Assert.assertTrue;
//
//public class PipelineComparatorTest {
//    @Test
//    public void compare() {
//        Pipeline p1 = new PipelineBuilder().withNewMetadata()
//                .addToAnnotations(ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER, "0")
//                .endMetadata().build();
//
//        Pipeline p2 = new PipelineBuilder().withNewMetadata()
//                .addToAnnotations(ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER, "1")
//                .endMetadata().build();
//
//        Pipeline p3 = new PipelineBuilder().withNewMetadata()
//                .addToAnnotations(ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER, "1")
//                .endMetadata().build();
//
//        Pipeline p4 = new PipelineBuilder()
//                .withNewMetadata().addToAnnotations("", "")
//                .endMetadata().build();
//
//        Pipeline p5 = new PipelineBuilder()
//                .withNewMetadata().addToAnnotations("", "").endMetadata()
//                .withNewStatus()
//                .withPhase("phase")
//                .withAborted(true)
//                .endStatus().build();
//
//        Pipeline p6 = new PipelineBuilder()
//                .withNewMetadata().addToAnnotations("", "").endMetadata()
//                .withNewStatus()
//                .withAborted(false)
//                .endStatus().build();
//
//        assertTrue(new PipelineComparator().compare(p1, p2) < 0);
//        assertTrue(new PipelineComparator().compare(p2, p1) > 0);
//        assertEquals(0, new PipelineComparator().compare(p2, p3));
//        assertEquals(0, new PipelineComparator().compare(p2, p4));
//        assertTrue(new PipelineComparator().compare(p2, p5) != 0);
//    }
//}
