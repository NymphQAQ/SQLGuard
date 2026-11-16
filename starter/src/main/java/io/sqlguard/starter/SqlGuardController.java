package io.sqlguard.starter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/sqlguard/api")
final class SqlGuardController {
    record Overview(SqlGuardStore.Metrics metrics, SqlGuardEngine.Health health, List<SqlGuardStore.Point> trend,
                    List<SqlGuardStore.Fingerprint> fingerprints, List<SqlGuardStore.Alert> alerts) {}

    private final SqlGuardEngine engine;
    private final byte[] authorization;

    SqlGuardController(SqlGuardEngine engine, String token) {
        this.engine = engine;
        this.authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/overview")
    ResponseEntity<Overview> overview(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, defaultValue = "") String bearer,
            @RequestParam(defaultValue = "5") int minutes) {
        authorize(bearer);
        SqlGuardStore store = engine.store;
        return noStore(new Overview(store.metrics(), engine.health(), store.trend(Math.max(1, Math.min(30, minutes))),
                store.fingerprints("", false, "latency", 7), store.alerts()));
    }

    @GetMapping("/fingerprints")
    ResponseEntity<List<SqlGuardStore.Fingerprint>> fingerprints(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, defaultValue = "") String bearer,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "false") boolean slow,
            @RequestParam(defaultValue = "recent") String order) {
        authorize(bearer);
        if (q.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search term is too long");
        }
        return noStore(engine.store.fingerprints(q.strip(), slow, order, 100));
    }

    @GetMapping("/fingerprints/{id}")
    ResponseEntity<SqlGuardStore.Detail> detail(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, defaultValue = "") String bearer, @PathVariable String id) {
        authorize(bearer);
        SqlGuardStore.Detail detail = engine.store.detail(id);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fingerprint unavailable");
        }
        return noStore(detail);
    }

    private void authorize(String bearer) {
        if (!MessageDigest.isEqual(authorization, bearer.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SQLGuard token required");
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
