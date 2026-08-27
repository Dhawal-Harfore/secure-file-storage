package com.dhawal.secure_file_storage.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {

    @PostMapping("/api/test")
    public String test() {
        return "POST request received successfully!";
    }
}