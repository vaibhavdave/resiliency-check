package com.example.resiliency.inventory.failure;

import com.example.resiliency.inventory.failure.FailureState.FailureSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/mode")
public class AdminController {

    private final FailureState failureState;

    public AdminController(FailureState failureState) {
        this.failureState = failureState;
    }

    @GetMapping
    public FailureSettings currentMode() {
        return failureState.get();
    }

    @PostMapping
    public FailureSettings setMode(@RequestBody FailureSettings newSettings) {
        failureState.set(newSettings);
        return failureState.get();
    }
}
