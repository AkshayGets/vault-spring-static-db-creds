package com.hashicorp.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DemoController {

    private final DemoService demo;
    private final VaultCredentialHolder holder;
    private final String page;

    public DemoController(DemoService demo,
                          VaultCredentialHolder holder,
                          @Value("${demo.vault.static-creds-path}") String path,
                          @Value("${demo.pool.size}") int poolSize) {
        this.demo = demo;
        this.holder = holder;
        this.page = Page.html(path, poolSize);
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        return page;
    }

    /**
     * Readiness / liveness target. Deliberately does NOT touch the
     * database -- this demo exists to show the database refusing
     * connections, and a probe that queried it would restart the pod
     * at the most interesting moment.
     */
    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return demo.status();
    }

    /** Force a refresh from Vault, to show the holder updating on demand. */
    @PostMapping("/api/refresh")
    public Map<String, Object> refresh() {
        holder.refresh();
        return Map.of("success", true, "username", holder.current().username());
    }
}
