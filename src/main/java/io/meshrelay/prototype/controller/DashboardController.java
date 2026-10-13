package io.meshrelay.prototype.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the browser-based dashboard used to explore the MeshRelay simulation.
 */
@Controller
public class DashboardController {

    /**
     * Returns the Thymeleaf view that drives the local interactive demo.
     */
    @GetMapping("/")
    public String home() {
        return "dashboard";
    }
}
