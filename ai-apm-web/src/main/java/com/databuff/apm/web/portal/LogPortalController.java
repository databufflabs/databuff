package com.databuff.apm.web.portal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Portal-compatible log APIs ({@code POST /webapi/log/*}). */
@RestController
@RequestMapping("/log")
public class LogPortalController {

    private final LogPortalService logPortalService;

    public LogPortalController(LogPortalService logPortalService) {
        this.logPortalService = logPortalService;
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body) {
        return logPortalService.search(body);
    }

    @PostMapping("/conditions")
    public Map<String, Object> conditions(@RequestBody(required = false) Map<String, Object> body) {
        return logPortalService.conditions(body);
    }

    @PostMapping("/trend")
    public Map<String, Object> trend(@RequestBody(required = false) Map<String, Object> body) {
        return logPortalService.trend(body);
    }
}
