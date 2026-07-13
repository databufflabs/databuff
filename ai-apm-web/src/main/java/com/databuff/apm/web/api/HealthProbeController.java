package com.databuff.apm.web.api;

import com.databuff.apm.web.storage.DorisAvailability;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
public class HealthProbeController {

    private final DorisAvailability dorisAvailability;

    public HealthProbeController(DorisAvailability dorisAvailability) {
        this.dorisAvailability = dorisAvailability;
    }

    @GetMapping("/health")
    @ResponseBody
    public Map<String, String> health() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("doris", dorisAvailability.isUnavailable() ? "UNAVAILABLE" : "UP");
        return body;
    }
}
