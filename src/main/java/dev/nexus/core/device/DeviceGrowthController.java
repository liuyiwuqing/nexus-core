package dev.nexus.core.device;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class DeviceGrowthController {
    private final DeviceGrowthService growth;
    public DeviceGrowthController(DeviceGrowthService growth){this.growth=growth;}
    @GetMapping("/api/v1/devices/{id}/growth") Map<String,Object> view(@PathVariable String id){return growth.view(id);}
    @GetMapping("/api/v1/pet/devices") Map<String,Object> devices(){return growth.devices();}
}
