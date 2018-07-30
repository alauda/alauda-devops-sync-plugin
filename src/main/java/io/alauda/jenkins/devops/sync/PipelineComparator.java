package io.alauda.jenkins.devops.sync;

import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.kubernetes.api.model.Pipeline;

import java.io.Serializable;
import java.util.Comparator;

public class PipelineComparator implements Comparator<Pipeline>, Serializable
{
    @Override
    public int compare(Pipeline p1, Pipeline p2)
    {
        if (p1.getMetadata().getAnnotations() == null
                || p1.getMetadata().getAnnotations()
                .get(Constants.ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER) == null) {
//            logger.warning("cannot compare pipeline "
//                    + p1.getMetadata().getName()
//                    + " from namespace "
//                    + p1.getMetadata().getNamespace()
//                    + ", has bad annotations: "
//                    + p1.getMetadata().getAnnotations());
            return 0;
        }
        if (p2.getMetadata().getAnnotations() == null
                || p2.getMetadata().getAnnotations()
                .get(Constants.ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER) == null) {
//            logger.warning("cannot compare pipeline "
//                    + p2.getMetadata().getName()
//                    + " from namespace "
//                    + p2.getMetadata().getNamespace()
//                    + ", has bad annotations: "
//                    + p2.getMetadata().getAnnotations());
            return 0;
        }
        int rc = 0;
        try {
            rc = Long.compare(

                    Long.parseLong(p1
                            .getMetadata()
                            .getAnnotations()
                            .get(Constants.ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER)),
                    Long.parseLong(p2
                            .getMetadata()
                            .getAnnotations()
                            .get(Constants.ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER)));
        } catch (Throwable t) {
//            logger.log(Level.FINE, "onInitialPipelines", t);
        }
        return rc;
    }
}
