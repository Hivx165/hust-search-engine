package edu.hust.web.controller;

import edu.hust.web.util.SubdomainUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin("*")
public class SubdomainController {

    @Autowired
    private SubdomainUtil subdomainUtil;

    @GetMapping("/api/subdomains")
    public List<Map<String, String>> getSubdomains() {
        return subdomainUtil.loadSubdomains();
    }
}
