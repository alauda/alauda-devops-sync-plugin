package io.alauda.jenkins.devops.sync;

import net.sf.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ABcTest {
    @Test
    public void hello(){
        Map<String, String> data = new HashMap<>();
        data.put("ds","sdf");
        System.out.printf(net.sf.json.JSONObject.fromObject(data).toString());

        String string = JSONObject.fromObject(data).toString();
        System.out.printf(JSONObject.fromObject(string).getString("ds"));
        System.out.printf(JSONObject.fromObject(string).getClass().toString());

    }
}
