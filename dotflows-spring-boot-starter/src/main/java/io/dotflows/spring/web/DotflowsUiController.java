package io.dotflows.spring.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Sends the base path to the single-page UI. The UI uses relative URLs, so the trailing slash matters. */
@Controller
public class DotflowsUiController {

    @GetMapping("${dotflows.web.base-path:/dotflows}")
    public String withoutSlash(HttpServletRequest request) {
        return "redirect:" + request.getRequestURI() + "/";
    }

    @GetMapping("${dotflows.web.base-path:/dotflows}/")
    public String index() {
        return "forward:index.html";
    }
}
