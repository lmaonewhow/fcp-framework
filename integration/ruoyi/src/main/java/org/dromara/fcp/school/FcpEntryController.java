package org.dromara.fcp.school;

import cn.dev33.satoken.annotation.SaIgnore;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FcpEntryController {
    @SaIgnore @GetMapping("/admin") public String admin() { return "redirect:/admin/"; }
    @SaIgnore @GetMapping("/teacher") public String teacher() { return "redirect:/teacher/"; }
}
