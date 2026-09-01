package com.dhawal.secure_file_storage.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dhawal.secure_file_storage.dto.UserRequest;
import org.springframework.web.bind.annotation.RequestBody;


@RestController
public class TestController {

    @PostMapping("/api/users")
    public UserRequest createUser(@RequestBody UserRequest user) {
        return user;
    }

    @PostMapping("/api/test")
    public String test() {
        return "POST request received successfully!";
    }
}