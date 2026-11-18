package dev.nexus.core.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 概览告警阈值：所有已认证角色可读，只有管理员能改（写路径由 RoleTokens 拦在控制器之前）。 */
@RestController
public class AlertThresholdsController {
    private final AlertThresholds thresholds;

    public AlertThresholdsController(AlertThresholds thresholds) { this.thresholds = thresholds; }

    @GetMapping("/api/v1/overview/thresholds")
    Map<String,Object> read() { return thresholds.read(); }

    @PutMapping("/api/v1/overview/thresholds")
    Map<String,Object> update(@RequestBody AlertThresholds.Update input,HttpServletRequest request) {
        return thresholds.update(input,(String)request.getAttribute("nexus.actor"));
    }
}
